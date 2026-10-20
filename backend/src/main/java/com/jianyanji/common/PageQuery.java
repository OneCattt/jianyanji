package com.jianyanji.common;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Function;

/** 后台列表分页查询；只接收服务端定义的 SQL 表达式，筛选值统一绑定参数。 */
public final class PageQuery {
    /** 页码，从 1 开始，最大 100000。 */
    private final int page;
    /** 每页条数，范围 1 至 100。 */
    private final int size;
    /** 客户端筛选值，不包含可执行 SQL。 */
    private final Map<String, String> query;
    /** 服务端定义的查询表、连接和基础条件，须过滤逻辑删除。 */
    private final StringBuilder from;
    /** 按顺序绑定的筛选参数。 */
    private final List<Object> args = new ArrayList<>();

    public PageQuery(Map<String, String> query, String from) {
        this.query = query;
        this.page = number(query.getOrDefault("page", "1"), 100000);
        this.size = number(query.getOrDefault("pageSize", "20"), 100);
        this.from = new StringBuilder(from);
    }

    private static int number(String raw, int max) {
        Input.require(raw != null && raw.matches("[0-9]{1,6}"), "分页参数须为正整数");
        int value = Integer.parseInt(raw);
        Input.require(value >= 1 && value <= max, "分页参数超出允许范围");
        return value;
    }

    /** 按指定字段精确筛选；空值表示不筛选。 */
    public PageQuery eq(String key, String expression) {
        String value = query.getOrDefault(key, "").trim();
        Input.require(value.length() <= 200, "筛选内容不能超过200字");
        if (!value.isEmpty()) {
            from.append(" AND ").append(expression).append("=?");
            args.add(value);
        }
        return this;
    }

    /** 对允许的字段执行包含搜索，百分号和下划线按普通文字处理。 */
    public PageQuery keyword(String... expressions) {
        String value = query.getOrDefault("q", "").trim();
        Input.require(value.length() <= 200, "搜索内容不能超过200字");
        if (!value.isEmpty()) {
            String pattern = "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            from.append(" AND (");
            for (int i = 0; i < expressions.length; i++) {
                if (i > 0) from.append(" OR ");
                from.append(expressions[i]).append(" LIKE ? ESCAPE '!'");
                args.add(pattern);
            }
            from.append(")");
        }
        return this;
    }

    /** 先查询筛选后的总数，再只读取当前页并转换该页视图。 */
    public ObjectNode select(Db db, String columns, String order, Function<ObjectNode, ObjectNode> view) {
        long total = l(db.one("SELECT COUNT(*) AS total " + from, args.toArray()), "total");
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        ArrayNode items = arr();
        for (ObjectNode row : db.list("SELECT " + columns + " " + from + " ORDER BY " + order + " LIMIT ? OFFSET ?", pageArgs.toArray()))
            items.add(view.apply(row));
        return obj("items", items, "total", total, "page", page, "pageSize", size);
    }
}
