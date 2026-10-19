package com.jianyanji.wallet;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 现金退款双重额度约束、结果未明占用及成功幂等回归。 */
class CashRefundServiceTest {
    /** 数据库边界桩。 */
    private Db db;
    /** 积分流水桩。 */
    private WalletService wallet;
    /** 被测现金退款服务。 */
    private CashRefundService service;
    /** 原退款记录，金额单位分。 */
    private ObjectNode row;

    @BeforeEach
    void setup() {
        db =
                mock(
                        Db.class,
                        i ->
                                "update".equals(i.getMethod().getName())
                                        ? 1
                                        : RETURNS_DEFAULTS.answer(i));
        wallet = mock(WalletService.class);
        service = new CashRefundService(db, mock(Access.class), wallet);
        row =
                obj(
                        "id",
                        8,
                        "user_id",
                        7,
                        "receipt_id",
                        21,
                        "lot_id",
                        11,
                        "amount_cents",
                        60000,
                        "number",
                        "CR001",
                        "status",
                        "processing");
        when(db.tx(org.mockito.ArgumentMatchers.<Supplier<ObjectNode>>any()))
                .thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
        when(db.one(startsWith("SELECT * FROM cash_refunds WHERE id="), eq(8L))).thenReturn(row);
    }

    /** 真实成功所需的付款凭证。 */
    private ObjectNode resolution(String outcome) {
        return obj(
                "outcome",
                outcome,
                "recipient",
                "原付款人",
                "paidAt",
                "2020-01-01T00:00:00Z",
                "proof",
                "已核对银行凭证",
                "confirmed",
                true,
                "confirmedNoPayment",
                false,
                "reason",
                "核实退款结果");
    }

    @Test
    void unknownDoesNotReleaseOrDeductEitherHold() {
        service.resolve(8, resolution("unknown"), 99, "追踪001");
        verify(db)
                .update(
                        startsWith("UPDATE cash_refunds SET status='unknown'"),
                        eq("核实退款结果"),
                        eq("追踪001"),
                        eq(99L),
                        eq(8L));
        verify(db, never()).update(startsWith("UPDATE wallet_lots"), any(Object[].class));
        verify(db, never()).update(startsWith("UPDATE company_receipts"), any(Object[].class));
        verifyNoInteractions(wallet);
    }

    @Test
    void unknownCannotReleaseWithoutProofOfNoPayment() {
        row.put("status", "unknown");
        assertThrows(
                BusinessException.class,
                () ->
                        service.resolve(
                                8,
                                resolution("failed")
                                        .put("confirmedNoPayment", true)
                                        .put("proof", ""),
                                99,
                                "追踪001"));
        assertThrows(
                BusinessException.class,
                () -> service.resolve(8, resolution("rejected"), 99, "追踪001"));
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "update".equals(i.getMethod().getName())));
    }

    @Test
    void confirmedFailureReleasesBothHoldsWithoutCashDebit() {
        row.put("status", "unknown");
        service.resolve(8, resolution("failed").put("confirmedNoPayment", true), 99, "追踪001");
        verify(db)
                .update(
                        startsWith("UPDATE wallet_lots"),
                        eq(60000L),
                        eq(0L),
                        eq(99L),
                        eq(11L),
                        eq(7L),
                        eq(21L),
                        eq(60000L),
                        eq(60000L));
        verify(db)
                .update(
                        startsWith("UPDATE company_receipts"),
                        eq(60000L),
                        eq(0L),
                        eq(99L),
                        eq(21L),
                        eq(7L),
                        eq(60000L),
                        eq(0L));
        verifyNoInteractions(wallet);
    }

    @Test
    void successDebitsOriginalLotAndRecordsSingleSource() {
        service.resolve(8, resolution("success"), 99, "追踪001");
        verify(db)
                .update(
                        startsWith("UPDATE wallet_lots"),
                        eq(60000L),
                        eq(60000L),
                        eq(99L),
                        eq(11L),
                        eq(7L),
                        eq(21L),
                        eq(60000L),
                        eq(60000L));
        verify(wallet)
                .ledger(
                        7,
                        21,
                        11,
                        null,
                        "cash_refund",
                        -60000,
                        "CR001",
                        "cash-refund:8",
                        "核实退款结果",
                        99);
    }

    @Test
    void successfulRefundIsIdempotentAndCannotChangeOutcome() {
        row.put("status", "success");
        assertSame(row, service.resolve(8, resolution("success"), 99, "追踪001"));
        assertThrows(
                BusinessException.class,
                () -> service.resolve(8, resolution("unknown"), 99, "追踪002"));
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "update".equals(i.getMethod().getName())));
        verifyNoInteractions(wallet);
    }

    @Test
    void reserveChecksBothOriginalCashAndAvailablePoints() {
        ObjectNode request =
                obj(
                        "userId",
                        7,
                        "receiptId",
                        21,
                        "amountCents",
                        60000,
                        "reason",
                        "退未使用积分",
                        "requestKey",
                        "cash-request-2026");
        when(db.one(startsWith("SELECT l.*"), eq(21L), eq(7L), eq(7L)))
                .thenReturn(
                        obj(
                                "id",
                                11,
                                "remaining_cents",
                                100000,
                                "held_cents",
                                0,
                                "amount_cents",
                                100000,
                                "cash_refunded_cents",
                                50000,
                                "cash_held_cents",
                                0));
        assertThrows(BusinessException.class, () -> service.reserve(request, 99, "追踪001"));
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "update".equals(i.getMethod().getName())));
    }

    @Test
    void identicalReserveDoesNotHoldAgainAndConflictingReserveFails() {
        when(db.one(
                        startsWith("SELECT * FROM cash_refunds WHERE request_key="),
                        eq("cash-request-2026")))
                .thenReturn(row);
        ObjectNode request =
                obj(
                        "userId",
                        7,
                        "receiptId",
                        21,
                        "amountCents",
                        60000,
                        "reason",
                        "退未使用积分",
                        "requestKey",
                        "cash-request-2026");
        assertSame(row, service.reserve(request, 99, "追踪001"));
        assertThrows(
                BusinessException.class,
                () -> service.reserve(request.put("receiptId", 22), 99, "追踪002"));
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "update".equals(i.getMethod().getName())));
    }

    @Test
    void successfulPaymentRequiresPastUtcTimeAndEvidence() {
        assertEquals(
                409,
                assertThrows(
                                BusinessException.class,
                                () ->
                                        service.resolve(
                                                8,
                                                resolution("success")
                                                        .put("paidAt", "2999-01-01T00:00:00Z"),
                                                99,
                                                "追踪001"))
                        .getStatus());
        assertThrows(
                BusinessException.class,
                () -> service.resolve(8, resolution("success").put("proof", ""), 99, "追踪001"));
        assertThrows(
                BusinessException.class,
                () ->
                        service.resolve(
                                8,
                                resolution("success").put("paidAt", "2020-01-01T08:00:00+08:00"),
                                99,
                                "追踪001"));
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "update".equals(i.getMethod().getName())));
    }
}
