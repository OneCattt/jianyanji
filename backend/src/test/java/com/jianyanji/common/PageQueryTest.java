package com.jianyanji.common;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 分页查询回归：历史页、筛选计数一致、参数边界及空结果。 */
class PageQueryTest {
    @Test
    void pageElevenUsesOffsetTwoHundredAndCountsTheSameFilters() {
        Db db = mock(Db.class);
        Map<String, String> query = new HashMap<>();
        query.put("page", "11");
        query.put("pageSize", "20");
        query.put("status", "paid");
        query.put("q", "旧单%_!");
        String from = "FROM orders WHERE is_deleted=0 AND status=? AND (number LIKE ? ESCAPE '!' OR CAST(user_id AS CHAR) LIKE ? ESCAPE '!')";
        String pattern = "%旧单!%!_!!%";
        when(db.one("SELECT COUNT(*) AS total " + from, "paid", pattern, pattern))
                .thenReturn(obj("total", 221));
        when(db.list("SELECT * " + from + " ORDER BY id DESC LIMIT ? OFFSET ?", "paid", pattern, pattern, 20, 200))
                .thenReturn(Collections.singletonList(obj("id", 21)));
        ObjectNode result = new PageQuery(query, "FROM orders WHERE is_deleted=0")
                .eq("status", "status").keyword("number", "CAST(user_id AS CHAR)")
                .select(db, "*", "id DESC", row -> row);
        assertEquals(221, result.path("total").asInt());
        assertEquals(21, result.path("items").get(0).path("id").asInt());
        assertEquals(11, result.path("page").asInt());
        assertEquals(20, result.path("pageSize").asInt());
    }

    @Test
    void emptyFiltersDoNotRestrictRecordsAndEmptyPageKeepsTotal() {
        Db db = mock(Db.class);
        when(db.one("SELECT COUNT(*) AS total FROM refunds WHERE is_deleted=0"))
                .thenReturn(obj("total", 0));
        ObjectNode result = new PageQuery(Collections.emptyMap(), "FROM refunds WHERE is_deleted=0")
                .eq("status", "status").keyword("number")
                .select(db, "*", "id DESC", row -> row);
        assertEquals(0, result.path("total").asInt());
        assertEquals(0, result.path("items").size());
        verify(db).list("SELECT * FROM refunds WHERE is_deleted=0 ORDER BY id DESC LIMIT ? OFFSET ?", 20, 0);
    }

    @Test
    void invalidPageParametersFailBeforeQuerying() {
        for (String page : Arrays.asList("0", "-1", "1.5", "100001", "1 OR 1=1", "")) {
            assertThrows(BusinessException.class, () -> new PageQuery(Collections.singletonMap("page", page), "FROM orders WHERE is_deleted=0"));
        }
        for (String size : Arrays.asList("0", "101", "1.1")) {
            assertThrows(BusinessException.class, () -> new PageQuery(Collections.singletonMap("pageSize", size), "FROM orders WHERE is_deleted=0"));
        }
    }
}
