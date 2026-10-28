package com.jianyanji.identity;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 用户快捷搜索的授权、最小字段、脱敏及筛选边界回归，数据库仅作显式替身。 */
class UserOptionsControllerTest {
    @Test
    void relevantBusinessPermissionsCanChooseUsersWithoutUserDirectoryPermission() throws Exception {
        for (String permission : new String[] {"orders:manage", "onboarding:manage", "finance:manage", "rewards:manage", "users:read", "audit:read"}) {
            Db db = mock(Db.class);
            Access access = new Access(db);
            String from = "FROM users u WHERE u.is_deleted=0";
            when(db.one("SELECT COUNT(*) AS total " + from)).thenReturn(obj("total", 1));
            when(db.list("SELECT u.id,u.nickname,u.phone,u.identity,u.identity_valid_until " + from + " ORDER BY u.id DESC LIMIT ? OFFSET ?", 20, 0))
                    .thenReturn(Collections.singletonList(obj("id", 88, "nickname", "测试客户", "phone", "13812345678", "identity", "D", "openid", "不可输出", "balance_cents", 10000)));
            mvc(db, access).perform(get("/api/admin/user-options").requestAttr("staff", obj("id", 1, "permissions", stringify(arr(permission)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].id").value(88))
                    .andExpect(jsonPath("$.items[0].phone").value("138****5678"))
                    .andExpect(jsonPath("$.items[0].openid").doesNotExist())
                    .andExpect(jsonPath("$.items[0].balance_cents").doesNotExist());
        }
    }

    @Test
    void anonymousAndUnrelatedStaffCannotQueryUsers() throws Exception {
        Db db = mock(Db.class);
        Access access = new Access(db);
        mvc(db, access).perform(get("/api/admin/user-options")).andExpect(status().isUnauthorized());
        mvc(db, access).perform(get("/api/admin/user-options").requestAttr("staff", obj("id", 1, "permissions", stringify(arr("catalog:manage")))))
                .andExpect(status().isForbidden());
        assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(call -> call.getMethod().getName().equals("list")));
    }

    @Test
    void keywordAndPaginationBindParametersAndLiteralWildcards() {
        Db db = mock(Db.class);
        Access access = mock(Access.class);
        Map<String, String> q = new HashMap<>();
        q.put("q", "昵称%_!"); q.put("page", "3");
        String from = "FROM users u WHERE u.is_deleted=0 AND (u.nickname LIKE ? ESCAPE '!' OR u.phone LIKE ? ESCAPE '!' OR CAST(u.id AS CHAR) LIKE ? ESCAPE '!')";
        String pattern = "%昵称!%!_!!%";
        when(db.one("SELECT COUNT(*) AS total " + from, pattern, pattern, pattern)).thenReturn(obj("total", 43));
        ObjectNode result = (ObjectNode) new UserOptionsController(db, access).options(q);
        assertEquals(43, l(result, "total"));
        verify(db).list("SELECT u.id,u.nickname,u.phone,u.identity,u.identity_valid_until " + from + " ORDER BY u.id DESC LIMIT ? OFFSET ?", pattern, pattern, pattern, 20, 40);
    }

    @Test
    void invalidIdentityOrIdCannotReachDatabase() {
        for (Map<String, String> q : Arrays.asList(Collections.singletonMap("identity", "staff"), Collections.singletonMap("id", "1 OR 1=1"), Collections.singletonMap("id", "9007199254740992"))) {
            Db db = mock(Db.class);
            assertThrows(BusinessException.class, () -> new UserOptionsController(db, mock(Access.class)).options(q));
            verifyNoInteractions(db);
        }
    }

    private MockMvc mvc(Db db, Access access) {
        return MockMvcBuilders.standaloneSetup(new UserOptionsController(db, access)).setControllerAdvice(new ApiErrors(access)).build();
    }
}
