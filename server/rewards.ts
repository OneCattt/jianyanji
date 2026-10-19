import type { Express } from "express";
import { z } from "zod";
import { auditColumns, transaction, recordAudit, type DB } from "./db.ts";
import { entityId, setting } from "./catalog.ts";
import { ownedOrder, type OrderRow } from "./orders.ts";
import { fail, HttpError, type BusinessContext } from "./http.ts";

const money = z.number().int().min(0).max(100000000);
const positiveMoney = money.refine((v) => v > 0, "金额必须大于零");
const text = z.string().trim().min(1).max(1000);
const key = z.string().regex(/^[\w-]{12,100}$/);
const time = z.iso
  .datetime()
  .refine((v) => Date.parse(v) <= Date.now(), "实际时间不能在未来");
export const rewardSettingsSchema = z
  .object({
    storeRewardCents: positiveMoney,
    principalRewardCents: positiveMoney,
    settlementDay: z.number().int().min(1).max(28),
  })
  .strict();
export type RewardSettings = z.infer<typeof rewardSettingsSchema>;
/** 入驻审核时冻结的证据；禁止从用户当前关系或当前档位推断历史。 */
export interface OnboardingRewardEvidence {
  identity: "B" | "C";
  inviterId: number;
  rewardCents: number;
  afterSalesDays: number;
  onboardingApproved: true;
  onboardingId: number;
  receiptId?: number;
  principalId?: number | null;
  afterSalesPolicy?: string;
}
export interface RewardsOptions {
  resolveOnboarding?: (db: DB, order: OrderRow) => OnboardingRewardEvidence;
  hasOpenAfterSales?: (db: DB, orderId: number) => boolean;
}
interface Reward {
  id: number;
  order_id: number;
  user_id: number;
  kind: "onboarding" | "margin";
  amount_cents: number;
  reversed_cents: number;
  settled_cents: number;
  snapshot: string;
  create_time: string;
}
interface Refund {
  id: number;
  goods_cents: number;
  lines: string;
}
interface BasisLine {
  lineId: number;
  skuId: number;
  quantity: number;
  unitCents: number;
  principalCents: number;
}
const stamp = "update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now')";
const exists = (db: DB, name: string) =>
  Boolean(
    db
      .prepare("SELECT name FROM sqlite_master WHERE type='table' AND name=?")
      .get(name),
  );
export function migrateRewards(db: DB) {
  db.exec(`
 CREATE TABLE IF NOT EXISTS reward_settings(id INTEGER PRIMARY KEY CHECK(id=1),value TEXT NOT NULL,${auditColumns});
 CREATE TABLE IF NOT EXISTS rewards(id INTEGER PRIMARY KEY,order_id INTEGER NOT NULL REFERENCES orders(id),user_id INTEGER NOT NULL REFERENCES users(id),kind TEXT NOT NULL CHECK(kind IN ('onboarding','margin')),amount_cents INTEGER NOT NULL CHECK(amount_cents>0),reversed_cents INTEGER NOT NULL DEFAULT 0,settled_cents INTEGER NOT NULL DEFAULT 0,snapshot TEXT NOT NULL,reason TEXT NOT NULL,request_id TEXT NOT NULL,${auditColumns});
 CREATE UNIQUE INDEX IF NOT EXISTS reward_source_active ON rewards(order_id,kind) WHERE is_deleted=0;
 CREATE INDEX IF NOT EXISTS reward_owner ON rewards(user_id,id) WHERE is_deleted=0;
 CREATE TABLE IF NOT EXISTS reward_reversals(id INTEGER PRIMARY KEY,reward_id INTEGER NOT NULL REFERENCES rewards(id),refund_id INTEGER NOT NULL,amount_cents INTEGER NOT NULL CHECK(amount_cents>=0),debt_cents INTEGER NOT NULL CHECK(debt_cents>=0),reason TEXT NOT NULL,request_id TEXT NOT NULL,${auditColumns});
 CREATE UNIQUE INDEX IF NOT EXISTS reward_refund_active ON reward_reversals(reward_id,refund_id) WHERE is_deleted=0;
 CREATE TABLE IF NOT EXISTS reward_settlements(id INTEGER PRIMARY KEY,user_id INTEGER NOT NULL REFERENCES users(id),month TEXT NOT NULL,payee TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('pending','failed','unknown','paid','cancelled')),gross_cents INTEGER NOT NULL,offset_cents INTEGER NOT NULL,net_cents INTEGER NOT NULL,plan TEXT NOT NULL,paid_at TEXT,proof TEXT,reason TEXT NOT NULL,request_key TEXT NOT NULL,request_id TEXT NOT NULL,${auditColumns});
 CREATE UNIQUE INDEX IF NOT EXISTS reward_month_active ON reward_settlements(user_id,month) WHERE is_deleted=0 AND status!='cancelled';
 CREATE UNIQUE INDEX IF NOT EXISTS reward_settlement_key ON reward_settlements(request_key) WHERE is_deleted=0;
 CREATE TABLE IF NOT EXISTS reward_offsets(id INTEGER PRIMARY KEY,reversal_id INTEGER NOT NULL REFERENCES reward_reversals(id),settlement_id INTEGER NOT NULL REFERENCES reward_settlements(id),reward_id INTEGER NOT NULL REFERENCES rewards(id),amount_cents INTEGER NOT NULL CHECK(amount_cents>0),${auditColumns});
 CREATE TABLE IF NOT EXISTS reward_recoveries(id INTEGER PRIMARY KEY,reversal_id INTEGER NOT NULL REFERENCES reward_reversals(id),amount_cents INTEGER NOT NULL CHECK(amount_cents>0),received_at TEXT NOT NULL,proof TEXT NOT NULL,reason TEXT NOT NULL,request_key TEXT NOT NULL,request_id TEXT NOT NULL,${auditColumns});
 CREATE UNIQUE INDEX IF NOT EXISTS reward_recovery_key ON reward_recoveries(request_key) WHERE is_deleted=0;
 `);
}
export function rewardSettings(db: DB): RewardSettings | null {
  const r = db
    .prepare("SELECT value FROM reward_settings WHERE id=1 AND is_deleted=0")
    .get();
  if (!r) return null;
  const original = JSON.parse(String(r.value));
  // 旧配置中的重复售后期保留存储，不再参与新业务；期限由统一售后政策管理。
  return {
    storeRewardCents: original.storeRewardCents,
    principalRewardCents: original.principalRewardCents,
    settlementDay: original.settlementDay,
  };
}
function getReward(db: DB, id: number, userId?: number): Reward {
  const r = db
    .prepare(
      "SELECT * FROM rewards WHERE id=? AND is_deleted=0" +
        (userId === undefined ? "" : " AND user_id=?"),
    )
    .get(id, ...(userId === undefined ? [] : [userId]));
  if (!r) throw new HttpError(404, "NOT_FOUND", "奖励不存在");
  return r as unknown as Reward;
}
function debtRows(db: DB, userId: number) {
  return db
    .prepare(
      `SELECT v.*,r.user_id,r.order_id,
 COALESCE((SELECT SUM(o.amount_cents) FROM reward_offsets o WHERE o.reversal_id=v.id AND o.is_deleted=0),0) AS offset_cents,
 COALESCE((SELECT SUM(c.amount_cents) FROM reward_recoveries c WHERE c.reversal_id=v.id AND c.is_deleted=0),0) AS recovered_cents
 FROM reward_reversals v JOIN rewards r ON r.id=v.reward_id AND r.is_deleted=0 WHERE r.user_id=? AND v.is_deleted=0 AND v.debt_cents>0 ORDER BY v.id`,
    )
    .all(userId)
    .map((v) => ({
      ...v,
      id: Number(v.id),
      reward_id: Number(v.reward_id),
      remainingCents:
        Number(v.debt_cents) -
        Number(v.offset_cents) -
        Number(v.recovered_cents),
    }));
}
/** 以整数分和 BigInt 计算累计冲回，避免乘积浮点误差；全退补齐尾差。 */
function reversalTargets(db: DB, r: Reward) {
  if (!exists(db, "refunds")) return [];
  const refunds = db
    .prepare(
      `SELECT f.id,f.goods_cents,f.lines FROM refunds f
       LEFT JOIN reward_reversals v ON v.refund_id=f.id AND v.reward_id=? AND v.is_deleted=0
       WHERE f.order_id=? AND f.status='success' AND f.is_deleted=0
       ORDER BY CASE WHEN v.id IS NULL THEN 1 ELSE 0 END,v.id,f.id`,
    )
    .all(r.id, r.order_id) as unknown as Refund[];
  const s = JSON.parse(r.snapshot);
  let goods = 0,
    total = 0;
  const quantities = new Map<number, number>();
  return refunds.map((f) => {
    if (!Number.isSafeInteger(f.goods_cents) || f.goods_cents < 0)
      fail("退款货款数据无效，需核对来源");
    goods += f.goods_cents;
    if (r.kind === "onboarding") {
      if (goods > s.goodsCents) fail("累计退款货款超过原货款");
      total =
        goods === s.goodsCents
          ? r.amount_cents
          : Number(
              (BigInt(r.amount_cents) * BigInt(goods) * 2n +
                BigInt(s.goodsCents)) /
                (2n * BigInt(s.goodsCents)),
            );
    } else {
      const lines = z
        .array(
          z
            .object({
              lineId: entityId,
              quantity: z.number().int().positive(),
              unitCents: money,
              principalCents: money,
            })
            .passthrough(),
        )
        .parse(JSON.parse(f.lines));
      for (const l of lines) {
        const original = (s.lines as BasisLine[]).find(
          (x) => x.lineId === l.lineId,
        );
        if (
          !original ||
          original.unitCents !== l.unitCents ||
          original.principalCents !== l.principalCents
        )
          fail("退款行与原奖励价格依据不符");
        const n = (quantities.get(l.lineId) || 0) + l.quantity;
        if (n > original!.quantity) fail("累计退款数量超过原订单");
        quantities.set(l.lineId, n);
        total += (original!.unitCents - original!.principalCents) * l.quantity;
      }
    }
    if (total > r.amount_cents) fail("累计冲回超过原奖励");
    return { refundId: f.id, target: total };
  });
}
/** 退款成功后在原事务调用；SAVEPOINT 兼容外层事务，重复调用不重复冲回。不会自动创建奖励或打款。 */
export function reconcileRewardReversals(
  db: DB,
  orderId: number,
  actorId: number,
  requestId: string,
) {
  db.exec("SAVEPOINT reward_reconcile");
  try {
    const rewards = db
      .prepare("SELECT * FROM rewards WHERE order_id=? AND is_deleted=0")
      .all(orderId) as unknown as Reward[];
    for (const r of rewards) {
      let previous = 0;
      for (const t of reversalTargets(db, r)) {
        const delta = t.target - previous;
        previous = t.target;
        const old = db
          .prepare(
            "SELECT amount_cents FROM reward_reversals WHERE reward_id=? AND refund_id=? AND is_deleted=0",
          )
          .get(r.id, t.refundId);
        if (old) {
          if (Number(old.amount_cents) !== delta)
            fail("退款历史被修改，需人工核查冲回依据");
          continue;
        }
        const reversed = Number(
          db.prepare("SELECT reversed_cents FROM rewards WHERE id=?").get(r.id)!
            .reversed_cents,
        );
        const next = reversed + delta;
        const debt =
          Math.max(0, r.settled_cents - (r.amount_cents - next)) -
          Math.max(0, r.settled_cents - (r.amount_cents - reversed));
        const id = Number(
          db
            .prepare(
              "INSERT INTO reward_reversals(reward_id,refund_id,amount_cents,debt_cents,reason,request_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?)",
            )
            .run(
              r.id,
              t.refundId,
              delta,
              debt,
              "来源实际退款冲回；已付部分持续待收回",
              requestId,
              actorId,
              actorId,
            ).lastInsertRowid,
        );
        db.prepare(
          `UPDATE rewards SET reversed_cents=?,update_user_id=?,${stamp} WHERE id=? AND is_deleted=0`,
        ).run(next, actorId, r.id);
        recordAudit(db, {
          actorKind: actorId ? "staff" : "system",
          actorId,
          action: "reward.reverse",
          objectType: "reward_reversal",
          objectId: id,
          result: "success",
          reason: `订单${orderId} 退款${t.refundId} 冲回${delta}分 待收回${debt}分`,
          requestId,
        });
      }
    }
    db.exec("RELEASE reward_reconcile");
  } catch (e) {
    db.exec("ROLLBACK TO reward_reconcile; RELEASE reward_reconcile");
    throw e;
  }
}
function defaultOnboarding(_db: DB, o: OrderRow): OnboardingRewardEvidence {
  return z
    .object({
      identity: z.enum(["B", "C"]),
      inviterId: entityId,
      rewardCents: positiveMoney,
      afterSalesDays: z.number().int().min(0).max(365),
      onboardingApproved: z.literal(true),
      onboardingId: entityId,
      receiptId: entityId.optional(),
      principalId: entityId.nullable().optional(),
      afterSalesPolicy: z.string().optional(),
    })
    .parse(JSON.parse(o.snapshot));
}
function source(db: DB, orderId: number, options: RewardsOptions) {
  const o = ownedOrder(db, orderId),
    s = JSON.parse(o.snapshot);
  if (!["paid", "partially_refunded", "refunded"].includes(o.payment_state))
    fail("来源订单尚未核实有效付款");
  let userId: number, amount: number, evidence: Record<string, unknown>;
  if (o.kind === "package") {
    const e = (options.resolveOnboarding || defaultOnboarding)(db, o);
    if (e.onboardingApproved !== true) fail("入驻尚未审核通过");
    userId = entityId.parse(e.inviterId);
    amount = positiveMoney.parse(e.rewardCents);
    evidence = { ...e };
  } else {
    if (s.identity !== "C") fail("仅店主订货可登记归属主理人收益");
    userId = entityId.parse(s.principalId);
    const lines = db
      .prepare(
        "SELECT id,sku_id,quantity,unit_cents,principal_cents FROM order_lines WHERE order_id=? AND is_deleted=0 ORDER BY id",
      )
      .all(o.id)
      .map((l) => ({
        lineId: Number(l.id),
        skuId: Number(l.sku_id),
        quantity: Number(l.quantity),
        unitCents: Number(l.unit_cents),
        principalCents:
          l.principal_cents === null ? null : Number(l.principal_cents),
      }));
    if (
      !lines.length ||
      lines.some(
        (l) => l.principalCents === null || l.unitCents < l.principalCents,
      )
    )
      fail("原订单缺少有效主理人价差快照");
    amount = lines.reduce(
      (a, l) => a + (l.unitCents - l.principalCents!) * l.quantity,
      0,
    );
    positiveMoney.parse(amount);
    evidence = {
      identity: s.identity,
      principalId: userId,
      afterSalesDays: s.afterSalesDays,
      lines,
    };
  }
  const days = z.number().int().min(0).max(365).parse(evidence.afterSalesDays);
  if (
    !db
      .prepare("SELECT id FROM users WHERE id=? AND is_deleted=0")
      .get(userId) ||
    userId === o.user_id
  )
    fail("奖励对象或来源归属无效");
  if (o.goods_cents <= 0) fail("来源货款无效");
  return {
    orderId: o.id,
    orderNumber: o.number,
    kind: o.kind === "package" ? "onboarding" : "margin",
    userId,
    amountCents: amount,
    snapshot: {
      ...evidence,
      orderNumber: o.number,
      goodsCents: o.goods_cents,
      afterSalesDays: days,
      paymentMethod: o.payment_method,
    },
  };
}
function eligibility(db: DB, r: Reward, options: RewardsOptions) {
  const o = ownedOrder(db, r.order_id),
    s = JSON.parse(r.snapshot);
  if (!o.received_at) return { eligible: false, reason: "未确认收货" };
  const until = new Date(
    Date.parse(o.received_at) + s.afterSalesDays * 86400000,
  );
  if (!Number.isFinite(until.getTime()))
    return { eligible: false, reason: "收货或售后期依据无效" };
  if (until.getTime() > Date.now())
    return {
      eligible: false,
      reason: "尚在原售后期内",
      eligibleAt: until.toISOString(),
    };
  if (!exists(db, "refunds"))
    return { eligible: false, reason: "退款模块尚未接入" };
  const open = options.hasOpenAfterSales
    ? options.hasOpenAfterSales(db, o.id)
    : Boolean(
        db
          .prepare(
            "SELECT id FROM refunds WHERE order_id=? AND is_deleted=0 AND status NOT IN ('success','rejected') LIMIT 1",
          )
          .get(o.id),
      );
  if (open)
    return { eligible: false, reason: "存在未完售后或售后模块尚未接入" };
  if (
    db
      .prepare(
        "SELECT id FROM order_lines WHERE order_id=? AND is_deleted=0 AND refund_held>0 LIMIT 1",
      )
      .get(o.id)
  )
    return { eligible: false, reason: "商品仍在售后处理中" };
  const targets = reversalTargets(db, r);
  if ((targets.at(-1)?.target || 0) !== r.reversed_cents)
    return { eligible: false, reason: "实际退款尚未完成奖励冲回核对" };
  if (r.kind === "onboarding" && s.onboardingApproved !== true)
    return { eligible: false, reason: "入驻审批依据缺失" };
  return {
    eligible: true,
    reason: "收货及原售后期已完成",
    eligibleAt: until.toISOString(),
  };
}
function rewardView(db: DB, r: Reward, options: RewardsOptions, admin = false) {
  const e = eligibility(db, r, options),
    s = JSON.parse(r.snapshot);
  const remainingCents = Math.max(
    0,
    r.amount_cents - r.reversed_cents - r.settled_cents,
  );
  return {
    id: r.id,
    orderNumber: s.orderNumber,
    kind: r.kind,
    targetIdentity: r.kind === "onboarding" ? s.identity : null,
    amountCents: r.amount_cents,
    reversedCents: r.reversed_cents,
    settledCents: r.settled_cents,
    remainingCents,
    createTime: r.create_time,
    ...e,
    status: remainingCents
      ? e.eligible
        ? "eligible"
        : "not_due"
      : r.reversed_cents === r.amount_cents
        ? "reversed"
        : "settled",
    ...(admin ? { userId: r.user_id, orderId: r.order_id, basis: s } : {}),
  };
}
function rewardDetail(
  db: DB,
  r: Reward,
  options: RewardsOptions,
  admin = false,
) {
  return {
    ...rewardView(db, r, options, admin),
    reversals: db
      .prepare(
        "SELECT id,refund_id,amount_cents,debt_cents,create_time FROM reward_reversals WHERE reward_id=? AND is_deleted=0 ORDER BY id",
      )
      .all(r.id),
    settlements: db
      .prepare(
        "SELECT s.id,s.month,s.payee,s.status,s.gross_cents,s.offset_cents,s.net_cents,s.paid_at,s.proof,s.plan FROM reward_settlements s WHERE s.user_id=? AND s.is_deleted=0 ORDER BY s.id DESC",
      )
      .all(r.user_id)
      .filter((s) =>
        JSON.parse(String(s.plan)).rewards.some(
          (a: { rewardId: number }) => a.rewardId === r.id,
        ),
      )
      .map((s) => ({ ...s, plan: JSON.parse(String(s.plan)) })),
    debts: debtRows(db, r.user_id).filter((d) => Number(d.reward_id) === r.id),
    offsets: db
      .prepare(
        "SELECT o.id,o.reversal_id,o.settlement_id,o.reward_id,o.amount_cents,o.create_time FROM reward_offsets o JOIN reward_reversals v ON v.id=o.reversal_id AND v.is_deleted=0 WHERE (v.reward_id=? OR o.reward_id=?) AND o.is_deleted=0 ORDER BY o.id",
      )
      .all(r.id, r.id),
    recoveries: db
      .prepare(
        "SELECT c.id,c.reversal_id,c.amount_cents,c.received_at,c.proof FROM reward_recoveries c JOIN reward_reversals v ON v.id=c.reversal_id AND v.is_deleted=0 WHERE v.reward_id=? AND c.is_deleted=0 ORDER BY c.id",
      )
      .all(r.id),
  };
}
interface Plan {
  rewards: { rewardId: number; amountCents: number }[];
  offsets: { reversalId: number; rewardId: number; amountCents: number }[];
  grossCents: number;
  offsetCents: number;
  netCents: number;
}
function planSettlement(
  db: DB,
  userId: number,
  ids: number[],
  options: RewardsOptions,
): Plan {
  const debts = debtRows(db, userId)
    .filter((d) => d.remainingCents > 0)
    .map((d) => ({ id: Number(d.id), left: d.remainingCents }));
  let gross = 0,
    offset = 0;
  const offsets: Plan["offsets"] = [];
  const rewards = [...ids]
    .sort((a, b) => a - b)
    .map((id) => {
      const r = getReward(db, id, userId),
        e = eligibility(db, r, options);
      if (!e.eligible) fail(e.reason);
      const amount = Math.max(
        0,
        r.amount_cents - r.reversed_cents - r.settled_cents,
      );
      if (!amount) fail("奖励已结算或已全部冲回");
      gross += amount;
      let left = amount;
      for (const d of debts) {
        const take = Math.min(d.left, left);
        if (take > 0) {
          offsets.push({ reversalId: d.id, rewardId: id, amountCents: take });
          d.left -= take;
          left -= take;
          offset += take;
        }
      }
      return { rewardId: id, amountCents: amount };
    });
  if (!Number.isSafeInteger(gross)) fail("结算合计超出范围");
  return {
    rewards,
    offsets,
    grossCents: gross,
    offsetCents: offset,
    netCents: gross - offset,
  };
}
export function mountRewards(
  app: Express,
  ctx: BusinessContext,
  options: RewardsOptions = {},
) {
  const { db, userAuth, staffAuth, permit, audit } = ctx;
  const staff = [staffAuth, permit("rewards:manage")];
  app.get("/api/admin/reward-settings", ...staff, (_req, res) => {
    const value = rewardSettings(db);
    res.json(
      value
        ? {
            ...value,
            afterSalesDays:
              setting<{ days: number }>(db, "aftersales")?.days ?? null,
          }
        : null,
    );
  });
  app.put("/api/admin/reward-settings", ...staff, (req, res) => {
    const { reason, ...input } = z
      .object({ ...rewardSettingsSchema.shape, reason: text })
      .strict()
      .parse(req.body);
    transaction(db, () => {
      db.prepare(
        `INSERT INTO reward_settings(id,value,create_user_id,update_user_id) VALUES(1,?,?,?) ON CONFLICT(id) DO UPDATE SET value=excluded.value,update_user_id=excluded.update_user_id,${stamp}`,
      ).run(JSON.stringify(input), res.locals.staff.id, res.locals.staff.id);
      audit(res, "reward.settings", "reward_settings", 1, reason);
    });
    res.json(input);
  });
  app.get("/api/admin/rewards/source/:id", ...staff, (req, res) =>
    res.json(source(db, entityId.parse(req.params.id), options)),
  );
  app.post("/api/admin/rewards", ...staff, (req, res) => {
    const v = z
      .object({ orderId: entityId, amountCents: positiveMoney, reason: text })
      .strict()
      .parse(req.body);
    const id = transaction(db, () => {
      const s = source(db, v.orderId, options);
      if (v.amountCents !== s.amountCents)
        fail("人工登记金额与原业务核算依据不一致");
      if (
        db
          .prepare(
            "SELECT id FROM rewards WHERE order_id=? AND kind=? AND is_deleted=0",
          )
          .get(s.orderId, s.kind)
      )
        fail("该来源奖励已经登记");
      const actor = res.locals.staff.id;
      const id = Number(
        db
          .prepare(
            "INSERT INTO rewards(order_id,user_id,kind,amount_cents,snapshot,reason,request_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?)",
          )
          .run(
            s.orderId,
            s.userId,
            s.kind,
            s.amountCents,
            JSON.stringify(s.snapshot),
            v.reason,
            res.locals.requestId,
            actor,
            actor,
          ).lastInsertRowid,
      );
      reconcileRewardReversals(db, s.orderId, actor, res.locals.requestId);
      audit(res, "reward.register", "reward", id, v.reason);
      return id;
    });
    res.json(rewardDetail(db, getReward(db, id), options, true));
  });
  app.get("/api/admin/rewards", ...staff, (_req, res) =>
    res.json(
      db
        .prepare(
          "SELECT * FROM rewards WHERE is_deleted=0 ORDER BY id DESC LIMIT 500",
        )
        .all()
        .map((r) => rewardView(db, r as unknown as Reward, options, true)),
    ),
  );
  app.get("/api/admin/rewards/:id", ...staff, (req, res) =>
    res.json(
      rewardDetail(
        db,
        getReward(db, entityId.parse(req.params.id)),
        options,
        true,
      ),
    ),
  );
  app.post("/api/admin/rewards/:id/reconcile", ...staff, (req, res) => {
    const v = z.object({ reason: text }).strict().parse(req.body),
      r = getReward(db, entityId.parse(req.params.id));
    transaction(db, () => {
      reconcileRewardReversals(
        db,
        r.order_id,
        res.locals.staff.id,
        res.locals.requestId,
      );
      audit(res, "reward.reconcile", "reward", r.id, v.reason);
    });
    res.json(rewardDetail(db, getReward(db, r.id), options, true));
  });
  app.get("/api/rewards", userAuth, (_req, res) => {
    const userId = res.locals.user.id;
    const items = db
      .prepare(
        "SELECT * FROM rewards WHERE user_id=? AND is_deleted=0 ORDER BY id DESC LIMIT 500",
      )
      .all(userId)
      .map((r) => rewardView(db, r as unknown as Reward, options));
    const month = new Intl.DateTimeFormat("sv-SE", {
      timeZone: "Asia/Shanghai",
      year: "numeric",
      month: "2-digit",
    }).format(new Date());
    const reversals = db
      .prepare(
        `SELECT v.id,v.reward_id AS rewardId,v.amount_cents AS amountCents,v.create_time AS createTime,r.snapshot
      FROM reward_reversals v JOIN rewards r ON r.id=v.reward_id AND r.is_deleted=0
      WHERE r.user_id=? AND v.is_deleted=0 AND v.amount_cents>0 ORDER BY v.id DESC LIMIT 500`,
      )
      .all(userId)
      .map((v) => ({
        id: v.id,
        rewardId: v.rewardId,
        amountCents: v.amountCents,
        createTime: v.createTime,
        orderNumber: JSON.parse(String(v.snapshot)).orderNumber,
      }));
    const totals = db
      .prepare(
        `SELECT COALESCE(SUM(MAX(0,amount_cents-reversed_cents-settled_cents)),0) AS pending,
      COALESCE(SUM(settled_cents),0) AS settled,COALESCE(SUM(reversed_cents),0) AS reversed,
      COALESCE(SUM(CASE WHEN strftime('%Y-%m',create_time,'+8 hours')=? THEN amount_cents-reversed_cents ELSE 0 END),0) AS estimated
      FROM rewards WHERE user_id=? AND is_deleted=0`,
      )
      .get(month, userId)!;
    res.json({
      items,
      reversals,
      summary: {
        month,
        estimatedCents: Number(totals.estimated),
        pendingCents: Number(totals.pending),
        settledCents: Number(totals.settled),
        reversedCents: Number(totals.reversed),
        debtCents: debtRows(db, userId).reduce(
          (a, d) => a + d.remainingCents,
          0,
        ),
      },
    });
  });
  app.get("/api/rewards/:id", userAuth, (req, res) =>
    res.json(
      rewardDetail(
        db,
        getReward(db, entityId.parse(req.params.id), res.locals.user.id),
        options,
      ),
    ),
  );
  app.get("/api/admin/reward-debts", ...staff, (_req, res) => {
    const ids = db
      .prepare("SELECT DISTINCT user_id FROM rewards WHERE is_deleted=0")
      .all();
    res.json(
      ids
        .flatMap((r) => debtRows(db, Number(r.user_id)))
        .filter((d) => d.remainingCents > 0),
    );
  });
  app.get("/api/admin/reward-settlements", ...staff, (_req, res) =>
    res.json(
      db
        .prepare(
          "SELECT * FROM reward_settlements WHERE is_deleted=0 ORDER BY id DESC LIMIT 500",
        )
        .all()
        .map((r) => ({ ...r, plan: JSON.parse(String(r.plan)) })),
    ),
  );
  const settlementInput = z
    .object({
      userId: entityId,
      month: z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/),
      rewardIds: z
        .array(entityId)
        .min(1)
        .max(100)
        .refine((v) => new Set(v).size === v.length, "奖励不可重复"),
      payee: text.max(100),
      requestKey: key,
      reason: text,
    })
    .strict();
  app.post("/api/admin/reward-settlements", ...staff, (req, res) => {
    const v = settlementInput.parse(req.body);
    const id = transaction(db, () => {
      if (!rewardSettings(db)) fail("真实奖励档位和月结日期尚未配置");
      const old = db
        .prepare(
          "SELECT * FROM reward_settlements WHERE request_key=? AND is_deleted=0",
        )
        .get(v.requestKey);
      if (old) {
        const plan = JSON.parse(String(old.plan)) as Plan;
        if (
          Number(old.user_id) !== v.userId ||
          old.month !== v.month ||
          old.payee !== v.payee ||
          JSON.stringify(plan.rewards.map((r) => r.rewardId)) !==
            JSON.stringify([...v.rewardIds].sort((a, b) => a - b))
        )
          fail("请求编号已用于其他结算");
        return Number(old.id);
      }
      if (
        db
          .prepare(
            "SELECT id FROM reward_settlements WHERE user_id=? AND month=? AND status!='cancelled' AND is_deleted=0",
          )
          .get(v.userId, v.month)
      )
        fail("该对象本月已有结算，请核对原记录");
      const p = planSettlement(db, v.userId, v.rewardIds, options),
        actor = res.locals.staff.id;
      const id = Number(
        db
          .prepare(
            "INSERT INTO reward_settlements(user_id,month,payee,status,gross_cents,offset_cents,net_cents,plan,reason,request_key,request_id,create_user_id,update_user_id) VALUES(?,?,?,'pending',?,?,?,?,?,?,?,?,?)",
          )
          .run(
            v.userId,
            v.month,
            v.payee,
            p.grossCents,
            p.offsetCents,
            p.netCents,
            JSON.stringify(p),
            v.reason,
            v.requestKey,
            res.locals.requestId,
            actor,
            actor,
          ).lastInsertRowid,
      );
      audit(res, "reward.settlement.plan", "reward_settlement", id, v.reason);
      return id;
    });
    res.json(
      db
        .prepare("SELECT * FROM reward_settlements WHERE id=? AND is_deleted=0")
        .get(id),
    );
  });
  app.post("/api/admin/reward-settlements/:id/result", ...staff, (req, res) => {
    const v = z
        .object({
          status: z.enum(["paid", "failed", "unknown", "cancelled"]),
          paidAt: time.optional(),
          proof: text.optional(),
          reason: text,
        })
        .strict()
        .parse(req.body),
      id = entityId.parse(req.params.id);
    transaction(db, () => {
      const s = db
        .prepare("SELECT * FROM reward_settlements WHERE id=? AND is_deleted=0")
        .get(id);
      if (!s) throw new HttpError(404, "NOT_FOUND", "结算不存在");
      if (s.status === "paid") {
        if (
          v.status !== "paid" ||
          s.proof !== v.proof ||
          s.paid_at !== v.paidAt
        )
          fail("结算已确认，不可覆盖付款依据");
        return;
      }
      if (s.status === "cancelled") fail("结算已取消");
      if (s.status === "unknown" && v.status === "cancelled")
        fail("付款结果未明，须先核实失败或成功，不能取消后重复付款");
      const actor = res.locals.staff.id;
      if (v.status === "paid") {
        if (!v.proof || !v.paidAt)
          fail("需提供真实付款或全额抵扣核实时间及凭证");
        const p = JSON.parse(String(s.plan)) as Plan,
          fresh = planSettlement(
            db,
            Number(s.user_id),
            p.rewards.map((r) => r.rewardId),
            options,
          );
        if (JSON.stringify(p) !== JSON.stringify(fresh))
          fail("奖励或欠款已变化，请核对实际付款，重新核算结算记录");
        for (const r of p.rewards)
          db.prepare(
            `UPDATE rewards SET settled_cents=settled_cents+?,update_user_id=?,${stamp} WHERE id=? AND is_deleted=0`,
          ).run(r.amountCents, actor, r.rewardId);
        for (const o of p.offsets)
          db.prepare(
            "INSERT INTO reward_offsets(reversal_id,settlement_id,reward_id,amount_cents,create_user_id,update_user_id) VALUES(?,?,?,?,?,?)",
          ).run(o.reversalId, id, o.rewardId, o.amountCents, actor, actor);
      }
      db.prepare(
        `UPDATE reward_settlements SET status=?,paid_at=?,proof=?,reason=?,request_id=?,update_user_id=?,${stamp} WHERE id=? AND is_deleted=0`,
      ).run(
        v.status,
        v.paidAt || null,
        v.proof || null,
        v.reason,
        res.locals.requestId,
        actor,
        id,
      );
      audit(
        res,
        "reward.settlement." + v.status,
        "reward_settlement",
        id,
        v.reason,
        v.status === "failed" ? "failure" : "success",
      );
    });
    res.json({ ok: true });
  });
  app.post("/api/admin/reward-recoveries", ...staff, (req, res) => {
    const v = z
      .object({
        reversalId: entityId,
        amountCents: positiveMoney,
        receivedAt: time,
        proof: text,
        requestKey: key,
        reason: text,
      })
      .strict()
      .parse(req.body);
    const id = transaction(db, () => {
      const old = db
        .prepare(
          "SELECT * FROM reward_recoveries WHERE request_key=? AND is_deleted=0",
        )
        .get(v.requestKey);
      if (old) {
        if (
          Number(old.reversal_id) !== v.reversalId ||
          Number(old.amount_cents) !== v.amountCents ||
          old.received_at !== v.receivedAt ||
          old.proof !== v.proof
        )
          fail("请求编号已用于其他实收记录");
        return Number(old.id);
      }
      const r = db
        .prepare(
          "SELECT r.user_id FROM reward_reversals v JOIN rewards r ON r.id=v.reward_id AND r.is_deleted=0 WHERE v.id=? AND v.is_deleted=0",
        )
        .get(v.reversalId);
      if (!r) throw new HttpError(404, "NOT_FOUND", "待收回记录不存在");
      const d = debtRows(db, Number(r.user_id)).find(
        (d) => Number(d.id) === v.reversalId,
      );
      if (!d || v.amountCents > d.remainingCents)
        fail("实收金额超过剩余待收回款");
      const actor = res.locals.staff.id,
        id = Number(
          db
            .prepare(
              "INSERT INTO reward_recoveries(reversal_id,amount_cents,received_at,proof,reason,request_key,request_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?)",
            )
            .run(
              v.reversalId,
              v.amountCents,
              v.receivedAt,
              v.proof,
              v.reason,
              v.requestKey,
              res.locals.requestId,
              actor,
              actor,
            ).lastInsertRowid,
        );
      audit(res, "reward.recovery.received", "reward_recovery", id, v.reason);
      return id;
    });
    res.json({ id });
  });
}
