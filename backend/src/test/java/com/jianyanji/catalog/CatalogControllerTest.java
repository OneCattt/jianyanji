package com.jianyanji.catalog;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jianyanji.common.Access;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 内容未配置时必须返回可被 response.json 解析的 JSON null。 */
class CatalogControllerTest {
    @Test
    void unconfiguredContentReturnsLiteralJsonNullWithPermissionCheck() throws Exception {
        CatalogService catalog = mock(CatalogService.class);
        Access access = mock(Access.class);
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new CatalogController(catalog, access)).build();
        mvc.perform(get("/api/admin/content"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string("null"));
        verify(access).staff("content:manage");
    }
}
