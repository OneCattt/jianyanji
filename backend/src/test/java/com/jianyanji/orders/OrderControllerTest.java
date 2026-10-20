package com.jianyanji.orders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jianyanji.common.Access;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 配送设置未配置时返回 JSON null，保留订单管理权限检查。 */
class OrderControllerTest {
    @Test
    void adminOrdersMustReturnTotalBeyondTheOldTwoHundredLimit() throws Exception {
        OrderService orders = mock(OrderService.class);
        Access access = mock(Access.class);
        when(orders.adminPage(anyMap())).thenReturn(com.jianyanji.common.J.obj(
                "items", com.jianyanji.common.J.arr(), "total", 221, "page", 11, "pageSize", 20));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderController(orders, access)).build();
        mvc.perform(get("/api/admin/orders").param("page", "11").param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(221))
                .andExpect(jsonPath("$.page").value(11));
        verify(access).staffAny("orders:manage", "finance:manage");
        verify(orders).adminPage(argThat(query -> "11".equals(query.get("page")) && "20".equals(query.get("pageSize"))));
    }

    @Test
    void callersWithoutPageKeepTheArrayContract() throws Exception {
        OrderService orders = mock(OrderService.class);
        Access access = mock(Access.class);
        when(orders.orders(null)).thenReturn(com.jianyanji.common.J.arr());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderController(orders, access)).build();
        mvc.perform(get("/api/admin/orders"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        verify(orders).orders(null);
        verify(orders, never()).adminPage(anyMap());
    }

    @Test
    void unconfiguredShippingReturnsLiteralJsonNull() throws Exception {
        OrderService orders = mock(OrderService.class);
        Access access = mock(Access.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderController(orders, access)).build();
        mvc.perform(get("/api/admin/shipping-settings"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string("null"));
        verify(access).staff("orders:manage");
    }
}
