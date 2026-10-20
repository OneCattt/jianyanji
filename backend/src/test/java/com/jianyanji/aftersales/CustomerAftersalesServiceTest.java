package com.jianyanji.aftersales;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import com.jianyanji.orders.OrderService;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 在线受理权限和原金额测试；数据库回滚及并发占用另由 MySQL 集成测试验证。 */
class CustomerAftersalesServiceTest {
    /** 数据访问测试替身。 */
    private Db db;
    /** 本人订单权限测试替身。 */
    private OrderService orders;
    /** 真实退款核价服务。 */
    private AftersalesService aftersales;
    /** 被测本人受理边界。 */
    private CustomerAftersalesService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        db =
                mock(
                        Db.class,
                        i -> {
                            if ("insert".equals(i.getMethod().getName())) return 91L;
                            if ("update".equals(i.getMethod().getName())) return 1;
                            return RETURNS_DEFAULTS.answer(i);
                        });
        orders = mock(OrderService.class);
        Access access = mock(Access.class);
        aftersales = new AftersalesService(db, access, orders, null, null, null, null);
        service = new CustomerAftersalesService(db, orders, aftersales);
        doAnswer(i -> ((Supplier<?>) i.getArgument(0)).get()).when(db).tx(any(Supplier.class));
        when(access.requestId()).thenReturn("customer-request");
        when(orders.owned(21L, null))
                .thenReturn(
                        obj(
                                "id",
                                21,
                                "payment_state",
                                "paid",
                                "kind",
                                "goods",
                                "shipping_cents",
                                100,
                                "total_cents",
                                10603,
                                "snapshot",
                                stringify(obj("afterSalesDays", 7, "afterSalesPolicy", "按原订单政策"))));
        when(db.one(startsWith("SELECT * FROM order_lines"), eq(31L), eq(21L)))
                .thenReturn(
                        obj(
                                "id",
                                31,
                                "sku_id",
                                41,
                                "quantity",
                                3,
                                "refunded_qty",
                                0,
                                "refund_held",
                                0,
                                "shipped_qty",
                                0,
                                "refunded_unshipped",
                                0,
                                "held_unshipped",
                                0,
                                "unit_cents",
                                3501));
        when(db.update(
                        startsWith("UPDATE order_lines"),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong()))
                .thenReturn(1);
        when(db.one("SELECT * FROM refunds WHERE id=? AND is_deleted=0", 91L))
                .thenReturn(obj("id", 91, "lines", "[]", "policy_snapshot", "{}"));
    }

    private ObjectNode body(int quantity) {
        return obj(
                "orderId",
                21,
                "items",
                arr(obj("lineId", 31, "quantity", quantity)),
                "reason",
                "商品不适合",
                "requestKey",
                "customer-unique-2026");
    }

    @Test
    void crossUserCannotCreateOrReplayRefund() {
        when(orders.owned(21L, 8L)).thenThrow(BusinessException.notFound("订单不存在"));
        assertThrows(BusinessException.class, () -> service.request(body(1), 8));
        verify(db, never()).one(contains("request_key"), anyString());
        verify(db, never()).insert(anyString(), any());
    }

    @Test
    void original3501CentsTimesTwoCannotBeRepricedByBuyer() {
        service.request(body(2), 8);
        verify(orders).owned(21L, 8L);
        org.mockito.invocation.Invocation insert =
                mockingDetails(db).getInvocations().stream()
                        .filter(i -> i.getMethod().getName().equals("insert"))
                        .findFirst()
                        .get();
        assertEquals(7002L, ((Number) insert.getArgument(4)).longValue());
        assertEquals(0L, ((Number) insert.getArgument(5)).longValue());
        assertEquals(7002L, ((Number) insert.getArgument(6)).longValue());
        assertEquals(8L, ((Number) insert.getArgument(12)).longValue());
    }

    @Test
    void buyerCannotSupplyMoneyOverrideOrExceptionApproval() {
        for (String field :
                new String[] {
                    "shippingCents",
                    "totalCents",
                    "exceptionReason",
                    "identityDecision",
                    "status",
                    "userId"
                }) {
            ObjectNode command = body(1);
            command.put(field, "伪造");
            assertThrows(BusinessException.class, () -> service.request(command, 8));
        }
        verifyNoInteractions(orders);
    }

    @Test
    void rejectsOverQuantityAndOccupiedGoodsBeforeInsert() {
        assertThrows(BusinessException.class, () -> service.request(body(4), 8));
        when(db.one(startsWith("SELECT * FROM order_lines"), eq(31L), eq(21L)))
                .thenReturn(obj("quantity", 3, "refunded_qty", 1, "refund_held", 1));
        assertThrows(BusinessException.class, () -> service.request(body(2), 8));
        verify(db, never()).insert(anyString(), any());
    }

    @Test
    void userCannotReceiveFinanceExceptionForExpiredOrder() {
        when(orders.owned(21L, null))
                .thenReturn(
                        obj(
                                "payment_state",
                                "paid",
                                "received_at",
                                "2020-01-01T00:00:00Z",
                                "snapshot",
                                stringify(obj("afterSalesDays", 7, "afterSalesPolicy", "原政策"))));
        assertThrows(BusinessException.class, () -> service.request(body(1), 8));
        verify(db, never()).insert(anyString(), any());
    }
}
