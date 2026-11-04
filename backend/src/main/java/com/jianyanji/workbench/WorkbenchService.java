package com.jianyanji.workbench;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Db;
import com.jianyanji.orders.OrderFulfillment;
import org.springframework.stereotype.Service;

/** 工作台待办聚合：只按现有权限返回计数，不读取分页列表，也不触发业务写操作。 */
@Service
public class WorkbenchService {
    /** 绑定参数的数据库读取。 */
    private final Db db;

    public WorkbenchService(Db db) {
        this.db = db;
    }

    /** 每项最多一条聚合查询；无权项直接省略，不返回可推断的隐藏数量。 */
    public ObjectNode todos(JsonNode staff) {
        ArrayNode items = arr();
        if (has(staff, "permissions")) {
            if (permission(staff, "finance:manage")) {
                // 当前没有待核款申请实体，核款入口只保留无数量快捷方式。
                items.add(obj("key", "receipt", "count", null, "unit", ""));
                items.add(
                        obj(
                                "key",
                                "refund_finance",
                                "count",
                                count(
                                        "SELECT COUNT(*) AS total FROM refunds WHERE is_deleted=0 AND status IN ('approved','processing','unknown','failed')"),
                                "unit",
                                "单"));
            }
            if (permission(staff, "orders:manage")) {
                items.add(
                        obj(
                                "key",
                                "pickup_review",
                                "count",
                                count(
                                        "SELECT COUNT(*) AS total FROM pickup_orders WHERE is_deleted=0 AND status='review'"),
                                "unit",
                                "单"));
                items.add(
                        obj(
                                "key",
                                "pending_shipping",
                                "count",
                                count(
                                        "SELECT COUNT(*) AS total FROM orders WHERE is_deleted=0 AND "
                                                + OrderFulfillment.condition(2)),
                                "unit",
                                "单"));
                items.add(
                        obj(
                                "key",
                                "refund_review",
                                "count",
                                count(
                                        "SELECT COUNT(*) AS total FROM refunds WHERE is_deleted=0 AND status='requested'"),
                                "unit",
                                "单"));
                items.add(
                        obj(
                                "key",
                                "storage_expiring",
                                "count",
                                count(
                                        "SELECT COUNT(*) AS total FROM storage_lots l JOIN orders o ON o.id=l.order_id AND o.is_deleted=0 JOIN order_lines ol ON ol.id=l.line_id AND ol.is_deleted=0 JOIN users u ON u.id=l.user_id AND u.is_deleted=0 WHERE l.is_deleted=0 AND l.remaining_qty>0 AND l.expires_on<=DATE_ADD(DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')),INTERVAL 30 DAY)"),
                                "unit",
                                "批"));
            }
        }
        return obj("asOf", now(), "items", items);
    }

    /** 权限数组按字符串精确匹配，客户端无法通过参数扩权。 */
    private boolean permission(JsonNode staff, String permission) {
        for (JsonNode value : parse(s(staff, "permissions")))
            if (permission.equals(value.asText())) return true;
        return false;
    }

    private long count(String sql) {
        return l(db.one(sql), "total");
    }
}
