import type { Express } from "express";
import { randomUUID } from "node:crypto";
import { z } from "zod";
import { auditColumns, transaction, type DB } from "./db.ts";
import { entityId } from "./catalog.ts";
import { fail, HttpError, type BusinessContext } from "./http.ts";
import { ledger } from "./wallet.ts";
export function migrateCashRefunds(db: DB) {
  db.exec(
    `CREATE TABLE IF NOT EXISTS cash_refunds(id INTEGER PRIMARY KEY,number TEXT NOT NULL UNIQUE,user_id INTEGER NOT NULL REFERENCES users(id),receipt_id INTEGER NOT NULL REFERENCES company_receipts(id),lot_id INTEGER NOT NULL REFERENCES wallet_lots(id),amount_cents INTEGER NOT NULL CHECK(amount_cents>0),status TEXT NOT NULL DEFAULT 'processing',reason TEXT NOT NULL,request_key TEXT NOT NULL UNIQUE,request_id TEXT NOT NULL,recipient TEXT,paid_at TEXT,proof TEXT,last_error TEXT NOT NULL DEFAULT '',completed_at TEXT,${auditColumns});CREATE INDEX IF NOT EXISTS cash_refunds_user ON cash_refunds(user_id,id);`,
  );
}
export function mountCashRefunds(app: Express, ctx: BusinessContext) {
  const { db, userAuth, staffAuth, permit, audit } = ctx;
  const get = (id: number) => {
    const r = db
      .prepare("SELECT * FROM cash_refunds WHERE id=? AND is_deleted=0")
      .get(id);
    if (!r) throw new HttpError(404, "NOT_FOUND", "积分余额退款记录不存在");
    return r;
  };
  app.get(
    "/api/admin/cash-refunds",
    staffAuth,
    permit("finance:manage"),
    (_req, res) =>
      res.json(
        db
          .prepare(
            "SELECT * FROM cash_refunds WHERE is_deleted=0 ORDER BY id DESC LIMIT 200",
          )
          .all(),
      ),
  );
  app.get("/api/cash-refunds", userAuth, (_req, res) =>
    res.json(
      db
        .prepare(
          "SELECT * FROM cash_refunds WHERE user_id=? AND is_deleted=0 ORDER BY id DESC LIMIT 100",
        )
        .all(res.locals.user.id),
    ),
  );
  app.post(
    "/api/admin/cash-refunds",
    staffAuth,
    permit("finance:manage"),
    (req, res) => {
      const v = z
          .object({
            userId: entityId,
            receiptId: entityId,
            amountCents: z.number().int().min(1).max(100000000),
            reason: z.string().trim().min(1).max(200),
            requestKey: z.string().regex(/^[a-zA-Z0-9_-]{12,100}$/),
          })
          .strict()
          .parse(req.body),
        actor = res.locals.staff.id;
      const id = transaction(db, () => {
        const old = db
          .prepare("SELECT * FROM cash_refunds WHERE request_key=?")
          .get(v.requestKey);
        if (old) {
          if (
            old.user_id !== v.userId ||
            old.receipt_id !== v.receiptId ||
            old.amount_cents !== v.amountCents
          )
            fail("该请求编号已用于其他余额退款");
          return Number(old.id);
        }
        const lot = db
          .prepare(
            `SELECT l.*,r.amount_cents,r.cash_refunded_cents,r.cash_held_cents,r.purpose FROM wallet_lots l JOIN company_receipts r ON r.id=l.receipt_id AND r.is_deleted=0 WHERE l.receipt_id=? AND l.user_id=? AND r.user_id=? AND l.is_deleted=0 AND r.purpose='points'`,
          )
          .get(v.receiptId, v.userId, v.userId);
        if (!lot)
          fail("该收款不是本人有效订货积分来源，产品包款不能退积分现金");
        if (
          Number(lot!.remaining_cents) - Number(lot!.held_cents) <
            v.amountCents ||
          Number(lot!.amount_cents) -
            Number(lot!.cash_refunded_cents) -
            Number(lot!.cash_held_cents) <
            v.amountCents
        )
          fail("可用积分或原收款剩余未退现金不足");
        if (
          !db
            .prepare(
              `UPDATE wallet_lots SET held_cents=held_cents+?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND is_deleted=0 AND remaining_cents-held_cents>=?`,
            )
            .run(v.amountCents, actor, lot!.id, v.amountCents).changes
        )
          fail("可用积分已变化");
        if (
          !db
            .prepare(
              `UPDATE company_receipts SET cash_held_cents=cash_held_cents+?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND is_deleted=0 AND amount_cents-cash_refunded_cents-cash_held_cents>=?`,
            )
            .run(v.amountCents, actor, v.receiptId, v.amountCents).changes
        )
          fail("原收款可退金额已变化");
        const id = Number(
          db
            .prepare(
              "INSERT INTO cash_refunds(number,user_id,receipt_id,lot_id,amount_cents,reason,request_key,request_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
            )
            .run(
              "CR" + randomUUID().replaceAll("-", ""),
              v.userId,
              v.receiptId,
              lot!.id,
              v.amountCents,
              v.reason,
              v.requestKey,
              res.locals.requestId,
              actor,
              actor,
            ).lastInsertRowid,
        );
        audit(res, "cash_refund.reserve", "cash_refund", id, v.reason);
        return id;
      });
      res.json(get(id));
    },
  );
  app.post(
    "/api/admin/cash-refunds/:id/resolve",
    staffAuth,
    permit("finance:manage"),
    (req, res) => {
      const v = z
          .object({
            outcome: z.enum(["success", "failed", "rejected", "unknown"]),
            recipient: z.string().trim().max(100),
            paidAt: z.string(),
            proof: z.string().trim().max(1000),
            confirmed: z.boolean(),
            confirmedNoPayment: z.boolean(),
            reason: z.string().trim().min(1).max(200),
          })
          .strict()
          .parse(req.body),
        id = entityId.parse(req.params.id),
        actor = res.locals.staff.id;
      if (
        v.outcome === "success" &&
        (!v.confirmed ||
          !v.recipient ||
          !v.proof ||
          !z.iso.datetime().safeParse(v.paidAt).success ||
          Date.parse(v.paidAt) > Date.now())
      )
        fail("实际退款成功需确认收款人、付款时间和凭证");
      const row = transaction(db, () => {
        const r = get(id);
        if (r.status === "success") {
          if (v.outcome !== "success") fail("已完成退款不能改为其他状态");
          return r;
        }
        if (["failed", "rejected"].includes(String(r.status)))
          fail("该退款已结束，重新办理须提交新申请");
        if (v.outcome === "unknown") {
          db.prepare(
            `UPDATE cash_refunds SET status='unknown',last_error=?,request_id=?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?`,
          ).run(v.reason, res.locals.requestId, actor, id);
          audit(
            res,
            "cash_refund.unknown",
            "cash_refund",
            id,
            v.reason,
            "failure",
          );
          return get(id);
        }
        if (
          ["failed", "rejected"].includes(v.outcome) &&
          (!v.confirmedNoPayment || (r.status === "unknown" && !v.proof))
        )
          fail("释放占用前须核实未实际退款，结果未明时须补充核查凭证");
        const amount = Number(r.amount_cents);
        const lot = db
          .prepare(
            `UPDATE wallet_lots SET held_cents=held_cents-?,remaining_cents=remaining_cents-?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND is_deleted=0 AND held_cents>=? AND remaining_cents>=?`,
          )
          .run(
            amount,
            v.outcome === "success" ? amount : 0,
            actor,
            r.lot_id,
            amount,
            amount,
          );
        if (!lot.changes) fail("原积分占用不一致，请核查");
        const receipt = db
          .prepare(
            `UPDATE company_receipts SET cash_held_cents=cash_held_cents-?,cash_refunded_cents=cash_refunded_cents+?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND is_deleted=0 AND cash_held_cents>=?`,
          )
          .run(
            amount,
            v.outcome === "success" ? amount : 0,
            actor,
            r.receipt_id,
            amount,
          );
        if (!receipt.changes) fail("原公司收款退款占用不一致，请核查");
        if (v.outcome === "success")
          ledger(db, {
            userId: Number(r.user_id),
            receiptId: Number(r.receipt_id),
            lotId: Number(r.lot_id),
            kind: "cash_refund",
            delta: -amount,
            reference: String(r.number),
            key: "cash-refund:" + id,
            reason: v.reason,
            actor,
          });
        db.prepare(
          `UPDATE cash_refunds SET status=?,recipient=?,paid_at=?,proof=?,completed_at=?,last_error=?,request_id=?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?`,
        ).run(
          v.outcome,
          v.recipient || null,
          v.paidAt || null,
          v.proof || null,
          new Date().toISOString(),
          v.outcome === "success" ? "" : v.reason,
          res.locals.requestId,
          actor,
          id,
        );
        audit(
          res,
          "cash_refund." + v.outcome,
          "cash_refund",
          id,
          v.reason,
          v.outcome === "failed" ? "failure" : "success",
        );
        return get(id);
      });
      res.json(row);
    },
  );
}
