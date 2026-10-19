import type { Express } from "express";
import { z } from "zod";
import {
  auditColumns,
  effectiveIdentity,
  transaction,
  type DB,
  type UserRow,
} from "./db.ts";
import { fail, HttpError, type BusinessContext } from "./http.ts";
export const entityId = z.coerce
  .number()
  .int()
  .positive()
  .max(Number.MAX_SAFE_INTEGER);
export const cents = z.number().int().min(1).max(100000000);
const text = z.string().trim().min(1).max(2000);
const image = z
  .string()
  .max(1000)
  .refine(
    (v) => /^\/assets\/[a-zA-Z0-9_./-]+$/.test(v) || /^https:\/\//.test(v),
    "请使用本站素材或 HTTPS 图片地址",
  );
export const contentSchema = z
  .object({
    brandName: text.max(30),
    introduction: text,
    heroTitle: text.max(40),
    heroSubtitle: text.max(100),
    heroImage: image,
    customerService: z.string().trim().max(300),
    purchaseNotes: z.string().trim().max(2000),
    companyName: z.string().trim().max(100),
    bankName: z.string().trim().max(100),
    bankAccount: z.string().trim().max(100),
    transferNotes: z.string().trim().max(2000),
  })
  .strict()
  .refine(
    (v) =>
      [v.companyName, v.bankName, v.bankAccount].every(Boolean) ||
      [v.companyName, v.bankName, v.bankAccount].every((v) => !v),
    "公司收款资料需完整填写或全部留空",
  );
export const skuSchema = z
  .object({
    id: entityId.optional(),
    name: text.max(60),
    unit: text.max(10),
    retailCents: cents.nullable(),
    storeCents: cents.nullable(),
    principalCents: cents.nullable(),
    stock: z.number().int().min(0).max(10000000),
    active: z.boolean(),
  })
  .strict()
  .refine(
    (v) =>
      v.principalCents === null ||
      v.storeCents === null ||
      v.principalCents <= v.storeCents,
    "主理人价不得高于店主价",
  );
const productSchema = z
  .object({
    categoryId: entityId,
    name: text.max(80),
    subtitle: z.string().trim().max(160),
    description: text.max(8000),
    image,
    detailImage: image,
    active: z.boolean(),
    featured: z.boolean(),
    skus: z.array(skuSchema).min(1).max(30),
    reason: text.max(200),
  })
  .strict()
  .refine(
    (v) =>
      new Set(v.skus.filter((s) => s.id).map((s) => s.id)).size ===
      v.skus.filter((s) => s.id).length,
    "规格不可重复",
  );
export function migrateCatalog(db: DB) {
  db.exec(`CREATE TABLE IF NOT EXISTS settings(id INTEGER PRIMARY KEY, key TEXT NOT NULL, value TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 1, ${auditColumns});
 CREATE UNIQUE INDEX IF NOT EXISTS setting_active ON settings(key) WHERE is_deleted=0;
 CREATE TABLE IF NOT EXISTS categories(id INTEGER PRIMARY KEY, name TEXT NOT NULL, sort INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1, ${auditColumns});
 CREATE TABLE IF NOT EXISTS products(id INTEGER PRIMARY KEY, category_id INTEGER NOT NULL REFERENCES categories(id), name TEXT NOT NULL, subtitle TEXT NOT NULL, description TEXT NOT NULL, image TEXT NOT NULL, detail_image TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 0, featured INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 1, ${auditColumns});
 CREATE TABLE IF NOT EXISTS skus(id INTEGER PRIMARY KEY, product_id INTEGER NOT NULL REFERENCES products(id), name TEXT NOT NULL, unit TEXT NOT NULL, retail_cents INTEGER, store_cents INTEGER, principal_cents INTEGER, stock INTEGER NOT NULL CHECK(stock>=0), reserved INTEGER NOT NULL DEFAULT 0 CHECK(reserved>=0 AND reserved<=stock), active INTEGER NOT NULL, ${auditColumns});`);
}
export function setting<T>(db: DB, key: string): T | null {
  const row = db
    .prepare("SELECT value FROM settings WHERE key=? AND is_deleted=0")
    .get(key);
  return row ? (JSON.parse(String(row.value)) as T) : null;
}
export function putSetting(db: DB, key: string, value: unknown, actor: number) {
  db.prepare(
    `INSERT INTO settings(key,value,create_user_id,update_user_id) VALUES(?,?,?,?) ON CONFLICT(key) WHERE is_deleted=0 DO UPDATE SET value=excluded.value,version=settings.version+1,update_user_id=excluded.update_user_id,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now')`,
  ).run(key, JSON.stringify(value), actor, actor);
}
export interface SkuRow {
  id: number;
  product_id: number;
  name: string;
  unit: string;
  retail_cents: number | null;
  store_cents: number | null;
  principal_cents: number | null;
  stock: number;
  reserved: number;
  active: number;
}
export function rolePrice(sku: SkuRow, user?: UserRow) {
  const identity = user ? effectiveIdentity(user) : "D";
  return identity === "B"
    ? sku.principal_cents
    : identity === "C"
      ? sku.store_cents
      : sku.retail_cents;
}
export function productView(
  db: DB,
  row: Record<string, unknown>,
  user?: UserRow,
  admin = false,
) {
  const skus = (
    db
      .prepare(
        "SELECT * FROM skus WHERE product_id=? AND is_deleted=0 ORDER BY id",
      )
      .all(Number(row.id)) as unknown as SkuRow[]
  ).map((s) => ({
    id: s.id,
    name: s.name,
    unit: s.unit,
    stock: s.stock - s.reserved,
    active: Boolean(s.active),
    priceCents: rolePrice(s, user),
    retailCents: s.retail_cents,
    available: Boolean(
      row.active &&
      s.active &&
      s.stock > s.reserved &&
      rolePrice(s, user) !== null,
    ),
    ...(admin
      ? {
          storeCents: s.store_cents,
          principalCents: s.principal_cents,
          stock: s.stock,
          reserved: s.reserved,
        }
      : {}),
  }));
  return {
    id: row.id,
    categoryId: row.category_id,
    name: row.name,
    subtitle: row.subtitle,
    description: row.description,
    image: row.image,
    detailImage: row.detail_image,
    active: Boolean(row.active),
    featured: Boolean(row.featured),
    version: row.version,
    skus,
  };
}
export function mountCatalog(app: Express, ctx: BusinessContext) {
  const { db, staffAuth, permit, optionalUser, audit } = ctx;
  app.get("/api/public/content", (_req, res) =>
    res.json({
      configured: Boolean(setting(db, "content")),
      content: setting(db, "content"),
    }),
  );
  app.get(
    "/api/admin/content",
    staffAuth,
    permit("content:manage"),
    (_req, res) => res.json(setting(db, "content")),
  );
  app.put(
    "/api/admin/content",
    staffAuth,
    permit("content:manage"),
    (req, res) => {
      const { reason, ...body } = z
        .object({ reason: text.max(200) })
        .passthrough()
        .parse(req.body);
      const value = contentSchema.parse(body);
      transaction(db, () => {
        putSetting(db, "content", value, res.locals.staff.id);
        audit(res, "content.update", "content", "brand", reason);
      });
      res.json(value);
    },
  );
  const cats = () =>
    db
      .prepare(
        "SELECT id,name,sort,active FROM categories WHERE is_deleted=0 ORDER BY sort,id",
      )
      .all();
  app.get("/api/public/categories", (_req, res) =>
    res.json(cats().filter((c) => c.active)),
  );
  app.get(
    "/api/admin/categories",
    staffAuth,
    permit("catalog:manage"),
    (_req, res) => res.json(cats()),
  );
  for (const method of ["post", "put"] as const)
    app[method](
      "/api/admin/categories" + (method === "put" ? "/:id" : ""),
      staffAuth,
      permit("catalog:manage"),
      (req, res) => {
        const v = z
          .object({
            name: text.max(40),
            sort: z.number().int().min(0).max(9999),
            active: z.boolean(),
            reason: text.max(200),
          })
          .strict()
          .parse(req.body);
        const id = transaction(db, () => {
          let id = method === "put" ? entityId.parse(req.params.id) : 0;
          if (id) {
            const r = db
              .prepare(
                `UPDATE categories SET name=?,sort=?,active=?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND is_deleted=0`,
              )
              .run(v.name, v.sort, Number(v.active), res.locals.staff.id, id);
            if (!r.changes) throw new HttpError(404, "NOT_FOUND", "分类不存在");
          } else
            id = Number(
              db
                .prepare(
                  "INSERT INTO categories(name,sort,active,create_user_id,update_user_id) VALUES(?,?,?,?,?)",
                )
                .run(
                  v.name,
                  v.sort,
                  Number(v.active),
                  res.locals.staff.id,
                  res.locals.staff.id,
                ).lastInsertRowid,
            );
          audit(res, "category.save", "category", id, v.reason);
          return id;
        });
        res.json({ id });
      },
    );
  app.get("/api/products", optionalUser, (req, res) => {
    const category =
      req.query.categoryId === undefined
        ? null
        : entityId.parse(req.query.categoryId);
    const rows = db
      .prepare(
        `SELECT p.* FROM products p JOIN categories c ON c.id=p.category_id AND c.is_deleted=0 AND c.active=1 WHERE p.is_deleted=0 AND p.active=1 ${category ? "AND p.category_id=?" : ""} ORDER BY p.id DESC LIMIT 200`,
      )
      .all(...(category ? [category] : []));
    res.json({
      identity: res.locals.user ? effectiveIdentity(res.locals.user) : "D",
      items: rows.map((p) => productView(db, p, res.locals.user)),
    });
  });
  app.get("/api/products/:id", optionalUser, (req, res) => {
    const row = db
      .prepare(
        "SELECT p.* FROM products p JOIN categories c ON c.id=p.category_id AND c.is_deleted=0 AND c.active=1 WHERE p.id=? AND p.is_deleted=0 AND p.active=1",
      )
      .get(entityId.parse(req.params.id));
    if (!row) throw new HttpError(404, "NOT_FOUND", "商品已下架或不存在");
    res.json(productView(db, row, res.locals.user));
  });
  app.get(
    "/api/admin/products",
    staffAuth,
    permit("catalog:manage"),
    (_req, res) =>
      res.json(
        db
          .prepare(
            "SELECT * FROM products WHERE is_deleted=0 ORDER BY id DESC LIMIT 200",
          )
          .all()
          .map((p) => productView(db, p, undefined, true)),
      ),
  );
  for (const method of ["post", "put"] as const)
    app[method](
      "/api/admin/products" + (method === "put" ? "/:id" : ""),
      staffAuth,
      permit("catalog:manage"),
      (req, res) => {
        const v = productSchema.parse(req.body),
          actor = res.locals.staff.id;
        const id = transaction(db, () => {
          if (
            !db
              .prepare("SELECT id FROM categories WHERE id=? AND is_deleted=0")
              .get(v.categoryId)
          )
            fail("分类不存在");
          let id = method === "put" ? entityId.parse(req.params.id) : 0;
          if (id) {
            if (
              !db
                .prepare("SELECT id FROM products WHERE id=? AND is_deleted=0")
                .get(id)
            )
              throw new HttpError(404, "NOT_FOUND", "商品不存在");
            db.prepare(
              `UPDATE products SET category_id=?,name=?,subtitle=?,description=?,image=?,detail_image=?,active=?,featured=?,version=version+1,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?`,
            ).run(
              v.categoryId,
              v.name,
              v.subtitle,
              v.description,
              v.image,
              v.detailImage,
              Number(v.active),
              Number(v.featured),
              actor,
              id,
            );
          } else
            id = Number(
              db
                .prepare(
                  "INSERT INTO products(category_id,name,subtitle,description,image,detail_image,active,featured,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                )
                .run(
                  v.categoryId,
                  v.name,
                  v.subtitle,
                  v.description,
                  v.image,
                  v.detailImage,
                  Number(v.active),
                  Number(v.featured),
                  actor,
                  actor,
                ).lastInsertRowid,
            );
          const existing = db
            .prepare(
              "SELECT id,reserved FROM skus WHERE product_id=? AND is_deleted=0",
            )
            .all(id);
          for (const s of v.skus) {
            if (s.id) {
              const old = existing.find((e) => e.id === s.id);
              if (!old) fail("规格不属于当前商品");
              if (s.stock < Number(old!.reserved))
                fail("库存不能低于待付款占用数量");
              db.prepare(
                `UPDATE skus SET name=?,unit=?,retail_cents=?,store_cents=?,principal_cents=?,stock=?,active=?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND product_id=? AND is_deleted=0`,
              ).run(
                s.name,
                s.unit,
                s.retailCents,
                s.storeCents,
                s.principalCents,
                s.stock,
                Number(s.active),
                actor,
                s.id,
                id,
              );
            } else
              db.prepare(
                "INSERT INTO skus(product_id,name,unit,retail_cents,store_cents,principal_cents,stock,active,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
              ).run(
                id,
                s.name,
                s.unit,
                s.retailCents,
                s.storeCents,
                s.principalCents,
                s.stock,
                Number(s.active),
                actor,
                actor,
              );
          }
          for (const old of existing.filter(
            (e) => !v.skus.some((s) => s.id === e.id),
          )) {
            if (Number(old!.reserved) > 0)
              fail("待付款订单占用的规格不能移除，请先停售");
            db.prepare(
              `UPDATE skus SET is_deleted=1,active=0,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?`,
            ).run(actor, old.id);
          }
          audit(res, "product.save", "product", id, v.reason);
          return id;
        });
        res.json({ id });
      },
    );
  app.post(
    "/api/admin/products/:id/remove",
    staffAuth,
    permit("catalog:manage"),
    (req, res) => {
      const id = entityId.parse(req.params.id),
        { reason } = z
          .object({ reason: text.max(200) })
          .strict()
          .parse(req.body);
      transaction(db, () => {
        if (
          db
            .prepare(
              "SELECT id FROM skus WHERE product_id=? AND reserved>0 AND is_deleted=0",
            )
            .get(id)
        )
          fail("待付款订单占用的商品请先下架");
        const r = db
          .prepare(
            `UPDATE products SET is_deleted=1,active=0,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND is_deleted=0`,
          )
          .run(res.locals.staff.id, id);
        if (!r.changes) throw new HttpError(404, "NOT_FOUND", "商品不存在");
        audit(res, "product.remove", "product", id, reason);
      });
      res.json({ ok: true });
    },
  );
}
