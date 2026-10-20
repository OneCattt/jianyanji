package com.jianyanji.content;

import static com.jianyanji.common.J.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jianyanji.common.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 后台写入、审核、发布和权益维护均须服务端内容权限；个人权益必须登录。 */
class ContentPermissionTest {
    @Test
    void everyAdminRouteDeniesMissingContentPermissionBeforeCallingServices() throws Exception {
        Access access = mock(Access.class);
        BrandContentService posts = mock(BrandContentService.class);
        CourseRightsService courses = mock(CourseRightsService.class);
        when(access.staff("content:manage"))
                .thenThrow(new BusinessException(403, "FORBIDDEN", "无内容权限"));
        when(access.user()).thenThrow(new BusinessException(401, "LOGIN_REQUIRED", "请先登录"));
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(
                                new BrandContentController(posts, access),
                                new CourseRightsController(courses, access))
                        .setControllerAdvice(new ApiErrors(access))
                        .build();
        mvc.perform(get("/api/admin/posts")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/posts").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/posts/5").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        for (String action : new String[] {"review", "publish", "unpublish", "remove"})
            mvc.perform(
                            post("/api/admin/posts/5/" + action)
                                    .contentType("application/json")
                                    .content("{\"reason\":\"操作\"}"))
                    .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/course-rights")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/course-rights").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/course-rights")).andExpect(status().isUnauthorized());
        verifyNoInteractions(posts, courses);
    }
}
