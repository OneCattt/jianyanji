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
