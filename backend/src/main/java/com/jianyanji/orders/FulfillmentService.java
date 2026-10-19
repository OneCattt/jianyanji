package com.jianyanji.orders;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Db;
import java.util.List;
import org.springframework.stereotype.Service;

/** 品牌仓分包发货、物流纠正和用户收货；所有商品数量调整在同一事务内完成。 */
@Service
public class FulfillmentService {
    /** 数据访问与串行事务。 */
    private final Db db;
    /** 订单归属与履约状态计算。 */
    private final OrderService orders;
    /** 操作人和审计上下文。 */
    private final Access access;

    public FulfillmentService(Db db, OrderService orders, Access access) {
        this.db = db;
        this.orders = orders;
        this.access = access;
    }

    /** 发货只扣除已发货、未发货退款及未发货售后占用，已出库退款不得重复减少可发货量。 */
    public List<ObjectNode> shippableLines(long orderId) {
        return db.list(
                "SELECT *,quantity-shipped_qty-refunded_unshipped-held_unshipped AS remaining FROM order_lines WHERE order_id=? AND is_deleted=0 ORDER BY id",
                orderId);
    }

    public ObjectNode fulfillment(long orderId) {
        ObjectNode order = orders.owned(orderId, null);
        return obj(
                "order",
                orders.view(order),
                "lines",
                shippableLines(orderId),
                "corrections",
                db.list(
                        "SELECT c.* FROM shipment_corrections c JOIN shipments s ON s.id=c.shipment_id AND s.is_deleted=0 WHERE s.order_id=? AND c.is_deleted=0 ORDER BY c.id",
                        orderId));
    }

    /** 请求编号与商品数组、物流信息共同保证重试幂等；任一行失败回滚整个包裹。 */
    public ObjectNode ship(long orderId, JsonNode body, long actor) {
        ObjectNode value = OrderInput.logistics(body, true);
        return db.tx(
                () -> {
                    ObjectNode order = orders.owned(orderId, null);
                    ObjectNode old =
                            db.one(
                                    "SELECT * FROM shipments WHERE order_id=? AND request_key=? AND is_deleted=0",
                                    orderId,
                                    s(value, "requestKey"));
                    String lines = stringify(value.get("lines"));
                    if (old != null) {
                        if (!s(old, "carrier").equals(s(value, "carrier"))
                                || !s(old, "tracking").equals(s(value, "tracking"))
                                || !s(old, "lines").equals(lines)) throw conflict("发货请求编号已用于其他内容");
                        return old;
                    }
                    String payment = s(order, "payment_state"), status = s(order, "status");
                    if (!("paid".equals(payment) || "partially_refunded".equals(payment))
                            || !("paid".equals(status)
                                    || "awaiting_shipment".equals(status)
                                    || "shipped".equals(status))) throw conflict("订单尚未付款或不允许发货");
                    for (JsonNode line : value.withArray("lines")) {
                        long quantity = l(line, "quantity");
                        if (db.update(
                                        "UPDATE order_lines SET shipped_qty=shipped_qty+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND order_id=? AND is_deleted=0 AND quantity-shipped_qty-refunded_unshipped-held_unshipped>=?",
                                        quantity,
                                        actor,
                                        l(line, "lineId"),
                                        orderId,
                                        quantity)
                                != 1) throw conflict("商品不存在、售后占用或剩余可发货数量不足");
                    }
                    long shipmentId =
                            db.insert(
                                    "INSERT INTO shipments(order_id,carrier,tracking,`lines`,shipped_at,request_key,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?)",
                                    orderId,
                                    s(value, "carrier"),
                                    s(value, "tracking"),
                                    lines,
                                    now(),
                                    s(value, "requestKey"),
                                    actor,
                                    actor);
                    db.update(
                            "UPDATE orders SET status='shipped',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            actor,
                            orderId);
                    access.audit("shipment.create", "order", orderId, s(value, "reason"));
                    return db.one(
                            "SELECT * FROM shipments WHERE id=? AND is_deleted=0", shipmentId);
                });
    }

    /** 纠正保留原物流、原因与请求编号，不重建包裹或再次累计出库数量。 */
    public ObjectNode correct(long shipmentId, JsonNode body, long actor) {
        ObjectNode value = OrderInput.logistics(body, false);
        return db.tx(
                () -> {
                    ObjectNode shipment =
                            db.one(
                                    "SELECT * FROM shipments WHERE id=? AND is_deleted=0",
                                    shipmentId);
                    if (shipment == null) throw notFound("交付记录不存在");
                    db.insert(
                            "INSERT INTO shipment_corrections(shipment_id,old_carrier,old_tracking,carrier,tracking,reason,request_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?)",
                            shipmentId,
                            s(shipment, "carrier"),
                            s(shipment, "tracking"),
                            s(value, "carrier"),
                            s(value, "tracking"),
                            s(value, "reason"),
                            access.requestId(),
                            actor,
                            actor);
                    db.update(
                            "UPDATE shipments SET carrier=?,tracking=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            s(value, "carrier"),
                            s(value, "tracking"),
                            actor,
                            shipmentId);
                    access.audit("shipment.correct", "shipment", shipmentId, s(value, "reason"));
                    return obj("ok", true);
                });
    }

    /** 本人逐包确认收货；未决售后阻断确认，退款数量不累计为真实收货。 */
    public ObjectNode receive(long orderId, long shipmentId, long userId) {
        return db.tx(
                () -> {
                    ObjectNode order = orders.owned(orderId, userId);
                    ObjectNode shipment =
                            db.one(
                                    "SELECT * FROM shipments WHERE id=? AND order_id=? AND is_deleted=0",
                                    shipmentId,
                                    orderId);
                    if (shipment == null) throw notFound("交付记录不存在");
                    if (has(shipment, "received_at") && !s(shipment, "received_at").isEmpty())
                        return orders.view(order);
                    String payment = s(order, "payment_state");
                    if (!("paid".equals(payment) || "partially_refunded".equals(payment))
                            || !"shipped".equals(s(order, "status"))) throw conflict("订单状态不允许确认收货");
                    if (orders.unsettledAftersales(orderId)
                            || db.one(
                                            "SELECT id FROM order_lines WHERE order_id=? AND is_deleted=0 AND refund_held>0 LIMIT 1",
                                            orderId)
                                    != null) throw conflict("订单尚有未处理售后，请处理完成后确认收货");
                    for (JsonNode parcelLine : parse(s(shipment, "lines"))) {
                        ObjectNode line =
                                db.one(
                                        "SELECT * FROM order_lines WHERE id=? AND order_id=? AND is_deleted=0",
                                        l(parcelLine, "lineId"),
                                        orderId);
                        if (line == null) throw conflict("交付商品记录缺失");
                        // 收货上限只包含已出库且未退款的数量，未发出的商品不能由当前包裹计入。
                        long remaining =
                                Math.max(
                                        0,
                                        l(line, "shipped_qty")
                                                - l(line, "refunded_qty")
                                                + l(line, "refunded_unshipped")
                                                - l(line, "received_qty"));
                        long quantity = Math.min(l(parcelLine, "quantity"), remaining);
                        db.update(
                                "UPDATE order_lines SET received_qty=received_qty+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND order_id=? AND is_deleted=0",
                                quantity,
                                userId,
                                l(parcelLine, "lineId"),
                                orderId);
                    }
                    db.update(
                            "UPDATE shipments SET received_at=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND order_id=? AND is_deleted=0 AND received_at IS NULL",
                            now(),
                            userId,
                            shipmentId,
                            orderId);
                    orders.refreshFulfillment(orderId);
                    access.audit("shipment.receive", "shipment", shipmentId, "用户确认本人实际收到的商品");
                    return orders.view(orders.owned(orderId, null));
                });
    }
}
