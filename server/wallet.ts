import type { Express } from "express";
import { z } from "zod";
import {
  assertTransaction,
  auditColumns,
  effectiveIdentity,
  transaction,
  type DB,
  type UserRow,
} from "./db.ts";
import { cents, entityId, setting } from "./catalog.ts";
import { ownedOrder, orderView } from "./orders.ts";
import { settlePaid } from "./payments.ts";
import { fail, HttpError, type BusinessContext } from "./http.ts";

const text = z.string().trim().min(1).max(1000);
const stamp = "strftime('%Y-%m-%dT%H:%M:%fZ','now')";
export function migrateReceipts(db: DB) {
  db.exec(`CREATE TABLE IF NOT EXISTS company_receipts (
    id INTEGER PRIMARY KEY,user_id INTEGER NOT NULL REFERENCES users(id),
    purpose TEXT NOT NULL CHECK(purpose IN ('package','points')),onboarding_id INTEGER,
    source_channel TEXT NOT NULL CHECK(source_channel IN ('bank','wechat_company')),
    account_ref TEXT NOT NULL,external_transaction_id TEXT NOT NULL,amount_cents INTEGER NOT NULL CHECK(amount_cents>0),
    received_at TEXT NOT NULL,evidence TEXT NOT NULL,reason TEXT NOT NULL,
    confirmed_by INTEGER NOT NULL,confirmed_at TEXT NOT NULL,
    cash_refunded_cents INTEGER NOT NULL DEFAULT 0 CHECK(cash_refunded_cents>=0),
    cash_held_cents INTEGER NOT NULL DEFAULT 0 CHECK(cash_held_cents>=0),
    ${auditColumns},CHECK(cash_refunded_cents+cash_held_cents<=amount_cents),
    UNIQUE(source_channel,account_ref,external_transaction_id));
    CREATE INDEX IF NOT EXISTS receipts_user ON company_receipts(user_id,id);
    CREATE INDEX IF NOT EXISTS receipts_reference ON company_receipts(external_transaction_id);`);
}
export function migrateWallet(db: DB) {
  migrateReceipts(db);
  db.exec(`CREATE TABLE IF NOT EXISTS wallet_lots (
    id INTEGER PRIMARY KEY,user_id INTEGER NOT NULL REFERENCES users(id),receipt_id INTEGER NOT NULL UNIQUE REFERENCES company_receipts(id),
    issued_cents INTEGER NOT NULL CHECK(issued_cents>0),remaining_cents INTEGER NOT NULL,
    held_cents INTEGER NOT NULL DEFAULT 0,${auditColumns},
    CHECK(remaining_cents>=0 AND remaining_cents<=issued_cents),CHECK(held_cents>=0 AND held_cents<=remaining_cents));
    CREATE INDEX IF NOT EXISTS lots_user ON wallet_lots(user_id,id);
    CREATE TABLE IF NOT EXISTS wallet_spend_allocations (
    id INTEGER PRIMARY KEY,order_id INTEGER NOT NULL REFERENCES orders(id),user_id INTEGER NOT NULL REFERENCES users(id),
    lot_id INTEGER NOT NULL REFERENCES wallet_lots(id),receipt_id INTEGER NOT NULL REFERENCES company_receipts(id),
    spent_cents INTEGER NOT NULL CHECK(spent_cents>0),refunded_cents INTEGER NOT NULL DEFAULT 0,
    ${auditColumns},CHECK(refunded_cents>=0 AND refunded_cents<=spent_cents),UNIQUE(order_id,lot_id));
    CREATE TABLE IF NOT EXISTS wallet_ledger (
    id INTEGER PRIMARY KEY,user_id INTEGER NOT NULL REFERENCES users(id),receipt_id INTEGER NOT NULL REFERENCES company_receipts(id),
    lot_id INTEGER NOT NULL REFERENCES wallet_lots(id),order_id INTEGER REFERENCES orders(id),
    kind TEXT NOT NULL CHECK(kind IN ('credit','spend','order_refund','cash_refund','correction')),
    delta_cents INTEGER NOT NULL CHECK(delta_cents!=0),reference TEXT NOT NULL,idempotency_key TEXT NOT NULL UNIQUE,
    reason TEXT NOT NULL,${auditColumns});
    CREATE INDEX IF NOT EXISTS ledger_user ON wallet_ledger(user_id,id);`);
}
export function requireAgent(db: DB, id: number) {
  const u = db
    .prepare("SELECT * FROM users WHERE id=? AND is_deleted=0")
    .get(id) as unknown as UserRow | undefined;
  if (!u || !["B", "C"].includes(effectiveIdentity(u)))
    throw new HttpError(
      403,
      "AGENT_REQUIRED",
      "仅当前有效代理可以办理订货积分业务",
    );
  return u;
}
export function walletView(db: DB, userId: number) {
  const balance = db
    .prepare(
      `SELECT COALESCE(SUM(remaining_cents),0) AS balanceCents,COALESCE(SUM(held_cents),0) AS heldCents,
    COALESCE(SUM(remaining_cents-held_cents),0) AS availableCents FROM wallet_lots WHERE user_id=? AND is_deleted=0`,
    )
    .get(userId) as {
    balanceCents: number;
    heldCents: number;
    availableCents: number;
  };
  const totals = db
    .prepare(
      `SELECT COALESCE(SUM(CASE WHEN kind='credit' THEN delta_cents ELSE 0 END),0) AS creditedCents,
    COALESCE(SUM(CASE WHEN kind='spend' THEN -delta_cents ELSE 0 END),0) AS spentCents FROM wallet_ledger WHERE user_id=? AND is_deleted=0`,
    )
    .get(userId) as { creditedCents: number; spentCents: number };
  return {
    ...balance,
    ...totals,
    lots: db
      .prepare(
        `SELECT l.*,r.source_channel,r.account_ref,r.external_transaction_id,r.amount_cents,r.cash_refunded_cents,r.cash_held_cents
      FROM wallet_lots l JOIN company_receipts r ON r.id=l.receipt_id AND r.is_deleted=0 WHERE l.user_id=? AND l.is_deleted=0 ORDER BY l.id`,
      )
      .all(userId),
    ledger: db
      .prepare(
        `SELECT l.*,o.number AS order_number,r.external_transaction_id FROM wallet_ledger l
      JOIN company_receipts r ON r.id=l.receipt_id AND r.is_deleted=0 LEFT JOIN orders o ON o.id=l.order_id AND o.is_deleted=0
      WHERE l.user_id=? AND l.is_deleted=0 ORDER BY l.id DESC LIMIT 200`,
      )
      .all(userId),
  };
}
export function companyTransfer(db: DB) {
  const c = setting<Record<string, string>>(db, "content");
  if (!c?.companyName || !c.bankName || !c.bankAccount)
    return {
      configured: false,
      companyName: "",
      bankName: "",
      bankAccount: "",
      transferNotes: "公司收款资料尚未配置，请联系品牌方核实",
      customerService: c?.customerService || "",
    };
  return {
    configured: true,
    companyName: c.companyName,
    bankName: c.bankName,
    bankAccount: c.bankAccount,
    transferNotes: c.transferNotes,
    customerService: c.customerService,
  };
}
export const receiptSchema = z
  .object({
    userId: entityId,
    purpose: z.enum(["package", "points"]),
    onboardingId: entityId.optional(),
    sourceChannel: z.enum(["bank", "wechat_company"]),
    accountRef: text.max(200),
    externalTransactionId: text.max(200),
    amountCents: cents,
    receivedAt: z.iso
      .datetime({ offset: true })
      .refine((v) => Date.parse(v) <= Date.now(), "到账时间不能在未来"),
    evidence: text,
    reason: text.max(200),
    verified: z.literal(true),
  })
  .strict();
export type ReceiptInput = z.infer<typeof receiptSchema>;
// 调用方持有事务；真实到账事实必须由有权限的工作人员确认。
export function registerReceipt(db: DB, raw: ReceiptInput, actor: number) {
  const v = receiptSchema.parse(raw);
  if (
    !db
      .prepare("SELECT id FROM users WHERE id=? AND is_deleted=0")
      .get(v.userId)
  )
    fail("收款用户不存在");
  if (
    db
      .prepare(
        "SELECT id FROM company_receipts WHERE source_channel=? AND account_ref=? AND external_transaction_id=?",
      )
      .get(v.sourceChannel, v.accountRef, v.externalTransactionId)
  )
    fail(
      "该原收款流水已登记，不得重复用于产品包或发积分",
      "RECEIPT_ALREADY_USED",
    );
  if (v.purpose === "points") {
    requireAgent(db, v.userId);
    if (v.onboardingId) fail("订货款不能绑定入驻用途");
  } else {
    if (!v.onboardingId) fail("产品包款必须指定入驻申请");
    const o = db
      .prepare(
        "SELECT id,package_snapshot,status,receipt_id FROM onboardings WHERE id=? AND user_id=? AND is_deleted=0",
      )
      .get(v.onboardingId!, v.userId);
    if (!o || o.status !== "submitted" || o.receipt_id)
      fail("该申请不能再确认产品包收款");
    if (JSON.parse(String(o!.package_snapshot)).amountCents !== v.amountCents)
      fail("到账金额须与当次产品包货款一致");
  }
  const id = Number(
    db
      .prepare(
        `INSERT INTO company_receipts(user_id,purpose,onboarding_id,source_channel,account_ref,external_transaction_id,amount_cents,received_at,evidence,reason,confirmed_by,confirmed_at,create_user_id,update_user_id)
    VALUES(?,?,?,?,?,?,?,?,?,?,?,${stamp},?,?)`,
      )
      .run(
        v.userId,
        v.purpose,
        v.onboardingId ?? null,
        v.sourceChannel,
        v.accountRef,
        v.externalTransactionId,
        v.amountCents,
        v.receivedAt,
        v.evidence,
        v.reason,
        actor,
        actor,
        actor,
      ).lastInsertRowid,
  );
  if (v.purpose === "points") {
    const lotId = Number(
      db
        .prepare(
          "INSERT INTO wallet_lots(user_id,receipt_id,issued_cents,remaining_cents,create_user_id,update_user_id) VALUES(?,?,?,?,?,?)",
        )
        .run(v.userId, id, v.amountCents, v.amountCents, actor, actor)
        .lastInsertRowid,
    );
    ledger(db, {
      userId: v.userId,
      receiptId: id,
      lotId,
      kind: "credit",
      delta: v.amountCents,
      reference: String(id),
      key: `receipt:${id}`,
      reason: v.reason,
      actor,
    });
  }
  return id;
}
interface Entry {
  userId: number;
  receiptId: number;
  lotId: number;
  orderId?: number;
  kind: string;
  delta: number;
  reference: string;
  key: string;
  reason: string;
  actor: number;
}
export function ledger(db: DB, e: Entry) {
  db.prepare(
    "INSERT INTO wallet_ledger(user_id,receipt_id,lot_id,order_id,kind,delta_cents,reference,idempotency_key,reason,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
  ).run(
    e.userId,
    e.receiptId,
    e.lotId,
    e.orderId ?? null,
    e.kind,
    e.delta,
    e.reference,
    e.key,
    e.reason,
    e.actor,
    e.actor,
  );
}
// 退款模块在其已有事务内调用，逐原收款批次归还；业务 reference 保证重复回调不重复返还。
export function restoreOrderPoints(
  db: DB,
  orderId: number,
  amountCents: number,
  reference: string,
  actor = 0,
) {
  assertTransaction(db, "restoreOrderPoints");
  cents.parse(amountCents);
  text.parse(reference);
  const old = db
    .prepare(
      "SELECT COALESCE(SUM(delta_cents),0) AS total FROM wallet_ledger WHERE kind='order_refund' AND order_id=? AND reference=? AND is_deleted=0",
    )
    .get(orderId, reference)!;
  if (Number(old.total)) {
    if (Number(old.total) !== amountCents) fail("退款编号与已退金额不一致");
    return;
  }
  const order = ownedOrder(db, orderId);
  if (
    order.payment_method !== "points" ||
    !["paid", "partially_refunded"].includes(order.payment_state)
  )
    fail("仅原积分已支付订单可退回积分");
  const allocations = db
    .prepare(
      "SELECT * FROM wallet_spend_allocations WHERE order_id=? AND is_deleted=0 ORDER BY id",
    )
    .all(orderId);
  if (
    allocations.reduce(
      (s, a) => s + Number(a.spent_cents) - Number(a.refunded_cents),
      0,
    ) < amountCents
  )
    fail("退款超过原积分支付剩余额度");
  let left = amountCents;
  for (const a of allocations) {
    if (!left) break;
    const n = Math.min(left, Number(a.spent_cents) - Number(a.refunded_cents));
    if (!n) continue;
    const changed = db
      .prepare(
        `UPDATE wallet_spend_allocations SET refunded_cents=refunded_cents+?,update_user_id=?,update_time=${stamp} WHERE id=? AND refunded_cents+?<=spent_cents AND is_deleted=0`,
      )
      .run(n, actor, a.id, n);
    if (!changed.changes) fail("退款来源已变化，请重新核对");
    const restored = db
      .prepare(
        `UPDATE wallet_lots SET remaining_cents=remaining_cents+?,update_user_id=?,update_time=${stamp} WHERE id=? AND user_id=? AND is_deleted=0 AND remaining_cents+?<=issued_cents`,
      )
      .run(n, actor, a.lot_id, order.user_id, n);
    if (!restored.changes) fail("原积分批次无法恢复");
    ledger(db, {
      userId: order.user_id,
      receiptId: Number(a.receipt_id),
      lotId: Number(a.lot_id),
      orderId,
      kind: "order_refund",
      delta: n,
      reference,
      key: `refund:${orderId}:${reference}:${a.id}`,
      reason: "商品订单退款返回原收款积分批次",
      actor,
    });
    left -= n;
  }
}
// 仅在整笔订单结果明确时支付，余额、库存和支付记录由同一事务提交。
export function payWithPoints(
  db: DB,
  userId: number,
  orderId: number,
  onPaid?: () => void,
) {
  return transaction(db, () => {
    const user = requireAgent(db, userId),
      o = ownedOrder(db, orderId, userId);
    if (o.payment_method === "points" && o.payment_state === "paid")
      return orderView(db, o);
    if (o.kind !== "goods") fail("产品包只支持公司转款");
    if (
      o.status !== "pending" ||
      !["unpaid", "failed"].includes(o.payment_state)
    )
      fail("原支付结果未明或订单已付款，不能改用积分", "PAYMENT_UNRESOLVED");
    if (
      o.payment_method &&
      o.payment_method !== "points" &&
      o.payment_state !== "failed"
    )
      fail("请先确认并关闭原微信交易");
    if (Date.parse(o.expires_at) <= Date.now()) fail("待付款订单已过期");
    if (JSON.parse(o.snapshot).identity !== effectiveIdentity(user))
      fail("代理身份已变化，请重新确认订单");
    const lots = db
      .prepare(
        "SELECT * FROM wallet_lots WHERE user_id=? AND is_deleted=0 AND remaining_cents>held_cents ORDER BY id",
      )
      .all(userId);
    const available = lots.reduce(
      (s, l) => s + Number(l.remaining_cents) - Number(l.held_cents),
      0,
    );
    if (available < o.total_cents)
      fail(
        `积分不足，还差 ${((o.total_cents - available) / 100).toFixed(2)} 积分，请核实公司转款或待原交易明确后整单微信付款`,
        "POINTS_INSUFFICIENT",
      );
    let left = o.total_cents;
    for (const lot of lots) {
      if (!left) break;
      const n = Math.min(
        left,
        Number(lot.remaining_cents) - Number(lot.held_cents),
      );
      if (
        !db
          .prepare(
            `UPDATE wallet_lots SET remaining_cents=remaining_cents-?,update_user_id=?,update_time=${stamp} WHERE id=? AND remaining_cents-held_cents>=? AND is_deleted=0`,
          )
          .run(n, userId, lot.id, n).changes
      )
        fail("可用积分已变化，请重试");
      db.prepare(
        "INSERT INTO wallet_spend_allocations(order_id,user_id,lot_id,receipt_id,spent_cents,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?)",
      ).run(orderId, userId, lot.id, lot.receipt_id, n, userId, userId);
      ledger(db, {
        userId,
        receiptId: Number(lot.receipt_id),
        lotId: Number(lot.id),
        orderId,
        kind: "spend",
        delta: -n,
        reference: o.number,
        key: `spend:${orderId}:${lot.id}`,
        reason: "整单积分订货付款",
        actor: userId,
      });
      left -= n;
    }
    settlePaid(db, o, "points", `points:${o.id}`);
    onPaid?.();
    return orderView(db, ownedOrder(db, orderId, userId));
  });
}
export function mountWallet(app: Express, ctx: BusinessContext) {
  const { db, userAuth, staffAuth, permit, audit } = ctx;
  app.get("/api/wallet", userAuth, (_req, res) =>
    res.json({
      ...walletView(db, res.locals.user.id),
      transfer: companyTransfer(db),
    }),
  );
  app.get("/api/wallet/transfer", userAuth, (_req, res) => {
    requireAgent(db, res.locals.user.id);
    res.json(companyTransfer(db));
  });
  app.get(
    "/api/admin/receipts",
    staffAuth,
    permit("finance:manage"),
    (req, res) => {
      const query = z
        .object({
          userId: entityId.optional(),
          receiptId: entityId.optional(),
          externalTransactionId: text.max(200).optional(),
        })
        .parse(req.query);
      const filters = ["is_deleted=0"];
      const params: (number | string)[] = [];
      if (query.userId) {
        filters.push("user_id=?");
        params.push(query.userId);
      }
      if (query.receiptId) {
        filters.push("id=?");
        params.push(query.receiptId);
      }
      if (query.externalTransactionId) {
        filters.push("external_transaction_id=?");
        params.push(query.externalTransactionId);
      }
      res.json(
        db
          .prepare(
            "SELECT * FROM company_receipts WHERE " +
              filters.join(" AND ") +
              " ORDER BY id DESC LIMIT 300",
          )
          .all(...params),
      );
    },
  );
  app.post(
    "/api/admin/receipts",
    staffAuth,
    permit("finance:manage"),
    (req, res) => {
      const v = receiptSchema.parse(req.body);
      if (v.purpose === "package") fail("产品包核款请从入驻申请办理");
      try {
        const id = transaction(db, () => {
          const id = registerReceipt(db, v, res.locals.staff.id);
          audit(res, "receipt.confirm", "company_receipt", id, v.reason);
          return id;
        });
        res.json({ id, ...walletView(db, v.userId) });
      } catch (error) {
        audit(
          res,
          "receipt.confirm",
          "company_receipt",
          v.externalTransactionId,
          `代理${v.userId}；${error instanceof HttpError ? error.message : "收款确认失败，请核实原流水"}`,
          "failure",
        );
        throw error;
      }
    },
  );
  app.get(
    "/api/admin/wallets/:id",
    staffAuth,
    permit("finance:manage"),
    (req, res) => res.json(walletView(db, entityId.parse(req.params.id))),
  );
  app.post(
    "/api/admin/wallet-lots/:id/correct",
    staffAuth,
    permit("finance:manage"),
    (req, res) => {
      const v = z
          .object({
            amountCents: cents,
            reason: text.max(200),
            requestKey: z.string().trim().min(12).max(100),
          })
          .strict()
          .parse(req.body),
        id = entityId.parse(req.params.id),
        actor = res.locals.staff.id;
      transaction(db, () => {
        const key = `correction:${v.requestKey}`,
          old = db
            .prepare(
              "SELECT * FROM wallet_ledger WHERE idempotency_key=? AND is_deleted=0",
            )
            .get(key);
        if (old) {
          if (
            Number(old.lot_id) !== id ||
            Number(old.delta_cents) !== -v.amountCents ||
            old.reason !== v.reason
          )
            fail("纠错编号已用于其他操作");
          return;
        }
        const lot = db
          .prepare("SELECT * FROM wallet_lots WHERE id=? AND is_deleted=0")
          .get(id);
        if (!lot) fail("原积分批次不存在");
        if (
          !db
            .prepare(
              `UPDATE wallet_lots SET remaining_cents=remaining_cents-?,update_user_id=?,update_time=${stamp} WHERE id=? AND remaining_cents-held_cents>=? AND is_deleted=0`,
            )
            .run(v.amountCents, actor, id, v.amountCents).changes
        )
          fail("纠错金额超过原批次未使用积分，已消费差额必须人工核实处理");
        ledger(db, {
          userId: Number(lot!.user_id),
          receiptId: Number(lot!.receipt_id),
          lotId: id,
          kind: "correction",
          delta: -v.amountCents,
          reference: v.requestKey,
          key,
          reason: v.reason,
          actor,
        });
        audit(res, "wallet.correct", "wallet_lot", id, v.reason);
      });
      res.json({ ok: true });
    },
  );
  app.post("/api/orders/:id/pay/points", userAuth, (req, res) => {
    const id = entityId.parse(req.params.id);
    const order = payWithPoints(db, res.locals.user.id, id, () =>
      audit(res, "order.pay_points", "order", id, "按原收款批次扣积分支付整单"),
    );
    res.json({ order, wallet: walletView(db, res.locals.user.id) });
  });
}
