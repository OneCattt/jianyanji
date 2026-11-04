package com.jianyanji.workbench;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.jianyanji.common.Db;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 验证工作台待办只按权限返回计数，无权项不泄漏且不额外读取列表。 */
class WorkbenchServiceTest {
    private Set<String> keys(JsonNode result) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : result.path("items")) values.add(s(item, "key"));
        return values;
    }

    @Test
    void ordersPermissionOnlyReturnsOperationalTodos() {
        Db db = mock(Db.class);
        when(db.one(anyString())).thenReturn(obj("total", 2));
        JsonNode result =
                new WorkbenchService(db).todos(obj("permissions", stringify(arr("orders:manage"))));
        assertEquals(
                new LinkedHashSet<>(
                        Arrays.asList(
                                "pickup_review", "pending_shipping", "refund_review",
                                "storage_expiring")),
                keys(result));
        verify(db, times(4)).one(anyString());
        assertFalse(s(result, "asOf").isEmpty());
    }

    @Test
    void financePermissionOnlyReturnsFinanceTodosWithoutFakeReceiptCount() {
        Db db = mock(Db.class);
        when(db.one(anyString())).thenReturn(obj("total", 1));
        JsonNode result =
                new WorkbenchService(db).todos(obj("permissions", stringify(arr("finance:manage"))));
        assertEquals(
                new LinkedHashSet<>(Arrays.asList("receipt", "refund_finance")),
                keys(result));
        assertTrue(result.path("items").get(0).path("count").isNull());
        verify(db, times(1)).one(anyString());
    }

    @Test
    void staffWithoutBusinessPermissionGetsEmptyItemsAndNoQuery() {
        Db db = mock(Db.class);
        JsonNode result = new WorkbenchService(db).todos(obj("permissions", stringify(arr("users:read"))));
        assertEquals(0, result.path("items").size());
        verify(db, never()).one(anyString());
    }
}
