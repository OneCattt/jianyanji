package com.jianyanji.membership;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 复用既有 MySQL 环境约定及 Db，仅验证关系保护 SQL；所有测试数据随事务回滚。 */
@EnabledIfEnvironmentVariable(named = "WALLET_TEST_MYSQL_URL", matches = ".+")
class MembershipBoundaryMysqlTest {
    /** 本人和直接邀请申请的未决交易都阻止调整，已关闭／已成功交易不阻止调整。 */
    @Test
    void unresolvedOwnAndInvitedPaymentsPreserveRelationsUntilResolved() {
        DriverManagerDataSource ds =
                new DriverManagerDataSource(
                        System.getenv("WALLET_TEST_MYSQL_URL"),
                        System.getenv().getOrDefault("WALLET_TEST_MYSQL_USER", "root"),
                        System.getenv().getOrDefault("WALLET_TEST_MYSQL_PASSWORD", ""));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
        Db db = new Db(new JdbcTemplate(ds), manager);
        new TransactionTemplate(manager)
                .execute(
                        status -> {
                            status.setRollbackOnly();
                            // 测试事务持有与业务相同的互斥行；不创建迁移，也不保留测试交易。
                            db.one("SELECT id FROM business_mutex WHERE id=1 FOR UPDATE");
                            long original = user(db, "B", null);
                            long successor = user(db, "B", null);
                            long inviter = user(db, "C", original);
                            long applicant = user(db, "D", null);
                            long invitedOrder = order(db, applicant, "package");
                            long application =
                                    db.insert(
                                            "INSERT INTO onboardings(user_id,target_identity,package_snapshot,inviter_id,principal_id,order_id,reward_cents,after_sales_days,request_key,relation_confirmed) VALUES(?,'C','{}',?,?,?,0,7,?,1)",
                                            applicant,
                                            inviter,
                                            original,
                                            invitedOrder,
                                            "boundary-" + id());
                            long invitedAttempt = attempt(db, invitedOrder, "ready");
                            InvitationService invitations =
                                    new InvitationService(db, mock(Access.class));
                            ObjectNode adjustment =
                                    obj(
                                            "userId",
                                            inviter,
                                            "inviterId",
                                            successor,
                                            "principalId",
                                            successor,
                                            "reason",
                                            "定向回归关系调整");

                            BusinessException invitedError =
                                    assertThrows(
                                            BusinessException.class,
                                            () -> invitations.adjust(adjustment, 1));
                            assertTrue(invitedError.getMessage().contains("未决微信交易"));
                            assertUnchanged(db, inviter, original, application);

                            resolve(db, invitedAttempt, "closed");
                            long ownOrder = order(db, inviter, "goods");
                            long ownAttempt = attempt(db, ownOrder, "unknown");
                            BusinessException ownError =
                                    assertThrows(
                                            BusinessException.class,
                                            () -> invitations.adjust(adjustment, 1));
                            assertTrue(ownError.getMessage().contains("未决微信交易"));
                            assertUnchanged(db, inviter, original, application);

                            // 构造本地已核实的终态，不调用外部微信，也不测试到账激活。
                            resolve(db, ownAttempt, "success");
                            db.update(
                                    "UPDATE orders SET status='paid',payment_state='paid',update_user_id=1,update_time=CURRENT_TIMESTAMP(3) WHERE id=?",
                                    ownOrder);
                            assertDoesNotThrow(() -> invitations.adjust(adjustment, 1));
                            assertEquals(
                                    successor,
                                    l(
                                            db.one(
                                                    "SELECT principal_id FROM users WHERE id=? AND is_deleted=0",
                                                    inviter),
                                            "principal_id"));
                            assertFalse(
                                    b(
                                            db.one(
                                                    "SELECT relation_confirmed FROM onboardings WHERE id=? AND is_deleted=0",
                                                    application),
                                            "relation_confirmed"));
                            return null;
                        });
    }

    /** 最小用户数据，仅为关系检查提供当前身份及归属。 */
    private long user(Db db, String identity, Long principal) {
        return db.insert(
                "INSERT INTO users(openid,identity,principal_id) VALUES(?,?,?)",
                "boundary-" + id(),
                identity,
                principal);
    }

    /** 最小订单数据，保留真实数据库外键与金额单位（分）。 */
    private long order(Db db, long uid, String kind) {
        return db.insert(
                "INSERT INTO orders(number,user_id,kind,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key) VALUES(?,?,?,298000,298000,0,'{}','2099-01-01T00:00:00Z',?)",
                "boundary-" + id(),
                uid,
                kind,
                "boundary-" + id());
    }

    /** 构造待核查交易状态，不调用微信预支付。 */
    private long attempt(Db db, long order, String state) {
        return db.insert(
                "INSERT INTO payment_attempts(order_id,number,state,app_id,mch_id,cents,openid) VALUES(?,?,?,'boundary-app','boundary-mch',298000,'boundary-openid')",
                order,
                "boundary-" + id(),
                state);
    }

    /** 改为已查明状态，同时维护修改审计字段。 */
    private void resolve(Db db, long attempt, String state) {
        db.update(
                "UPDATE payment_attempts SET state=?,update_user_id=1,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                state,
                attempt);
    }

    /** 拒绝调整后，用户归属、关系版本和申请确认必须原样保留。 */
    private void assertUnchanged(Db db, long inviter, long original, long application) {
        ObjectNode user =
                db.one(
                        "SELECT principal_id,relation_version FROM users WHERE id=? AND is_deleted=0",
                        inviter);
        assertEquals(original, l(user, "principal_id"));
        assertEquals(1, l(user, "relation_version"));
        assertTrue(
                b(
                        db.one(
                                "SELECT relation_confirmed FROM onboardings WHERE id=? AND is_deleted=0",
                                application),
                        "relation_confirmed"));
    }
}
