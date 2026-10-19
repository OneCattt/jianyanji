package com.jianyanji.payments;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import com.jianyanji.orders.OrderService;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 本地结算的凭据幂等和库存守卫测试，跨进程事务仍需 MySQL 联调。 */
class PaymentServiceTest {
    /** 数据访问测试替身。 */
    private Db db;
    /** 订单服务测试替身。 */
    private OrderService orders;
    /** 被测支付服务。 */
    private PaymentService service;
    /** 待结算订单样本，金额为整数分。 */
    private ObjectNode order;

    @BeforeEach
    void prepare() {
        db = mock(Db.class);
        orders = mock(OrderService.class);
        service = new PaymentService(db, mock(Access.class), orders, mock(WechatPayClient.class));
        order =
                obj(
                        "id",
                        21,
                        "user_id",
                        1,
                        "status",
                        "pending",
                        "payment_state",
                        "paying",
                        "payment_method",
                        "wechat",
                        "total_cents",
                        1000);
        when(orders.owned(21L, null)).thenAnswer(i -> order.deepCopy());
    }

    @Test
    void identicalReceiptIsIdempotentAndDifferentTransactionCannotSettle() {
        ObjectNode receipt =
                obj("method", "wechat", "transaction_id", "wx-original", "cents", 1000);
        when(db.one(startsWith("SELECT * FROM payments"), anyLong())).thenReturn(receipt);
        assertTrue(b(service.settlePaid(order, "wechat", "wx-original"), "alreadyPaid"));
        assertThrows(BusinessException.class, () -> service.settlePaid(order, "wechat", "other"));
        assertEquals(0, sqlCalls("update", ""));
    }

    @Test
    void stockMismatchPreventsReceiptAndOrderPaidUpdate() {
        when(db.list(startsWith("SELECT sku_id,quantity"), anyLong()))
                .thenReturn(Arrays.asList(obj("sku_id", 41, "quantity", 2)));
        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () -> service.settlePaid(order, "wechat", "wx-original"));
        assertEquals("STOCK_SETTLEMENT_CONFLICT", error.getCode());
        assertEquals(0, sqlCalls("insert", ""));
        assertEquals(0, sqlCalls("update", "UPDATE orders SET status='paid'"));
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
    void unresolvedOriginalTradeBlocksSwitchingPaymentMethod() {
        order.put("payment_state", "unpaid");
        when(db.one(startsWith("SELECT p.id FROM payment_attempts"), eq(1L), eq(0L)))
                .thenReturn(obj("id", 9));
        assertEquals(
                "PAYMENT_UNRESOLVED",
                assertThrows(
                                BusinessException.class,
                                () -> service.settlePaid(order, "points", "points:21"))
                        .getCode());
        assertEquals(0, sqlCalls("update", ""));
    }
}
