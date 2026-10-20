package com.jianyanji.membership;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import com.jianyanji.orders.OrderService;
import com.jianyanji.wallet.WalletService;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** V2 定向复核回归：在途付款保护及按产品包资格来源检查退款交接。 */
class MembershipBoundaryTest {
    /** 在途微信交易必须在任何关系写入之前阻止调整，保留申请人的原确认。 */
    @Test
    @SuppressWarnings("unchecked")
    void unresolvedPaymentRejectsRelationAdjustmentBeforeAnyWrite() {
        ObjectNode inviter = obj("id", 21, "identity", "C", "principal_id", 31);
        Db db =
                mock(
                        Db.class,
                        invocation -> {
                            if ("one".equals(invocation.getMethod().getName())) {
                                String sql = invocation.getArgument(0);
                                if (sql.contains("payment_attempts")) return obj("id", 91);
                                if (sql.startsWith("SELECT * FROM users WHERE id=")) return inviter;
                            }
                            return RETURNS_DEFAULTS.answer(invocation);
                        });
        doAnswer(i -> ((Supplier<?>) i.getArgument(0)).get())
                .when(db)
                .tx(org.mockito.ArgumentMatchers.any(Supplier.class));
        Access access = mock(Access.class);
        InvitationService invitations = new InvitationService(db, access);

        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () ->
                                invitations.adjust(
                                        obj(
                                                "userId",
                                                21,
                                                "inviterId",
                                                32,
                                                "principalId",
                                                32,
                                                "reason",
                                                "品牌调整店主归属"),
                                        1));

        assertTrue(error.getMessage().contains("未决微信交易"));
        assertFalse(
                mockingDetails(db).getInvocations().stream()
                        .anyMatch(
                                i ->
                                        "update".equals(i.getMethod().getName())
                                                || "insert".equals(i.getMethod().getName())),
                "拒绝时不得修改用户、资格、申请确认或创建其他记录");
        verifyNoInteractions(access);
    }

    /** 同一用户退旧 C 来源不交接；退唯一 B 来源仍必须先安排有效接管人。 */
    @Test
    void originalStoreRefundPreservesPrincipalButPrincipalRefundRequiresHandover() {
        ObjectNode current = obj("id", 8, "identity", "B", "onboarding_id", 32, "principal_id", 8);
        ObjectNode storeSource = obj("id", 11, "user_id", 8, "target_identity", "C");
        ObjectNode principalSource = obj("id", 32, "user_id", 8, "target_identity", "B");
        ObjectNode principalQualification =
                obj("id", 102, "user_id", 8, "identity", "B", "onboarding_id", 32);
        Db db =
                mock(
                        Db.class,
                        invocation -> {
                            if ("one".equals(invocation.getMethod().getName())) {
                                String sql = invocation.getArgument(0);
                                if (sql.startsWith("SELECT * FROM onboardings WHERE order_id="))
                                    return ((Number) invocation.getArgument(1)).longValue() == 41
                                            ? storeSource
                                            : principalSource;
                                if (sql.startsWith("SELECT * FROM users WHERE id=")) return current;
                                if (sql.startsWith("SELECT id FROM users WHERE principal_id="))
                                    return obj("id", 51);
                                if (sql.startsWith("SELECT * FROM qualifications WHERE user_id="))
                                    return principalQualification;
                                // 唯一的 B 来源就是本次升级包，排除它后没有其他 B 资格；也尚未安排交接。
                                if (sql.contains("FROM qualifications")
                                        || sql.contains("FROM team_handovers")) return null;
                            }
                            if ("update".equals(invocation.getMethod().getName())) return 1;
                            return RETURNS_DEFAULTS.answer(invocation);
                        });
        MembershipService membership =
                new MembershipService(
                        db,
                        mock(Access.class),
                        mock(WalletService.class),
                        mock(OrderService.class));

        assertDoesNotThrow(() -> membership.assertRefundRevocable(packageOrder(41)));
        assertFalse(queriedHandover(db), "退旧 C 包不应检查当前 B 团队交接");
        membership.qualifications().revoke(storeSource, 1, "仅撤销旧店主包资格");
        assertEquals("B", s(current, "identity"));
        assertFalse(
                mockingDetails(db).getInvocations().stream()
                        .anyMatch(
                                i ->
                                        "update".equals(i.getMethod().getName())
                                                && ((String) i.getArgument(0))
                                                        .contains("UPDATE users SET identity=")),
                "撤销旧 C 来源不能改变仍有效的 B 身份");

        clearInvocations(db);
        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () -> membership.assertRefundRevocable(packageOrder(42)));
        assertTrue(error.getMessage().contains("接管"));
        assertTrue(queriedHandover(db), "撤销唯一 B 来源仍须检查接管计划");
        assertFalse(
                mockingDetails(db).getInvocations().stream()
                        .anyMatch(i -> "update".equals(i.getMethod().getName())),
                "退款预检不应实际撤销资格");
    }

    /** 同一用户的产品包订单，只以订单编号区分两次资格来源。 */
    private ObjectNode packageOrder(long orderId) {
        return obj("id", orderId, "kind", "package", "user_id", 8);
    }

    /** 检查预检是否实际读取团队交接计划。 */
    private boolean queriedHandover(Db db) {
        return mockingDetails(db).getInvocations().stream()
                .anyMatch(
                        i ->
                                "one".equals(i.getMethod().getName())
                                        && ((String) i.getArgument(0))
                                                .contains("FROM team_handovers"));
    }
}
