package com.jianyanji.orders;

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

/** 履约售后交叉边界与物流幂等回归，渠道和数据库替身仅用于本地单元测试。 */
class FulfillmentServiceTest {
    /** 数据访问测试替身。 */
    private Db db;
    /** 订单模块测试替身。 */
    private OrderService orders;
    /** 审计请求测试替身。 */
    private Access access;
    /** 被测履约服务。 */
    private FulfillmentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        db = mock(Db.class);
        orders = mock(OrderService.class);
        access = mock(Access.class);
        doAnswer(call -> ((Supplier<?>) call.getArgument(0)).get())
                .when(db)
                .tx(any(Supplier.class));
        service = new FulfillmentService(db, orders, access);
    }

    @Test
    void replayedShipmentDoesNotShipAgainAndChangedContentIsRejected() {
        ObjectNode body =
                obj(
                        "carrier",
                        "顺丰",
                        "tracking",
                        "SF1234",
                        "requestKey",
                        "ship-request-2026",
                        "reason",
                        "出库",
                        "lines",
                        arr(obj("lineId", 5, "quantity", 2)));
        ObjectNode old =
                obj(
                        "id",
                        7,
                        "carrier",
                        "顺丰",
                        "tracking",
                        "SF1234",
                        "lines",
                        stringify(body.get("lines")));
        when(orders.owned(1L, null))
                .thenReturn(obj("id", 1, "status", "completed", "payment_state", "paid"));
        when(db.one(
                        "SELECT * FROM shipments WHERE order_id=? AND request_key=? AND is_deleted=0",
                        1L,
                        "ship-request-2026"))
                .thenReturn(old);
        assertEquals(7, service.ship(1, body, 9).path("id").asInt());
        verify(db, never()).update(anyString(), any());
        body.put("tracking", "SF5678");
        assertThrows(BusinessException.class, () -> service.ship(1, body, 9));
    }

    @Test
    void aftersalesHeldOrRefundedUnshippedQuantityCannotBeShipped() {
        when(orders.owned(1L, null))
                .thenReturn(obj("id", 1, "status", "paid", "payment_state", "paid"));
        ObjectNode body =
                obj(
                        "carrier",
                        "顺丰",
                        "tracking",
                        "SF1234",
                        "requestKey",
                        "ship-request-2026",
                        "reason",
                        "出库",
                        "lines",
                        arr(obj("lineId", 5, "quantity", 3)));
        assertThrows(BusinessException.class, () -> service.ship(1, body, 9));
        verify(db)
                .update(
                        contains("quantity-shipped_qty-refunded_unshipped-held_unshipped-COALESCE"),
                        eq(3L),
                        eq(9L),
                        eq(5L),
                        eq(1L),
                        eq(3L));
        verify(db, never())
                .insert(
                        startsWith("INSERT INTO shipments"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void unresolvedAftersalesBlocksReceiptBeforeAnyQuantityUpdate() {
        when(orders.owned(1L, 2L))
                .thenReturn(obj("id", 1, "status", "shipped", "payment_state", "paid"));
        when(db.one("SELECT * FROM shipments WHERE id=? AND order_id=? AND is_deleted=0", 7L, 1L))
                .thenReturn(obj("id", 7, "received_at", null, "lines", "[]"));
        when(orders.unsettledAftersales(1)).thenReturn(true);
        assertThrows(BusinessException.class, () -> service.receive(1, 7, 2));
        verify(db, never()).update(anyString(), any());
        verify(orders, never()).refreshFulfillment(anyLong());
    }

    @Test
    void receiptCountsOnlyUnrefundedRemainderThenRefreshesOrder() {
        ObjectNode order = obj("id", 1, "status", "shipped", "payment_state", "partially_refunded");
        when(orders.owned(1L, 2L)).thenReturn(order);
        when(orders.owned(1L, null)).thenReturn(order);
        when(db.one("SELECT * FROM shipments WHERE id=? AND order_id=? AND is_deleted=0", 7L, 1L))
                .thenReturn(
                        obj(
                                "id",
                                7,
                                "received_at",
                                null,
                                "lines",
                                "[{\"lineId\":5,\"quantity\":4}]"));
        when(db.one("SELECT * FROM order_lines WHERE id=? AND order_id=? AND is_deleted=0", 5L, 1L))
                .thenReturn(
                        obj(
                                "id",
                                5,
                                "quantity",
                                6,
                                "shipped_qty",
                                6,
                                "refunded_qty",
                                3,
                                "refunded_unshipped",
                                0,
                                "received_qty",
                                1));
        service.receive(1, 7, 2);
        verify(db)
                .update(
                        startsWith("UPDATE order_lines SET received_qty=received_qty+?"),
                        eq(2L),
                        eq(2L),
                        eq(5L),
                        eq(1L));
        verify(orders).refreshFulfillment(1);
        verify(access).audit("shipment.receive", "shipment", 7L, "用户确认本人实际收到的商品");
    }

    @Test
    void logisticsCorrectionKeepsOriginalValuesAndRequestWithoutChangingShippedCounts() {
        when(db.one("SELECT * FROM shipments WHERE id=? AND is_deleted=0", 7L))
                .thenReturn(obj("id", 7, "carrier", "原物流", "tracking", "OLD1234"));
        when(access.requestId()).thenReturn("correction-request");
        service.correct(7, obj("carrier", "顺丰", "tracking", "SF5678", "reason", "更正录入错误"), 9);
        verify(db)
                .insert(
                        startsWith("INSERT INTO shipment_corrections"),
                        eq(7L),
                        eq("原物流"),
                        eq("OLD1234"),
                        eq("顺丰"),
                        eq("SF5678"),
                        eq("更正录入错误"),
                        eq("correction-request"),
                        eq(9L),
                        eq(9L));
        verify(db, never()).update(startsWith("UPDATE order_lines"), any());
    }
}
