package com.jianyanji.membership;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import com.jianyanji.orders.OrderService;
import com.jianyanji.wallet.WalletService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 邀请核验、篡改请求及提交前资格变化的定向回归，不模拟数据库提交成功。 */
class InvitationGuardTest {
    /** 只读用户替身，保留稳定编号、有效身份和归属字段。 */
    private final Map<Long, ObjectNode> users = new HashMap<>();
    /** 标识业务事务是否已取得互斥边界。 */
    private final AtomicBoolean entered = new AtomicBoolean();

    /** 默认数据库替身执行真实邀请判断，提交进入事务后可改变资格。 */
    @SuppressWarnings("unchecked")
    private Db database(Runnable atTransaction) {
        Db db = mock(Db.class, invocation -> {
            if ("one".equals(invocation.getMethod().getName())) {
                String sql = invocation.getArgument(0);
                if (sql.startsWith("SELECT * FROM users WHERE id="))
                    return users.get(((Number) invocation.getArgument(1)).longValue());
                if (sql.contains("share_code=")) return users.get(20L);
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        doAnswer(i -> {
            entered.set(true);
            atTransaction.run();
            return ((Supplier<?>) i.getArgument(0)).get();
        }).when(db).tx(org.mockito.ArgumentMatchers.any(Supplier.class));
        users.put(7L, obj("id", 7, "identity", "D"));
        users.put(20L, obj("id", 20, "identity", "B", "nickname", "主理人", "relation_version", 1));
        users.put(21L, obj("id", 21, "identity", "C", "nickname", "店主", "principal_id", 20));
        return db;
    }

    /** 普通客户端申请载荷；金额和归属仍由现有业务服务核对。 */
    private ObjectNode request(String target, Long inviter, long principal) {
        return obj("targetIdentity", target, "addressId", 1, "inviterId", inviter,
                "principalId", principal, "relationshipsConfirmed", true, "conditionsMet", true,
                "agreementAccepted", true, "requestKey", "onboard-invitation-guard");
    }

    /** 直接编号、分享码和最终申请均使用同一稳定用户编号阻止自邀。 */
    @Test
    void selfMissingAndIneligibleInvitersCannotPassCandidateOrApply() {
        Db db = database(() -> {});
        Access access = mock(Access.class);
        InvitationService invitations = new InvitationService(db, access);
        assertTrue(assertThrows(BusinessException.class, () -> invitations.candidate("", 7L, "C", 7)).getMessage().contains("自己"));
        assertTrue(assertThrows(BusinessException.class, () -> invitations.candidate("share", null, "C", 20)).getMessage().contains("自己"));
        assertThrows(BusinessException.class, () -> invitations.candidate("", 999L, "C", 7));
        assertThrows(BusinessException.class, () -> invitations.candidate("", 21L, "B", 7));
        assertEquals(20, l(invitations.candidate("", 21L, "C", 7), "principalId"));
        users.get(20L).put("identity", "D");
        assertThrows(BusinessException.class, () -> invitations.candidate("", 21L, "C", 7));
        MembershipService membership = new MembershipService(db, access, mock(WalletService.class), mock(OrderService.class));
        assertThrows(BusinessException.class, () -> membership.apply(request("C", null, 20), users.get(7L)));
        assertTrue(assertThrows(BusinessException.class, () -> membership.apply(request("C", 7L, 7), users.get(7L))).getMessage().contains("自己"));
        assertNoWrites(db);
    }

    /** 等待事务锁期间邀请人失去主理人资格，不能沿用锁外的验证结果提交。 */
    @Test
    void eligibilityIsRecheckedAfterEnteringSubmissionTransaction() {
        Db db = database(() -> users.get(20L).put("identity", "C"));
        MembershipService membership = new MembershipService(db, mock(Access.class), mock(WalletService.class), mock(OrderService.class));
        BusinessException error = assertThrows(BusinessException.class, () -> membership.apply(request("B", 20L, 7), users.get(7L)));
        assertTrue(entered.get());
        assertTrue(error.getMessage().contains("主理人入驻须由有效主理人邀请"), error.getMessage());
        assertNoWrites(db);
    }

    /** 客户端不能用分享码搭配其他编号，或篡改由服务端计算的归属。 */
    @Test
    void tamperedPrincipalOrCodeAndIdMismatchCannotWriteApplication() {
        Db db = database(() -> {});
        MembershipService membership = new MembershipService(db, mock(Access.class), mock(WalletService.class), mock(OrderService.class));
        assertTrue(assertThrows(BusinessException.class, () -> membership.apply(request("C", 21L, 999), users.get(7L))).getMessage().contains("归属已变化"));
        assertTrue(assertThrows(BusinessException.class, () -> membership.apply(request("C", 21L, 20).put("shareCode", "share"), users.get(7L))).getMessage().contains("邀请候选已变化"));
        assertNoWrites(db);
    }

    /** 拒绝阶段不能创建申请、订单或发生资金及库存变动。 */
    private void assertNoWrites(Db db) {
        assertFalse(mockingDetails(db).getInvocations().stream().anyMatch(i -> "insert".equals(i.getMethod().getName()) || "update".equals(i.getMethod().getName())));
    }
}
