package com.jianyanji.orders;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.catalog.CatalogService;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import com.jianyanji.payments.PaymentService;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 库存释放、报价幂等及履约完成条件的服务回归；不替代真实数据库并发验证。 */
class OrderServiceTest {
    /** 显式数据库测试替身。 */
    private Db db;
    /** 当前请求上下文测试替身。 */
    private Access access;
    /** 支付模块测试替身。 */
    private PaymentService payments;
    /** 被测订单服务。 */
    private OrderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        db = mock(Db.class);
        access = mock(Access.class);
        payments = mock(PaymentService.class);
        doAnswer(call -> ((Supplier<?>) call.getArgument(0)).get())
                .when(db)
                .tx(any(Supplier.class));
        doAnswer(
                        call -> {
                            ((Runnable) call.getArgument(0)).run();
                            return null;
                        })
                .when(db)
                .tx(any(Runnable.class));
        service = new OrderService(db, access, new CatalogService(db, access), payments);
    }

    @Test
    void historicalOrderPageIsReadFromDatabaseWithTheRequestedOffset() {
        java.util.Map<String, String> query = new java.util.HashMap<>();
        query.put("page", "11");
        query.put("pageSize", "20");
        query.put("status", "paid");
        when(db.one("SELECT COUNT(*) AS total FROM orders WHERE is_deleted=0 AND status=?", "paid"))
                .thenReturn(obj("total", 221));
        when(db.list("SELECT * FROM orders WHERE is_deleted=0 AND status=? ORDER BY id DESC LIMIT ? OFFSET ?", "paid", 20, 200))
                .thenReturn(Collections.singletonList(obj("id", 21, "snapshot", "{}")));
        ObjectNode result = service.adminPage(query);
        assertEquals(221, result.path("total").asInt());
        assertEquals(21, result.path("items").get(0).path("id").asInt());
        assertTrue(result.path("items").get(0).path("snapshot").isObject());
        verify(db).list("SELECT * FROM orders WHERE is_deleted=0 AND status=? ORDER BY id DESC LIMIT ? OFFSET ?", "paid", 20, 200);
    }

    @Test
    void unpaidOrdersMustReconfirmIdentityOrPrincipalChangesWhilePaidSnapshotsStayFrozen() {
        ObjectNode order =
                obj(
                        "kind",
                        "goods",
                        "payment_state",
                        "unpaid",
                        "user_id",
                        2,
                        "snapshot",
                        stringify(obj("identity", "C", "principalId", 9)));
        when(db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", 2L))
                .thenReturn(obj("identity", "C", "principal_id", 9));
        assertDoesNotThrow(() -> service.assertRelationsCurrent(order));
        when(db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", 2L))
                .thenReturn(obj("identity", "C", "principal_id", 10));
        assertEquals(
                "ORDER_RELATIONS_CHANGED",
                assertThrows(BusinessException.class, () -> service.assertRelationsCurrent(order))
                        .getCode());
        when(db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", 2L))
                .thenReturn(obj("identity", "B", "principal_id", 9));
        assertThrows(BusinessException.class, () -> service.assertRelationsCurrent(order));
        order.put("payment_state", "paid");
        assertDoesNotThrow(() -> service.assertRelationsCurrent(order));
        assertEquals(9, parse(s(order, "snapshot")).path("principalId").asInt());
    }

    @Test
    void closingUnpaidPackageRejectsSubmittedApplicationButUnknownPaymentCannotRelease() {
        ObjectNode packageOrder =
                obj("id", 1, "kind", "package", "status", "pending", "payment_state", "unpaid");
        when(db.one("SELECT * FROM orders WHERE id=? AND is_deleted=0", 1L))
                .thenReturn(packageOrder);
        assertTrue(service.release(packageOrder));
        verify(db).update(startsWith("UPDATE onboardings SET status='rejected'"), eq(0L), eq(1L));
        clearInvocations(db);
        packageOrder.put("payment_state", "unknown");
        assertFalse(service.release(packageOrder));
        verify(db, never()).update(startsWith("UPDATE onboardings"), anyLong(), anyLong());
        verify(db, never())
                .update(startsWith("UPDATE orders SET status='closed'"), anyLong(), anyLong());
    }

    @Test
    void staleOrderSnapshotCannotReleaseTwiceOrReleaseProcessingPayment() {
        ObjectNode pending = obj("id", 1, "status", "pending", "payment_state", "unpaid");
        ObjectNode closed = obj("id", 1, "status", "closed", "payment_state", "unpaid");
        when(db.one("SELECT * FROM orders WHERE id=? AND is_deleted=0", 1L))
                .thenReturn(pending, closed);
        when(db.list(
                        "SELECT sku_id,quantity FROM order_lines WHERE order_id=? AND is_deleted=0",
                        1L))
                .thenReturn(Collections.singletonList(obj("sku_id", 3, "quantity", 2)));
        when(db.update(
                        "UPDATE skus SET reserved=reserved-?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0 AND reserved>=?",
                        2L,
                        0L,
                        3L,
                        2L))
                .thenReturn(1);
        assertTrue(service.release(pending));
        assertFalse(service.release(pending));
        verify(db, times(1))
                .update(
                        startsWith("UPDATE skus SET reserved=reserved-?"),
                        eq(2L),
                        eq(0L),
                        eq(3L),
                        eq(2L));
        when(db.one("SELECT * FROM orders WHERE id=? AND is_deleted=0", 1L))
                .thenReturn(obj("id", 1, "status", "pending", "payment_state", "processing"));
        assertFalse(service.release(pending));
    }

    @Test
    void brokenReservationCannotMarkOrderClosed() {
        ObjectNode pending = obj("id", 1, "status", "pending", "payment_state", "unpaid");
        when(db.one("SELECT * FROM orders WHERE id=? AND is_deleted=0", 1L)).thenReturn(pending);
        when(db.list(
                        "SELECT sku_id,quantity FROM order_lines WHERE order_id=? AND is_deleted=0",
                        1L))
                .thenReturn(Collections.singletonList(obj("sku_id", 3, "quantity", 2)));
        assertThrows(BusinessException.class, () -> service.release(pending));
        verify(db, never())
                .update(startsWith("UPDATE orders SET status='closed'"), anyLong(), anyLong());
    }

    @Test
    void repeatedOrderRequestReturnsExistingOrderBeforeCheckingNewPayment() {
        ObjectNode old = obj("id", 8, "quote_id", 5, "snapshot", "{}");
        when(db.one(
                        "SELECT * FROM orders WHERE user_id=? AND request_key=? AND is_deleted=0",
                        2L,
                        "order-request-2026"))
                .thenReturn(old);
        assertEquals(
                8,
                service.create(obj("quoteId", 5, "requestKey", "order-request-2026"), obj("id", 2))
                        .path("id")
                        .asInt());
        verifyNoInteractions(payments);
        assertThrows(
                BusinessException.class,
                () ->
                        service.create(
                                obj("quoteId", 6, "requestKey", "order-request-2026"),
                                obj("id", 2)));
        verify(db, never()).insert(anyString(), any());
    }

    @Test
    void quoteFreezesStoreOwnerPrincipalPriceAndAllPolicies() {
        when(db.setting("shipping"))
                .thenReturn(
                        obj(
                                "provinces",
                                arr("浙江省"),
                                "feeCents",
                                1000,
                                "instructions",
                                "配送说明",
                                "pendingMinutes",
                                30));
        when(db.setting("aftersales")).thenReturn(obj("days", 7, "policy", "售后政策"));
        when(db.one(
                        "SELECT id,recipient,phone,province,city,district,detail FROM addresses WHERE id=? AND user_id=? AND is_deleted=0",
                        4L,
                        2L))
                .thenReturn(obj("id", 4, "province", "浙江省", "detail", "旧地址"));
        when(db.one("SELECT principal_id FROM users WHERE id=? AND is_deleted=0", 2L))
                .thenReturn(obj("principal_id", 9));
        when(db.one(startsWith("SELECT s.*,p.name AS product_name"), eq(3L)))
                .thenReturn(
                        obj(
                                "id",
                                3,
                                "product_id",
                                1,
                                "active",
                                1,
                                "product_active",
                                1,
                                "category_active",
                                1,
                                "stock",
                                10,
                                "reserved",
                                0,
                                "retail_cents",
                                10000,
                                "store_cents",
                                8000,
                                "principal_cents",
                                6000,
                                "version",
                                2));
        ObjectNode quote =
                service.buildQuote(
                        obj("id", 2, "identity", "C"), 4, arr(obj("skuId", 3, "quantity", 2)));
        assertEquals(15000, quote.path("totalCents").asInt());
        assertEquals(9, quote.path("principalId").asInt());
        assertEquals(3500, quote.path("lines").get(0).path("principalCents").asInt());
        assertEquals(7, quote.path("afterSalesDays").asInt());
        assertEquals("旧地址", quote.path("address").path("detail").asText());
    }

    @Test
    void priceChangeAfterQuoteFailsBeforeInventoryReservation() {
        OrderService spy = spy(service);
        ObjectNode original =
                obj(
                        "address",
                        obj("id", 4),
                        "lines",
                        arr(obj("skuId", 3, "quantity", 2)),
                        "totalCents",
                        10000);
        when(db.one(startsWith("SELECT * FROM quotes WHERE id=?"), eq(5L), eq(2L), anyString()))
                .thenReturn(obj("snapshot", stringify(original)));
        doReturn(
                        obj(
                                "address",
                                obj("id", 4),
                                "lines",
                                arr(obj("skuId", 3, "quantity", 2)),
                                "totalCents",
                                12000))
                .when(spy)
                .buildQuote(any(), eq(4L), any());
        assertThrows(
                BusinessException.class,
                () ->
                        spy.create(
                                obj("quoteId", 5, "requestKey", "order-request-2026"),
                                obj("id", 2)));
        verify(db, never())
                .update(startsWith("UPDATE skus"), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void fullyRefundedWithoutRealReceiptNeverCompletesAndPendingAftersalesBlockCompletion() {
        when(db.one("SELECT * FROM orders WHERE id=? AND is_deleted=0", 1L))
                .thenReturn(obj("id", 1, "payment_state", "partially_refunded"));
        when(db.list("SELECT * FROM order_lines WHERE order_id=? AND is_deleted=0 ORDER BY id", 1L))
                .thenReturn(
                        Collections.singletonList(
                                obj(
                                        "quantity",
                                        2,
                                        "refunded_qty",
                                        2,
                                        "received_qty",
                                        0,
                                        "shipped_qty",
                                        0,
                                        "refund_held",
                                        0)));
        service.refreshFulfillment(1);
        verify(db)
                .update(
                        startsWith("UPDATE orders SET status=?"),
                        eq("paid"),
                        eq("paid"),
                        anyString(),
                        eq(0L),
                        eq(1L));
        when(db.list("SELECT * FROM order_lines WHERE order_id=? AND is_deleted=0 ORDER BY id", 1L))
                .thenReturn(
                        Arrays.asList(
                                obj(
                                        "quantity",
                                        2,
                                        "refunded_qty",
                                        0,
                                        "received_qty",
                                        2,
                                        "shipped_qty",
                                        2,
                                        "refund_held",
                                        1)));
        service.refreshFulfillment(1);
        verify(db)
                .update(
                        startsWith("UPDATE orders SET status=?"),
                        eq("shipped"),
                        eq("shipped"),
                        anyString(),
                        eq(0L),
                        eq(1L));
    }
}
