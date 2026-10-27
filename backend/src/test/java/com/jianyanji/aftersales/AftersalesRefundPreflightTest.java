package com.jianyanji.aftersales;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import com.jianyanji.membership.MembershipService;
import com.jianyanji.orders.OrderService;
import com.jianyanji.payments.WechatPayClient;
import com.jianyanji.wallet.WalletService;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;

/** 累计全退误选保留时仍须在实际退款前确认资格可撤销，不允许先退资金再失败。 */
class AftersalesRefundPreflightTest {
    /** 追踪退款前数据库写入的测试替身。 */
    private Db db;
    /** 主线程资格撤销及接管计划接口。 */
    private MembershipService membership;
    /** 外部微信退款渠道替身。 */
    private WechatPayClient channel;
    /** 退积分接口替身。 */
    private WalletService wallet;
    /** 原订单支付快照。 */
    private ObjectNode order;
    /** 本次售后状态，金额单位分。 */
    private ObjectNode refund;
    /** 被测退款生命周期服务。 */
    private AftersalesService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        db = mock(Db.class);
        Access access = mock(Access.class);
        OrderService orders = mock(OrderService.class);
        membership = mock(MembershipService.class);
        channel = mock(WechatPayClient.class);
        wallet = mock(WalletService.class);
        service =
                new AftersalesService(
                        db,
                        access,
                        orders,
                        wallet,
                        membership,
                        mock(com.jianyanji.rewards.RewardService.class),
                        channel);
        doAnswer(i -> ((Supplier<?>) i.getArgument(0)).get()).when(db).tx(any(Supplier.class));
        doAnswer(
                        i -> {
                            ((Runnable) i.getArgument(0)).run();
                            return null;
                        })
                .when(db)
                .tx(any(Runnable.class));
        order =
                obj(
                        "id",
                        21,
                        "user_id",
                        8,
                        "kind",
                        "package",
                        "payment_method",
                        "wechat",
                        "total_cents",
                        1000);
        refund =
                obj(
                        "id",
                        11,
                        "order_id",
                        21,
                        "status",
                        "approved",
                        "total_cents",
                        300,
                        "identity_decision",
                        "retain",
                        "lines",
                        "[]",
                        "policy_snapshot",
                        "{}");
        when(db.one("SELECT * FROM refunds WHERE id=? AND is_deleted=0", 11L)).thenReturn(refund);
        when(orders.owned(21L, null)).thenReturn(order);
        when(db.one(
                        startsWith("SELECT COALESCE(SUM(total_cents),0) AS total FROM refunds"),
                        eq(21L)))
                .thenReturn(obj("total", 700));
        when(channel.configured()).thenReturn(true);
        doThrow(BusinessException.conflict("请先登记有效团队接管计划"))
                .when(membership)
                .assertRefundRevocable(order);
    }

    @Test
    void disabledOnlineRefundCannotReachWechatOrCreateLease() {
        assertThrows(
                BusinessException.class, () -> service.channel(11, obj("reason", "原路退货"), false));
        verifyNoInteractions(membership);
        verify(channel, never()).refund(any());
        verify(channel, never()).queryRefund(anyString());
        assertNoWrite();
    }

    @Test
    void fullOfflineRefundRetainsIdentity() {
        order.put("payment_method", "offline");
        assertDoesNotThrow(
                () ->
                        service.offline(
                                11,
                                obj(
                                        "recipient",
                                        "本人",
                                        "paidAt",
                                        "2020-01-01T00:00:00Z",
                                        "proof",
                                        "财务原退款凭证",
                                        "confirmed",
                                        true,
                                        "reason",
                                        "退款")));
        verifyNoInteractions(membership);
    }

    @Test
    void pointsRefundDoesNotInvokeIdentityRevocation() {
        order.put("payment_method", "points");
        assertDoesNotThrow(() -> service.points(11, obj("reason", "退积分")));
        verifyNoInteractions(membership);
        verify(wallet)
                .restoreOrderPoints(eq(21L), eq(11L), eq(300L), anyLong(), nullable(String.class));
    }

    @Test
    void legacyIdentityDecisionDoesNotCloseIdentityOnRefund() {
        refund.put("identity_decision", "revoke");
        order.put("payment_method", "offline");
        when(db.one(
                        startsWith("SELECT COALESCE(SUM(total_cents),0) AS total FROM refunds"),
                        eq(21L)))
                .thenReturn(obj("total", 0));
        assertDoesNotThrow(
                () ->
                        service.offline(
                                11,
                                obj(
                                        "recipient",
                                        "本人",
                                        "paidAt",
                                        "2020-01-01T00:00:00Z",
                                        "proof",
                                        "原退款凭证",
                                        "confirmed",
                                        true,
                                        "reason",
                                        "部分退货撤销身份")));
        verifyNoInteractions(membership);
        verify(channel, never()).refund(any());
    }

    private void assertNoWrite() {
        assertFalse(
                mockingDetails(db).getInvocations().stream()
                        .anyMatch(i -> i.getMethod().getName().equals("update")));
    }
}
