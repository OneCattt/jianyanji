import type { Express } from "express";
import { z } from "zod";
import { auditColumns, transaction, type DB } from "./db.ts";
import { entityId } from "./catalog.ts";
import { ownedOrder, orderView, type OrderRow } from "./orders.ts";
import { fail, HttpError, type BusinessContext } from "./http.ts";
const stamp = `update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now')`;
export function migrateFulfillment(db: DB) {
  db.exec(
    `CREATE TABLE IF NOT EXISTS shipment_corrections(id INTEGER PRIMARY KEY,shipment_id INTEGER NOT NULL REFERENCES shipments(id),old_carrier TEXT NOT NULL,old_tracking TEXT NOT NULL,carrier TEXT NOT NULL,tracking TEXT NOT NULL,reason TEXT NOT NULL,request_id TEXT NOT NULL,${auditColumns});`,
  );
  const cols = db.prepare("PRAGMA table_info(shipments)").all();
  if (!cols.some((c) => c.name === "request_key"))
    db.exec("ALTER TABLE shipments ADD COLUMN request_key TEXT");
  db.exec(
    "CREATE UNIQUE INDEX IF NOT EXISTS shipment_request ON shipments(order_id,request_key) WHERE is_deleted=0 AND request_key IS NOT NULL",
  );
}
/** 发货只占用未出库数量；已出库后退款不再次抵减可发货量。 */
export function shippableLines(db: DB, orderId: number) {
  return db
    .prepare(
      `SELECT *,quantity-shipped_qty-refunded_unshipped-held_unshipped AS remaining FROM order_lines WHERE order_id=? AND is_deleted=0 ORDER BY id`,
    )
    .all(orderId);
}
function unsettledAftersales(db: DB, orderId: number) {
  if (
    !db
      .prepare(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='refunds'",
      )
      .get()
  )
    return false;
  return Boolean(
    db
      .prepare(
        "SELECT id FROM refunds WHERE order_id=? AND is_deleted=0 AND status IN ('requested','approved','processing','unknown','failed') LIMIT 1",
      )
      .get(orderId),
  );
}
/** 售后完成/驳回后可在同事务调用，重新计算履约完成状态。 */
export function refreshFulfillment(db: DB, orderId: number) {
  const o = ownedOrder(db, orderId),
    lines = shippableLines(db, orderId);
  if (!["paid", "partially_refunded"].includes(o.payment_state)) return o;
  const remaining = lines.some(
    (l) => Number(l.quantity) - Number(l.refunded_qty) > Number(l.received_qty),
  );
  const pending =
    lines.some((l) => Number(l.refund_held) > 0) ||
    unsettledAftersales(db, orderId);
  const anyReceived = lines.some((l) => Number(l.received_qty) > 0);
  const openParcels = Boolean(
    db
      .prepare(
        "SELECT id FROM shipments WHERE order_id=? AND is_deleted=0 AND received_at IS NULL LIMIT 1",
      )
      .get(orderId),
  );
  const status =
    !remaining && !pending && !openParcels && anyReceived
      ? "completed"
      : lines.some((l) => Number(l.shipped_qty) > 0)
        ? "shipped"
        : "paid";
  // 退款导致无剩余待收商品时，曾经真实收货才允许进入完成；全退由售后维护退款状态。
  db.prepare(
    `UPDATE orders SET status=?,received_at=CASE WHEN ?='completed' THEN COALESCE(received_at,strftime('%Y-%m-%dT%H:%M:%fZ','now')) ELSE NULL END,${stamp} WHERE id=?`,
  ).run(status, status, orderId);
  return ownedOrder(db, orderId);
}
const reason = z.string().trim().min(1).max(200);
const logistics = {
  carrier: z.string().trim().min(1).max(60),
  tracking: z
    .string()
    .trim()
    .regex(/^[a-zA-Z0-9-]{4,80}$/),
};
const shipSchema = z
  .object({
    ...logistics,
    lines: z
      .array(
        z
          .object({
            lineId: entityId,
            quantity: z.number().int().positive().max(999),
          })
          .strict(),
      )
      .min(1)
      .max(50),
    requestKey: z.string().regex(/^[a-zA-Z0-9_-]{12,100}$/),
    reason,
  })
  .strict()
  .refine(
    (v) => new Set(v.lines.map((l) => l.lineId)).size === v.lines.length,
    "发货商品不可重复",
  );
export function mountFulfillment(app: Express, ctx: BusinessContext) {
  const { db, userAuth, staffAuth, permit, audit } = ctx;
  app.get(
    "/api/admin/orders/:id/fulfillment",
    staffAuth,
    permit("orders:manage"),
    (req, res) => {
      const o = ownedOrder(db, entityId.parse(req.params.id));
      res.json({
        order: orderView(db, o),
        lines: shippableLines(db, o.id),
        corrections: db
          .prepare(
            "SELECT c.* FROM shipment_corrections c JOIN shipments s ON s.id=c.shipment_id AND s.is_deleted=0 WHERE s.order_id=? AND c.is_deleted=0 ORDER BY c.id",
          )
          .all(o.id),
      });
    },
  );
  app.post(
    "/api/admin/orders/:id/shipments",
    staffAuth,
    permit("orders:manage"),
    (req, res) => {
      const v = shipSchema.parse(req.body),
        id = entityId.parse(req.params.id),
        actor = res.locals.staff.id;
      const result = transaction(db, () => {
        const o = ownedOrder(db, id);
        const old = db
          .prepare(
            "SELECT * FROM shipments WHERE order_id=? AND request_key=? AND is_deleted=0",
          )
          .get(id, v.requestKey);
        if (old) {
          if (
            old.carrier !== v.carrier ||
            old.tracking !== v.tracking ||
            old.lines !== JSON.stringify(v.lines)
          )
            fail("发货请求编号已用于其他内容");
          return old;
        }
        if (
          !["paid", "partially_refunded"].includes(o.payment_state) ||
          !["paid", "awaiting_shipment", "shipped"].includes(o.status)
        )
          fail("订单尚未付款或不允许发货");
        for (const line of v.lines) {
          const updated = db
            .prepare(
              `UPDATE order_lines SET shipped_qty=shipped_qty+?,update_user_id=?,${stamp} WHERE id=? AND order_id=? AND is_deleted=0 AND quantity-shipped_qty-refunded_unshipped-held_unshipped>=?`,
            )
            .run(line.quantity, actor, line.lineId, id, line.quantity);
          if (!updated.changes)
            fail("商品不存在、售后占用或剩余可发货数量不足");
        }
        const r = db
          .prepare(
            "INSERT INTO shipments(order_id,carrier,tracking,lines,shipped_at,request_key,create_user_id,update_user_id) VALUES(?,?,?,?,strftime('%Y-%m-%dT%H:%M:%fZ','now'),?,?,?)",
          )
          .run(
            id,
            v.carrier,
            v.tracking,
            JSON.stringify(v.lines),
            v.requestKey,
            actor,
            actor,
          );
        db.prepare(
          `UPDATE orders SET status='shipped',update_user_id=?,${stamp} WHERE id=?`,
        ).run(actor, id);
        audit(res, "shipment.create", "order", id, v.reason);
        return db
          .prepare("SELECT * FROM shipments WHERE id=? AND is_deleted=0")
          .get(r.lastInsertRowid);
      });
      res.json(result);
    },
  );
  app.put(
    "/api/admin/shipments/:id",
    staffAuth,
    permit("orders:manage"),
    (req, res) => {
      const v = z
          .object({ ...logistics, reason })
          .strict()
          .parse(req.body),
        id = entityId.parse(req.params.id),
        actor = res.locals.staff.id;
      transaction(db, () => {
        const s = db
          .prepare("SELECT * FROM shipments WHERE id=? AND is_deleted=0")
          .get(id);
        if (!s) throw new HttpError(404, "NOT_FOUND", "交付记录不存在");
        db.prepare(
          "INSERT INTO shipment_corrections(shipment_id,old_carrier,old_tracking,carrier,tracking,reason,request_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?)",
        ).run(
          id,
          s.carrier,
          s.tracking,
          v.carrier,
          v.tracking,
          v.reason,
          res.locals.requestId,
          actor,
          actor,
        );
        db.prepare(
          `UPDATE shipments SET carrier=?,tracking=?,update_user_id=?,${stamp} WHERE id=? AND is_deleted=0`,
        ).run(v.carrier, v.tracking, actor, id);
        audit(res, "shipment.correct", "shipment", id, v.reason);
      });
      res.json({ ok: true });
    },
  );
  app.post(
    "/api/orders/:id/shipments/:shipmentId/receive",
    userAuth,
    (req, res) => {
      const result = transaction(db, () => {
        const o = ownedOrder(
            db,
            entityId.parse(req.params.id),
            res.locals.user.id,
          ),
          shipmentId = entityId.parse(req.params.shipmentId);
        const s = db
          .prepare(
            "SELECT * FROM shipments WHERE id=? AND order_id=? AND is_deleted=0",
          )
          .get(shipmentId, o.id);
        if (!s) throw new HttpError(404, "NOT_FOUND", "交付记录不存在");
        if (s.received_at) return orderView(db, o);
        if (
          !["paid", "partially_refunded"].includes(o.payment_state) ||
          o.status !== "shipped"
        )
          fail("订单状态不允许确认收货");
        // 售后占用期间暂停收货，避免待验货商品被当成正常交付完成。
        if (
          unsettledAftersales(db, o.id) ||
          db
            .prepare(
              "SELECT id FROM order_lines WHERE order_id=? AND is_deleted=0 AND refund_held>0 LIMIT 1",
            )
            .get(o.id)
        )
          fail("订单尚有未处理售后，请处理完成后确认收货");
        const lines = JSON.parse(String(s.lines)) as {
          lineId: number;
          quantity: number;
        }[];
        for (const l of lines) {
          // 已发货退款可能先发生，收货数只计仍有效的商品。
          const line = db
            .prepare(
              "SELECT * FROM order_lines WHERE id=? AND order_id=? AND is_deleted=0",
            )
            .get(l.lineId, o.id);
          if (!line) fail("交付商品记录缺失");
          const remaining = Math.max(
            0,
            Number(line!.quantity) -
              Number(line!.refunded_qty) -
              Number(line!.received_qty),
          );
          const qty = Math.min(l.quantity, remaining);
          db.prepare(
            `UPDATE order_lines SET received_qty=received_qty+?,update_user_id=?,${stamp} WHERE id=?`,
          ).run(qty, res.locals.user.id, l.lineId);
        }
        db.prepare(
          `UPDATE shipments SET received_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),update_user_id=?,${stamp} WHERE id=? AND received_at IS NULL`,
        ).run(res.locals.user.id, shipmentId);
        refreshFulfillment(db, o.id);
        audit(
          res,
          "shipment.receive",
          "shipment",
          shipmentId,
          "用户确认本人实际收到的商品",
        );
        return orderView(db, ownedOrder(db, o.id));
      });
      res.json(result);
    },
  );
}
