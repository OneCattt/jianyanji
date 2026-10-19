package com.jianyanji.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.*;

/** 严格输入边界，拒绝多余字段和隐式数值转换，避免金额、身份被客户端改写。 */
public final class Input {
    private Input() {}

    public static void strict(JsonNode v, String... allowed) {
        require(v != null && v.isObject(), "请提交 JSON 对象");
        Set<String> keys = new HashSet<>(Arrays.asList(allowed));
        v.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!keys.contains(k)) throw BusinessException.invalid("不支持的字段：" + k);
                        });
    }

    public static String text(JsonNode v, String key, int min, int max) {
        require(v != null && v.hasNonNull(key) && v.get(key).isTextual(), key + " 必须为文字");
        String value = v.get(key).asText().trim();
        require(value.length() >= min && value.length() <= max, key + " 长度不符合要求");
        return value;
    }

    public static long integer(JsonNode v, String key, long min, long max) {
        require(
                v != null
                        && v.hasNonNull(key)
                        && v.get(key).isIntegralNumber()
                        && v.get(key).canConvertToLong(),
                key + " 必须为整数");
        long n = v.get(key).longValue();
        require(n >= min && n <= max, key + " 超出允许范围");
        return n;
    }

    public static boolean bool(JsonNode v, String key) {
        require(v != null && v.hasNonNull(key) && v.get(key).isBoolean(), key + " 必须为布尔值");
        return v.get(key).booleanValue();
    }

    public static ArrayNode array(JsonNode v, String key, int min, int max) {
        require(v != null && v.hasNonNull(key) && v.get(key).isArray(), key + " 必须为数组");
        ArrayNode a = (ArrayNode) v.get(key);
        require(a.size() >= min && a.size() <= max, key + " 数量不符合要求");
        return a;
    }

    public static long id(long id) {
        require(id > 0 && id <= 9007199254740991L, "编号不合法");
        return id;
    }

    public static String reason(JsonNode v) {
        return text(v, "reason", 1, 200);
    }

    public static void require(boolean ok, String message) {
        if (!ok) throw BusinessException.invalid(message);
    }
}
