package com.jianyanji.orders;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;

/** 按实际包裹、未配送数量和寄存计算展示状态；不改变原订单支付及结算状态。 */
public final class OrderFulfillment {
    /** 有效已付款订单范围，历史关闭及全额退款不计待配送。 */
    private static final String PAID =
            "payment_state IN ('paid','partially_refunded') AND status NOT IN ('closed','refunded','completed')";
    /** 原订单仍有未签收包裹。 */
    private static final String TRANSIT =
            "EXISTS (SELECT 1 FROM shipments fs WHERE fs.order_id=orders.id AND fs.received_at IS NULL AND fs.is_deleted=0)";
    /** 原订单仍有客户所有的寄存货品，包含申请占用。 */
    private static final String STORED =
            "EXISTS (SELECT 1 FROM storage_lots fs WHERE fs.order_id=orders.id AND fs.remaining_qty>0 AND fs.is_deleted=0)";
    /** 待配送包含普通未发货量以及待审核、待发货的寄存提货，均在分页之前筛选。 */
    private static final String PENDING =
            "(EXISTS (SELECT 1 FROM order_lines fl WHERE fl.order_id=orders.id AND fl.is_deleted=0 AND fl.quantity-fl.shipped_qty-fl.refunded_unshipped-fl.held_unshipped>COALESCE((SELECT sl.remaining_qty FROM storage_lots sl WHERE sl.line_id=fl.id AND sl.is_deleted=0),0)) OR EXISTS (SELECT 1 FROM pickup_lines fp JOIN pickup_orders po ON po.id=fp.pickup_id AND po.is_deleted=0 JOIN order_lines fl ON fl.id=fp.line_id AND fl.is_deleted=0 WHERE fl.order_id=orders.id AND fp.is_deleted=0 AND po.status IN ('review','approved')))";

    private OrderFulfillment() {}

    /** orders 表查询中的业务筛选条件；状态值保持原客户端编号，新增5为含寄存订单。 */
    public static String condition(int tab) {
        switch (tab) {
            case 2:
                return "(" + PAID + " AND " + PENDING + ")";
            case 3:
                return "(" + PAID + " AND " + TRANSIT + ")";
            case 5:
                return "(" + PAID + " AND " + STORED + ")";
            default:
                throw new IllegalArgumentException("不支持的履约筛选");
        }
    }

    /** 复用已读取的订单行及包裹计算数量，不额外查询；数量单位为SKU件数。 */
    public static void describe(ObjectNode order) {
        long stored = 0, direct = 0, received = 0, pendingQuantity = 0, transitQuantity = 0;
        boolean pending = false, transit = false, review = false;
        Map<Long, Long> pendingLines = new HashMap<>(), transitLines = new HashMap<>();
        for (JsonNode pickup : order.path("pickups")) {
            boolean active = "review".equals(s(pickup, "status")) || "approved".equals(s(pickup, "status"));
            pending |= active;
            review |= "review".equals(s(pickup, "status"));
            if (active)
                for (JsonNode line : pickup.path("lines"))
                    pendingLines.merge(l(line, "lineId"), l(line, "quantity"), Long::sum);
        }
        for (JsonNode shipment : order.path("shipments")) {
            if (has(shipment, "received_at")) continue;
            transit = true;
            for (JsonNode line : shipment.path("lines"))
                transitLines.merge(l(line, "lineId"), l(line, "quantity"), Long::sum);
        }
        for (JsonNode line : order.path("lines")) {
            stored += l(line, "storage_remaining");
            received += l(line, "received_qty");
            long unallocated =
                    Math.max(
                            0,
                            l(line, "quantity")
                                    - l(line, "shipped_qty")
                                    - l(line, "refunded_unshipped")
                                    - l(line, "held_unshipped")
                                    - l(line, "storage_remaining")
                                    - l(line, "pickup_held"));
            direct += unallocated;
            long waiting = unallocated + pendingLines.getOrDefault(l(line, "id"), 0L);
            long inTransit = transitLines.getOrDefault(l(line, "id"), 0L);
            ((ObjectNode) line).put("pending_ship_qty", waiting);
            ((ObjectNode) line).put("in_transit_qty", inTransit);
            pendingQuantity += waiting;
            transitQuantity += inTransit;
        }
        String state = s(order, "status"), text = "";
        if (("paid".equals(s(order, "payment_state"))
                        || "partially_refunded".equals(s(order, "payment_state")))
                && !"completed".equals(state)
                && !"closed".equals(state)) {
            if (transit) {
                state = "shipped";
                text = "运输中";
            } else if (direct > 0 || pending) {
                state = "awaiting_shipment";
                text = review ? "待审核配送" : "待发货";
            } else if (stored > 0) {
                state = "stored";
                text = received > 0 ? "部分已收货，余货寄存" : "寄存中";
            } else if ("shipped".equals(state)) {
                state = "processing";
                text = "待处理";
            }
        }
        order.put("fulfillmentState", state);
        order.put("fulfillmentText", text);
        order.put("storageRemainingQty", stored);
        order.put("directShipQuantity", direct);
        order.put("pendingShipQuantity", pendingQuantity);
        order.put("inTransitQuantity", transitQuantity);
    }
}
