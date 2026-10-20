package com.jianyanji.orders;

import static com.jianyanji.common.J.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.ApiErrors;
import com.jianyanji.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证批量勾选路由优先于规格编号路由、本人会话边界和整袋汇总响应。 */
class CartControllerTest {
    @Test
    void selectionUsesAuthenticatedUserAndReturnsGlobalSummary() throws Exception {
        OrderService orders = mock(OrderService.class);
        Access access = mock(Access.class);
        ObjectNode user = obj("id", 7, "identity", "C");
        ObjectNode body = obj("selected", true);
        when(access.user()).thenReturn(user);
        when(orders.selectCart(body, 7L)).thenReturn(obj("ok", true));
        when(orders.cartSummary(user)).thenReturn(obj("totalKinds", 41, "kinds", 41,
                "pieces", 48, "totalCents", 336000, "allSelected", true));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderController(orders, access)).build();
        mvc.perform(put("/api/cart/selection").contentType(MediaType.APPLICATION_JSON).content(stringify(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allSelected").value(true))
                .andExpect(jsonPath("$.totalKinds").value(41))
                .andExpect(jsonPath("$.pieces").value(48))
                .andExpect(jsonPath("$.totalCents").value(336000));
        verify(orders).selectCart(body, 7L);
        verify(orders).cartSummary(user);
        verifyNoMoreInteractions(orders);
    }

    @Test
    void unauthenticatedSelectionCannotReachCartWrites() throws Exception {
        OrderService orders = mock(OrderService.class);
        Access access = mock(Access.class);
        when(access.user()).thenThrow(new BusinessException(401, "UNAUTHORIZED", "请先登录"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderController(orders, access))
                .setControllerAdvice(new ApiErrors(access)).build();
        mvc.perform(put("/api/cart/selection").contentType(MediaType.APPLICATION_JSON).content("{\"selected\":true}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(orders);
    }
}
