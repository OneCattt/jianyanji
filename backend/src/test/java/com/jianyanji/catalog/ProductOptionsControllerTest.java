package com.jianyanji.catalog;

import static com.jianyanji.common.J.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jianyanji.common.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 选择器权限回归，跨模块只读不要求商品编辑权，原订单和寄存仍限履约权限。 */
class ProductOptionsControllerTest {
    @Test
    void lightweightCatalogLookupUsesAnyRelevantPermission() throws Exception {
        Access access = mock(Access.class);
        ProductOptionsService service = mock(ProductOptionsService.class);
        when(service.page(anyMap())).thenReturn(obj("items", arr().add(obj("id", 23, "label", "修护精华 · 30ml", "active", true)), "total", 1));
        MockMvcBuilders.standaloneSetup(new ProductOptionsController(service, access)).build()
                .perform(get("/api/admin/product-options").param("kind", "sku").param("q", "修护"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(23))
                .andExpect(jsonPath("$.items[0].stock").doesNotExist());
        verify(access).staffAny("catalog:manage", "onboarding:manage", "content:manage", "orders:manage", "finance:manage");
        verify(access, never()).staff("catalog:manage");
    }

    @Test
    void deniedUsersCannotReadCatalogOrOtherUsersSources() throws Exception {
        for (String kind : new String[] {"sku", "product", "orderLine", "lot"}) {
            Access access = mock(Access.class);
            ProductOptionsService service = mock(ProductOptionsService.class);
            BusinessException denied = new BusinessException(403, "FORBIDDEN", "无权限");
            when(access.staffAny("catalog:manage", "onboarding:manage", "content:manage", "orders:manage", "finance:manage")).thenThrow(denied);
            when(access.staff("orders:manage")).thenThrow(denied);
            MockMvcBuilders.standaloneSetup(new ProductOptionsController(service, access))
                    .setControllerAdvice(new ApiErrors(access)).build()
                    .perform(get("/api/admin/product-options").param("kind", kind))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(service);
        }
    }
}
