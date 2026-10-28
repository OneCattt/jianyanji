package com.jianyanji.identity;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jianyanji.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 用户名称和地址选项的权限、批量参数及用户隔离回归。 */
class AdminUserLookupControllerTest {
    private MockMvc mvc(Db db) {
        Access access = new Access(db);
        return MockMvcBuilders.standaloneSetup(new AdminUserLookupController(db, access)).setControllerAdvice(new ApiErrors(access)).build();
    }

    @Test
    void namesDeduplicateAndReturnOnlyIdentifiersAndNicknames() throws Exception {
        Db db = mock(Db.class);
        when(db.list("SELECT id,nickname FROM users WHERE is_deleted=0 AND id IN (?,?)", 7L, 8L)).thenReturn(Arrays.asList(obj("id",7,"nickname","本地客户","phone","不应输出","openid","不应输出"), obj("id",8,"nickname","")));
        mvc(db).perform(get("/api/admin/user-labels").param("ids","7,8,7").requestAttr("staff",obj("id",1,"permissions",stringify(arr("orders:manage")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].nickname").value("本地客户")).andExpect(jsonPath("$.items[0].phone").doesNotExist()).andExpect(jsonPath("$.items[0].openid").doesNotExist());
        verify(db).list("SELECT id,nickname FROM users WHERE is_deleted=0 AND id IN (?,?)", 7L, 8L);
    }

    @Test
    void anonymousAndUnrelatedPermissionsCannotReadNamesOrAddresses() throws Exception {
        Db db = mock(Db.class);
        mvc(db).perform(get("/api/admin/user-labels").param("ids","1")).andExpect(status().isUnauthorized());
        mvc(db).perform(get("/api/admin/user-labels").param("ids","1").requestAttr("staff",obj("id",1,"permissions",stringify(arr("catalog:manage"))))).andExpect(status().isForbidden());
        mvc(db).perform(get("/api/admin/users/7/address-options").requestAttr("staff",obj("id",1,"permissions",stringify(arr("finance:manage"))))).andExpect(status().isForbidden());
        assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(call -> Arrays.asList("list","one").contains(call.getMethod().getName())));
    }

    @Test
    void malformedOrOversizedNameBatchesNeverReachDatabase() {
        for(String ids : Arrays.asList("", "0", "-1", "1,", "1 OR 1=1", "9007199254740992", String.join(",", Collections.nCopies(101,"1")))) {
            Db db = mock(Db.class);
            assertThrows(BusinessException.class, () -> new AdminUserLookupController(db,mock(Access.class)).labels(ids));
            verifyNoInteractions(db);
        }
    }

    @Test
    void addressPaginationAlwaysBindsPathUserAndExcludesDeletedData() {
        Db db = mock(Db.class);
        Map<String,String> query = new HashMap<>(); query.put("page","2"); query.put("q","客户%_");
        String from = "FROM addresses a WHERE a.is_deleted=0 AND a.user_id=? AND (a.recipient LIKE ? ESCAPE '!' OR a.phone LIKE ? ESCAPE '!' OR CONCAT(a.province,a.city,a.district,a.detail) LIKE ? ESCAPE '!')";
        String pattern = "%客户!%!_%";
        when(db.one("SELECT COUNT(*) AS total " + from, "7", pattern, pattern, pattern)).thenReturn(obj("total",23));
        new AdminUserLookupController(db,mock(Access.class)).addresses(7,query);
        verify(db).list("SELECT a.id,a.recipient,a.phone,a.province,a.city,a.district,a.detail,a.is_default " + from + " ORDER BY a.is_default DESC,a.id DESC LIMIT ? OFFSET ?", "7",pattern,pattern,pattern,20,20);
    }
}
