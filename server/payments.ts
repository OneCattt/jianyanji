import express, { type Express, type Request } from "express";
import { randomUUID } from "node:crypto";
import { z } from "zod";
import {
  auditColumns,
  transaction,
  recordAudit,
  assertTransaction,
  type DB,
} from "./db.ts";
import { entityId } from "./catalog.ts";
import {
  ownedOrder,
  orderView,
  releaseOrder,
  type OrderRow,
} from "./orders.ts";
import { fail, HttpError, type BusinessContext } from "./http.ts";
import {
  paymentAdapterFromEnv,
  ChannelError,
  type PaymentAdapter,
  type ChannelTrade,
} from "./wechat-pay.ts";
export {
  WechatPayV3,
  verifyWechatSignature,
  paymentAdapterFromEnv,
} from "./wechat-pay.ts";
export { createRefund, queryRefund } from "./wechat-pay.ts";
export type { PayChannel, RefundInput, RefundResult } from "./wechat-pay.ts";
export type { PaymentAdapter, ChannelTrade } from "./wechat-pay.ts";
const now = () => new Date().toISOString();
const stamp = `update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now')`;
interface Attempt {
  id: number;
  order_id: number;
  number: string;
  state: string;
  app_id: string;
  mch_id: string;
  cents: number;
  openid: string;
  prepay: string | null;
  transaction_id: string | null;
  verified_trade: string | null;
  last_error: string;
  close_requested: number;
  lease_token: string | null;
  lease_until: string | null;
}
export function migratePayments(db: DB) {
  db.exec(`CREATE TABLE IF NOT EXISTS payment_attempts(id INTEGER PRIMARY KEY,order_id INTEGER NOT NULL REFERENCES orders(id),number TEXT NOT NULL UNIQUE,state TEXT NOT NULL,app_id TEXT NOT NULL,mch_id TEXT NOT NULL,cents INTEGER NOT NULL,openid TEXT NOT NULL,prepay TEXT,transaction_id TEXT,verified_trade TEXT,close_requested INTEGER NOT NULL DEFAULT 0,lease_token TEXT,lease_until TEXT,last_error TEXT NOT NULL DEFAULT '',last_request_id TEXT NOT NULL DEFAULT '',${auditColumns});
  CREATE UNIQUE INDEX IF NOT EXISTS payment_active_order ON payment_attempts(order_id) WHERE is_deleted=0 AND state!='closed';
  CREATE TABLE IF NOT EXISTS payments(id INTEGER PRIMARY KEY,order_id INTEGER NOT NULL REFERENCES orders(id),method TEXT NOT NULL,transaction_id TEXT NOT NULL,out_trade_no TEXT,cents INTEGER NOT NULL,paid_at TEXT NOT NULL,${auditColumns});
  CREATE UNIQUE INDEX IF NOT EXISTS receipt_order ON payments(order_id) WHERE is_deleted=0;
  CREATE UNIQUE INDEX IF NOT EXISTS receipt_channel_transaction ON payments(method,transaction_id) WHERE is_deleted=0;
  CREATE TABLE IF NOT EXISTS payment_events(id INTEGER PRIMARY KEY,order_id INTEGER REFERENCES orders(id),attempt_id INTEGER REFERENCES payment_attempts(id),action TEXT NOT NULL,result TEXT NOT NULL,detail TEXT NOT NULL,request_id TEXT NOT NULL,${auditColumns});
  CREATE INDEX IF NOT EXISTS payment_events_order ON payment_events(order_id,id);`);
}
/** 只在调用方 SQLite 事务内运行；积分余额与库存、订单、收款凭据应同事务提交。 */
export function settlePaid(
  db: DB,
  order: OrderRow,
  method: "wechat" | "points" | "company",
  transactionId: string,
) {
  assertTransaction(db, "settlePaid");
  if (!transactionId?.trim()) fail("缺少支付流水号");
  const current = ownedOrder(db, order.id);
  const receipt = db
    .prepare("SELECT * FROM payments WHERE order_id=? AND is_deleted=0")
    .get(current.id);
  if (receipt) {
    if (
      receipt.method !== method ||
      receipt.transaction_id !== transactionId ||
      receipt.cents !== current.total_cents
    )
      fail("订单已由其他支付流水结算", "PAYMENT_MISMATCH");
    return { alreadyPaid: true, order: current };
  }
  if (current.status !== "pending" || current.payment_state === "paid")
    fail("订单状态不允许结算", "SETTLEMENT_CONFLICT");
  if (method !== "wechat") {
    assertNoUnresolvedPayment(db, current.user_id);
    if (!["unpaid", "failed"].includes(current.payment_state))
      fail("原支付结果尚未确定，禁止切换支付方式");
  } else if (
    current.payment_method !== "wechat" ||
    !["paying", "unknown", "review"].includes(current.payment_state)
  )
    fail("微信支付状态不匹配");
  const lines = db
    .prepare(
      "SELECT sku_id,quantity FROM order_lines WHERE order_id=? AND is_deleted=0",
    )
    .all(current.id);
  if (!lines.length) fail("订单没有可结算商品");
  for (const l of lines) {
    const result = db
      .prepare(
        `UPDATE skus SET stock=stock-?,reserved=reserved-?,update_user_id=?,${stamp} WHERE id=? AND is_deleted=0 AND stock>=? AND reserved>=?`,
      )
      .run(
        l.quantity,
        l.quantity,
        current.user_id,
        l.sku_id,
        l.quantity,
        l.quantity,
      );
    if (!result.changes)
      fail("库存与占用不一致，需要人工核对", "STOCK_SETTLEMENT_CONFLICT");
  }
  db.prepare(
    `UPDATE orders SET status='paid',payment_state='paid',payment_method=?,update_user_id=?,${stamp} WHERE id=?`,
  ).run(method, current.user_id, current.id);
  db.prepare(
    "INSERT INTO payments(order_id,method,transaction_id,cents,paid_at,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?)",
  ).run(
    current.id,
    method,
    transactionId,
    current.total_cents,
    now(),
    current.user_id,
    current.user_id,
  );
  return { alreadyPaid: false, order: ownedOrder(db, current.id) };
}
/** 同一用户存在未决微信交易时，09 积分支付及新微信支付均调用此检查。 */
export function assertNoUnresolvedPayment(
  db: DB,
  userId: number,
  exceptOrderId = 0,
) {
  if (
    db
      .prepare(
        `SELECT p.id FROM payment_attempts p JOIN orders o ON o.id=p.order_id AND o.is_deleted=0 WHERE o.user_id=? AND o.id!=? AND p.is_deleted=0 AND p.state NOT IN ('closed','success') LIMIT 1`,
      )
      .get(userId, exceptOrderId)
  )
    fail("存在待确认的原微信交易，请先查询或关闭原交易", "PAYMENT_UNRESOLVED");
}
function event(
  db: DB,
  attempt: Attempt | undefined,
  action: string,
  result: string,
  detail: string,
  requestId: string,
) {
  db.prepare(
    "INSERT INTO payment_events(order_id,attempt_id,action,result,detail,request_id) VALUES(?,?,?,?,?,?)",
  ).run(
    attempt?.order_id ?? null,
    attempt?.id ?? null,
    action,
    result,
    detail.slice(0, 500),
    requestId,
  );
  recordAudit(db, {
    actorKind: "system",
    actorId: 0,
    action: "payment." + action,
    objectType: "order",
    objectId: attempt?.order_id ?? "notification",
    result: result === "success" ? "success" : "failure",
    reason: detail.slice(0, 500),
    requestId,
  });
}
function currentAttempt(db: DB, id: number) {
  return db
    .prepare("SELECT * FROM payment_attempts WHERE id=? AND is_deleted=0")
    .get(id) as unknown as Attempt;
}
function activeAttempt(db: DB, orderId: number) {
  return db
    .prepare(
      "SELECT * FROM payment_attempts WHERE order_id=? AND is_deleted=0 AND state!='closed' ORDER BY id DESC LIMIT 1",
    )
    .get(orderId) as unknown as Attempt | undefined;
}
function claim(db: DB, a: Attempt) {
  const token = randomUUID();
  const result = db
    .prepare(
      `UPDATE payment_attempts SET lease_token=?,lease_until=?,${stamp} WHERE id=? AND is_deleted=0 AND (lease_until IS NULL OR lease_until<?)`,
    )
    .run(token, new Date(Date.now() + 30000).toISOString(), a.id, now());
  if (!result.changes)
    fail("原交易正在处理，请稍后查询", "PAYMENT_IN_PROGRESS");
  return token;
}
function unclaim(db: DB, a: Attempt, token: string) {
  db.prepare(
    "UPDATE payment_attempts SET lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",
  ).run(a.id, token);
}
function unknown(
  db: DB,
  a: Attempt,
  action: string,
  error: unknown,
  requestId: string,
) {
  const detail = error instanceof Error ? error.message : "渠道处理异常";
  transaction(db, () => {
    db.prepare(
      `UPDATE payment_attempts SET state=CASE WHEN state IN ('success','closed') THEN state ELSE 'unknown' END,last_error=?,last_request_id=?,${stamp} WHERE id=?`,
    ).run(detail.slice(0, 500), requestId, a.id);
    db.prepare(
      `UPDATE orders SET payment_state='unknown',${stamp} WHERE id=? AND payment_state!='paid' AND status='pending'`,
    ).run(a.order_id);
    event(db, a, action, "failure", detail, requestId);
  });
}
function match(a: Attempt, t: ChannelTrade) {
  if (
    t.out_trade_no !== a.number ||
    t.mchid !== a.mch_id ||
    t.appid !== a.app_id ||
    !t.amount ||
    !Number.isSafeInteger(t.amount.total) ||
    t.amount.total !== a.cents ||
    t.amount.currency !== "CNY" ||
    (t.payer?.openid && t.payer.openid !== a.openid)
  )
    throw new HttpError(
      409,
      "PAYMENT_MISMATCH",
      "支付订单、金额、付款人或商户不匹配",
    );
  if (
    t.trade_state === "SUCCESS" &&
    (!t.transaction_id || !t.payer || t.payer.openid !== a.openid)
  )
    throw new HttpError(
      409,
      "PAYMENT_MISMATCH",
      "成功交易缺少有效流水或付款人",
    );
}
function applyTrade(db: DB, a: Attempt, t: ChannelTrade, requestId: string) {
  match(a, t);
  if (t.trade_state === "SUCCESS")
    db.prepare(
      `UPDATE payment_attempts SET transaction_id=?,verified_trade=?,${stamp} WHERE id=?`,
    ).run(t.transaction_id!, JSON.stringify(t), a.id);
  return transaction(db, () => {
    const latest = currentAttempt(db, a.id),
      order = ownedOrder(db, a.order_id);
    if (t.trade_state === "SUCCESS") {
      if (latest.state === "closed")
        fail(
          "已关闭交易发生异常到账，需要按原渠道流水人工补偿",
          "SETTLEMENT_CONFLICT",
        );
      const result = settlePaid(db, order, "wechat", t.transaction_id!);
      db.prepare(
        "UPDATE payments SET out_trade_no=? WHERE order_id=? AND is_deleted=0",
      ).run(a.number, order.id);
      db.prepare(
        `UPDATE payment_attempts SET state='success',last_error='',last_request_id=?,${stamp} WHERE id=?`,
      ).run(requestId, a.id);
      event(
        db,
        a,
        "verified",
        "success",
        result.alreadyPaid
          ? "重复成功通知，已幂等处理"
          : "服务端核验到账并结算库存",
        requestId,
      );
      return result.order;
    }
    if (latest.state === "success" || order.payment_state === "paid")
      return order;
    if (t.trade_state === "CLOSED" || t.trade_state === "REVOKED") {
      db.prepare(
        `UPDATE payment_attempts SET state='closed',prepay=NULL,last_error='',last_request_id=?,${stamp} WHERE id=?`,
      ).run(requestId, a.id);
      db.prepare(
        `UPDATE orders SET payment_state='failed',${stamp} WHERE id=? AND status='pending'`,
      ).run(order.id);
      const updated = ownedOrder(db, order.id);
      if (
        latest.close_requested ||
        Date.parse(updated.expires_at) <= Date.now()
      )
        releaseOrder(db, updated);
      event(
        db,
        a,
        "closed",
        "success",
        "渠道已关闭，允许释放原订单库存",
        requestId,
      );
    } else if (!["NOTPAY", "USERPAYING", "PAYERROR"].includes(t.trade_state))
      throw new Error("未知微信交易状态，保留支付锁");
    else
      event(
        db,
        a,
        "query",
        "success",
        "微信交易状态：" + t.trade_state,
        requestId,
      );
    return ownedOrder(db, order.id);
  });
}
async function reconcile(
  db: DB,
  a: Attempt,
  adapter: PaymentAdapter,
  requestId: string,
  close: boolean,
) {
  const token = claim(db, a);
  try {
    if (adapter.appId !== a.app_id || adapter.mchId !== a.mch_id)
      throw new Error("支付渠道配置与原商户不匹配，保留原交易待核查");
    let trade: ChannelTrade;
    try {
      trade = await adapter.query(a.number);
    } catch (error) {
      const o = ownedOrder(db, a.order_id),
        latest = currentAttempt(db, a.id);
      // 原下单请求带固定 time_expire。过期且原渠道验签确认不存在时，延迟创建也已不可支付。
      // 有预支付凭据或已验明到账的交易不能沿此分支释放。
      if (
        error instanceof ChannelError &&
        error.code === "ORDER_NOT_EXIST" &&
        Date.parse(o.expires_at) <= Date.now() &&
        !latest.prepay &&
        !latest.verified_trade
      ) {
        return transaction(db, () => {
          const current = ownedOrder(db, a.order_id);
          if (current.payment_state === "paid") return current;
          db.prepare(
            `UPDATE payment_attempts SET state='closed',last_error='',last_request_id=?,${stamp} WHERE id=?`,
          ).run(requestId, a.id);
          db.prepare(
            `UPDATE orders SET payment_state='failed',${stamp} WHERE id=? AND status='pending'`,
          ).run(current.id);
          releaseOrder(db, ownedOrder(db, current.id));
          event(
            db,
            a,
            "expired-absent",
            "success",
            "原固定有效期已过，原商户验签查询确认不存在，释放未付款占用",
            requestId,
          );
          return ownedOrder(db, current.id);
        });
      }
      throw error;
    }
    let order = applyTrade(db, a, trade, requestId);
    if (
      (close || trade.trade_state === "PAYERROR") &&
      order.payment_state !== "paid" &&
      !["CLOSED", "REVOKED"].includes(trade.trade_state)
    ) {
      try {
        await adapter.close(a.number);
      } catch (error) {
        // close 超时或遇到到账竞争时只重查原交易，不凭错误推断已关闭。
        order = applyTrade(db, a, await adapter.query(a.number), requestId);
        if (
          order.payment_state !== "paid" &&
          currentAttempt(db, a.id).state !== "closed"
        )
          throw error;
        return order;
      }
      order = applyTrade(db, a, { ...trade, trade_state: "CLOSED" }, requestId);
    }
    return order;
  } catch (error) {
    unknown(db, a, "reconcile", error, requestId);
    throw error;
  } finally {
    unclaim(db, a, token);
  }
}
/** 主线程定时调用；每批有限数量，返回逐项真实结果，异常继续保留支付锁。 */
export async function reconcilePayments(
  db: DB,
  adapter: PaymentAdapter | undefined,
  limit = 20,
) {
  if (!adapter) return { configured: false, results: [] };
  const attempts = db
    .prepare(
      `SELECT p.* FROM payment_attempts p JOIN orders o ON o.id=p.order_id AND o.is_deleted=0 WHERE p.is_deleted=0 AND p.state NOT IN ('closed','success') AND (p.lease_until IS NULL OR p.lease_until<?) ORDER BY p.update_time,p.id LIMIT ?`,
    )
    .all(now(), Math.min(100, Math.max(1, limit))) as unknown as Attempt[];
  const results = [];
  for (const a of attempts) {
    const o = ownedOrder(db, a.order_id),
      close =
        Boolean(a.close_requested) || Date.parse(o.expires_at) <= Date.now();
    try {
      const final = await reconcile(db, a, adapter, randomUUID(), close);
      results.push({
        orderId: a.order_id,
        ok: true,
        state: final.payment_state,
        status: final.status,
      });
    } catch {
      results.push({ orderId: a.order_id, ok: false });
    }
  }
  return { configured: true, results };
}
export interface PaymentsOptions {
  adapter?: PaymentAdapter;
  env?: NodeJS.ProcessEnv;
}
export function mountPayments(
  app: Express,
  ctx: BusinessContext,
  options: PaymentsOptions = {},
) {
  const { db, userAuth, staffAuth, permit, audit } = ctx;
  let configured: PaymentAdapter | undefined;
  let configError: unknown;
  try {
    configured = options.adapter ?? paymentAdapterFromEnv(options.env);
  } catch (e) {
    configError = e;
  }
  const channel = () => {
    if (configError) throw configError;
    if (!configured)
      throw new HttpError(
        503,
        "PAYMENT_NOT_CONFIGURED",
        "微信支付尚未配置，请联系品牌方",
      );
    return configured;
  };
  app.get("/api/payments/capabilities", userAuth, (_req, res) =>
    res.json({
      wechatAvailable: Boolean(configured),
      message: configured ? "" : "微信支付尚未配置，请联系品牌方",
    }),
  );
  app.post("/api/orders/:id/pay/wechat", userAuth, async (req, res) => {
    const adapter = channel(),
      user = res.locals.user;
    let token = "";
    const a = transaction(db, () => {
      const order = ownedOrder(db, entityId.parse(req.params.id), user.id);
      assertNoUnresolvedPayment(db, user.id, order.id);
      if (
        order.status !== "pending" ||
        Date.parse(order.expires_at) <= Date.now()
      )
        fail("订单已付款、关闭或过期");
      if (!Number.isSafeInteger(order.total_cents) || order.total_cents < 1)
        fail("微信支付金额必须为正整数分");
      const old = activeAttempt(db, order.id);
      if (old) {
        if (old.close_requested) fail("原交易正在关闭，请先查询");
        if (old.state === "ready" && old.prepay) return old;
        fail("原交易尚未确定，请查询原支付结果", "PAYMENT_UNRESOLVED");
      }
      if (!["unpaid", "failed"].includes(order.payment_state))
        fail("订单已有未决支付");
      // 序列化所有本地发起；外部下单期间用持久租约阻止另一进程 close。
      const number = "P" + randomUUID().replaceAll("-", "").slice(0, 31);
      const result = db
        .prepare(
          `INSERT INTO payment_attempts(order_id,number,state,app_id,mch_id,cents,openid,last_request_id,create_user_id,update_user_id) VALUES(?,?,'creating',?,?,?,?,?,?,?)`,
        )
        .run(
          order.id,
          number,
          adapter.appId,
          adapter.mchId,
          order.total_cents,
          user.openid,
          res.locals.requestId,
          user.id,
          user.id,
        );
      db.prepare(
        `UPDATE orders SET payment_method='wechat',payment_state='paying',update_user_id=?,${stamp} WHERE id=?`,
      ).run(user.id, order.id);
      const a = currentAttempt(db, Number(result.lastInsertRowid));
      token = claim(db, a);
      audit(
        res,
        "payment.start",
        "order",
        order.id,
        "发起微信支付并锁定支付方式",
      );
      return a;
    });
    if (!token) {
      res.json({ state: a.state, parameters: JSON.parse(a.prepay!) });
      return;
    }
    try {
      const order = ownedOrder(db, a.order_id);
      const parameters = await adapter.create({
        number: a.number,
        cents: a.cents,
        openid: a.openid,
        description: "清欣简妍肌商品订单",
        expiresAt: order.expires_at,
      });
      transaction(db, () => {
        db.prepare(
          `UPDATE payment_attempts SET state='ready',prepay=?,${stamp} WHERE id=? AND state='creating'`,
        ).run(JSON.stringify(parameters), a.id);
        event(
          db,
          a,
          "prepay",
          "success",
          "预支付已生成，仍以通知或查询到账为准",
          res.locals.requestId,
        );
      });
      const latest = currentAttempt(db, a.id);
      res.json({
        state: latest.state,
        ...(latest.state === "ready" && !latest.close_requested
          ? { parameters }
          : {}),
      });
    } catch (error) {
      unknown(db, a, "prepay", error, res.locals.requestId);
      throw new HttpError(
        502,
        "PAYMENT_UNRESOLVED",
        "支付发起结果未确定，请查询原交易",
      );
    } finally {
      unclaim(db, a, token);
    }
  });
  for (const admin of [false, true]) {
    const root = admin
      ? "/api/admin/orders/:id/payment"
      : "/api/orders/:id/payment";
    const middleware = admin
      ? [staffAuth, permit("orders:manage")]
      : [userAuth];
    app.get(root, ...middleware, (req, res) => {
      const o = ownedOrder(
        db,
        entityId.parse(req.params.id),
        admin ? undefined : res.locals.user.id,
      );
      const a = activeAttempt(db, o.id);
      res.json({
        order: orderView(db, o),
        attempt: a
          ? {
              id: a.id,
              number: a.number,
              state: a.state,
              closeRequested: Boolean(a.close_requested),
              transactionId: (a as Attempt & { transaction_id?: string })
                .transaction_id,
              ...(admin
                ? {
                    lastError: (a as Attempt & { last_error?: string })
                      .last_error,
                  }
                : {}),
            }
          : null,
        receipt:
          db
            .prepare(
              "SELECT method,transaction_id,cents,paid_at FROM payments WHERE order_id=? AND is_deleted=0",
            )
            .get(o.id) ?? null,
        ...(admin
          ? {
              attempts: db
                .prepare(
                  "SELECT id,number,state,transaction_id,last_error,last_request_id,close_requested,create_time,update_time FROM payment_attempts WHERE order_id=? AND is_deleted=0 ORDER BY id DESC",
                )
                .all(o.id),
              events: db
                .prepare(
                  "SELECT * FROM payment_events WHERE order_id=? AND is_deleted=0 ORDER BY id DESC LIMIT 100",
                )
                .all(o.id),
            }
          : {}),
      });
    });
    app.post(root + "/query", ...middleware, async (req, res) => {
      const o = ownedOrder(
          db,
          entityId.parse(req.params.id),
          admin ? undefined : res.locals.user.id,
        ),
        a = activeAttempt(db, o.id);
      if (a && a.state !== "success")
        await reconcile(
          db,
          a,
          channel(),
          res.locals.requestId,
          Boolean(a.close_requested) || Date.parse(o.expires_at) <= Date.now(),
        );
      audit(res, "payment.query", "order", o.id, "查询原微信交易");
      res.json(orderView(db, ownedOrder(db, o.id)));
    });
    app.post(root + "/close", ...middleware, async (req, res) => {
      if (admin)
        z.object({ reason: z.string().trim().min(1).max(200) })
          .strict()
          .parse(req.body);
      const o = ownedOrder(
        db,
        entityId.parse(req.params.id),
        admin ? undefined : res.locals.user.id,
      );
      const a = transaction(db, () => {
        const a = activeAttempt(db, o.id);
        if (a && a.state !== "success")
          db.prepare(
            `UPDATE payment_attempts SET close_requested=1,${stamp} WHERE id=?`,
          ).run(a.id);
        else if (o.payment_state !== "paid" && o.status !== "closed")
          releaseOrder(db, o);
        audit(
          res,
          "payment.close.request",
          "order",
          o.id,
          admin ? req.body.reason : "用户申请关闭原微信交易",
        );
        return a;
      });
      if (a && a.state !== "success")
        await reconcile(db, a, channel(), res.locals.requestId, true);
      res.json(orderView(db, ownedOrder(db, o.id)));
    });
  }
  // 需要主线程 express.json({verify}) 将原始字节保存到 req.rawBody；缺失时拒绝通知。
  app.post(
    "/api/payments/wechat/notify",
    express.raw({ type: "application/json", limit: "128kb" }),
    (req, res) => {
      let a: Attempt | undefined;
      try {
        const raw =
          (req as Request & { rawBody?: Buffer }).rawBody ??
          (Buffer.isBuffer(req.body) ? req.body : undefined);
        if (!raw)
          throw new HttpError(
            503,
            "RAW_BODY_REQUIRED",
            "微信通知验签需要原始请求体",
          );
        const trade = channel().notification(raw, req.headers);
        a = db
          .prepare(
            "SELECT * FROM payment_attempts WHERE number=? AND is_deleted=0",
          )
          .get(trade.out_trade_no) as unknown as Attempt | undefined;
        if (!a)
          throw new HttpError(404, "PAYMENT_NOT_FOUND", "原支付记录不存在");
        applyTrade(db, a, trade, res.locals.requestId);
        res.status(200).json({ code: "SUCCESS", message: "成功" });
      } catch (e) {
        if (a) unknown(db, a, "notify", e, res.locals.requestId);
        else
          event(
            db,
            undefined,
            "notify",
            "failure",
            e instanceof Error ? e.message : "通知处理失败",
            res.locals.requestId,
          );
        res
          .status(e instanceof HttpError ? e.status : 400)
          .json({ code: "FAIL", message: "通知未确认，请重试或查询原交易" });
      }
    },
  );
  return { reconcile: () => reconcilePayments(db, configured) };
}
