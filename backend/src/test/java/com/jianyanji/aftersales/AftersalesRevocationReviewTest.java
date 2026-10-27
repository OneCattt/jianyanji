package com.jianyanji.aftersales;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import com.jianyanji.membership.MembershipService;
import com.jianyanji.orders.OrderService;
import org.junit.jupiter.api.Test;

/** 退款审核不再操作身份；身份关闭和团队交接从独立入口办理。 */
class AftersalesRevocationReviewTest {
    @Test
    void refundApprovalDoesNotCloseIdentityOrRequireTeamHandover() {
        Db db = mock(Db.class);
        Access access = mock(Access.class);
        MembershipService membership = mock(MembershipService.class);
        OrderService orders = mock(OrderService.class);
        AftersalesService service =
                new AftersalesService(db, access, orders, null, membership, null, null);
        doAnswer(
                        i -> {
                            ((Runnable) i.getArgument(0)).run();
                            return null;
                        })
                .when(db)
                .tx(any(Runnable.class));
        ObjectNode order = obj("id", 21, "kind", "package", "user_id", 8);
        when(db.one("SELECT * FROM refunds WHERE id=? AND is_deleted=0", 11L))
                .thenReturn(
                        obj("id", 11, "order_id", 21, "kind", "package", "status", "requested"));
        when(orders.owned(21L, null)).thenReturn(order);
        doThrow(BusinessException.conflict("请先安排团队接管"))
                .when(membership)
                .assertRefundRevocable(order);
        assertDoesNotThrow(
                () ->
                        service.review(
                                11,
                                obj(
                                        "approve",
                                        true,
                                        "identityDecision",
                                        "revoke",
                                        "reason",
                                        "退回产品包")));
        verifyNoInteractions(membership);
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .anyMatch(i -> i.getMethod().getName().equals("update")));
    }
}
