package com.jianyanji.wallet;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import com.jianyanji.orders.OrderService;
import com.jianyanji.payments.PaymentService;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 钱包金额边界、跨批次原路退积分及支付幂等回归。 */
class WalletServiceTest {
    /** 模拟持久化边界；数据库回滚及并发锁由集成测试另行验证。 */
    private Db db;
    /** 订单查询桩。 */
    private OrderService orders;
    /** 统一支付结算桩。 */
    private PaymentService payments;
    /** 被测钱包服务。 */
    private WalletService wallet;

    @BeforeEach
    void setup() {
        db =
                mock(
                        Db.class,
                        i ->
                                "update".equals(i.getMethod().getName())
                                        ? 1
                                        : RETURNS_DEFAULTS.answer(i));
        orders = mock(OrderService.class);
        payments = mock(PaymentService.class);
        wallet = new WalletService(db, mock(Access.class), orders, payments);
        when(db.tx(org.mockito.ArgumentMatchers.<Supplier<ObjectNode>>any()))
                .thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
    }

    /** 合法收款样例，实际金额以整数分表达。 */
    private ObjectNode receipt() {
        return obj(
                "userId",
                7,
                "purpose",
                "points",
                "sourceChannel",
                "bank",
                "accountRef",
                "公司账户",
                "externalTransactionId",
                "到账001",
                "amountCents",
                100000,
                "receivedAt",
                "2020-01-01T08:00:00+08:00",
                "evidence",
                "已核实流水",
                "reason",
                "核实真实到账",
                "verified",
                true);
    }

    @Test
    void receiptRejectsForgedAndNonIntegerMoney() {
        assertEquals(100000, l(wallet.validateReceipt(receipt()), "amountCents"));
        assertEquals(
                400,
                assertThrows(
                                BusinessException.class,
                                () -> wallet.validateReceipt(receipt().put("verified", false)))
                        .getStatus());
        assertThrows(
                BusinessException.class,
                () -> wallet.validateReceipt(receipt().put("amountCents", "100000")));
        assertThrows(
                BusinessException.class,
                () -> wallet.validateReceipt(receipt().put("amountCents", 1.5)));
        assertThrows(
                BusinessException.class, () -> wallet.validateReceipt(receipt().put("extra", 1)));
        assertThrows(
                BusinessException.class,
                () -> wallet.validateReceipt(receipt().put("amountCents", 100000001)));
        assertThrows(
                BusinessException.class,
                () -> wallet.validateReceipt(receipt().put("receivedAt", "2999-01-01T00:00:00Z")));
    }

    @Test
    void receiptCannotBeReusedForAnotherPurpose() {
        when(db.one(startsWith("SELECT id FROM users"), eq(7L))).thenReturn(obj("id", 7));
        when(db.one(
                        startsWith("SELECT id FROM company_receipts"),
                        eq("bank"),
                        eq("公司账户"),
                        eq("到账001")))
                .thenReturn(obj("id", 1));
        BusinessException error =
                assertThrows(BusinessException.class, () -> wallet.registerReceipt(receipt(), 99));
        assertEquals("RECEIPT_ALREADY_USED", error.getCode());
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "insert".equals(i.getMethod().getName())));
    }

    @Test
    void refundsReturnToEachOriginalLot() {
        when(db.one(startsWith("SELECT COALESCE(SUM(delta_cents)"), eq(30L), eq("8")))
                .thenReturn(obj("total", 0));
        when(orders.owned(30, null))
                .thenReturn(
                        obj(
                                "id",
                                30,
                                "user_id",
                                7,
                                "payment_method",
                                "points",
                                "payment_state",
                                "partially_refunded"));
        when(db.list(startsWith("SELECT * FROM wallet_spend_allocations"), eq(30L)))
                .thenReturn(
                        Arrays.asList(
                                obj(
                                        "id",
                                        1,
                                        "lot_id",
                                        11,
                                        "receipt_id",
                                        21,
                                        "spent_cents",
                                        5000,
                                        "refunded_cents",
                                        0),
                                obj(
                                        "id",
                                        2,
                                        "lot_id",
                                        12,
                                        "receipt_id",
                                        22,
                                        "spent_cents",
                                        6000,
                                        "refunded_cents",
                                        0)));
        wallet.restoreOrderPoints(30, 8, 7000, 99, "请求001");
        verify(db)
                .update(
                        startsWith("UPDATE wallet_lots"),
                        eq(5000L),
                        eq(99L),
                        eq(11L),
                        eq(7L),
                        eq(5000L));
        verify(db)
                .update(
                        startsWith("UPDATE wallet_lots"),
                        eq(2000L),
                        eq(99L),
                        eq(12L),
                        eq(7L),
                        eq(2000L));
        verify(db)
                .insert(
                        startsWith("INSERT INTO wallet_ledger"),
                        eq(7L),
                        eq(21L),
                        eq(11L),
                        eq(30L),
                        eq("order_refund"),
                        eq(5000L),
                        eq("8"),
                        eq("refund:30:8:1"),
                        anyString(),
                        eq(99L),
                        eq(99L));
    }

    @Test
    void repeatedRefundDoesNotRestoreTwiceAndRejectsDifferentAmount() {
        when(db.one(startsWith("SELECT COALESCE(SUM(delta_cents)"), eq(30L), eq("8")))
                .thenReturn(obj("total", 7000));
        wallet.restoreOrderPoints(30, 8, 7000, 99, "请求001");
        assertThrows(
                BusinessException.class, () -> wallet.restoreOrderPoints(30, 8, 7001, 99, "请求002"));
        verifyNoInteractions(orders);
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "update".equals(i.getMethod().getName())));
    }

    @Test
    void refundRejectsOverRefundAndRequiresTransaction() {
        when(db.one(startsWith("SELECT COALESCE(SUM(delta_cents)"), eq(30L), eq("8")))
                .thenReturn(obj("total", 0));
        when(orders.owned(30, null))
                .thenReturn(
                        obj(
                                "id",
                                30,
                                "user_id",
                                7,
                                "payment_method",
                                "points",
                                "payment_state",
                                "paid"));
        when(db.list(startsWith("SELECT * FROM wallet_spend_allocations"), eq(30L)))
                .thenReturn(
                        Collections.singletonList(
                                obj("spent_cents", 5000, "refunded_cents", 3000)));
        assertThrows(
                BusinessException.class, () -> wallet.restoreOrderPoints(30, 8, 2001, 99, "请求001"));
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "update".equals(i.getMethod().getName())));
        doThrow(new IllegalStateException("必须处于事务内")).when(db).assertTransaction();
        assertThrows(
                IllegalStateException.class,
                () -> wallet.restoreOrderPoints(30, 8, 2000, 99, "请求002"));
    }

    @Test
    void pendingWechatCannotSwitchToPoints() {
        when(db.one(startsWith("SELECT * FROM users"), eq(7L)))
                .thenReturn(obj("id", 7, "identity", "C"));
        when(orders.owned(30, 7L))
                .thenReturn(
                        obj(
                                "id",
                                30,
                                "kind",
                                "goods",
                                "status",
                                "pending",
                                "payment_method",
                                "wechat",
                                "payment_state",
                                "unknown"));
        assertEquals(
                "PAYMENT_UNRESOLVED",
                assertThrows(BusinessException.class, () -> wallet.payWithPoints(7, 30)).getCode());
        verifyNoInteractions(payments);
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "update".equals(i.getMethod().getName())));
    }

    @Test
    void cashHoldsAreExcludedFromSpendablePoints() {
        when(db.one(startsWith("SELECT * FROM users"), eq(7L)))
                .thenReturn(obj("id", 7, "identity", "C"));
        when(orders.owned(30, 7L))
                .thenReturn(
                        obj(
                                "id",
                                30,
                                "kind",
                                "goods",
                                "status",
                                "pending",
                                "payment_state",
                                "unpaid",
                                "expires_at",
                                "2999-01-01T00:00:00Z",
                                "snapshot",
                                "{\"identity\":\"C\"}",
                                "total_cents",
                                50000));
        when(db.list(startsWith("SELECT * FROM wallet_lots"), eq(7L)))
                .thenReturn(
                        Collections.singletonList(
                                obj("remaining_cents", 100000, "held_cents", 60000)));
        assertEquals(
                "POINTS_INSUFFICIENT",
                assertThrows(BusinessException.class, () -> wallet.payWithPoints(7, 30)).getCode());
        verify(payments).assertNoUnresolved(7, 0);
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "update".equals(i.getMethod().getName())));
    }

    @Test
    void paidPointsOrderDoesNotSpendAgain() {
        ObjectNode order = obj("id", 30, "payment_method", "points", "payment_state", "paid");
        when(db.one(startsWith("SELECT * FROM users"), eq(7L)))
                .thenReturn(obj("id", 7, "identity", "C"));
        when(orders.owned(30, 7L)).thenReturn(order);
        when(orders.view(order)).thenReturn(order);
        assertSame(order, wallet.payWithPoints(7, 30));
        verifyNoInteractions(payments);
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "update".equals(i.getMethod().getName())));
    }
}
