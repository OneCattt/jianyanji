import { mountPayments, type PaymentsOptions } from "./payments.ts";
import { mountFulfillment, refreshFulfillment } from "./fulfillment.ts";
import { mountMembership, applyPackageRefundIdentity } from "./membership.ts";
import { mountWallet, restoreOrderPoints } from "./wallet.ts";
import { mountRewards, reconcileRewardReversals } from "./rewards.ts";
import { mountCashRefunds } from "./cash-refunds.ts";
import { paymentAdapterFromEnv, type PayChannel } from "./wechat-pay.ts";
import express, {
  type Request,
  type Response,
  type NextFunction,
} from "express";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { resolve } from "node:path";
import { existsSync } from "node:fs";
import { z } from "zod";
import {
  type DB,
  type UserRow,
  type StaffRow,
  type Permission,
  permissions,
  publicUser,
  publicStaff,
  recordAudit,
  transaction,
  createStaff,
  checkPassword,
  passwordHash,
} from "./db.ts";
import { exchangeWechatCode, WechatError } from "./wechat.ts";

import { HttpError } from "./http.ts";
import { mountAftersales, type RefundHooks } from "./aftersales.ts";
import { mountOrders } from "./orders.ts";
import { mountCatalog } from "./catalog.ts";

export interface AppOptions {
  payments?: PaymentsOptions;
  refunds?: RefundHooks;
  devAuth: boolean;
  production?: boolean;
  appId?: string;
  appSecret?: string;
  exchangeCode?: (code: string) => Promise<string>;
}
const hash = (value: string) =>
  createHash("sha256").update(value).digest("hex");
const idSchema = z.coerce
  .number()
  .int()
  .positive()
  .max(Number.MAX_SAFE_INTEGER);
const profileSchema = z
  .object({
    nickname: z.string().trim().min(1).max(30),
    phone: z.string().regex(/^$|^1[3-9]\d{9}$/, "请填写正确的联系电话"),
  })
  .strict();
const grantsSchema = z
  .array(z.enum(permissions))
  .max(permissions.length)
  .refine((v) => new Set(v).size === v.length);
const staffSchema = z
  .object({
    username: z.string().regex(/^[a-zA-Z][a-zA-Z0-9_-]{2,31}$/),
    displayName: z.string().trim().min(1).max(30),
    password: z.string().min(12).max(128),
    permissions: grantsSchema,
    reason: z.string().trim().min(1).max(200),
  })
  .strict();

export function createApp(db: DB, options: AppOptions) {
  if (options.production && options.devAuth)
    throw new Error("生产环境禁止开启调试登录");
  const app = express();
  app.disable("x-powered-by");
  app.use((req, res, next) => {
    res.locals.requestId = randomUUID();
    res.set({
      "X-Request-ID": res.locals.requestId,
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "same-origin",
      "X-Frame-Options": "DENY",
    });
    if (req.path.startsWith("/api/")) res.set("Cache-Control", "no-store");
    next();
  });
  app.use(
    express.json({
      limit: "128kb",
      verify: (req, _res, buffer) => {
        if (req.url?.startsWith("/api/payments/wechat/notify"))
          (req as Request & { rawBody?: Buffer }).rawBody = Buffer.from(buffer);
      },
    }),
  );
  const audit = (
    res: Response,
    action: string,
    objectType: string,
    objectId: string | number,
    reason: string,
    result: "success" | "denied" | "failure" = "success",
  ) => {
    const staff = res.locals.staff as StaffRow | undefined;
    const user = res.locals.user as UserRow | undefined;
    recordAudit(db, {
      actorKind: staff ? "staff" : user ? "user" : "system",
      actorId: staff?.id ?? user?.id ?? 0,
      action,
      objectType,
      objectId,
      reason,
      result,
      requestId: res.locals.requestId,
    });
    res.locals.auditRecorded = true;
  };
  function issueSession(kind: "user" | "staff", subjectId: number) {
    const token = randomBytes(32).toString("base64url");
    const hours = kind === "staff" ? 12 : 24 * 7;
    db.prepare(
      "INSERT INTO sessions(token_hash,kind,subject_id,expires_at,create_user_id,update_user_id) VALUES(?,?,?,?,?,?)",
    ).run(
      hash(token),
      kind,
      subjectId,
      new Date(Date.now() + hours * 3600000).toISOString(),
      subjectId,
      subjectId,
    );
    return token;
  }
  function readToken(req: Request, kind: "user" | "staff") {
    return kind === "user"
      ? req.headers.authorization?.replace(/^Bearer /, "")
      : req.headers.cookie
          ?.split(";")
          .map((s) => s.trim())
          .find((s) => s.startsWith("jyj_admin="))
          ?.slice(10);
  }
  function authenticate(kind: "user" | "staff") {
    return (req: Request, res: Response, next: NextFunction) => {
      const token = readToken(req, kind);
      if (!token) throw new HttpError(401, "LOGIN_REQUIRED", "请先登录");
      const session = db
        .prepare(
          "SELECT subject_id FROM sessions WHERE token_hash=? AND kind=? AND is_deleted=0 AND expires_at>?",
        )
        .get(hash(token), kind, new Date().toISOString()) as
        { subject_id: number } | undefined;
      if (!session)
        throw new HttpError(401, "SESSION_EXPIRED", "登录已失效，请重新登录");
      const subject = db
        .prepare(
          `SELECT * FROM ${kind === "user" ? "users" : "staff"} WHERE id=? AND is_deleted=0`,
        )
        .get(session.subject_id) as UserRow | StaffRow | undefined;
      if (!subject || (kind === "staff" && !(subject as StaffRow).enabled))
        throw new HttpError(
          401,
          "ACCOUNT_DISABLED",
          "账号已停用，请联系管理员",
        );
      res.locals[kind] = subject;
      next();
    };
  }
  function permit(permission: Permission) {
    return (_req: Request, res: Response, next: NextFunction) => {
      if (!JSON.parse(res.locals.staff.permissions).includes(permission)) {
        audit(
          res,
          "permission.denied",
          "permission",
          permission,
          "当前账号没有该功能权限",
          "denied",
        );
        throw new HttpError(403, "FORBIDDEN", "当前账号没有该功能权限");
      }
      next();
    };
  }
  function ownUser(req: Request, res: Response, next: NextFunction) {
    const id = idSchema.parse(req.params.id);
    if (id !== res.locals.user.id) {
      audit(
        res,
        "profile.denied",
        "user",
        id,
        "不能访问他人的个人资料",
        "denied",
      );
      throw new HttpError(404, "NOT_FOUND", "资料不存在或无权访问");
    }
    next();
  }
  const userAuth = authenticate("user");
  const staffAuth = authenticate("staff");
  const limits = new Map<string, { count: number; until: number }>();
  const limitLogin = (req: Request, _res: Response, next: NextFunction) => {
    const key = req.ip || "local";
    const now = Date.now();
    for (const [k, v] of limits) if (v.until <= now) limits.delete(k);
    const value = limits.get(key) || { count: 0, until: now + 15 * 60000 };
    if (value.count >= 20)
      throw new HttpError(429, "TOO_MANY_ATTEMPTS", "登录尝试过多，请稍后再试");
    value.count++;
    limits.set(key, value);
    next();
  };

  app.get("/api/public/context", (_req, res) =>
    res.json({
      brandName: "清欣简妍肌",
      devAuth: options.devAuth,
      wechatConfigured: Boolean(options.appId && options.appSecret),
    }),
  );
  const loginUser = (openid: string, res: Response) => {
    return transaction(db, () => {
      let user = db
        .prepare("SELECT * FROM users WHERE openid=? AND is_deleted=0")
        .get(openid) as UserRow | undefined;
      if (!user) {
        const result = db
          .prepare("INSERT INTO users(openid) VALUES(?)")
          .run(openid);
        user = db
          .prepare("SELECT * FROM users WHERE id=? AND is_deleted=0")
          .get(result.lastInsertRowid) as unknown as UserRow;
      }
      res.locals.user = user;
      const token = issueSession("user", user.id);
      audit(res, "user.login", "user", user.id, "用户登录");
      return { token, user: publicUser(user) };
    });
  };
  app.post("/api/auth/wechat", limitLogin, async (req, res) => {
    const { code } = z
      .object({ code: z.string().min(1).max(256) })
      .strict()
      .parse(req.body);
    try {
      const openid = await (options.exchangeCode
        ? options.exchangeCode(code)
        : exchangeWechatCode(
            code,
            options.appId || "",
            options.appSecret || "",
          ));
      res.json(loginUser(openid, res));
    } catch (error) {
      if (!(error instanceof WechatError)) throw error;
      audit(res, "user.login", "user", "wechat", error.message, "failure");
      throw new HttpError(502, "WECHAT_LOGIN_FAILED", error.message);
    }
  });
  app.post("/api/auth/dev", limitLogin, (req, res) => {
    if (!options.devAuth) throw new HttpError(404, "NOT_FOUND", "接口不存在");
    const { account } = z
      .object({ account: z.string().regex(/^[a-z0-9_-]{3,40}$/) })
      .strict()
      .parse(req.body);
    res.json(loginUser(`dev:${account}`, res));
  });
  app.get("/api/me", userAuth, (_req, res) =>
    res.json(publicUser(res.locals.user)),
  );
  app.get("/api/users/:id", userAuth, ownUser, (_req, res) =>
    res.json(publicUser(res.locals.user)),
  );
  const updateProfile = (req: Request, res: Response) => {
    const value = profileSchema.parse(req.body),
      id = res.locals.user.id;
    transaction(db, () => {
      db.prepare(
        "UPDATE users SET nickname=?,phone=?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND is_deleted=0",
      ).run(value.nickname, value.phone, id, id);
      audit(res, "profile.update", "user", id, "本人更新昵称及联系电话");
    });
    res.json(
      publicUser(
        db
          .prepare("SELECT * FROM users WHERE id=? AND is_deleted=0")
          .get(id) as unknown as UserRow,
      ),
    );
  };
  app.patch("/api/me", userAuth, updateProfile);
  app.patch("/api/users/:id", userAuth, ownUser, updateProfile);
  app.post("/api/auth/logout", userAuth, (req, res) => {
    db.prepare(
      "UPDATE sessions SET is_deleted=1,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE token_hash=? AND is_deleted=0",
    ).run(res.locals.user.id, hash(readToken(req, "user")!));
    audit(res, "user.logout", "user", res.locals.user.id, "用户退出登录");
    res.json({ ok: true });
  });

  // 后台 cookie 会话只接受同源写入，避免跨站请求借用管理员登录状态。
  app.use("/api/admin", (req, _res, next) => {
    if (!["GET", "HEAD", "OPTIONS"].includes(req.method)) {
      let sameOrigin = false;
      try {
        sameOrigin = new URL(req.headers.origin || "").host === req.get("host");
      } catch {
        /* 缺少有效来源时拒绝写入。 */
      }
      if (!sameOrigin || req.headers["sec-fetch-site"] === "cross-site")
        throw new HttpError(403, "ORIGIN_DENIED", "请从管理后台页面操作");
    }
    next();
  });
  const dummyPassword = passwordHash(randomBytes(24).toString("hex"));
  app.post("/api/admin/login", limitLogin, (req, res) => {
    const value = z
      .object({
        username: z.string().trim().min(1).max(32),
        password: z.string().min(1).max(128),
      })
      .strict()
      .parse(req.body);
    const staff = db
      .prepare("SELECT * FROM staff WHERE username=? AND is_deleted=0")
      .get(value.username) as StaffRow | undefined;
    const valid = checkPassword(
      value.password,
      staff?.password_hash || dummyPassword,
    );
    if (!staff || !valid || !staff.enabled) {
      audit(
        res,
        "staff.login",
        "staff",
        staff?.id || "unknown",
        "账号或密码错误，或账号已停用",
        "failure",
      );
      throw new HttpError(
        401,
        "INVALID_CREDENTIALS",
        "账号或密码错误，或账号已停用",
      );
    }
    res.locals.staff = staff;
    transaction(db, () => {
      const token = issueSession("staff", staff.id);
      res.cookie("jyj_admin", token, {
        httpOnly: true,
        secure: !!options.production,
        sameSite: "strict",
        path: "/api/admin",
        maxAge: 12 * 3600000,
      });
      audit(res, "staff.login", "staff", staff.id, "工作人员登录");
    });
    res.json(publicStaff(staff));
  });
  app.get("/api/admin/me", staffAuth, (_req, res) =>
    res.json(publicStaff(res.locals.staff)),
  );
  app.post("/api/admin/logout", staffAuth, (req, res) => {
    db.prepare(
      "UPDATE sessions SET is_deleted=1,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE token_hash=? AND is_deleted=0",
    ).run(res.locals.staff.id, hash(readToken(req, "staff")!));
    res.clearCookie("jyj_admin", { path: "/api/admin" });
    audit(
      res,
      "staff.logout",
      "staff",
      res.locals.staff.id,
      "工作人员退出登录",
    );
    res.json({ ok: true });
  });
  app.get(
    "/api/admin/permissions",
    staffAuth,
    permit("staff:manage"),
    (_req, res) => res.json(permissions),
  );
  app.get(
    "/api/admin/staff",
    staffAuth,
    permit("staff:manage"),
    (_req, res) => {
      res.json(
        db
          .prepare("SELECT * FROM staff WHERE is_deleted=0 ORDER BY id DESC")
          .all()
          .map((row) => publicStaff(row as unknown as StaffRow)),
      );
    },
  );
  app.post(
    "/api/admin/staff",
    staffAuth,
    permit("staff:manage"),
    (req, res) => {
      const value = staffSchema.parse(req.body);
      if (
        db
          .prepare("SELECT id FROM staff WHERE username=? AND is_deleted=0")
          .get(value.username)
      )
        throw new HttpError(409, "USERNAME_EXISTS", "账号已存在");
      const id = transaction(db, () => {
        const id = createStaff(
          db,
          value.username,
          value.displayName,
          value.password,
          value.permissions,
          res.locals.staff.id,
        );
        audit(res, "staff.create", "staff", id, value.reason);
        return id;
      });
      res
        .status(201)
        .json(
          publicStaff(
            db
              .prepare("SELECT * FROM staff WHERE id=? AND is_deleted=0")
              .get(id) as unknown as StaffRow,
          ),
        );
    },
  );
  function findStaff(id: number) {
    const staff = db
      .prepare("SELECT * FROM staff WHERE id=? AND is_deleted=0")
      .get(id) as StaffRow | undefined;
    if (!staff) throw new HttpError(404, "NOT_FOUND", "工作人员不存在");
    return staff;
  }
  function protectManager(staff: StaffRow, losingManager: boolean) {
    if (
      losingManager &&
      staff.enabled &&
      JSON.parse(staff.permissions).includes("staff:manage")
    ) {
      const count = db
        .prepare(
          "SELECT count(*) AS count FROM staff WHERE is_deleted=0 AND enabled=1 AND EXISTS (SELECT 1 FROM json_each(staff.permissions) WHERE value='staff:manage')",
        )
        .get() as { count: number };
      if (count.count <= 1)
        throw new HttpError(
          409,
          "LAST_MANAGER",
          "至少保留一个启用的账号管理员",
        );
    }
  }
  app.patch(
    "/api/admin/staff/:id",
    staffAuth,
    permit("staff:manage"),
    (req, res) => {
      const id = idSchema.parse(req.params.id);
      const value = z
        .object({
          displayName: z.string().trim().min(1).max(30),
          permissions: grantsSchema,
          enabled: z.boolean(),
          reason: z.string().trim().min(1).max(200),
        })
        .strict()
        .parse(req.body);
      transaction(db, () => {
        const staff = findStaff(id);
        protectManager(
          staff,
          !value.enabled || !value.permissions.includes("staff:manage"),
        );
        db.prepare(
          "UPDATE staff SET display_name=?,permissions=?,enabled=?,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND is_deleted=0",
        ).run(
          value.displayName,
          JSON.stringify(value.permissions),
          Number(value.enabled),
          res.locals.staff.id,
          id,
        );
        if (!value.enabled)
          db.prepare(
            "UPDATE sessions SET is_deleted=1,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE kind='staff' AND subject_id=? AND is_deleted=0",
          ).run(res.locals.staff.id, id);
        audit(
          res,
          "staff.update",
          "staff",
          id,
          `${value.reason}；权限：${value.permissions.join("、") || "无"}；${value.enabled ? "启用" : "停用"}`,
        );
      });
      res.json(publicStaff(findStaff(id)));
    },
  );
  app.post(
    "/api/admin/staff/:id/remove",
    staffAuth,
    permit("staff:manage"),
    (req, res) => {
      const id = idSchema.parse(req.params.id);
      const { reason } = z
        .object({ reason: z.string().trim().min(1).max(200) })
        .strict()
        .parse(req.body);
      transaction(db, () => {
        protectManager(findStaff(id), true);
        db.prepare(
          "UPDATE staff SET is_deleted=1,enabled=0,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND is_deleted=0",
        ).run(res.locals.staff.id, id);
        db.prepare(
          "UPDATE sessions SET is_deleted=1,update_user_id=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE kind='staff' AND subject_id=? AND is_deleted=0",
        ).run(res.locals.staff.id, id);
        audit(res, "staff.remove", "staff", id, reason);
      });
      res.json({ ok: true });
    },
  );
  app.get("/api/admin/users", staffAuth, permit("users:read"), (req, res) => {
    const page = z.coerce
      .number()
      .int()
      .min(1)
      .max(100000)
      .default(1)
      .parse(req.query.page);
    const items = db
      .prepare(
        "SELECT * FROM users WHERE is_deleted=0 ORDER BY id DESC LIMIT 30 OFFSET ?",
      )
      .all((page - 1) * 30)
      .map((row) => {
        const user = publicUser(row as unknown as UserRow);
        return {
          ...user,
          phone: user.phone
            ? `${user.phone.slice(0, 3)}****${user.phone.slice(-4)}`
            : "",
        };
      });
    const { total } = db
      .prepare("SELECT count(*) AS total FROM users WHERE is_deleted=0")
      .get() as { total: number };
    res.json({ items, page, total });
  });
  app.get("/api/admin/audit", staffAuth, permit("audit:read"), (req, res) => {
    const query = z
      .object({
        objectType: z.enum(["user", "staff", "permission"]).optional(),
        objectId: z.string().max(40).optional(),
        page: z.coerce.number().int().min(1).max(100000).default(1),
      })
      .strict()
      .parse(req.query);
    const where = ["is_deleted=0"],
      params: (string | number)[] = [];
    if (query.objectType) {
      where.push("object_type=?");
      params.push(query.objectType);
    }
    if (query.objectId) {
      where.push("object_id=?");
      params.push(query.objectId);
    }
    const clause = where.join(" AND ");
    const items = db
      .prepare(
        `SELECT id,actor_kind,actor_id,action,object_type,object_id,result,reason,request_id,create_time FROM audit_events WHERE ${clause} ORDER BY id DESC LIMIT 30 OFFSET ?`,
      )
      .all(...params, (query.page - 1) * 30);
    const { total } = db
      .prepare(`SELECT count(*) AS total FROM audit_events WHERE ${clause}`)
      .get(...params) as { total: number };
    res.json({ items, page: query.page, total });
  });
  const business = {
    db,
    userAuth,
    staffAuth,
    permit,
    audit,
    optionalUser: (req: Request, res: Response, next: NextFunction) =>
      req.headers.authorization ? userAuth(req, res, next) : next(),
  };
  mountCatalog(app, business);
  mountOrders(app, business);
  mountPayments(app, business, options.payments);
  mountFulfillment(app, business);
  mountMembership(app, business);
  mountWallet(app, business);
  mountRewards(app, business);
  let refundChannel = options.refunds?.channel;
  if (!refundChannel) {
    try {
      const channel =
        options.payments?.adapter ??
        paymentAdapterFromEnv(options.payments?.env);
      if (channel && "refund" in channel && "queryRefund" in channel)
        refundChannel = channel as PayChannel;
    } catch {
      /* 商户配置无效时由支付能力接口明确提示，禁止伪造退款。 */
    }
  }
  mountAftersales(app, business, {
    channel: refundChannel,
    returnPoints: (db, orderId, refundId, amount, actor) =>
      restoreOrderPoints(db, orderId, amount, "refund:" + refundId, actor),
    reverseRewards: reconcileRewardReversals,
    packageIdentity: applyPackageRefundIdentity,
    ...options.refunds,
  });
  mountCashRefunds(app, business);
  app.use("/api", (_req, _res) => {
    throw new HttpError(404, "NOT_FOUND", "接口不存在");
  });
  app.use("/assets", express.static(resolve("miniprogram/assets")));
  const dist = resolve("dist");
  if (existsSync(dist)) {
    app.use(express.static(dist));
    app.get(["/", "/admin"], (_req, res) =>
      res.sendFile(resolve(dist, "index.html")),
    );
  }
  app.use(
    (error: unknown, req: Request, res: Response, _next: NextFunction) => {
      if (
        error instanceof HttpError &&
        !res.locals.auditRecorded &&
        (res.locals.staff || res.locals.user) &&
        !["GET", "HEAD"].includes(req.method)
      ) {
        audit(
          res,
          "request.denied",
          "permission",
          `${req.method} ${req.path}`,
          error.message,
          "denied",
        );
      }
      if (error instanceof z.ZodError) {
        res.status(400).json({
          code: "VALIDATION_ERROR",
          message: "填写内容有误，请检查字段和格式",
          requestId: res.locals.requestId,
        });
        return;
      }
      if (error instanceof HttpError) {
        res.status(error.status).json({
          code: error.code,
          message: error.message,
          requestId: res.locals.requestId,
        });
        return;
      }
      if ((error as { type?: string }).type === "entity.parse.failed") {
        res.status(400).json({
          code: "INVALID_JSON",
          message: "请求内容格式有误",
          requestId: res.locals.requestId,
        });
        return;
      }
      console.error(
        JSON.stringify({
          message: "请求处理失败",
          requestId: res.locals.requestId,
          error:
            error instanceof Error
              ? { name: error.name, message: error.message, stack: error.stack }
              : "UnknownError",
          method: req.method,
          path: req.path,
        }),
      );
      res.status(500).json({
        code: "INTERNAL_ERROR",
        message: "处理失败，请稍后重试",
        requestId: res.locals.requestId,
      });
    },
  );
  return app;
}
