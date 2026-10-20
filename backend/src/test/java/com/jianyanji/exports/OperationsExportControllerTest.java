package com.jianyanji.exports;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jianyanji.common.*;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证导出不会绕过对应业务权限，文本单元格不会被表格解释为公式。 */
class OperationsExportControllerTest {
    @Test
    void deniedExportDoesNotQueryAnyBusinessData() throws Exception {
        Db db = mock(Db.class);
        Access access = mock(Access.class);
        when(access.staff("orders:manage"))
                .thenThrow(new BusinessException(403, "FORBIDDEN", "当前账号没有订单权限"));
        MockMvcBuilders.standaloneSetup(new OperationsExportController(db, access))
                .setControllerAdvice(new ApiErrors(access))
                .build()
                .perform(get("/api/admin/exports/operations/orders"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(db);
    }

    @Test
    void authorizedExportPreservesTextAndDoesNotExecuteFormula() {
        Db db = mock(Db.class);
        Access access = mock(Access.class);
        when(db.list(anyString()))
                .thenReturn(
                        Collections.singletonList(
                                obj("id", 9, "nickname", "=1+2\n下一行", "identity", "C")));
        String csv =
                new String(
                        new OperationsExportController(db, access)
                                .export("qualifications")
                                .getBody(),
                        java.nio.charset.StandardCharsets.UTF_8);
        verify(access).staff("onboarding:manage");
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("\"'=1+2\n下一行\""));
        assertFalse(csv.contains("phone"));
        assertFalse(csv.contains("evidence"));
    }
}
