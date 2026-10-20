package com.jianyanji.membership;

import static com.jianyanji.common.J.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jianyanji.common.Access;
import com.jianyanji.common.ApiErrors;
import com.jianyanji.common.BusinessException;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 规格选择仅依赖入驻权限，权限被拒绝时不得读取商品数据。 */
class MembershipSkuControllerTest {
    @Test
    void onboardingStaffCanSelectSkuWithoutCatalogPermission() throws Exception {
        Access access = mock(Access.class);
        MembershipService membership = mock(MembershipService.class);
        when(access.staff("onboarding:manage")).thenReturn(obj("id", 7));
        when(membership.packageSkuOptions())
                .thenReturn(Collections.singletonList(
                        obj("id", 11, "productName", "修护精华", "skuName", "30ml", "unit", "瓶")));
        MockMvcBuilders.standaloneSetup(new MembershipController(access, membership))
                .build()
                .perform(get("/api/admin/membership-skus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(11))
                .andExpect(jsonPath("$[0].productName").value("修护精华"))
                .andExpect(jsonPath("$[0].skuName").value("30ml"));
        verify(access).staff("onboarding:manage");
        verify(access, never()).staff("catalog:manage");
    }

    @Test
    void deniedStaffCannotReadSkuOptions() throws Exception {
        Access access = mock(Access.class);
        MembershipService membership = mock(MembershipService.class);
        when(access.staff("onboarding:manage"))
                .thenThrow(new BusinessException(403, "FORBIDDEN", "无入驻管理权限"));
        MockMvcBuilders.standaloneSetup(new MembershipController(access, membership))
                .setControllerAdvice(new ApiErrors(access))
                .build()
                .perform(get("/api/admin/membership-skus"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(membership);
    }
}
