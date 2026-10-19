import type { Express } from "express";
import { randomUUID } from "node:crypto";
import { z } from "zod";
import { auditColumns, transaction, type DB } from "./db.ts";
import { entityId, putSetting, setting } from "./catalog.ts";
import { ownedOrder, reasonSchema, type OrderRow } from "./orders.ts";
import { refreshFulfillment } from "./fulfillment.ts";
import { fail, HttpError, type BusinessContext } from "./http.ts";
export interface RefundResult {
  status: "processing" | "success" | "failed" | "unknown";
  outRefundNo: string;
  outTradeNo: string;
  refundCents: number;
  totalCents: number;
  refundId?: string;
  reason?: string;
}
export interface RefundChannel {
  refund(input: {
    outTradeNo: string;
    transactionId: string;
    outRefundNo: string;
    totalCents: number;
    refundCents: number;
    reason: string;
  }): Promise<RefundResult>;
  queryRefund(outRefundNo: string): Promise<RefundResult>;
}
export interface RefundHooks {
  channel?: RefundChannel;
  returnPoints?: (
    db: DB,
    orderId: number,
    refundId: number,
    amount: number,
    actor: number,
    requestId: string,
  ) => void;
  reverseRewards?: (
    db: DB,
    orderId: number,
    actor: number,
    requestId: string,
  ) => void;
  packageIdentity?: (
    db: DB,
    order: OrderRow,
    full: boolean,
    decision: "retain" | "revoke" | null,
    actor: number,
    reason: string,
  ) => void;
}
export interface RefundLine {
  lineId: number;
  skuId: number;
  quantity: number;
  unitCents: number;
  principalCents: number | null;
  unshippedQuantity: number;
  returnQuantity: number;
}
export interface RefundRow {
  id: number;
  order_id: number;
  number: string;
  kind: string;
  status: string;
  goods_cents: number;
  shipping_cents: number;
  total_cents: number;
  lines: string;
  reason: string;
  policy_snapshot: string;
  returned_at: string | null;
  inspection: string | null;
  channel_ref: string | null;
  completed_at: string | null;
  request_key: string;
  request_id: string;
  identity_decision: "retain" | "revoke" | null;
  channel_token: string | null;
  channel_until: string | null;
}
export function migrateAftersales(db: DB) {
  db.exec(`CREATE TABLE IF NOT EXISTS refunds(id INTEGER PRIMARY KEY,order_id INTEGER NOT NULL REFERENCES orders(id),number TEXT NOT NULL UNIQUE,kind TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'requested',goods_cents INTEGER NOT NULL,shipping_cents INTEGER NOT NULL,total_cents INTEGER NOT NULL,lines TEXT NOT NULL,reason TEXT NOT NULL,policy_snapshot TEXT NOT NULL,returned_at TEXT,inspection TEXT,channel_ref TEXT,completed_at TEXT,request_key TEXT NOT NULL,request_id TEXT NOT NULL,identity_decision TEXT,recipient TEXT,paid_at TEXT,proof TEXT,channel_token TEXT,channel_until TEXT,last_error TEXT NOT NULL DEFAULT '',${auditColumns});
 CREATE UNIQUE INDEX IF NOT EXISTS refunds_request_active ON refunds(request_key) WHERE is_deleted=0;
 CREATE INDEX IF NOT EXISTS refunds_order ON refunds(order_id,id);
 CREATE TABLE IF NOT EXISTS returned_inventory(id INTEGER PRIMARY KEY,refund_id INTEGER NOT NULL REFERENCES refunds(id),line_id INTEGER NOT NULL REFERENCES order_lines(id),quantity INTEGER NOT NULL,restocked INTEGER NOT NULL DEFAULT 0,inspection TEXT NOT NULL,${auditColumns});
 CREATE UNIQUE INDEX IF NOT EXISTS returned_inventory_active ON returned_inventory(refund_id,line_id) WHERE is_deleted=0;`);
  const columns = db.prepare("PRAGMA table_info(refunds)").all();
  for (const column of ["channel_token", "channel_until"])
    if (!columns.some((c) => c.name === column))
      db.exec(`ALTER TABLE refunds ADD COLUMN ${column} TEXT`);
}
export function getRefund(db: DB, id: number) {
  const r = db
    .prepare("SELECT * FROM refunds WHERE id=? AND is_deleted=0")
    .get(id) as unknown as RefundRow | undefined;
  if (!r) throw new HttpError(404, "NOT_FOUND", "售后记录不存在");
  return r;
}
export function refundView(row: RefundRow) {
  const { channel_token, channel_until, ...view } = row;
  return {
    ...view,
    lines: JSON.parse(row.lines),
    policy: JSON.parse(row.policy_snapshot),
  };
}
export function finishRefund(
  db: DB,
  refund: RefundRow,
  hooks: RefundHooks,
  actor: number,
  requestId: string,
) {
  const r = getRefund(db, refund.id);
  if (r.status === "success") return;
  if (!["approved", "processing", "unknown", "failed"].includes(r.status))
    fail("售后尚未审核通过");
  const o = ownedOrder(db, r.order_id);
  const lines: RefundLine[] = JSON.parse(r.lines);
  if (lines.some((l) => l.returnQuantity > 0) && !r.returned_at)
    fail("请先登记实际退货验收");
  if (o.payment_method === "points") {
    if (!hooks.returnPoints) fail("积分退款服务尚未接入");
    hooks.returnPoints!(db, o.id, r.id, r.total_cents, actor, requestId);
  }
  for (const line of lines) {
    const result = db
      .prepare(
        `UPDATE order_lines SET refund_held=refund_held-?,held_unshipped=held_unshipped-?,refunded_qty=refunded_qty+?,refunded_unshipped=refunded_unshipped+?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND order_id=? AND refund_held>=? AND held_unshipped>=? AND is_deleted=0`,
      )
      .run(
        line.quantity,
        line.unshippedQuantity,
        line.quantity,
        line.unshippedQuantity,
        actor,
        line.lineId,
        o.id,
        line.quantity,
        line.unshippedQuantity,
      );
    if (!result.changes) fail("售后占用数量不一致，请核对");
  }
  db.prepare(
    `UPDATE refunds SET status='success',completed_at=?,last_error='',update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?`,
  ).run(new Date().toISOString(), actor, r.id);
  const fullyRefunded = !db
    .prepare(
      "SELECT id FROM order_lines WHERE order_id=? AND quantity>refunded_qty AND is_deleted=0",
    )
    .get(o.id);
  if (o.kind === "package") {
    if (!hooks.packageIdentity) fail("产品包身份处理服务尚未接入");
    hooks.packageIdentity!(
      db,
      o,
      fullyRefunded,
      r.identity_decision,
      actor,
      r.reason,
    );
  }
  hooks.reverseRewards?.(db, o.id, actor, requestId);
  if (!fullyRefunded) refreshFulfillment(db, o.id);
  if (fullyRefunded)
    db.prepare(
      `UPDATE orders SET status='refunded',update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?`,
    ).run(actor, o.id);
}
const input = z
  .object({
    orderId: entityId,
    items: z
      .array(
        z
          .object({
            lineId: entityId,
            quantity: z.number().int().min(1).max(999),
          })
          .strict(),
      )
      .max(50),
    shippingCents: z.number().int().min(0).max(10000000),
    reason: z.string().trim().min(1).max(200),
    requestKey: z.string().regex(/^[a-zA-Z0-9_-]{12,100}$/),
    exceptionReason: z.string().trim().min(1).max(200).optional(),
  })
  .strict()
  .refine(
    (v) =>
      new Set(v.items.map((i) => i.lineId)).size === v.items.length &&
      (v.items.length > 0 || v.shippingCents > 0),
  );
export function mountAftersales(
  app: Express,
  ctx: BusinessContext,
  hooks: RefundHooks = {},
) {
  const { db, staffAuth, userAuth, permit, audit } = ctx;
  app.get(
    "/api/admin/aftersales-settings",
    staffAuth,
    permit("orders:manage"),
    (_req, res) => res.json(setting(db, "aftersales")),
  );
  app.put(
    "/api/admin/aftersales-settings",
    staffAuth,
    permit("orders:manage"),
    (req, res) => {
      const { reason, ...body } = req.body;
      reasonSchema.parse({ reason });
      const v = z
        .object({
          days: z.number().int().min(0).max(365),
          policy: z.string().trim().min(1).max(4000),
        })
        .strict()
        .parse(body);
      transaction(db, () => {
        putSetting(db, "aftersales", v, res.locals.staff.id);
        audit(res, "aftersales.settings", "aftersales", "policy", reason);
      });
      res.json(v);
    },
  );
  app.get(
    "/api/admin/refunds",
    staffAuth,
    permit("orders:manage"),
    (_req, res) =>
      res.json(
        (
          db
            .prepare(
              "SELECT * FROM refunds WHERE is_deleted=0 ORDER BY id DESC LIMIT 200",
            )
            .all() as unknown as RefundRow[]
        ).map(refundView),
      ),
  );
  app.get("/api/refunds", userAuth, (_req, res) =>
    res.json(
      (
        db
          .prepare(
            "SELECT r.* FROM refunds r JOIN orders o ON o.id=r.order_id AND o.is_deleted=0 WHERE o.user_id=? AND r.is_deleted=0 ORDER BY r.id DESC LIMIT 100",
          )
          .all(res.locals.user.id) as unknown as RefundRow[]
      ).map(refundView),
    ),
  );
  app.post(
    "/api/admin/refunds",
    staffAuth,
    permit("orders:manage"),
    (req, res) => {
      const v = input.parse(req.body),
        actor = res.locals.staff.id;
      const r = transaction(db, () => {
        const old = db
          .prepare("SELECT * FROM refunds WHERE request_key=? AND is_deleted=0")
          .get(v.requestKey) as unknown as RefundRow | undefined;
        if (old) {
          if (
            old.order_id !== v.orderId ||
            old.reason !== v.reason ||
            (JSON.parse(old.policy_snapshot).exceptionReason ?? null) !==
              (v.exceptionReason ?? null) ||
            old.shipping_cents !== v.shippingCents ||
            JSON.stringify(
              JSON.parse(old.lines).map((l: RefundLine) => ({
                lineId: l.lineId,
                quantity: l.quantity,
              })),
            ) !== JSON.stringify(v.items)
          )
            fail("请求编号已用于其他售后");
          return old;
        }
        const o = ownedOrder(db, v.orderId);
        if (o.payment_state !== "paid") fail("订单尚未实际付款，不能退款");
        const snapshot = JSON.parse(o.snapshot);
        if (snapshot.afterSalesDays === undefined || !snapshot.afterSalesPolicy)
          fail("原订单缺少售后政策快照，请核对原购买依据");
        const expired = Boolean(
          o.received_at &&
          Date.now() >
            Date.parse(o.received_at) + snapshot.afterSalesDays * 86400000,
        );
        if (expired && !v.exceptionReason)
          fail("已超过原订单售后期限，须由具备财务权限的品牌方特批并记录原因");
        if (
          v.exceptionReason &&
          !JSON.parse(res.locals.staff.permissions).includes("finance:manage")
        )
          throw new HttpError(403, "FORBIDDEN", "超期特批须具备财务权限");
        const lines = v.items.map((item) => {
          const line = db
            .prepare(
              "SELECT * FROM order_lines WHERE id=? AND order_id=? AND is_deleted=0",
            )
            .get(item.lineId, o.id);
          if (!line) fail("商品不属于该订单");
          const l = line!;
          if (
            item.quantity >
            Number(l.quantity) - Number(l.refunded_qty) - Number(l.refund_held)
          )
            fail("商品已退款或正在办理售后，不能超退");
          const unshipped = Math.min(
            item.quantity,
            Number(l.quantity) -
              Number(l.shipped_qty) -
              Number(l.refunded_unshipped) -
              Number(l.held_unshipped),
          );
          const result = db
            .prepare(
              `UPDATE order_lines SET refund_held=refund_held+?,held_unshipped=held_unshipped+?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND quantity-refunded_qty-refund_held>=? AND is_deleted=0`,
            )
            .run(item.quantity, unshipped, actor, item.lineId, item.quantity);
          if (!result.changes) fail("可退数量不足");
          return {
            lineId: item.lineId,
            skuId: Number(l.sku_id),
            quantity: item.quantity,
            unitCents: Number(l.unit_cents),
            principalCents:
              l.principal_cents === null ? null : Number(l.principal_cents),
            unshippedQuantity: unshipped,
            returnQuantity: item.quantity - unshipped,
          };
        });
        const goods = lines.reduce((n, l) => n + l.unitCents * l.quantity, 0);
        const held = db
          .prepare(
            "SELECT coalesce(sum(shipping_cents),0) AS shipping,coalesce(sum(total_cents),0) AS total FROM refunds WHERE order_id=? AND status<>'rejected' AND is_deleted=0",
          )
          .get(o.id)!;
        if (
          v.shippingCents + Number(held.shipping) > o.shipping_cents ||
          goods + v.shippingCents + Number(held.total) > o.total_cents
        )
          fail("退款金额不能超过原订单剩余额度");
        const id = Number(
          db
            .prepare(
              "INSERT INTO refunds(order_id,number,kind,goods_cents,shipping_cents,total_cents,lines,reason,policy_snapshot,request_key,request_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            )
            .run(
              o.id,
              "RF" + randomUUID().replaceAll("-", ""),
              o.kind,
              goods,
              v.shippingCents,
              goods + v.shippingCents,
              JSON.stringify(lines),
              v.reason,
              JSON.stringify({
                days: snapshot.afterSalesDays,
                policy: snapshot.afterSalesPolicy,
                exceptionReason: v.exceptionReason ?? null,
              }),
              v.requestKey,
              res.locals.requestId,
              actor,
              actor,
            ).lastInsertRowid,
        );
        audit(
          res,
          "refund.request",
          "refund",
          id,
          v.reason +
            (v.exceptionReason ? "；品牌方特批：" + v.exceptionReason : ""),
        );
        return getRefund(db, id);
      });
      res.json(refundView(r));
    },
  );
  app.post(
    "/api/admin/refunds/:id/review",
    staffAuth,
    permit("orders:manage"),
    (req, res) => {
      const v = z
          .object({
            approve: z.boolean(),
            identityDecision: z.enum(["retain", "revoke"]).nullable(),
            reason: z.string().trim().min(1).max(200),
          })
          .strict()
          .parse(req.body),
        id = entityId.parse(req.params.id),
        actor = res.locals.staff.id;
      transaction(db, () => {
        const r = getRefund(db, id);
        if (r.status !== "requested") fail("该售后已审核");
        if (v.approve && r.kind === "package" && !v.identityDecision)
          fail("产品包部分退款须记录身份保留或撤销结论");
        if (!v.approve) {
          for (const l of JSON.parse(r.lines) as RefundLine[])
            db.prepare(
              `UPDATE order_lines SET refund_held=refund_held-?,held_unshipped=held_unshipped-?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?`,
            ).run(l.quantity, l.unshippedQuantity, actor, l.lineId);
        }
        db.prepare(
          `UPDATE refunds SET status=?,identity_decision=?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?`,
        ).run(
          v.approve ? "approved" : "rejected",
          v.identityDecision,
          actor,
          id,
        );
        if (!v.approve) refreshFulfillment(db, r.order_id);
        audit(res, "refund.review", "refund", id, v.reason);
      });
      res.json(refundView(getRefund(db, id)));
    },
  );
  app.post(
    "/api/admin/refunds/:id/inspect",
    staffAuth,
    permit("orders:manage"),
    (req, res) => {
      const v = z
          .object({
            returnedAt: z.iso.datetime(),
            inspection: z.string().trim().min(1).max(1000),
            restock: z.boolean(),
            reason: z.string().trim().min(1).max(200),
          })
          .strict()
          .parse(req.body),
        id = entityId.parse(req.params.id),
        actor = res.locals.staff.id;
      if (Date.parse(v.returnedAt) > Date.now()) fail("退货收货时间不能在未来");
      transaction(db, () => {
        const r = getRefund(db, id);
        if (
          !["approved", "processing", "unknown", "failed", "success"].includes(
            r.status,
          )
        )
          fail("请先审核售后");
        if (r.returned_at) fail("该退货已登记验收");
        for (const l of JSON.parse(r.lines) as RefundLine[]) {
          if (!l.returnQuantity) continue;
          db.prepare(
            "INSERT INTO returned_inventory(refund_id,line_id,quantity,restocked,inspection,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?)",
          ).run(
            id,
            l.lineId,
            l.returnQuantity,
            Number(v.restock),
            v.inspection,
            actor,
            actor,
          );
          if (v.restock) {
            const change = db
              .prepare(
                `UPDATE skus SET stock=stock+?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?`,
              )
              .run(l.returnQuantity, actor, l.skuId);
            if (!change.changes) fail("原规格不存在，请先处理库存依据");
          }
        }
        db.prepare(
          `UPDATE refunds SET returned_at=?,inspection=?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?`,
        ).run(v.returnedAt, v.inspection, actor, id);
        audit(res, "refund.inspect", "refund", id, v.reason);
      });
      res.json(refundView(getRefund(db, id)));
    },
  );
  app.post(
    "/api/admin/refunds/:id/points",
    staffAuth,
    permit("finance:manage"),
    (req, res) => {
      const { reason } = reasonSchema.parse(req.body),
        id = entityId.parse(req.params.id);
      transaction(db, () => {
        const r = getRefund(db, id),
          o = ownedOrder(db, r.order_id);
        if (o.payment_method !== "points") fail("只能退回原积分支付");
        finishRefund(db, r, hooks, res.locals.staff.id, res.locals.requestId);
        audit(res, "refund.points", "refund", id, reason);
      });
      res.json(refundView(getRefund(db, id)));
    },
  );
  app.post(
    "/api/admin/refunds/:id/offline",
    staffAuth,
    permit("finance:manage"),
    (req, res) => {
      const v = z
          .object({
            recipient: z.string().trim().min(1).max(100),
            paidAt: z.iso.datetime(),
            proof: z.string().trim().min(1).max(1000),
            confirmed: z.literal(true),
            reason: z.string().trim().min(1).max(200),
          })
          .strict()
          .parse(req.body),
        id = entityId.parse(req.params.id);
      if (Date.parse(v.paidAt) > Date.now()) fail("实际付款时间不能在未来");
      transaction(db, () => {
        const r = getRefund(db, id),
          o = ownedOrder(db, r.order_id);
        if (o.kind !== "package" || o.payment_method !== "offline")
          fail("仅公司转款产品包可登记线下退款");
        if (r.status === "success") return;
        db.prepare(
          "UPDATE refunds SET recipient=?,paid_at=?,proof=?,channel_ref=? WHERE id=?",
        ).run(v.recipient, v.paidAt, v.proof, v.proof, id);
        finishRefund(db, r, hooks, res.locals.staff.id, res.locals.requestId);
        audit(res, "refund.offline", "refund", id, v.reason);
      });
      res.json(refundView(getRefund(db, id)));
    },
  );
  // 退款只使用已入账的原微信支付凭据，不能把订单应付金额当作渠道实付依据。
  function originalPayment(o: OrderRow) {
    const payment = db
      .prepare(
        "SELECT * FROM payments WHERE order_id=? AND method='wechat' AND is_deleted=0",
      )
      .get(o.id);
    if (
      !payment ||
      payment.cents !== o.total_cents ||
      !String(payment.out_trade_no ?? "").trim() ||
      !String(payment.transaction_id ?? "").trim()
    )
      fail("原微信支付金额或流水不完整，请核对原支付依据");
    return payment!;
  }
  function applyChannel(
    id: number,
    token: string,
    result: RefundResult,
    ctxRes: Parameters<BusinessContext["audit"]>[0],
  ) {
    transaction(db, () => {
      const r = getRefund(db, id);
      // 租约过期后可能已由另一请求查询原单，旧响应不能覆盖新的核查结果。
      if (r.channel_token !== token || r.status === "success") return;
      const payment = originalPayment(ownedOrder(db, r.order_id));
      if (
        result.outRefundNo !== r.number ||
        result.outTradeNo !== payment.out_trade_no ||
        result.totalCents !== payment.cents ||
        result.refundCents !== r.total_cents
      )
        fail("渠道退款对象或金额不一致，保留待核查状态");
      db.prepare(
        `UPDATE refunds SET status=?,channel_ref=?,last_error=?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?`,
      ).run(
        result.status === "success" ? "processing" : result.status,
        result.refundId ?? null,
        result.reason ?? "",
        ctxRes.locals.staff.id,
        id,
      );
      if (result.status === "success")
        finishRefund(
          db,
          getRefund(db, id),
          hooks,
          ctxRes.locals.staff.id,
          ctxRes.locals.requestId,
        );
      audit(
        ctxRes,
        "refund.channel",
        "refund",
        id,
        result.reason || "核对渠道退款结果",
        result.status === "failed" ? "failure" : "success",
      );
    });
  }
  for (const action of ["wechat", "query"] as const)
    app.post(
      "/api/admin/refunds/:id/" + action,
      staffAuth,
      permit("finance:manage"),
      async (req, res) => {
        const { reason } = reasonSchema.parse(req.body),
          id = entityId.parse(req.params.id);
        if (!hooks.channel)
          throw new HttpError(
            503,
            "WECHAT_PAY_NOT_CONFIGURED",
            "微信商户退款尚未配置",
          );
        const attempt = transaction(db, () => {
          const r = getRefund(db, id),
            o = ownedOrder(db, r.order_id);
          if (o.payment_method !== "wechat") fail("该订单不是微信支付");
          if (r.status === "success") return { r, token: null };
          if (
            !["approved", "failed", "processing", "unknown"].includes(r.status)
          )
            fail("请先审核售后");
          if (
            (JSON.parse(r.lines) as RefundLine[]).some(
              (l) => l.returnQuantity > 0,
            ) &&
            !r.returned_at
          )
            fail("请先登记实际退货验收");
          if (
            action === "wechat" &&
            ["processing", "unknown"].includes(r.status)
          )
            fail("原退款结果未明，请查询原退款单");
          const payment = originalPayment(o),
            token = randomUUID(),
            now = new Date().toISOString();
          // 发起与查询共用持久租约，跨请求、跨进程均只允许一个渠道调用。
          const claimed = db
            .prepare(
              `UPDATE refunds SET status='processing',channel_token=?,channel_until=?,request_id=?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND is_deleted=0 AND (channel_until IS NULL OR channel_until<?)`,
            )
            .run(
              token,
              new Date(Date.now() + 30000).toISOString(),
              res.locals.requestId,
              res.locals.staff.id,
              id,
              now,
            );
          if (!claimed.changes) fail("原退款正在处理，请稍后查询");
          return { r, o, payment, token };
        });
        const { r, token } = attempt;
        if (!token) {
          res.json(refundView(r));
          return;
        }
        const { o, payment } = attempt;
        try {
          const result =
            action === "query"
              ? await hooks.channel.queryRefund(r.number)
              : await hooks.channel.refund({
                  outTradeNo: String(payment!.out_trade_no),
                  transactionId: String(payment!.transaction_id),
                  outRefundNo: r.number,
                  totalCents: Number(payment!.cents),
                  refundCents: r.total_cents,
                  reason,
                });
          applyChannel(id, token, result, res);
        } catch (error) {
          const recorded = transaction(db, () => {
            const current = getRefund(db, id);
            if (current.status === "success" || current.channel_token !== token)
              return false;
            const message =
              error instanceof HttpError ? error.message : "渠道退款结果待核查";
            db.prepare(
              "UPDATE refunds SET status='unknown',last_error=?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",
            ).run(message, res.locals.staff.id, id);
            audit(res, "refund.unknown", "refund", id, message, "failure");
            return true;
          });
          if (recorded)
            console.error(
              JSON.stringify({
                message: "退款结果待核查",
                refund: r.number,
                order: o!.number,
                requestId: res.locals.requestId,
              }),
            );
        } finally {
          db.prepare(
            "UPDATE refunds SET channel_token=NULL,channel_until=NULL WHERE id=? AND channel_token=?",
          ).run(id, token);
        }
        res.json(refundView(getRefund(db, id)));
      },
    );
}
