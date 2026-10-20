package com.jianyanji.exports;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jianyanji.common.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 财务导出沿用功能权限、必要筛选和字段白名单，不以导出扩大读取范围。 */
class FinanceExportControllerTest {
    @Test
    void eachKindRequiresItsOriginalBusinessPermissionBeforeQuery() throws Exception {
        String[][] cases = {
            {"receipts", "finance:manage"}, {"points", "finance:manage"},
            {"cash-refunds", "finance:manage"}, {"payments", "orders:manage"},
            {"refunds", "orders:manage"}, {"rewards", "rewards:manage"},
            {"settlements", "rewards:manage"}, {"reversals", "rewards:manage"},
            {"offsets", "rewards:manage"}, {"recoveries", "rewards:manage"}
        };
        for (String[] entry : cases) {
            Db db = mock(Db.class);
            Access access = mock(Access.class);
            when(access.staff(entry[1]))
                    .thenThrow(new BusinessException(403, "FORBIDDEN", "无业务查询权限"));
            MockMvcBuilders.standaloneSetup(new FinanceExportController(db, access))
                    .setControllerAdvice(new ApiErrors(access))
                    .build()
                    .perform(get("/api/admin/exports/finance/" + entry[0]))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(db);
        }
    }

    @Test
    void mandatoryFiltersPreventUnboundedPointsAndPaymentQueries() {
        Db db = mock(Db.class);
        FinanceExportController controller = new FinanceExportController(db, mock(Access.class));
        assertThrows(BusinessException.class, () -> controller.export("points", null, null, null));
        assertThrows(
                BusinessException.class, () -> controller.export("payments", null, null, null));
        assertThrows(
                BusinessException.class, () -> controller.export("settlements", null, 9L, null));
        verifyNoInteractions(db);
    }

    @Test
    void exportWhitelistsColumnsEscapesCsvAndRecordsScopeInAudit() {
        Db db = mock(Db.class);
        Access access = mock(Access.class);
        when(db.list(anyString(), eq(42L), eq(9L), eq(88L)))
                .thenReturn(
                        Collections.singletonList(
                                obj(
                                        "id",
                                        8,
                                        "reference",
                                        " \t=1+2\n\"核对\"",
                                        "delta_cents",
                                        -100,
                                        "proof",
                                        "不应导出的凭证",
                                        "account_ref",
                                        "不应导出的账户")));
        String csv =
                new String(
                        new FinanceExportController(db, access)
                                .export("points", 42L, 9L, 88L)
                                .getBody(),
                        StandardCharsets.UTF_8);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).list(sql.capture(), eq(42L), eq(9L), eq(88L));
        assertTrue(
                sql.getValue()
                        .contains("t.is_deleted=0 AND t.user_id=? AND t.order_id=? AND t.id<?"));
        assertTrue(sql.getValue().endsWith("ORDER BY t.id DESC LIMIT 200"));
        assertFalse(sql.getValue().contains("proof"));
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("\"' \t=1+2\n\"\"核对\"\"\""));
        assertFalse(csv.contains("不应导出"));
        verify(access)
                .audit(
                        eq("finance.export"),
                        eq("export"),
                        eq("points"),
                        contains("userId=42 orderId=9 beforeId=88 rows=1"));
    }
}
