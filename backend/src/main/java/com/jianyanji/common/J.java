package com.jianyanji.common;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;

/** JSON 合同工具，快照保留历史字段名称与空值语义，金额统一使用整数分。 */
public final class J {
    /** 线程安全的 JSON 映射器，不启用宽松数字或多态反序列化。 */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private J() {}

    public static ObjectNode obj(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("JSON 键值数量不匹配");
        ObjectNode result = MAPPER.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2)
            result.set(String.valueOf(pairs[i]), json(pairs[i + 1]));
        return result;
    }

    public static ArrayNode arr(Object... values) {
        ArrayNode a = MAPPER.createArrayNode();
        for (Object v : values) a.add(json(v));
        return a;
    }

    public static JsonNode json(Object v) {
        return v instanceof JsonNode ? (JsonNode) v : MAPPER.valueToTree(v);
    }

    public static JsonNode parse(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 数据格式错误", e);
        }
    }

    public static String stringify(Object v) {
        try {
            return MAPPER.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 数据序列化失败", e);
        }
    }

    public static String s(JsonNode v, String k) {
        return !has(v, k) ? "" : v.get(k).asText();
    }

    public static long l(JsonNode v, String k) {
        return !has(v, k) ? 0 : v.get(k).asLong();
    }

    public static boolean b(JsonNode v, String k) {
        return has(v, k) && (v.get(k).isBoolean() ? v.get(k).asBoolean() : v.get(k).asInt() == 1);
    }

    public static boolean has(JsonNode v, String k) {
        return v != null && v.hasNonNull(k);
    }

    public static String now() {
        return new java.time.format.DateTimeFormatterBuilder()
                .appendInstant(3)
                .toFormatter()
                .format(Instant.now());
    }

    public static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static List<JsonNode> list(JsonNode v) {
        List<JsonNode> a = new ArrayList<>();
        if (v != null && v.isArray()) v.forEach(a::add);
        return a;
    }

    public static String effectiveIdentity(JsonNode user) {
        if (user == null) return "D";
        if (has(user, "identity_valid_until")
                && !s(user, "identity_valid_until").isEmpty()
                && Instant.parse(s(user, "identity_valid_until")).compareTo(Instant.now()) <= 0)
            return "D";
        return s(user, "identity");
    }

    public static ObjectNode publicUser(ObjectNode row) {
        String role = effectiveIdentity(row);
        return obj(
                "id",
                l(row, "id"),
                "nickname",
                s(row, "nickname"),
                "phone",
                s(row, "phone"),
                "identity",
                role,
                "identityName",
                "B".equals(role) ? "调肤主理人" : "C".equals(role) ? "调肤店主" : "游客");
    }

    public static ObjectNode publicStaff(ObjectNode row) {
        return obj(
                "id",
                l(row, "id"),
                "username",
                s(row, "username"),
                "displayName",
                s(row, "display_name"),
                "permissions",
                parse(s(row, "permissions")),
                "enabled",
                b(row, "enabled"));
    }
}
