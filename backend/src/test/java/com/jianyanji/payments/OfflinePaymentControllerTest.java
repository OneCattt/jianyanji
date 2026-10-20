package com.jianyanji.payments;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jianyanji.common.*;
import com.jianyanji.orders.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 首期线上接口不可绕过页面限制；普通货款核款必须具备财务权限。 */
class OfflinePaymentControllerTest {
    @Test
    void onlineActionsRejectWithoutDatabaseWritesOrChannelCalls() throws Exception {
        Db db = mock(Db.class);
        Access access = mock(Access.class);
        OrderService orders = mock(OrderService.class);
        WechatPayClient channel = mock(WechatPayClient.class);
        when(channel.configured()).thenReturn(true);
        when(access.user()).thenReturn(obj("id", 7));
        PaymentService service = new PaymentService(db, access, orders, channel);
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new PaymentController(service, access))
                        .setControllerAdvice(new ApiErrors(access))
                        .build();
        String[] actions = {
            "/api/orders/21/pay/wechat", "/api/orders/21/payment/query",
            "/api/orders/21/payment/close", "/api/admin/orders/21/payment/query",
            "/api/admin/orders/21/payment/close", "/api/payments/wechat/notify"
        };
        for (String action : actions)
            mvc.perform(
                            post(action)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"reason\":\"核对\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ONLINE_PAYMENT_DISABLED"));
        assertFalse(b(service.capabilities(), "wechatAvailable"));
        service.reconcile();
        service.scheduledReconcile();
        verifyNoInteractions(db, orders, channel);
        assertEquals(
                "ONLINE_PAYMENT_DISABLED",
                assertThrows(
                                BusinessException.class,
                                () -> service.notify(new byte[0], new MockHttpServletRequest()))
                        .getCode());
    }

    @Test
    void orderManagementPermissionCannotConfirmFinancialReceipt() throws Exception {
        Access access = mock(Access.class);
        PaymentService service = mock(PaymentService.class);
        when(access.staff("finance:manage"))
                .thenThrow(new BusinessException(403, "FORBIDDEN", "无财务权限"));
        MockMvcBuilders.standaloneSetup(new PaymentController(service, access))
                .setControllerAdvice(new ApiErrors(access))
                .build()
                .perform(
                        post("/api/admin/orders/21/receipt")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
