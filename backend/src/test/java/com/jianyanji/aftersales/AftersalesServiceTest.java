package com.jianyanji.aftersales;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import com.jianyanji.membership.MembershipService;
import com.jianyanji.orders.OrderService;
import com.jianyanji.payments.WechatPayClient;
import com.jianyanji.rewards.RewardService;
import com.jianyanji.wallet.WalletService;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 售后领域边界测试，覆盖退货验收、积分及身份奖励补偿、原支付依据和租约隔离。 */
class AftersalesServiceTest {
    /** 数据访问测试替身；本测试不宣称验证 MySQL 事务回滚。 */
    private Db db;
    /** 请求审计测试上下文。 */
    private Access access;
    /** 订单领域接口测试替身。 */
    private OrderService orders;
    /** 积分补偿接口测试替身。 */
    private WalletService wallet;
    /** 身份补偿接口测试替身。 */
    private MembershipService membership;
    /** 奖励补偿接口测试替身。 */
    private RewardService rewards;
    /** 显式渠道测试替身，不执行生产退款。 */
    private WechatPayClient channel;
    /** 被测售后服务。 */
    private AftersalesService service;
    /** 退款持久化行测试样本，金额为分。 */
    private ObjectNode refund;
    /** 订单持久化行测试样本。 */
    private ObjectNode order;
    /** 原微信支付凭据测试样本。 */
    private ObjectNode payment;

    /** 只在渠道状态测试中追踪持久字段，不替代真实数据库事务验证。 */
    private boolean trackChannelState;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() {
        db =
                mock(
                        Db.class,
                        invocation -> {
                            if (!"update".equals(invocation.getMethod().getName()))
                                return RETURNS_DEFAULTS.answer(invocation);
                            if (trackChannelState) {
                                String sql = invocation.getArgument(0);
                                if (sql.startsWith(
                                        "UPDATE refunds SET status='processing',channel_token=")) {
                                    refund.put("channel_token", (String) invocation.getArgument(1));
                                    refund.put("status", "processing");
                                } else if (sql.startsWith("UPDATE refunds SET status='unknown'")) {
                                    refund.put("status", "unknown");
                                    refund.put("last_error", (String) invocation.getArgument(1));
                                } else if (sql.startsWith(
                                        "UPDATE refunds SET status=?,channel_ref=")) {
                                    refund.put("status", (String) invocation.getArgument(1));
                                    refund.put("channel_ref", (String) invocation.getArgument(2));
                                } else if (sql.startsWith("UPDATE refunds SET status='success'"))
                                    refund.put("status", "success");
                                else if (sql.startsWith("UPDATE refunds SET channel_token=NULL")
                                        && s(refund, "channel_token")
                                                .equals(invocation.getArgument(3)))
                                    refund.putNull("channel_token");
                            }
                            return 1;
                        });
        access = mock(Access.class);
        orders = mock(OrderService.class);
        wallet = mock(WalletService.class);
        membership = mock(MembershipService.class);
        rewards = mock(RewardService.class);
        channel = mock(WechatPayClient.class);
        service = new AftersalesService(db, access, orders, wallet, membership, rewards, channel);
        when(access.actor()).thenReturn(7L);
        when(access.requestId()).thenReturn("request-test");
        when(channel.configured()).thenReturn(true);
        doAnswer(i -> ((Supplier<?>) i.getArgument(0)).get()).when(db).tx(any(Supplier.class));
        doAnswer(
                        i -> {
                            ((Runnable) i.getArgument(0)).run();
                            return null;
                        })
                .when(db)
                .tx(any(Runnable.class));
        refund =
                obj(
                        "id",
                        11,
                        "order_id",
                        21,
                        "number",
                        "RF-persisted",
                        "status",
                        "approved",
                        "total_cents",
                        300,
                        "lines",
                        stringify(
                                arr(
                                        obj(
                                                "lineId",
                                                31,
                                                "skuId",
                                                41,
                                                "quantity",
                                                1,
                                                "unshippedQuantity",
                                                1,
                                                "returnQuantity",
                                                0))),
                        "policy_snapshot",
                        "{}",
                        "reason",
                        "原申请依据",
                        "identity_decision",
                        "retain",
                        "channel_token",
                        null,
                        "channel_until",
                        null);
        order =
                obj(
                        "id",
                        21,
                        "number",
                        "ORDER-original",
                        "kind",
                        "goods",
                        "payment_method",
                        "wechat",
                        "total_cents",
                        1000);
        payment = obj("cents", 1000, "out_trade_no", "P-original", "transaction_id", "wx-original");
        when(db.one(startsWith("SELECT * FROM refunds"), anyLong()))
                .thenAnswer(i -> refund.deepCopy());
        when(orders.owned(21L, null)).thenAnswer(i -> order.deepCopy());
        when(db.one(startsWith("SELECT * FROM payments"), anyLong()))
                .thenAnswer(i -> payment.deepCopy());
    }

    @Test
    void uninspectedShippedGoodsBlockAllLocalCompensation() {
        refund.put(
                "lines",
                stringify(
                        arr(
                                obj(
                                        "lineId",
                                        31,
                                        "skuId",
                                        41,
                                        "quantity",
                                        1,
                                        "unshippedQuantity",
                                        0,
                                        "returnQuantity",
                                        1))));
        order.put("payment_method", "points");
        assertThrows(
                BusinessException.class, () -> service.finishRefund(refund, 7, "request-test"));
        verifyNoInteractions(wallet, rewards, membership);
    }

    @Test
    void partialPackagePointsRefundRestoresPointsAndReversesRewardsWithoutClosingIdentity() {
        order.put("payment_method", "points");
        order.put("kind", "package");
        when(db.one(startsWith("SELECT id FROM order_lines"), anyLong())).thenReturn(obj("id", 32));
        service.finishRefund(refund, 7, "request-test");
        verify(db, atLeastOnce()).assertTransaction();
        verify(wallet).restoreOrderPoints(21, 11, 300, 7, "request-test");
        verifyNoInteractions(membership);
        verify(rewards).reconcileReversals(21, 7, "request-test");
        verify(orders).refreshFulfillment(21);
    }

    @Test
    void successfulRefundIsIdempotentAndCannotRestorePointsTwice() {
        refund.put("status", "success");
        service.finishRefund(refund, 7, "request-test");
        verifyNoInteractions(wallet, rewards, membership, orders);
        assertEquals(0, sqlCalls("update", ""));
    }

    @Test
    void missingOriginalPaymentStopsBeforeChannelAndKeepsRefundUnchanged() {
        payment.put("transaction_id", "");
        assertThrows(
                BusinessException.class, () -> service.channel(11, obj("reason", "原渠道退款"), false));
        verify(channel, never()).refund(any());
        verify(channel, never()).queryRefund(anyString());
        assertEquals(0, sqlCalls("update", ""));
    }

    @Test
    void unresolvedRefundOnlyAllowsQueryAndLiveLeaseRejectsConcurrentQuery() {
        refund.put("status", "unknown");
        assertThrows(
                BusinessException.class, () -> service.channel(11, obj("reason", "重新发起"), false));
        when(db.update(
                        startsWith("UPDATE refunds SET status='processing',channel_token="),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyLong(),
                        anyString()))
                .thenReturn(0);
        assertThrows(
                BusinessException.class, () -> service.channel(11, obj("reason", "核对原单"), true));
        verify(channel, never()).refund(any());
        verify(channel, never()).queryRefund(anyString());
    }

    @Test
    void onlineRefundQueryIsDisabledWithoutTouchingOriginalUnknownState() {
        refund.put("status", "unknown");
        assertEquals(
                "ONLINE_PAYMENT_DISABLED",
                assertThrows(
                                BusinessException.class,
                                () -> service.channel(11, obj("reason", "核对原单"), true))
                        .getCode());
        assertEquals("unknown", s(refund, "status"));
        verifyNoInteractions(channel, wallet, membership, rewards);
        assertEquals(0, sqlCalls("update", ""));
    }

    @Test
    void configuredMerchantCannotEnableOnlineRefund() {
        assertEquals(
                "ONLINE_PAYMENT_DISABLED",
                assertThrows(
                                BusinessException.class,
                                () -> service.channel(11, obj("reason", "退款"), false))
                        .getCode());
        verifyNoInteractions(channel, wallet, membership, rewards);
        assertEquals(0, sqlCalls("update", ""));
    }

    @Test
    void offlineRefundRequiresActualPaymentConfirmation() {
        order.put("payment_method", "offline");
        assertThrows(BusinessException.class, () -> service.offline(11, offlineProof(false)));
        verifyNoInteractions(wallet, membership, rewards);
        assertEquals(0, sqlCalls("update", ""));
    }

    @Test
    void completedOfflineRefundCannotBeRewrittenWithDifferentProof() {
        order.put("payment_method", "offline");
        refund.put("status", "success")
                .put("recipient", "原收款人")
                .put("paid_at", "2020-01-01T00:00:00Z")
                .put("proof", "原凭证");
        assertThrows(BusinessException.class, () -> service.offline(11, offlineProof(true)));
        assertEquals(0, sqlCalls("update", ""));
        verifyNoInteractions(wallet, membership, rewards);
    }

    private ObjectNode offlineProof(boolean confirmed) {
        return obj(
                "recipient",
                "原收款人",
                "paidAt",
                "2020-01-01T00:00:00Z",
                "proof",
                "实际线下退款凭证",
                "confirmed",
                confirmed,
                "reason",
                "退货退款");
    }

    private long sqlCalls(String method, String prefix) {
        return mockingDetails(db).getInvocations().stream()
                .filter(
                        i ->
                                method.equals(i.getMethod().getName())
                                        && ((String) i.getArgument(0)).startsWith(prefix))
                .count();
    }

    @Test
    void missingPolicyIsSerializedAsJsonNullInsteadOfEmptyBody() throws Exception {
        AftersalesService settingsService = mock(AftersalesService.class);
        org.springframework.test.web.servlet.MockMvc mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new AftersalesController(settingsService, access))
                        .build();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/admin/aftersales-settings"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .string("null"));
        verify(access).staff("orders:manage");
    }

    @Test
    void requestRejectsDuplicateItemsExtraFieldsAndInvalidIdentityDecision() {
        ObjectNode body =
                obj(
                        "orderId",
                        21,
                        "items",
                        arr(obj("lineId", 31, "quantity", 1), obj("lineId", 31, "quantity", 1)),
                        "shippingCents",
                        0,
                        "reason",
                        "退货",
                        "requestKey",
                        "request-unique-2026");
        assertThrows(BusinessException.class, () -> service.request(body, obj("id", 7)));
        body.put("unknown", true);
        assertThrows(BusinessException.class, () -> service.request(body, obj("id", 7)));
        assertThrows(
                BusinessException.class,
                () -> service.review(11, obj("approve", true, "reason", "审核")));
        assertThrows(
                BusinessException.class,
                () ->
                        service.review(
                                11,
                                obj("approve", true, "identityDecision", "other", "reason", "审核")));
    }
}
