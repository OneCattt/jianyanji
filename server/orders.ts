import { assertNoUnresolvedPayment } from "./payments.ts";
import type { Express } from "express";
import { randomUUID, createHash } from "node:crypto";
import { z } from "zod";
import {
  auditColumns,
  effectiveIdentity,
  transaction,
  type DB,
  type UserRow,
} from "./db.ts";
import {
  entityId,
  rolePrice,
  setting,
  putSetting,
  type SkuRow,
} from "./catalog.ts";
import { fail, HttpError, type BusinessContext } from "./http.ts";
const required = z.string().trim().min(1);
export const reasonSchema = z.object({ reason: required.max(200) }).strict();
const addressSchema = z
  .object({
    recipient: required.max(40),
    phone: z.string().regex(/^1[3-9]\d{9}$/),
    province: required.max(30),
    city: required.max(30),
    district: required.max(30),
    detail: required.max(200),
    isDefault: z.boolean(),
  })
  .strict();
export const shippingSchema = z
  .object({
    provinces: z.array(required.max(30)).min(1).max(40),
    feeCents: z.number().int().min(0).max(10000000),
    instructions: required.max(2000),
    pendingMinutes: z.number().int().min(5).max(1440),
  })
  .strict();
export interface Shipping {
  provinces: string[];
  feeCents: number;
  instructions: string;
  pendingMinutes: number;
}
export interface OrderRow {
  id: number;
  number: string;
  user_id: number;
  kind: "goods" | "package";
  status: string;
  payment_method: string | null;
  payment_state: string;
  total_cents: number;
  goods_cents: number;
  shipping_cents: number;
  snapshot: string;
  expires_at: string;
  received_at: string | null;
  request_key: string;
  quote_id: number | null;
}
export function migrateOrders(db: DB) {
  const cols = db.prepare("PRAGMA table_info(users)").all();
  for (const col of ["inviter_id", "principal_id", "onboarding_id"])
    if (!cols.some((c) => c.name === col))
      db.exec(`ALTER TABLE users ADD COLUMN ${col} INTEGER`);
  db.exec(`CREATE TABLE IF NOT EXISTS addresses(id INTEGER PRIMARY KEY,user_id INTEGER NOT NULL REFERENCES users(id),recipient TEXT NOT NULL,phone TEXT NOT NULL,province TEXT NOT NULL,city TEXT NOT NULL,district TEXT NOT NULL,detail TEXT NOT NULL,is_default INTEGER NOT NULL DEFAULT 0,${auditColumns});
 CREATE TABLE IF NOT EXISTS cart_items(id INTEGER PRIMARY KEY,user_id INTEGER NOT NULL REFERENCES users(id),sku_id INTEGER NOT NULL REFERENCES skus(id),quantity INTEGER NOT NULL CHECK(quantity>0),selected INTEGER NOT NULL DEFAULT 1,${auditColumns});
 CREATE UNIQUE INDEX IF NOT EXISTS cart_active ON cart_items(user_id,sku_id) WHERE is_deleted=0;
 CREATE TABLE IF NOT EXISTS quotes(id INTEGER PRIMARY KEY,user_id INTEGER NOT NULL REFERENCES users(id),snapshot TEXT NOT NULL,expires_at TEXT NOT NULL,${auditColumns});
 CREATE TABLE IF NOT EXISTS orders(id INTEGER PRIMARY KEY,number TEXT NOT NULL UNIQUE,user_id INTEGER NOT NULL REFERENCES users(id),kind TEXT NOT NULL DEFAULT 'goods',status TEXT NOT NULL DEFAULT 'pending',payment_method TEXT,payment_state TEXT NOT NULL DEFAULT 'unpaid',total_cents INTEGER NOT NULL,goods_cents INTEGER NOT NULL,shipping_cents INTEGER NOT NULL,snapshot TEXT NOT NULL,expires_at TEXT NOT NULL,received_at TEXT,request_key TEXT NOT NULL,quote_id INTEGER,${auditColumns});
 CREATE UNIQUE INDEX IF NOT EXISTS orders_request_active ON orders(user_id,request_key) WHERE is_deleted=0;
 CREATE INDEX IF NOT EXISTS orders_user ON orders(user_id,id);
 CREATE TABLE IF NOT EXISTS order_lines(id INTEGER PRIMARY KEY,order_id INTEGER NOT NULL REFERENCES orders(id),sku_id INTEGER NOT NULL REFERENCES skus(id),quantity INTEGER NOT NULL,unit_cents INTEGER NOT NULL,principal_cents INTEGER,shipped_qty INTEGER NOT NULL DEFAULT 0,received_qty INTEGER NOT NULL DEFAULT 0,refunded_qty INTEGER NOT NULL DEFAULT 0,refund_held INTEGER NOT NULL DEFAULT 0,refunded_unshipped INTEGER NOT NULL DEFAULT 0,held_unshipped INTEGER NOT NULL DEFAULT 0,${auditColumns});
 CREATE TABLE IF NOT EXISTS shipments(id INTEGER PRIMARY KEY,order_id INTEGER NOT NULL REFERENCES orders(id),carrier TEXT NOT NULL,tracking TEXT NOT NULL,lines TEXT NOT NULL,shipped_at TEXT NOT NULL,received_at TEXT,${auditColumns});`);
  const lineColumns = db.prepare("PRAGMA table_info(order_lines)").all();
  for (const col of ["refunded_unshipped", "held_unshipped"])
    if (!lineColumns.some((c) => c.name === col))
      db.exec(
        `ALTER TABLE order_lines ADD COLUMN ${col} INTEGER NOT NULL DEFAULT 0`,
      );
}
export function ownedOrder(db: DB, id: number, userId?: number): OrderRow {
  const row = db
    .prepare(
      "SELECT * FROM orders WHERE id=? AND is_deleted=0" +
        (userId ? " AND user_id=?" : ""),
    )
    .get(id, ...(userId ? [userId] : [])) as unknown as OrderRow | undefined;
  if (!row) throw new HttpError(404, "NOT_FOUND", "订单不存在");
  return row;
}
export function orderView(db: DB, o: OrderRow) {
  return {
    ...o,
    snapshot: JSON.parse(o.snapshot),
    lines: db
      .prepare(
        "SELECT * FROM order_lines WHERE order_id=? AND is_deleted=0 ORDER BY id",
      )
      .all(o.id),
    shipments: db
      .prepare(
        "SELECT id,carrier,tracking,lines,shipped_at,received_at FROM shipments WHERE order_id=? AND is_deleted=0 ORDER BY id",
      )
      .all(o.id)
      .map((s) => ({ ...s, lines: JSON.parse(String(s.lines)) })),
  };
}
export function releaseOrder(db: DB, o: OrderRow) {
  if (o.status !== "pending" || !["unpaid", "failed"].includes(o.payment_state))
    return false;
  const lines = db
    .prepare(
      "SELECT sku_id,quantity FROM order_lines WHERE order_id=? AND is_deleted=0",
    )
    .all(o.id);
  for (const l of lines)
    db.prepare(
      "UPDATE skus SET reserved=reserved-? WHERE id=? AND reserved>=?",
    ).run(l.quantity, l.sku_id, l.quantity);
  db.prepare(
    `UPDATE orders SET status='closed',update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND status='pending'`,
  ).run(o.id);
  return true;
}
export function expireOrders(db: DB) {
  transaction(db, () => {
    for (const o of db
      .prepare(
        "SELECT * FROM orders WHERE status='pending' AND payment_state IN ('unpaid','failed') AND expires_at<? AND is_deleted=0",
      )
      .all(new Date().toISOString()) as unknown as OrderRow[])
      releaseOrder(db, o);
  });
}
interface LineRequest {
  skuId: number;
  quantity: number;
}
export function buildQuote(
  db: DB,
  user: UserRow,
  addressId: number,
  items: LineRequest[],
) {
  const shipping = setting<Shipping>(db, "shipping");
  if (!shipping) fail("配送和待付款有效期尚未配置");
  const address = db
    .prepare(
      "SELECT id,recipient,phone,province,city,district,detail FROM addresses WHERE id=? AND user_id=? AND is_deleted=0",
    )
    .get(addressId, user.id);
  if (!address) fail("请选择本人有效收货地址");
  if (!shipping!.provinces.includes(String(address!.province)))
    fail("该地区暂不支持配送");
  const owner =
    db
      .prepare("SELECT principal_id FROM users WHERE id=? AND is_deleted=0")
      .get(user.id)?.principal_id ?? null;
  const identity = effectiveIdentity(user);
  const aftersales = setting<{ days: number; policy: string }>(
    db,
    "aftersales",
  );
  if (!aftersales) fail("售后政策尚未配置，请联系品牌方");
  const lines = items.map((item) => {
    const s = db
      .prepare(
        "SELECT s.*,p.name AS product_name,p.image,p.version,p.active AS product_active,c.active AS category_active FROM skus s JOIN products p ON p.id=s.product_id AND p.is_deleted=0 JOIN categories c ON c.id=p.category_id AND c.is_deleted=0 WHERE s.id=? AND s.is_deleted=0",
      )
      .get(item.skuId) as unknown as
      | (SkuRow & {
          product_name: string;
          image: string;
          version: number;
          product_active: number;
          category_active: number;
        })
      | undefined;
    if (!s || !s.active || !s.product_active || !s.category_active)
      fail("商品或规格已停售，请重新选择");
    const sku = s!;
    const price = rolePrice(sku, user);
    if (price === null) fail("当前身份价格尚未配置");
    if (sku.stock - sku.reserved < item.quantity) fail("商品库存不足");
    if (identity === "C" && sku.principal_cents === null)
      fail("商品主理人价格尚未配置，暂不能订货");
    return {
      skuId: sku.id,
      productId: sku.product_id,
      name: sku.product_name,
      skuName: sku.name,
      unit: sku.unit,
      image: sku.image,
      quantity: item.quantity,
      unitCents: price!,
      principalCents: identity === "C" ? sku.principal_cents : null,
      version: sku.version,
    };
  });
  const goodsCents = lines.reduce(
    (sum, l) => sum + l.unitCents * l.quantity,
    0,
  );
  if (!Number.isSafeInteger(goodsCents) || goodsCents > 100000000)
    fail("订单金额超出允许范围");
  return {
    afterSalesDays: aftersales!.days,
    afterSalesPolicy: aftersales!.policy,
    identity,
    principalId: identity === "C" ? owner : null,
    address,
    lines,
    goodsCents,
    shippingCents: shipping!.feeCents,
    totalCents: goodsCents + shipping!.feeCents,
    shippingInstructions: shipping!.instructions,
    pendingMinutes: shipping!.pendingMinutes,
  };
}
const checkout = z
  .object({
    addressId: entityId,
    items: z
      .array(
        z
          .object({
            skuId: entityId,
            quantity: z.number().int().min(1).max(999),
          })
          .strict(),
      )
      .min(1)
      .max(50),
  })
  .strict()
  .refine(
    (v) => new Set(v.items.map((i) => i.skuId)).size === v.items.length,
    "同规格不可重复",
  );
export function mountOrders(app: Express, ctx: BusinessContext) {
  const { db, userAuth, staffAuth, permit, audit } = ctx;
  app.get(
    "/api/admin/shipping-settings",
    staffAuth,
    permit("orders:manage"),
    (_req, res) => res.json(setting(db, "shipping")),
  );
  app.put(
    "/api/admin/shipping-settings",
    staffAuth,
    permit("orders:manage"),
    (req, res) => {
      const { reason, ...input } = req.body;
      reasonSchema.parse({ reason });
      const v = shippingSchema.parse(input);
      transaction(db, () => {
        putSetting(db, "shipping", v, res.locals.staff.id);
        audit(res, "shipping.settings", "shipping", "settings", reason);
      });
      res.json(v);
    },
  );
  app.get("/api/addresses", userAuth, (_req, res) =>
    res.json(
      db
        .prepare(
          "SELECT * FROM addresses WHERE user_id=? AND is_deleted=0 ORDER BY is_default DESC,id DESC",
        )
        .all(res.locals.user.id),
    ),
  );
  for (const method of ["post", "put"] as const)
    app[method](
      "/api/addresses" + (method === "put" ? "/:id" : ""),
      userAuth,
      (req, res) => {
        const v = addressSchema.parse(req.body),
          u = res.locals.user.id;
        const id = transaction(db, () => {
          let id = method === "put" ? entityId.parse(req.params.id) : 0;
          if (
            id &&
            !db
              .prepare(
                "SELECT id FROM addresses WHERE id=? AND user_id=? AND is_deleted=0",
              )
              .get(id, u)
          )
            throw new HttpError(404, "NOT_FOUND", "地址不存在");
          if (v.isDefault)
            db.prepare(
              `UPDATE addresses SET is_default=0,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE user_id=? AND is_deleted=0`,
            ).run(u, u);
          if (id)
            db.prepare(
              `UPDATE addresses SET recipient=?,phone=?,province=?,city=?,district=?,detail=?,is_default=?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND user_id=? AND is_deleted=0`,
            ).run(
              v.recipient,
              v.phone,
              v.province,
              v.city,
              v.district,
              v.detail,
              Number(v.isDefault),
              u,
              id,
              u,
            );
          else
            id = Number(
              db
                .prepare(
                  "INSERT INTO addresses(user_id,recipient,phone,province,city,district,detail,is_default,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                )
                .run(
                  u,
                  v.recipient,
                  v.phone,
                  v.province,
                  v.city,
                  v.district,
                  v.detail,
                  Number(v.isDefault),
                  u,
                  u,
                ).lastInsertRowid,
            );
          audit(res, "address.save", "address", id, "维护本人收货地址");
          return id;
        });
        res.json({ id });
      },
    );
  app.post("/api/addresses/:id/remove", userAuth, (req, res) => {
    const r = db
      .prepare(
        `UPDATE addresses SET is_deleted=1,is_default=0,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND user_id=? AND is_deleted=0`,
      )
      .run(
        res.locals.user.id,
        entityId.parse(req.params.id),
        res.locals.user.id,
      );
    if (!r.changes) throw new HttpError(404, "NOT_FOUND", "地址不存在");
    res.json({ ok: true });
  });
  app.get("/api/cart", userAuth, (_req, res) => {
    const items = db
      .prepare(
        "SELECT c.id,c.sku_id AS skuId,c.quantity,c.selected,p.name,p.image,s.name AS skuName,s.unit,s.stock-s.reserved AS stock,s.retail_cents,s.store_cents,s.principal_cents,s.active,p.active AS product_active,p.is_deleted AS product_deleted,s.is_deleted AS sku_deleted,ct.active AS category_active,ct.is_deleted AS category_deleted FROM cart_items c JOIN skus s ON s.id=c.sku_id JOIN products p ON p.id=s.product_id JOIN categories ct ON ct.id=p.category_id WHERE c.user_id=? AND c.is_deleted=0 ORDER BY c.id",
      )
      .all(res.locals.user.id)
      .map((row) => {
        const price = rolePrice(row as unknown as SkuRow, res.locals.user);
        return {
          id: row.id,
          skuId: row.skuId,
          quantity: row.quantity,
          selected: Boolean(row.selected),
          name: row.name,
          image: row.image,
          skuName: row.skuName,
          unit: row.unit,
          stock: row.stock,
          priceCents: price,
          available: Boolean(
            !row.product_deleted &&
            !row.sku_deleted &&
            !row.category_deleted &&
            row.active &&
            row.product_active &&
            row.category_active &&
            price !== null &&
            Number(row.stock) >= Number(row.quantity),
          ),
        };
      });
    res.json({ items });
  });
  app.put("/api/cart/:id", userAuth, (req, res) => {
    const skuId = entityId.parse(req.params.id),
      v = z
        .object({
          quantity: z.number().int().min(1).max(999),
          selected: z.boolean(),
        })
        .strict()
        .parse(req.body);
    const s = db
      .prepare(
        "SELECT s.*,p.active AS pa FROM skus s JOIN products p ON p.id=s.product_id AND p.is_deleted=0 WHERE s.id=? AND s.is_deleted=0",
      )
      .get(skuId) as unknown as SkuRow & { pa: number };
    if (!s || !s.active || !s.pa || rolePrice(s, res.locals.user) === null)
      fail("该商品暂不可购买");
    if (s.stock - s.reserved < v.quantity) fail("库存不足");
    db.prepare(
      `INSERT INTO cart_items(user_id,sku_id,quantity,selected,create_user_id,update_user_id) VALUES(?,?,?,?,?,?) ON CONFLICT(user_id,sku_id) WHERE is_deleted=0 DO UPDATE SET quantity=excluded.quantity,selected=excluded.selected,update_user_id=excluded.update_user_id,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now')`,
    ).run(
      res.locals.user.id,
      skuId,
      v.quantity,
      Number(v.selected),
      res.locals.user.id,
      res.locals.user.id,
    );
    res.json({ ok: true });
  });
  app.post("/api/cart/:id/remove", userAuth, (req, res) => {
    db.prepare(
      `UPDATE cart_items SET is_deleted=1,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE sku_id=? AND user_id=? AND is_deleted=0`,
    ).run(
      res.locals.user.id,
      entityId.parse(req.params.id),
      res.locals.user.id,
    );
    res.json({ ok: true });
  });
  app.post("/api/checkout/quote", userAuth, (req, res) => {
    expireOrders(db);
    const v = checkout.parse(req.body),
      u = res.locals.user;
    const snapshot = buildQuote(db, u, v.addressId, v.items);
    const id = Number(
      db
        .prepare(
          "INSERT INTO quotes(user_id,snapshot,expires_at,create_user_id,update_user_id) VALUES(?,?,?,?,?)",
        )
        .run(
          u.id,
          JSON.stringify(snapshot),
          new Date(Date.now() + 600000).toISOString(),
          u.id,
          u.id,
        ).lastInsertRowid,
    );
    res.json({ id, ...snapshot });
  });
  app.post("/api/orders", userAuth, (req, res) => {
    const v = z
      .object({
        quoteId: entityId,
        requestKey: z.string().regex(/^[a-zA-Z0-9_-]{12,100}$/),
      })
      .strict()
      .parse(req.body);
    const u = res.locals.user;
    const result = transaction(db, () => {
      const old = db
        .prepare(
          "SELECT * FROM orders WHERE user_id=? AND request_key=? AND is_deleted=0",
        )
        .get(u.id, v.requestKey) as unknown as OrderRow | undefined;
      if (old) {
        if (old.quote_id !== v.quoteId) fail("请求编号已用于其他订单");
        return old;
      }
      assertNoUnresolvedPayment(db, u.id);
      const q = db
        .prepare(
          "SELECT * FROM quotes WHERE id=? AND user_id=? AND is_deleted=0 AND expires_at>?",
        )
        .get(v.quoteId, u.id, new Date().toISOString());
      if (!q) fail("确认信息已过期，请重新结算");
      const original = JSON.parse(String(q!.snapshot));
      const latest = buildQuote(
        db,
        u,
        original.address.id,
        original.lines.map((l: LineRequest) => ({
          skuId: l.skuId,
          quantity: l.quantity,
        })),
      );
      if (JSON.stringify(latest) !== q!.snapshot)
        fail("价格、身份或配送信息已变化，请重新确认", "QUOTE_CHANGED");
      for (const l of latest.lines) {
        const r = db
          .prepare(
            "UPDATE skus SET reserved=reserved+? WHERE id=? AND is_deleted=0 AND stock-reserved>=?",
          )
          .run(l.quantity, l.skuId, l.quantity);
        if (!r.changes) fail("库存不足");
      }
      const id = Number(
        db
          .prepare(
            "INSERT INTO orders(number,user_id,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key,quote_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
          )
          .run(
            "JY" +
              new Date().toISOString().slice(0, 10).replaceAll("-", "") +
              randomUUID().replaceAll("-", "").slice(0, 16),
            u.id,
            latest.totalCents,
            latest.goodsCents,
            latest.shippingCents,
            JSON.stringify(latest),
            new Date(Date.now() + latest.pendingMinutes * 60000).toISOString(),
            v.requestKey,
            v.quoteId,
            u.id,
            u.id,
          ).lastInsertRowid,
      );
      for (const l of latest.lines)
        db.prepare(
          "INSERT INTO order_lines(order_id,sku_id,quantity,unit_cents,principal_cents,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?)",
        ).run(
          id,
          l.skuId,
          l.quantity,
          l.unitCents,
          l.principalCents,
          u.id,
          u.id,
        );
      audit(res, "order.create", "order", id, "确认订单并占用库存，尚未扣款");
      return ownedOrder(db, id, u.id);
    });
    res.json(orderView(db, result));
  });
  app.get("/api/orders", userAuth, (_req, res) => {
    expireOrders(db);
    res.json(
      db
        .prepare(
          "SELECT * FROM orders WHERE user_id=? AND is_deleted=0 ORDER BY id DESC LIMIT 100",
        )
        .all(res.locals.user.id)
        .map((o) => orderView(db, o as unknown as OrderRow)),
    );
  });
  app.get("/api/orders/:id", userAuth, (req, res) => {
    expireOrders(db);
    res.json(
      orderView(
        db,
        ownedOrder(db, entityId.parse(req.params.id), res.locals.user.id),
      ),
    );
  });
  app.get(
    "/api/admin/orders",
    staffAuth,
    permit("orders:manage"),
    (_req, res) => {
      expireOrders(db);
      res.json(
        db
          .prepare(
            "SELECT * FROM orders WHERE is_deleted=0 ORDER BY id DESC LIMIT 200",
          )
          .all()
          .map((o) => orderView(db, o as unknown as OrderRow)),
      );
    },
  );
  app.post("/api/orders/:id/cancel", userAuth, (req, res) => {
    transaction(db, () => {
      const o = ownedOrder(
        db,
        entityId.parse(req.params.id),
        res.locals.user.id,
      );
      if (o.status === "closed") return;
      if (!releaseOrder(db, o)) fail("支付已发起或已付款，请先查询原交易结果");
      audit(res, "order.cancel", "order", o.id, "用户取消未支付订单");
    });
    res.json({ ok: true });
  });
}
