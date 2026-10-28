package com.jianyanji.catalog;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 商品选择的轻量分页查询，原交易明细按成交快照展示，数量仅供参考、写入仍实时复核。 */
@Service
public class ProductOptionsService {
    /** 只读分页访问。 */
    private final Db db;

    public ProductOptionsService(Db db) {
        this.db = db;
    }

    /** 支持名称、规格、分类、编号搜索；指定编号只用于已选项回显，不受上架筛选影响。 */
    public ObjectNode page(Map<String, String> query) {
        String kind = query.getOrDefault("kind", "sku");
        Input.require(Arrays.asList("sku", "product", "orderLine", "lot").contains(kind), "商品选择类型无效");
        boolean product = "product".equals(kind), source = "orderLine".equals(kind) || "lot".equals(kind);
        String id = product ? "p.id" : "sku".equals(kind) ? "s.id" : "orderLine".equals(kind) ? "ol.id" : "l.id";
        String from, columns, quantity = "0";
        if (source) {
            if ("lot".equals(kind)) {
                Input.require(query.getOrDefault("userId", "").matches("[1-9][0-9]{0,15}"), "请先指定提货用户");
                Input.id(Long.parseLong(query.get("userId")));
                quantity = "l.remaining_qty-l.held_qty-l.refund_held_qty";
                from = "FROM storage_lots l JOIN order_lines ol ON ol.id=l.line_id AND ol.is_deleted=0 JOIN orders o ON o.id=l.order_id AND o.is_deleted=0 LEFT JOIN skus s ON s.id=ol.sku_id AND s.is_deleted=0 LEFT JOIN products p ON p.id=s.product_id AND p.is_deleted=0 LEFT JOIN users u ON u.id=o.user_id AND u.is_deleted=0 WHERE l.is_deleted=0 AND " + quantity + ">0";
            } else {
                quantity = "ol.quantity-ol.shipped_qty-ol.refunded_unshipped";
                from = "FROM order_lines ol JOIN orders o ON o.id=ol.order_id AND o.is_deleted=0 JOIN skus s ON s.id=ol.sku_id AND s.is_deleted=0 LEFT JOIN products p ON p.id=s.product_id AND p.is_deleted=0 LEFT JOIN users u ON u.id=o.user_id AND u.is_deleted=0 WHERE ol.is_deleted=0 AND s.storage_enabled=1 AND s.storage_exclusion='none' AND o.payment_state IN ('paid','partially_refunded') AND ol.refund_held=0 AND " + quantity + ">0 AND NOT EXISTS (SELECT 1 FROM storage_lots sl WHERE sl.line_id=ol.id AND sl.is_deleted=0) AND NOT EXISTS (SELECT 1 FROM pickup_lines pl JOIN pickup_orders po ON po.id=pl.pickup_id AND po.is_deleted=0 WHERE pl.line_id=ol.id AND pl.is_deleted=0 AND po.status IN ('awaiting_payment','review','approved'))";
            }
            columns = id + " AS id,p.name,s.name AS sku_name,s.unit,ol.sku_id,ol.is_gift,o.snapshot,o.number,o.user_id,u.nickname AS user_name,u.id AS named_user_id," + quantity + " AS quantity";
        } else {
            from = product ? "FROM products p WHERE p.is_deleted=0" : "FROM skus s JOIN products p ON p.id=s.product_id AND p.is_deleted=0 WHERE s.is_deleted=0";
            String active = product ? "p.active" : "(p.active=1 AND s.active=1)";
            if (!"true".equals(query.get("includeInactive")) && !query.containsKey("ids")) from += " AND " + active + "=1";
            columns = id + " AS id,p.name," + (product ? "'' AS sku_name,'' AS unit," : "s.name AS sku_name,s.unit,") + active + " AS active";
        }
        if (query.containsKey("ids")) {
            String[] values = query.get("ids").split(",", -1);
            Input.require(values.length > 0 && values.length <= 100, "已选商品最多回显100项");
            List<String> ids = new ArrayList<>();
            for (String value : values) {
                Input.require(value.matches("[1-9][0-9]{0,15}"), "商品编号无效");
                ids.add(String.valueOf(Input.id(Long.parseLong(value))));
            }
            // 编号经整数范围校验后规范化；其余筛选仍通过参数绑定。
            from += " AND " + id + " IN (" + String.join(",", ids) + ")";
        }
        PageQuery page = new PageQuery(query, from);
        if (source) page.eq("userId", "o.user_id").keyword("p.name", "s.name", "o.number", "CAST(" + id + " AS CHAR)", "JSON_EXTRACT(o.snapshot,'$.lines[*].name')", "JSON_EXTRACT(o.snapshot,'$.lines[*].skuName')");
        else if (product) page.keyword("p.name", "CAST(p.id AS CHAR)", "(SELECT c.name FROM categories c WHERE c.id=p.category_id AND c.is_deleted=0)", "(SELECT JSON_ARRAYAGG(sk.name) FROM skus sk WHERE sk.product_id=p.id AND sk.is_deleted=0)");
        else page.keyword("p.name", "s.name", "CAST(s.id AS CHAR)", "(SELECT c.name FROM categories c WHERE c.id=p.category_id AND c.is_deleted=0)");
        return page.select(db, columns, id + " DESC", row -> option(row, source));
    }

    /** 仅输出显示所需字段，避免将完整商品、订单或客户信息带入选择器。 */
    private ObjectNode option(ObjectNode row, boolean source) {
        String name = s(row, "name"), sku = s(row, "sku_name"), unit = s(row, "unit");
        if (source) {
            for (JsonNode line : parse(s(row, "snapshot")).path("lines")) {
                if (l(line, "skuId") == l(row, "sku_id") && b(line, "isGift") == b(row, "is_gift")) {
                    name = s(line, "name");
                    sku = s(line, "skuName");
                    unit = s(line, "unit");
                    break;
                }
            }
        }
        String label = name + (sku.isEmpty() ? "" : " · " + sku) + (unit.isEmpty() ? "" : "（" + unit + "）");
        if (source) label += (b(row, "is_gift") ? " · 赠品" : "") + " · " + s(row, "number") + " · " + (!has(row, "named_user_id") ? "资料不可用" : s(row, "user_name").isEmpty() ? "未填写昵称" : s(row, "user_name")) + "（用户#" + l(row, "user_id") + "）" + " · 可用" + l(row, "quantity") + unit;
        else if (!b(row, "active")) label += " · 已停用";
        ObjectNode result = obj("id", row.get("id"), "label", label, "active", source || b(row, "active"));
        if (source) result.put("quantity", l(row, "quantity"));
        return result;
    }
}
