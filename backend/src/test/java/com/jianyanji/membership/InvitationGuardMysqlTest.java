package com.jianyanji.membership;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import com.jianyanji.orders.OrderService;
import com.jianyanji.wallet.WalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 仅隔离本机测试库验证真实邀请查询和提交拒绝；整个测试事务回滚，不调用微信。 */
@EnabledIfEnvironmentVariable(named = "WALLET_TEST_MYSQL_URL", matches = "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/java_rewards_test_.*")
class InvitationGuardMysqlTest {
    /** 核验有效两层归属及各类拒绝后，申请数量和申请人身份保持原值。 */
    @Test
    void actualCandidatesAndRejectedSubmissionsPreserveBusinessData() {
        DriverManagerDataSource ds = new DriverManagerDataSource(System.getenv("WALLET_TEST_MYSQL_URL"), "root", "");
        DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
        Db db = new Db(new JdbcTemplate(ds), manager);
        new TransactionTemplate(manager).execute(status -> {
            status.setRollbackOnly();
            db.one("SELECT id FROM business_mutex WHERE id=1 FOR UPDATE");
            long principal = user(db, "B", null), inviter = user(db, "C", principal), applicant = user(db, "D", null);
            InvitationService invitations = new InvitationService(db, mock(Access.class));
            ObjectNode candidate = invitations.candidate("", inviter, "C", applicant);
            assertEquals(inviter, l(candidate, "inviterId"));
            assertEquals(principal, l(candidate, "principalId"));
            assertEquals(applicant, l(invitations.candidate("", principal, "B", applicant), "principalId"));
            assertThrows(BusinessException.class, () -> invitations.candidate("", inviter, "B", applicant));
            assertThrows(BusinessException.class, () -> invitations.candidate("", applicant, "C", applicant));
            assertThrows(BusinessException.class, () -> invitations.candidate("", Long.MAX_VALUE, "C", applicant));
            long before = l(db.one("SELECT COUNT(*) AS total FROM onboardings WHERE user_id=? AND is_deleted=0", applicant), "total");
            MembershipService membership = new MembershipService(db, mock(Access.class), mock(WalletService.class), mock(OrderService.class));
            ObjectNode person = db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", applicant);
            assertThrows(BusinessException.class, () -> membership.apply(request(applicant, principal), person));
            assertThrows(BusinessException.class, () -> membership.apply(request(inviter, applicant), person));
            db.update("UPDATE users SET identity='D',update_user_id=1,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0", principal);
            assertThrows(BusinessException.class, () -> membership.apply(request(inviter, principal), person));
            assertEquals(before, l(db.one("SELECT COUNT(*) AS total FROM onboardings WHERE user_id=? AND is_deleted=0", applicant), "total"));
            assertEquals("D", s(db.one("SELECT identity FROM users WHERE id=? AND is_deleted=0", applicant), "identity"));
            return null;
        });
    }

    /** 本地测试用户，归属只能通过已有字段关联。 */
    private long user(Db db, String identity, Long principal) {
        return db.insert("INSERT INTO users(openid,nickname,identity,principal_id) VALUES(?,'邀请校验测试',?,?)", "invitation-" + id(), identity, principal);
    }

    /** 非法场景在读取产品包、地址或写入业务数据之前必须被拒绝。 */
    private ObjectNode request(long inviter, long principal) {
        return obj("targetIdentity", "C", "inviterId", inviter, "principalId", principal, "addressId", 1,
                "relationshipsConfirmed", true, "conditionsMet", true, "agreementAccepted", true,
                "requestKey", "invitation-mysql-" + id());
    }
}
