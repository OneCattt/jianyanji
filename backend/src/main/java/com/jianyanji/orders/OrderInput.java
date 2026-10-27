package com.jianyanji.orders;

import static com.jianyanji.common.BusinessException.invalid;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Input;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/** 订单领域严格输入校验；编号兼容原数字字符串，数量和金额仅接受 JSON 整数。 */
final class OrderInput {
    private OrderInput() {}

    static long entityId(JsonNode node) {
        if (node == null
                || node.isNull()
                || !(node.isNumber() || node.isTextual() || node.isBoolean()))
            throw invalid("编号无效");
        try {
            BigDecimal value =
                    new BigDecimal(
                            node.isBoolean()
                                    ? (node.asBoolean() ? "1" : "0")
                                    : node.asText().trim());
            return Input.id(value.longValueExact());
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid("编号无效");
        }
    }

    static String rawText(JsonNode body, String key) {
        JsonNode value = body.get(key);
        Input.require(value != null && value.isTextual(), key + "必须为字符串");
        return value.asText();
    }

    static String requestKey(JsonNode body) {
        String key = rawText(body, "requestKey");
        Input.require(key.matches("^[a-zA-Z0-9_-]{12,100}$"), "请求编号格式错误");
        return key;
    }

    static ObjectNode address(JsonNode body) {
        Input.strict(
                body, "recipient", "phone", "province", "city", "district", "detail", "isDefault");
        String phone = rawText(body, "phone");
        Input.require(phone.matches("^1[3-9][0-9]{9}$"), "手机号格式错误");
        return obj(
                "recipient",
                Input.text(body, "recipient", 1, 40),
                "phone",
                phone,
                "province",
                Input.text(body, "province", 1, 30),
                "city",
                Input.text(body, "city", 1, 30),
                "district",
                Input.text(body, "district", 1, 30),
                "detail",
                Input.text(body, "detail", 1, 200),
                "isDefault",
                Input.bool(body, "isDefault"));
    }

    static ObjectNode shipping(JsonNode body) {
        Input.strict(body, "provinces", "feeCents", "instructions", "pendingMinutes", "reason");
        Input.reason(body);
        ArrayNode provinces = arr();
        for (JsonNode node : Input.array(body, "provinces", 1, 40)) {
            Input.require(node.isTextual(), "配送省份必须为字符串");
            String value = node.asText().trim();
            Input.require(value.length() >= 1 && value.length() <= 30, "配送省份长度不正确");
            provinces.add(value);
        }
        return obj(
                "provinces",
                provinces,
                "feeCents",
                Input.integer(body, "feeCents", 0, 10000000),
                "instructions",
                Input.text(body, "instructions", 1, 2000),
                "pendingMinutes",
                Input.integer(body, "pendingMinutes", 5, 1440));
    }

    static ObjectNode checkout(JsonNode body) {
        Input.strict(
                body,
                "addressId",
                "items",
                "salesCode",
                "salesConfirmed",
                "storedItems",
                "offerId",
                "offerQuantity",
                "offerDelivery",
                "offerShipQuantities");
        ObjectNode value =
                obj(
                        "addressId",
                        entityId(body.get("addressId")),
                        "items",
                        body.has("offerId") ? arr() : lines(body, "items", "skuId", "同规格不可重复"));
        if (has(body, "salesCode")) {
            value.put("salesCode", Input.text(body, "salesCode", 0, 64));
            value.put("salesConfirmed", Input.bool(body, "salesConfirmed"));
        }
        if (body.has("offerId")) {
            value.put("offerId", Input.integer(body, "offerId", 1, 1000000));
            value.put("offerQuantity", Input.integer(body, "offerQuantity", 1, 99));
            String mode = Input.text(body, "offerDelivery", 4, 5);
            Input.require(java.util.Arrays.asList("ship", "store").contains(mode), "套餐交付方式无效");
            value.put("offerDelivery", mode);
        }
        if (body.has("offerShipQuantities"))
            value.set("offerShipQuantities", body.get("offerShipQuantities"));
        if (body.has("storedItems")) value.set("storedItems", body.get("storedItems"));
        return value;
    }

    static ArrayNode lines(JsonNode body, String field, String idField, String duplicateMessage) {
        ArrayNode result = arr();
        Set<Long> seen = new HashSet<>();
        for (JsonNode line : Input.array(body, field, 1, 50)) {
            if ("skuId".equals(idField)) Input.strict(line, idField, "quantity", "shipQuantity");
            else Input.strict(line, idField, "quantity");
            long id = entityId(line.get(idField));
            Input.require(seen.add(id), duplicateMessage);
            ObjectNode item = obj(idField, id, "quantity", Input.integer(line, "quantity", 1, 999));
            if (line.has("shipQuantity"))
                item.put(
                        "shipQuantity",
                        Input.integer(line, "shipQuantity", 0, l(item, "quantity")));
            result.add(item);
        }
        return result;
    }

    static ObjectNode logistics(JsonNode body, boolean shipment) {
        if (shipment) Input.strict(body, "carrier", "tracking", "lines", "requestKey", "reason");
        else Input.strict(body, "carrier", "tracking", "reason");
        String tracking = Input.text(body, "tracking", 1, 80);
        Input.require(tracking.matches("^[a-zA-Z0-9-]{4,80}$"), "物流单号格式错误");
        ObjectNode result =
                obj(
                        "carrier",
                        Input.text(body, "carrier", 1, 60),
                        "tracking",
                        tracking,
                        "reason",
                        Input.reason(body));
        if (shipment) {
            result.put("requestKey", requestKey(body));
            result.set("lines", lines(body, "lines", "lineId", "发货商品不可重复"));
        }
        return result;
    }
}
