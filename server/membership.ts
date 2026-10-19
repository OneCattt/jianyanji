import type { Express } from "express";
import { randomUUID } from "node:crypto";
import { z } from "zod";
import {
  assertTransaction,
  auditColumns,
  effectiveIdentity,
  transaction,
  type DB,
  type UserRow,
} from "./db.ts";
import { cents, entityId, putSetting, setting } from "./catalog.ts";
import { orderView, ownedOrder, type OrderRow } from "./orders.ts";
import {
  companyTransfer,
  migrateReceipts,
  receiptSchema,
  registerReceipt,
  requireAgent,
} from "./wallet.ts";
import { fail, HttpError, type BusinessContext } from "./http.ts";
import { rewardSettings } from "./rewards.ts";
const text = z.string().trim().min(1).max(2000);
const stamp = "strftime('%Y-%m-%dT%H:%M:%fZ','now')";
const packageSchema = z
  .object({
    targetIdentity: z.enum(["B", "C"]),
    name: text.max(80),
    amountCents: cents,
    valueCents: cents,
    selfUseLabel: text.max(40),
    benefits: text,
    conditions: text,
    agreement: text,
    rightsImage: z
      .string()
      .max(1000)
      .refine(
        (v) =>
          !v || /^https:\/\//.test(v) || /^\/assets\/[a-zA-Z0-9_./-]+$/.test(v),
      ),
    active: z.boolean(),
    lines: z
      .array(
        z
          .object({
            skuId: entityId,
            quantity: z.number().int().min(1).max(999),
            unitCents: cents,
          })
          .strict(),
      )
      .min(1)
      .max(50),
  })
  .strict()
  .refine(
    (v) => new Set(v.lines.map((l) => l.skuId)).size === v.lines.length,
    "产品包规格不可重复",
  )
  .refine(
    (v) =>
      v.lines.reduce((s, l) => s + l.unitCents * l.quantity, 0) ===
      v.amountCents,
    "产品包各商品分摊货款之和须等于产品包货款",
  );
export const membershipSchema = z
  .object({ packages: z.array(packageSchema).length(2) })
  .strict()
  .refine(
    (v) => new Set(v.packages.map((p) => p.targetIdentity)).size === 2,
    "须分别配置主理人和店主产品包",
  );
export type MembershipSettings = z.infer<typeof membershipSchema>;
interface Application {
  id: number;
  user_id: number;
  target_identity: "B" | "C";
  package_snapshot: string;
  status: string;
  requested_inviter_id: number | null;
  inviter_id: number | null;
  principal_id: number | null;
  receipt_id: number | null;
  order_id: number | null;
  reward_cents: number;
  after_sales_days: number;
  qualification_snapshot: string | null;
  request_key: string;
}
export function migrateMembership(db: DB) {
  migrateReceipts(db);
  db.exec(`CREATE TABLE IF NOT EXISTS onboardings (
    id INTEGER PRIMARY KEY,user_id INTEGER NOT NULL REFERENCES users(id),target_identity TEXT NOT NULL CHECK(target_identity IN ('B','C')),
    status TEXT NOT NULL DEFAULT 'submitted' CHECK(status IN ('submitted','approved','rejected','revoked')),
    package_snapshot TEXT NOT NULL,requested_inviter_id INTEGER REFERENCES users(id),inviter_id INTEGER REFERENCES users(id),principal_id INTEGER REFERENCES users(id),
    receipt_id INTEGER UNIQUE REFERENCES company_receipts(id),order_id INTEGER UNIQUE REFERENCES orders(id),
    reward_cents INTEGER NOT NULL CHECK(reward_cents>=0),after_sales_days INTEGER NOT NULL CHECK(after_sales_days>=0),
    qualification_snapshot TEXT,approved_at TEXT,reviewed_by INTEGER,review_reason TEXT NOT NULL DEFAULT '',
    request_key TEXT NOT NULL,${auditColumns});
    CREATE UNIQUE INDEX IF NOT EXISTS onboarding_request ON onboardings(user_id,request_key) WHERE is_deleted=0;
    CREATE UNIQUE INDEX IF NOT EXISTS onboarding_pending ON onboardings(user_id) WHERE status='submitted' AND is_deleted=0;
    CREATE TABLE IF NOT EXISTS onboarding_identity_events(id INTEGER PRIMARY KEY,onboarding_id INTEGER NOT NULL REFERENCES onboardings(id),order_id INTEGER NOT NULL REFERENCES orders(id),prior_status TEXT NOT NULL,decision TEXT NOT NULL,full_refund INTEGER NOT NULL,refunded_cents INTEGER NOT NULL,user_snapshot TEXT NOT NULL,reason TEXT NOT NULL,${auditColumns},UNIQUE(onboarding_id,refunded_cents));`);
}
function application(db: DB, id: number, userId?: number): Application {
  const a = db
    .prepare(
      "SELECT * FROM onboardings WHERE id=? AND is_deleted=0" +
        (userId ? " AND user_id=?" : ""),
    )
    .get(id, ...(userId ? [userId] : [])) as unknown as Application | undefined;
  if (!a) throw new HttpError(404, "NOT_FOUND", "入驻申请不存在");
  return a;
}
function applicationView(db: DB, a: Application) {
  const receipt = a.receipt_id
    ? db
        .prepare(
          "SELECT id,purpose,amount_cents,received_at,confirmed_at,external_transaction_id FROM company_receipts WHERE id=? AND is_deleted=0",
        )
        .get(a.receipt_id)
    : null;
  return {
    ...a,
    package_snapshot: JSON.parse(a.package_snapshot),
    qualification_snapshot: a.qualification_snapshot
      ? JSON.parse(a.qualification_snapshot)
      : null,
    receipt,
    identityEvents: db
      .prepare(
        "SELECT * FROM onboarding_identity_events WHERE onboarding_id=? AND is_deleted=0 ORDER BY id",
      )
      .all(a.id),
    order: a.order_id
      ? orderView(db, ownedOrder(db, a.order_id, a.user_id))
      : null,
  };
}
function aftersales(db: DB) {
  const p = setting<{ days: number; policy: string }>(db, "aftersales");
  if (!p || !Number.isInteger(p.days) || p.days < 0 || !p.policy?.trim())
    fail("售后期和售后政策尚未配置，暂不能申请入驻");
  return p!;
}
function findPackage(db: DB, target: "B" | "C") {
  const s = setting<MembershipSettings>(db, "membership");
  if (!s) fail("产品包和两档入驻奖励尚未配置");
  const validated = membershipSchema.parse(s),
    p = validated.packages.find((p) => p.targetIdentity === target && p.active);
  if (!p) fail("该身份产品包暂未开放申请");
  const rewards = rewardSettings(db);
  if (!rewards) fail("两档入驻奖励尚未配置");
  return {
    p: p!,
    rewardCents:
      target === "B"
        ? rewards!.principalRewardCents
        : rewards!.storeRewardCents,
  };
}
function packageLines(db: DB, p: z.infer<typeof packageSchema>) {
  return p.lines.map((l) => {
    const sku = db
      .prepare(
        `SELECT s.id,s.name AS skuName,s.unit,s.stock,s.reserved,s.active,p.id AS productId,p.name,p.image,p.active AS pa,c.active AS ca
      FROM skus s JOIN products p ON p.id=s.product_id AND p.is_deleted=0 JOIN categories c ON c.id=p.category_id AND c.is_deleted=0
      WHERE s.id=? AND s.is_deleted=0`,
      )
      .get(l.skuId);
    if (!sku || !sku.active || !sku.pa || !sku.ca)
      fail("产品包商品已停售，请品牌方重新配置");
    return {
      skuId: l.skuId,
      quantity: l.quantity,
      unitCents: l.unitCents,
      principalCents: null,
      productId: sku!.productId,
      name: sku!.name,
      skuName: sku!.skuName,
      unit: sku!.unit,
      image: sku!.image,
    };
  });
}
export function mountMembership(app: Express, ctx: BusinessContext) {
  const { db, userAuth, staffAuth, permit, audit } = ctx;
  app.get("/api/membership", (_req, res) => {
    const s = setting<MembershipSettings>(db, "membership"),
      p = setting(db, "aftersales");
    res.json({
      packages:
        s?.packages
          .filter((p) => p.active)
          .map((p) => ({ ...p, lines: packageLines(db, p) })) || [],
      aftersales: p,
      transfer: companyTransfer(db),
    });
  });
  app.get(
    "/api/admin/membership-settings",
    staffAuth,
    permit("onboarding:manage"),
    (_req, res) => res.json(setting(db, "membership")),
  );
  app.put(
    "/api/admin/membership-settings",
    staffAuth,
    permit("onboarding:manage"),
    (req, res) => {
      const v = z
        .object({
          packages: membershipSchema.shape.packages,
          reason: text.max(200),
        })
        .strict()
        .parse(req.body);
      const s = membershipSchema.parse({ packages: v.packages });
      for (const p of s.packages) packageLines(db, p);
      transaction(db, () => {
        putSetting(db, "membership", s, res.locals.staff.id);
        audit(res, "membership.settings", "membership", "settings", v.reason);
      });
      res.json(s);
    },
  );
  app.post("/api/onboardings", userAuth, (req, res) => {
    const v = z
        .object({
          targetIdentity: z.enum(["B", "C"]),
          addressId: entityId,
          inviterId: entityId.nullable(),
          agreementAccepted: z.literal(true),
          requestKey: z.string().trim().min(12).max(100),
        })
        .strict()
        .parse(req.body),
      u = res.locals.user as UserRow;
    const id = transaction(db, () => {
      const old = db
        .prepare(
          "SELECT * FROM onboardings WHERE user_id=? AND request_key=? AND is_deleted=0",
        )
        .get(u.id, v.requestKey) as unknown as Application | undefined;
      if (old) {
        const snap = JSON.parse(old.package_snapshot);
        if (
          old.target_identity !== v.targetIdentity ||
          old.requested_inviter_id !== v.inviterId ||
          snap.address.id !== v.addressId
        )
          fail("申请编号已用于其他内容");
        return old.id;
      }
      const identity = effectiveIdentity(u);
      if (identity === "B" || (identity === "C" && v.targetIdentity === "C"))
        fail("已具备该身份，请勿重复申请");
      if (
        db
          .prepare(
            "SELECT id FROM onboardings WHERE user_id=? AND status='submitted' AND is_deleted=0",
          )
          .get(u.id)
      )
        fail("已有办理中的入驻申请");
      if (v.inviterId) {
        if (v.inviterId === u.id) fail("不能邀请自己");
        requireAgent(db, v.inviterId);
      }
      const { p, rewardCents } = findPackage(db, v.targetIdentity),
        policy = aftersales(db);
      const address = db
        .prepare(
          "SELECT id,recipient,phone,province,city,district,detail FROM addresses WHERE id=? AND user_id=? AND is_deleted=0",
        )
        .get(v.addressId, u.id);
      if (!address) fail("请选择本人有效收货地址");
      const shipping = setting<{ provinces: string[] }>(db, "shipping");
      if (!shipping?.provinces.includes(String(address!.province)))
        fail("该地址暂不支持配送，请联系品牌方");
      const snapshot = {
        ...p,
        lines: packageLines(db, p),
        address,
        identity: v.targetIdentity,
        inviterId: v.inviterId,
        principalId: null,
        rewardCents,
        afterSalesDays: policy.days,
        afterSalesPolicy: policy.policy,
        agreementAcceptedAt: new Date().toISOString(),
      };
      const id = Number(
        db
          .prepare(
            "INSERT INTO onboardings(user_id,target_identity,package_snapshot,requested_inviter_id,reward_cents,after_sales_days,request_key,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?)",
          )
          .run(
            u.id,
            v.targetIdentity,
            JSON.stringify(snapshot),
            v.inviterId,
            rewardCents,
            policy.days,
            v.requestKey,
            u.id,
            u.id,
          ).lastInsertRowid,
      );
      db.prepare("UPDATE onboardings SET package_snapshot=? WHERE id=?").run(
        JSON.stringify({ ...snapshot, onboardingId: id }),
        id,
      );
      audit(
        res,
        "onboarding.apply",
        "onboarding",
        id,
        "确认产品包及协议，等待真实到账核实与身份审批",
      );
      return id;
    });
    res.json(applicationView(db, application(db, id, u.id)));
  });
  app.get("/api/onboardings", userAuth, (_req, res) =>
    res.json(
      (
        db
          .prepare(
            "SELECT * FROM onboardings WHERE user_id=? AND is_deleted=0 ORDER BY id DESC LIMIT 100",
          )
          .all(res.locals.user.id) as unknown as Application[]
      ).map((a) => applicationView(db, a)),
    ),
  );
  app.get("/api/onboardings/:id", userAuth, (req, res) =>
    res.json(
      applicationView(
        db,
        application(db, entityId.parse(req.params.id), res.locals.user.id),
      ),
    ),
  );
  app.get(
    "/api/admin/onboardings",
    staffAuth,
    permit("onboarding:manage"),
    (_req, res) =>
      res.json(
        (
          db
            .prepare(
              "SELECT o.*,u.nickname FROM onboardings o JOIN users u ON u.id=o.user_id AND u.is_deleted=0 WHERE o.is_deleted=0 ORDER BY o.id DESC LIMIT 200",
            )
            .all() as unknown as Application[]
        ).map((a) => applicationView(db, a)),
      ),
  );
  app.post(
    "/api/admin/onboardings/:id/receipt",
    staffAuth,
    permit("finance:manage"),
    (req, res) => {
      const id = entityId.parse(req.params.id),
        actor = res.locals.staff.id;
      const v = receiptSchema
        .omit({ userId: true, purpose: true, onboardingId: true })
        .parse(req.body);
      transaction(db, () => {
        const a = application(db, id);
        if (a.status !== "submitted" || a.receipt_id)
          fail("该申请已核款或不在办理中");
        const p = JSON.parse(a.package_snapshot),
          receiptId = registerReceipt(
            db,
            { ...v, userId: a.user_id, purpose: "package", onboardingId: id },
            actor,
          );
        // 产品包从未占用普通订单库存，直接扣可用实物；不得减其他订单 reserved。
        for (const l of p.lines) {
          if (
            !db
              .prepare(
                `UPDATE skus SET stock=stock-?,update_user_id=?,update_time=${stamp} WHERE id=? AND is_deleted=0 AND stock-reserved>=?`,
              )
              .run(l.quantity, actor, l.skuId, l.quantity).changes
          )
            fail("产品包库存不足，收款登记与交付订单未提交，请人工核实后重试");
        }
        const snapshot = {
          ...p,
          onboardingId: id,
          receiptId,
          goodsCents: p.amountCents,
          shippingCents: 0,
          totalCents: p.amountCents,
        };
        const orderId = Number(
          db
            .prepare(
              `INSERT INTO orders(number,user_id,kind,status,payment_method,payment_state,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key,create_user_id,update_user_id)
        VALUES(?,?,'package','paid','offline','paid',?,?,0,?,?,?, ?,?)`,
            )
            .run(
              "PK" + randomUUID().replaceAll("-", ""),
              a.user_id,
              p.amountCents,
              p.amountCents,
              JSON.stringify(snapshot),
              new Date().toISOString(),
              `onboarding:${id}`,
              actor,
              actor,
            ).lastInsertRowid,
        );
        db.prepare(
          "INSERT INTO payments(order_id,method,transaction_id,cents,paid_at,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?)",
        ).run(
          orderId,
          "offline",
          `company_receipt:${receiptId}`,
          p.amountCents,
          new Date().toISOString(),
          actor,
          actor,
        );
        for (const l of p.lines)
          db.prepare(
            "INSERT INTO order_lines(order_id,sku_id,quantity,unit_cents,principal_cents,create_user_id,update_user_id) VALUES(?,?,?,?,NULL,?,?)",
          ).run(orderId, l.skuId, l.quantity, l.unitCents, actor, actor);
        db.prepare(
          `UPDATE onboardings SET receipt_id=?,order_id=?,update_user_id=?,update_time=${stamp} WHERE id=?`,
        ).run(receiptId, orderId, actor, id);
        audit(res, "onboarding.receipt", "onboarding", id, v.reason);
      });
      res.json(applicationView(db, application(db, id)));
    },
  );
  app.post(
    "/api/admin/onboardings/:id/review",
    staffAuth,
    permit("onboarding:manage"),
    (req, res) => {
      const id = entityId.parse(req.params.id),
        actor = res.locals.staff.id;
      const v = z
        .discriminatedUnion("decision", [
          z
            .object({
              decision: z.literal("approved"),
              inviterId: entityId.nullable(),
              principalId: entityId,
              relationshipsConfirmed: z.literal(true),
              conditionsMet: z.literal(true),
              reason: text.max(200),
            })
            .strict(),
          z
            .object({ decision: z.literal("rejected"), reason: text.max(200) })
            .strict(),
        ])
        .parse(req.body);
      transaction(db, () => {
        const a = application(db, id);
        if (a.status !== "submitted")
          fail("该申请已审批，不得重复修改审批结果");
        if (v.decision === "approved") {
          if (!a.receipt_id || !a.order_id)
            fail("须先核实有效产品包收款并建立实物交付订单");
          const receipt = db
            .prepare(
              "SELECT * FROM company_receipts WHERE id=? AND user_id=? AND purpose='package' AND onboarding_id=? AND is_deleted=0",
            )
            .get(a.receipt_id, a.user_id, a.id);
          if (
            !receipt ||
            Number(receipt.cash_refunded_cents) ||
            Number(receipt.cash_held_cents)
          )
            fail("收款无效、已退款或正在退款，不能审批通过");
          const o = ownedOrder(db, a.order_id!);
          if (
            o.payment_state !== "paid" ||
            o.kind !== "package" ||
            o.payment_method !== "offline"
          )
            fail("产品包交付订单状态无效");
          if (
            db
              .prepare(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='refunds'",
              )
              .get() &&
            db
              .prepare(
                "SELECT id FROM refunds WHERE order_id=? AND status IN ('requested','approved','processing','unknown','failed') AND is_deleted=0 LIMIT 1",
              )
              .get(o.id)
          )
            fail("产品包正在办理售后，须先核实最终收款与资格");
          if (v.inviterId === a.user_id || v.principalId === a.user_id)
            fail("直接邀请人或归属主理人不能是申请人本人");
          const inviter = v.inviterId ? requireAgent(db, v.inviterId) : null,
            principal = requireAgent(db, v.principalId);
          if (effectiveIdentity(principal) !== "B")
            fail("归属对象必须是当前有效调肤主理人");
          const rewards = rewardSettings(db);
          if (!rewards) fail("入驻奖励尚未配置");
          const rewardCents =
            a.target_identity === "B"
              ? rewards!.principalRewardCents
              : rewards!.storeRewardCents;
          const p = JSON.parse(a.package_snapshot),
            qualified = {
              conditionsMet: true,
              conditions: p.conditions,
              relationshipsConfirmed: true,
              inviterId: v.inviterId,
              inviterIdentity: inviter ? effectiveIdentity(inviter) : null,
              principalId: v.principalId,
              targetIdentity: a.target_identity,
              receiptId: a.receipt_id,
              orderId: a.order_id,
              rewardCents,
              afterSalesDays: p.afterSalesDays,
              afterSalesPolicy: p.afterSalesPolicy,
            };
          const snapshot = {
            ...p,
            inviterId: v.inviterId,
            principalId: v.principalId,
            rewardCents,
            qualification: qualified,
            onboardingApproved: true,
          };
          db.prepare(
            `UPDATE onboardings SET status='approved',inviter_id=?,principal_id=?,reward_cents=?,package_snapshot=?,qualification_snapshot=?,approved_at=${stamp},reviewed_by=?,review_reason=?,update_user_id=?,update_time=${stamp} WHERE id=?`,
          ).run(
            v.inviterId,
            v.principalId,
            rewardCents,
            JSON.stringify(snapshot),
            JSON.stringify(qualified),
            actor,
            v.reason,
            actor,
            id,
          );
          db.prepare(
            `UPDATE orders SET snapshot=?,update_user_id=?,update_time=${stamp} WHERE id=?`,
          ).run(
            JSON.stringify({ ...JSON.parse(o.snapshot), ...snapshot }),
            actor,
            o.id,
          );
          db.prepare(
            `UPDATE users SET identity=?,identity_valid_until=NULL,inviter_id=?,principal_id=?,onboarding_id=?,update_user_id=?,update_time=${stamp} WHERE id=? AND is_deleted=0`,
          ).run(
            a.target_identity,
            v.inviterId,
            v.principalId,
            id,
            actor,
            a.user_id,
          );
        } else
          db.prepare(
            `UPDATE onboardings SET status='rejected',reviewed_by=?,review_reason=?,update_user_id=?,update_time=${stamp} WHERE id=?`,
          ).run(actor, v.reason, actor, id);
        audit(res, "onboarding.review", "onboarding", id, v.reason);
      });
      res.json(applicationView(db, application(db, id)));
    },
  );
}

/** 产品包实际退款完成后调用；由售后模块保持同一事务，不取消后续另次入驻的身份。 */
export function applyPackageRefundIdentity(
  db: DB,
  order: OrderRow,
  full: boolean,
  decision: "retain" | "revoke" | null,
  actor: number,
  reason: string,
) {
  assertTransaction(db, "applyPackageRefundIdentity");
  text.parse(reason);
  if (order.kind !== "package" || order.payment_method !== "offline")
    fail("该订单不是公司转款产品包");
  const a = db
    .prepare(
      "SELECT * FROM onboardings WHERE order_id=? AND user_id=? AND is_deleted=0",
    )
    .get(order.id, order.user_id) as unknown as Application | undefined;
  if (!a || !a.receipt_id) fail("找不到原产品包入驻与收款依据");
  const receipt = db
    .prepare(
      "SELECT * FROM company_receipts WHERE id=? AND purpose='package' AND onboarding_id=? AND user_id=? AND is_deleted=0",
    )
    .get(a!.receipt_id, a!.id, order.user_id);
  if (!receipt) fail("原产品包收款不存在");
  const refunded = Number(
    db
      .prepare(
        "SELECT COALESCE(SUM(total_cents),0) AS total FROM refunds WHERE order_id=? AND status='success' AND is_deleted=0",
      )
      .get(order.id)!.total,
  );
  if (
    !refunded ||
    refunded > Number(receipt!.amount_cents) ||
    refunded < Number(receipt!.cash_refunded_cents)
  )
    fail("实际成功退款与原产品包收款不一致");
  if (full && refunded !== Number(receipt!.amount_cents))
    fail("产品包全退金额与原收款不一致");
  const chosen = full ? "revoke" : decision;
  if (!chosen) fail("部分退款须明确保留或撤销身份");
  const existing = db
    .prepare(
      "SELECT decision,full_refund FROM onboarding_identity_events WHERE onboarding_id=? AND refunded_cents=? AND is_deleted=0",
    )
    .get(a!.id, refunded);
  if (existing) {
    if (existing.decision !== chosen || Boolean(existing.full_refund) !== full)
      fail("本次退款身份结论已登记，不能无痕更改");
    return;
  }
  const user = db
    .prepare(
      "SELECT identity,identity_valid_until,inviter_id,principal_id,onboarding_id FROM users WHERE id=? AND is_deleted=0",
    )
    .get(order.user_id);
  if (!user) fail("原申请人不存在");
  const r = db
    .prepare(
      `UPDATE company_receipts SET cash_refunded_cents=?,update_user_id=?,update_time=${stamp} WHERE id=? AND cash_held_cents+?<=amount_cents`,
    )
    .run(refunded, actor, a!.receipt_id, refunded);
  if (!r.changes) fail("原产品包款正在办理其他退款，须先核对");
  if (chosen === "revoke") {
    db.prepare(
      `UPDATE onboardings SET status='revoked',review_reason=?,update_user_id=?,update_time=${stamp} WHERE id=?`,
    ).run(reason, actor, a!.id);
    db.prepare(
      `UPDATE users SET identity='D',identity_valid_until=NULL,onboarding_id=NULL,update_user_id=?,update_time=${stamp} WHERE id=? AND onboarding_id=? AND is_deleted=0`,
    ).run(actor, order.user_id, a!.id);
    const snapshot = {
      ...JSON.parse(order.snapshot),
      onboardingApproved: false,
      identityRefundDecision: "revoke",
    };
    db.prepare(
      `UPDATE orders SET snapshot=?,update_user_id=?,update_time=${stamp} WHERE id=?`,
    ).run(JSON.stringify(snapshot), actor, order.id);
  }
  db.prepare(
    "INSERT INTO onboarding_identity_events(onboarding_id,order_id,prior_status,decision,full_refund,refunded_cents,user_snapshot,reason,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
  ).run(
    a!.id,
    order.id,
    a!.status,
    chosen,
    Number(full),
    refunded,
    JSON.stringify(user),
    reason,
    actor,
    actor,
  );
}
