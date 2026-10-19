import { test, type TestContext } from "node:test";
import assert from "node:assert/strict";
import express, {
  type RequestHandler,
  type ErrorRequestHandler,
} from "express";
import { once } from "node:events";
import {
  createHash,
  createCipheriv,
  generateKeyPairSync,
  sign,
  verify,
  randomUUID,
} from "node:crypto";
import { commerceFixture } from "./commerce-fixture.ts";
import { recordAudit, transaction } from "./db.ts";
import { HttpError, type BusinessContext } from "./http.ts";
import {
  migratePayments,
  mountPayments,
  settlePaid,
  reconcilePayments,
  assertNoUnresolvedPayment,
  type PaymentAdapter,
  type ChannelTrade,
} from "./payments.ts";
import {
  migrateFulfillment,
  mountFulfillment,
  shippableLines,
} from "./fulfillment.ts";
import { ownedOrder } from "./orders.ts";
import {
  WechatPayV3,
  verifyWechatSignature,
  type WechatPayConfig,
} from "./wechat-pay.ts";
class TestChannel implements PaymentAdapter {
  appId = "test-app";
  mchId = "test-mch";
  creates = 0;
  closes = 0;
  createError = false;
  queryError = false;
  pause: Promise<void> | undefined;
  trades = new Map<string, ChannelTrade>();
  async create(v: { number: string; cents: number; openid: string }) {
    this.creates++;
    this.trades.set(v.number, {
      out_trade_no: v.number,
      appid: this.appId,
      mchid: this.mchId,
      trade_state: "NOTPAY",
      amount: { total: v.cents, currency: "CNY" },
      payer: { openid: v.openid },
    });
    if (this.pause) await this.pause;
    if (this.createError) throw new Error("测试网络中断");
    return {
      timeStamp: "1",
      nonceStr: "test-nonce",
      package: "prepay_id=test",
      signType: "RSA" as const,
      paySign: "test",
    };
  }
  async query(number: string) {
    if (this.queryError) throw new Error("测试查询超时");
    return this.trades.get(number)!;
  }
  async close(number: string) {
    this.closes++;
    this.trades.get(number)!.trade_state = "CLOSED";
  }
  notification(raw: Buffer) {
    return JSON.parse(raw.toString()) as ChannelTrade;
  }
  paid() {
    const t = [...this.trades.values()].at(-1)!;
    t.trade_state = "SUCCESS";
    t.transaction_id = "wx-" + randomUUID();
    return t;
  }
}
async function setup(
  t: TestContext,
  channel: TestChannel | null = new TestChannel(),
) {
  const f = await commerceFixture(t),
    app = express();
  migratePayments(f.db);
  migrateFulfillment(f.db);
  app.use((req, res, next) => {
    res.locals.requestId = randomUUID();
    next();
  });
  app.use(
    express.json({
      verify: (req, _res, buf) => {
        (req as typeof req & { rawBody: Buffer }).rawBody = Buffer.from(buf);
      },
    }),
  );
  const auth =
    (kind: "user" | "staff"): RequestHandler =>
    (req, res, next) => {
      const token =
        kind === "user"
          ? req.headers.authorization?.slice(7)
          : req.headers.cookie?.split("=")[1];
      const s = token
        ? f.db
            .prepare(
              "SELECT subject_id FROM sessions WHERE token_hash=? AND kind=? AND is_deleted=0",
            )
            .get(createHash("sha256").update(token).digest("hex"), kind)
        : null;
      if (!s) throw new HttpError(401, "LOGIN_REQUIRED", "请登录");
      res.locals[kind] = f.db
        .prepare(
          `SELECT * FROM ${kind === "user" ? "users" : "staff"} WHERE id=? AND is_deleted=0`,
        )
        .get(s.subject_id);
      next();
    };
  const ctx: BusinessContext = {
    db: f.db,
    userAuth: auth("user"),
    staffAuth: auth("staff"),
    optionalUser: auth("user"),
    permit: (p) => (_req, res, next) => {
      if (!JSON.parse(res.locals.staff.permissions).includes(p))
        throw new HttpError(403, "FORBIDDEN", "无权限");
      next();
    },
    audit: (res, action, objectType, objectId, reason, result = "success") => {
      recordAudit(f.db, {
        actorKind: res.locals.staff ? "staff" : "user",
        actorId: res.locals.staff?.id ?? res.locals.user?.id ?? 0,
        action,
        objectType,
        objectId,
        reason,
        result,
        requestId: res.locals.requestId,
      });
    },
  };
  mountPayments(app, ctx, { adapter: channel ?? undefined, env: {} });
  mountFulfillment(app, ctx);
  app.use(((e, req, res, next) => {
    res
      .status(e.status ?? 409)
      .json({ code: e.code ?? "ERROR", message: e.message });
  }) as ErrorRequestHandler);
  const server = app.listen(0, "127.0.0.1");
  await once(server, "listening");
  const addr = server.address();
  assert.ok(addr && typeof addr !== "string");
  t.after(async () => {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });
  const call = async (
    path: string,
    body?: unknown,
    credentials: { token?: string; cookie?: string } = { token: f.u.token },
    method = body ? "POST" : "GET",
  ) => {
    const response = await fetch(`http://127.0.0.1:${addr.port}/api${path}`, {
      method,
      headers: {
        ...(body ? { "Content-Type": "application/json" } : {}),
        ...(credentials.token
          ? { Authorization: "Bearer " + credentials.token }
          : {}),
        ...(credentials.cookie ? { Cookie: credentials.cookie } : {}),
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    return { status: response.status, data: await response.json() };
  };
  return { ...f, pay: call, channel: channel! };
}

test("微信支付缺配置明确不可付款且不锁订单", async (t) => {
  const f = await setup(t, null),
    o = await f.order();
  assert.equal(
    (await f.pay("/payments/capabilities")).data.wechatAvailable,
    false,
  );
  const pay = await f.pay(`/orders/${o.id}/pay/wechat`, {});
  assert.equal(pay.status, 503);
  assert.equal(pay.data.code, "PAYMENT_NOT_CONFIGURED");
  assert.equal(ownedOrder(f.db, o.id).payment_state, "unpaid");
});
test("服务端结算同步事务、库存扣减和重复通知幂等，权限与本人隔离", async (t) => {
  const f = await setup(t),
    o = await f.order(3);
  assert.throws(
    () => settlePaid(f.db, ownedOrder(f.db, o.id), "points", "points-1"),
    /事务/,
  );
  const [a, b] = await Promise.all([
    f.pay(`/orders/${o.id}/pay/wechat`, {}),
    f.pay(`/orders/${o.id}/pay/wechat`, {}),
  ]);
  assert.ok([a.status, b.status].includes(200));
  assert.equal(f.channel.creates, 1);
  assert.equal(ownedOrder(f.db, o.id).payment_state, "paying");
  assert.equal(
    f.db.prepare("SELECT stock FROM skus WHERE id=?").get(f.p.skus[0].id)
      ?.stock,
    10,
  );
  const paid = f.channel.paid();
  assert.equal((await f.pay("/payments/wechat/notify", paid, {})).status, 200);
  assert.equal((await f.pay("/payments/wechat/notify", paid, {})).status, 200);
  assert.equal(
    f.db.prepare("SELECT COUNT(*) n FROM payments WHERE order_id=?").get(o.id)
      ?.n,
    1,
  );
  assert.equal(
    f.db.prepare("SELECT out_trade_no FROM payments WHERE order_id=?").get(o.id)
      ?.out_trade_no,
    paid.out_trade_no,
  );
  assert.deepEqual(
    {
      ...f.db
        .prepare("SELECT stock,reserved FROM skus WHERE id=?")
        .get(f.p.skus[0].id),
    },
    { stock: 7, reserved: 0 },
  );
  const other = await f.user("principal");
  assert.equal(
    (await f.pay(`/orders/${o.id}/payment`, undefined, { token: other.token }))
      .status,
    404,
  );
  const operator = await f.admin("operator");
  assert.equal(
    (
      await f.pay(`/admin/orders/${o.id}/payment`, undefined, {
        cookie: operator,
      })
    ).status,
    403,
  );
  assert.equal(
    (await f.pay(`/orders/${o.id}/payment`)).data.receipt.transaction_id,
    paid.transaction_id,
  );
});
test("金额及商户不匹配不能结算，通知失败保留可查事件和库存", async (t) => {
  const f = await setup(t),
    o = await f.order();
  await f.pay(`/orders/${o.id}/pay/wechat`, {});
  const paid = f.channel.paid();
  const bad = {
    ...paid,
    amount: { total: o.total_cents + 1, currency: "CNY" },
  };
  assert.equal((await f.pay("/payments/wechat/notify", bad, {})).status, 409);
  assert.equal(
    (await f.pay("/payments/wechat/notify", { ...paid, mchid: "other" }, {}))
      .status,
    409,
  );
  assert.equal(ownedOrder(f.db, o.id).payment_state, "unknown");
  assert.equal(
    f.db
      .prepare("SELECT stock,reserved FROM skus WHERE id=?")
      .get(f.p.skus[0].id)?.reserved,
    3,
  );
  assert.equal((await f.pay(`/orders/${o.id}/payment/query`, {})).status, 200);
  assert.equal(ownedOrder(f.db, o.id).payment_state, "paid");
  assert.ok(
    (
      await f.pay(`/admin/orders/${o.id}/payment`, undefined, {
        cookie: f.cookie,
      })
    ).data.events.some((e: { result: string }) => e.result === "failure"),
  );
});
test("发起超时不切换支付，不允许替代订单绕过；过期后的真实成功照常核验", async (t) => {
  const f = await setup(t),
    o = await f.order(3);
  const alternative = await f.order(1);
  f.channel.createError = true;
  assert.equal((await f.pay(`/orders/${o.id}/pay/wechat`, {})).status, 502);
  assert.throws(
    () =>
      transaction(f.db, () =>
        settlePaid(f.db, ownedOrder(f.db, o.id), "points", "points-retry"),
      ),
    /未决|未确定|待确认/,
  );
  assert.equal(
    (await f.pay(`/orders/${alternative.id}/pay/wechat`, {})).status,
    409,
  );
  assert.equal(
    (
      await f.call(`/orders/${o.id}/cancel`, {
        method: "POST",
        token: f.u.token,
      })
    ).status,
    409,
  );
  f.db
    .prepare(
      "UPDATE orders SET expires_at='2020-01-01T00:00:00.000Z' WHERE id=?",
    )
    .run(o.id);
  f.channel.paid();
  assert.equal((await f.pay(`/orders/${o.id}/payment/query`, {})).status, 200);
  assert.equal(ownedOrder(f.db, o.id).payment_state, "paid");
});
test("关闭与到账竞争以真实到账为准；查失败持久保留，批量补偿不虚报", async (t) => {
  const f = await setup(t),
    o = await f.order();
  await f.pay(`/orders/${o.id}/pay/wechat`, {});
  f.channel.queryError = true;
  assert.equal((await f.pay(`/orders/${o.id}/payment/close`, {})).status, 409);
  assert.equal(ownedOrder(f.db, o.id).status, "pending");
  assert.equal(
    f.db
      .prepare("SELECT close_requested FROM payment_attempts WHERE order_id=?")
      .get(o.id)?.close_requested,
    1,
  );
  const sweep = await reconcilePayments(f.db, f.channel);
  assert.equal(sweep.results[0].ok, false);
  f.channel.queryError = false;
  f.channel.paid();
  await reconcilePayments(f.db, f.channel);
  assert.equal(ownedOrder(f.db, o.id).status, "paid");
  assert.equal(f.channel.closes, 0);
});
test("确认渠道关闭后才释放预占，查询并发不能越过发起租约", async (t) => {
  const f = await setup(t),
    o = await f.order();
  let release!: () => void;
  f.channel.pause = new Promise<void>((r) => (release = r));
  const paying = f.pay(`/orders/${o.id}/pay/wechat`, {});
  while (!f.channel.creates) await new Promise((r) => setImmediate(r));
  assert.equal((await f.pay(`/orders/${o.id}/payment/close`, {})).status, 409);
  assert.equal(f.channel.closes, 0);
  release();
  await paying;
  await f.pay(`/orders/${o.id}/payment/query`, {});
  assert.equal(ownedOrder(f.db, o.id).status, "closed");
  assert.deepEqual(
    {
      ...f.db
        .prepare("SELECT stock,reserved FROM skus WHERE id=?")
        .get(f.p.skus[0].id),
    },
    { stock: 10, reserved: 0 },
  );
});
test("关闭成功后异常迟到的到账保留原流水和补偿证据，不能伪造本地成功", async (t) => {
  const f = await setup(t),
    o = await f.order();
  await f.pay(`/orders/${o.id}/pay/wechat`, {});
  await f.pay(`/orders/${o.id}/payment/close`, {});
  const paid = f.channel.paid();
  assert.equal((await f.pay("/payments/wechat/notify", paid, {})).status, 409);
  assert.equal(ownedOrder(f.db, o.id).status, "closed");
  assert.equal(
    f.db
      .prepare("SELECT transaction_id FROM payment_attempts WHERE order_id=?")
      .get(o.id)?.transaction_id,
    paid.transaction_id,
  );
  assert.equal(
    f.db.prepare("SELECT COUNT(*) n FROM payments WHERE order_id=?").get(o.id)
      ?.n,
    0,
  );
});
test("发货校验剩余未出库量、物流纠正、按包裹收货重复幂等及部分退款", async (t) => {
  const f = await setup(t),
    o = await f.order(3),
    line = o.lines[0];
  const ship = {
    carrier: "顺丰速运",
    tracking: "SF123456",
    lines: [{ lineId: line.id, quantity: 1 }],
    requestKey: "ship-first-2026",
    reason: "品牌仓出库",
  };
  assert.equal(
    (await f.pay(`/admin/orders/${o.id}/shipments`, ship, { cookie: f.cookie }))
      .status,
    409,
  );
  await f.pay(`/orders/${o.id}/pay/wechat`, {});
  f.channel.paid();
  await f.pay(`/orders/${o.id}/payment/query`, {});
  const s = await f.pay(`/admin/orders/${o.id}/shipments`, ship, {
    cookie: f.cookie,
  });
  assert.equal(s.status, 200);
  assert.equal(
    (await f.pay(`/admin/orders/${o.id}/shipments`, ship, { cookie: f.cookie }))
      .data.id,
    s.data.id,
  );
  await f.pay(
    `/admin/shipments/${s.data.id}`,
    { carrier: "中通快递", tracking: "ZT123456", reason: "纠正录入单号" },
    { cookie: f.cookie },
    "PUT",
  );
  assert.equal(
    f.db
      .prepare(
        "SELECT old_tracking FROM shipment_corrections WHERE shipment_id=?",
      )
      .get(s.data.id)?.old_tracking,
    "SF123456",
  );
  f.db
    .prepare(
      "UPDATE order_lines SET refunded_qty=1,refunded_unshipped=1,refund_held=1,held_unshipped=1 WHERE id=?",
    )
    .run(line.id);
  assert.equal(shippableLines(f.db, o.id)[0].remaining, 0);
  assert.equal(
    (await f.pay(`/orders/${o.id}/shipments/${s.data.id}/receive`, {})).status,
    409,
  );
  f.db
    .prepare("UPDATE order_lines SET refund_held=0,held_unshipped=0 WHERE id=?")
    .run(line.id);
  assert.equal(
    (await f.pay(`/orders/${o.id}/shipments/${s.data.id}/receive`, {})).status,
    200,
  );
  assert.equal(
    (await f.pay(`/orders/${o.id}/shipments/${s.data.id}/receive`, {})).status,
    200,
  );
  assert.equal(ownedOrder(f.db, o.id).status, "shipped");
  // 已发货退货：refunded_qty 包含已出库 1 件，不再抵减未出库剩余量。
  f.db.prepare("UPDATE order_lines SET refunded_qty=2 WHERE id=?").run(line.id);
  assert.equal(shippableLines(f.db, o.id)[0].remaining, 1);
  const second = await f.pay(
    `/admin/orders/${o.id}/shipments`,
    { ...ship, tracking: "SF789012", requestKey: "ship-second-2026" },
    { cookie: f.cookie },
  );
  assert.equal(second.status, 200);
  // 该例已退数量变化后，剩余交付按实际剩余商品核算。
  const received = await f.pay(
    `/orders/${o.id}/shipments/${second.data.id}/receive`,
    {},
  );
  assert.equal(received.status, 200);
  assert.equal(received.data.status, "completed");
});

const keys = generateKeyPairSync("rsa", {
  modulusLength: 2048,
  publicKeyEncoding: { type: "spki", format: "pem" },
  privateKeyEncoding: { type: "pkcs8", format: "pem" },
});
const config: WechatPayConfig = {
  appId: "wx-app",
  mchId: "merchant",
  serial: "merchant-serial",
  privateKey: keys.privateKey,
  platformSerial: "platform-serial",
  platformPublicKey: keys.publicKey,
  apiV3Key: "12345678901234567890123456789012",
  notifyUrl: "https://merchant.example/api/payments/wechat/notify",
};
function signature(
  raw: Buffer,
  timestamp = String(Math.floor(Date.now() / 1000)),
) {
  const salt = "test-salt";
  return {
    "wechatpay-timestamp": timestamp,
    "wechatpay-nonce": salt,
    "wechatpay-serial": config.platformSerial,
    "wechatpay-signature": sign(
      "RSA-SHA256",
      Buffer.concat([
        Buffer.from(`${timestamp}\n${salt}\n`),
        raw,
        Buffer.from("\n"),
      ]),
      keys.privateKey,
    ).toString("base64"),
  };
}
test("真实V3算法：请求RSA签名、小程序签名、响应验签、AES-GCM通知和重放/篡改拒绝", async () => {
  let calls = 0;
  const channel = new WechatPayV3(config, (async (url, init) => {
    calls++;
    const headers = init!.headers as Record<string, string>,
      auth = headers.Authorization;
    const timestamp = /timestamp="(.*?)"/.exec(auth)![1],
      salt = /nonce_str="(.*?)"/.exec(auth)![1],
      sig = /signature="(.*?)"/.exec(auth)![1];
    const u = new URL(String(url)),
      msg = `${init!.method}\n${u.pathname + u.search}\n${timestamp}\n${salt}\n${init!.body ?? ""}\n`;
    assert.equal(
      verify(
        "RSA-SHA256",
        Buffer.from(msg),
        keys.publicKey,
        Buffer.from(sig, "base64"),
      ),
      true,
    );
    const raw = Buffer.from(JSON.stringify({ prepay_id: "wx-prepay" }));
    return new Response(raw, { status: 200, headers: signature(raw) });
  }) as typeof fetch);
  const params = await channel.create({
    number: "P123",
    cents: 100,
    openid: "openid",
    description: "商品订单",
    expiresAt: new Date(Date.now() + 600000).toISOString(),
  });
  assert.equal(calls, 1);
  assert.equal(
    verify(
      "RSA-SHA256",
      Buffer.from(
        `${config.appId}\n${params.timeStamp}\n${params.nonceStr}\n${params.package}\n`,
      ),
      keys.publicKey,
      Buffer.from(params.paySign, "base64"),
    ),
    true,
  );
  const trade = {
    out_trade_no: "P123",
    appid: config.appId,
    mchid: config.mchId,
    trade_state: "SUCCESS",
    transaction_id: "transaction",
    amount: { total: 100, currency: "CNY" },
    payer: { openid: "openid" },
  };
  const nonce = "123456789012",
    aad = "transaction";
  const cipher = createCipheriv(
    "aes-256-gcm",
    Buffer.from(config.apiV3Key),
    Buffer.from(nonce),
  );
  cipher.setAAD(Buffer.from(aad));
  const ciphertext = Buffer.concat([
    cipher.update(JSON.stringify(trade)),
    cipher.final(),
    cipher.getAuthTag(),
  ]).toString("base64");
  const raw = Buffer.from(
    JSON.stringify({
      event_type: "TRANSACTION.SUCCESS",
      resource_type: "encrypt-resource",
      resource: {
        algorithm: "AEAD_AES_256_GCM",
        nonce,
        associated_data: aad,
        ciphertext,
      },
    }),
  );
  assert.deepEqual(channel.notification(raw, signature(raw)), trade);
  assert.throws(
    () =>
      channel.notification(Buffer.from(raw.toString() + " "), signature(raw)),
    /验签失败/,
  );
  assert.throws(
    () => verifyWechatSignature(raw, signature(raw, "1"), config),
    /签名时间/,
  );
  assert.throws(
    () =>
      verifyWechatSignature(
        raw,
        { ...signature(raw), "wechatpay-serial": "foreign" },
        config,
      ),
    /证书/,
  );
});
test("退款真实请求沿用原交易和退款单号、签名验签与结果标准化", async () => {
  const refund = {
    out_trade_no: "P123",
    out_refund_no: "R123",
    refund_id: "wx-refund",
    status: "SUCCESS",
    amount: { total: 1000, refund: 300, currency: "CNY" },
  };
  let body: any;
  const channel = new WechatPayV3(config, (async (url, init) => {
    body = init?.body ? JSON.parse(String(init.body)) : undefined;
    const raw = Buffer.from(JSON.stringify(refund));
    return new Response(raw, { headers: signature(raw) });
  }) as typeof fetch);
  const result = await channel.refund({
    outTradeNo: "P123",
    transactionId: "WX123",
    outRefundNo: "R123",
    totalCents: 1000,
    refundCents: 300,
    reason: "部分退款",
  });
  assert.equal(body.transaction_id, "WX123");
  assert.equal(body.out_refund_no, "R123");
  assert.equal(result.status, "success");
  assert.equal(result.refundCents, 300);
  assert.deepEqual(await channel.queryRefund("R123"), result);
});

test("游客、店主、主理人均按订单身份价格微信支付，不要求积分余额", async (t) => {
  const f = await setup(t);
  for (const [identity, goods] of [
    ["D", 10000],
    ["C", 10000],
    ["B", 8000],
  ] as const) {
    f.db
      .prepare("UPDATE users SET identity=? WHERE id=?")
      .run(identity, f.u.user.id);
    const o = await f.order(1);
    assert.equal(o.goods_cents, goods);
    assert.equal(o.total_cents, goods + 1000);
    assert.equal((await f.pay(`/orders/${o.id}/pay/wechat`, {})).status, 200);
    f.channel.paid();
    await f.pay(`/orders/${o.id}/payment/query`, {});
    assert.equal(ownedOrder(f.db, o.id).payment_state, "paid");
  }
});
test("可信PAYERROR先关闭原单再允许重试，失败不等于订单关闭", async (t) => {
  const f = await setup(t),
    o = await f.order();
  await f.pay(`/orders/${o.id}/pay/wechat`, {});
  const first = [...f.channel.trades.values()][0];
  first.trade_state = "PAYERROR";
  assert.equal((await f.pay(`/orders/${o.id}/payment/query`, {})).status, 200);
  assert.equal(ownedOrder(f.db, o.id).status, "pending");
  assert.equal(ownedOrder(f.db, o.id).payment_state, "failed");
  assert.equal(f.channel.closes, 1);
  await f.pay(`/orders/${o.id}/pay/wechat`, {});
  assert.equal(f.channel.creates, 2);
  assert.notEqual([...f.channel.trades.keys()][1], first.out_trade_no);
  assert.equal(
    f.db.prepare("SELECT reserved FROM skus WHERE id=?").get(f.p.skus[0].id)
      ?.reserved,
    3,
  );
});
test("原有效期后可信查询不存在可回收从未生成预支付的占用，过期前保持锁", async (t) => {
  const { ChannelError } = await import("./wechat-pay.ts");
  const f = await setup(t),
    o = await f.order();
  f.channel.createError = true;
  await f.pay(`/orders/${o.id}/pay/wechat`, {});
  f.channel.query = async () => {
    throw new ChannelError("ORDER_NOT_EXIST", "原商户验签确认订单不存在");
  };
  assert.equal((await f.pay(`/orders/${o.id}/payment/query`, {})).status, 409);
  assert.equal(ownedOrder(f.db, o.id).status, "pending");
  f.db
    .prepare(
      "UPDATE orders SET expires_at='2020-01-01T00:00:00.000Z' WHERE id=?",
    )
    .run(o.id);
  assert.equal((await f.pay(`/orders/${o.id}/payment/query`, {})).status, 200);
  assert.equal(ownedOrder(f.db, o.id).status, "closed");
  assert.equal(
    f.db.prepare("SELECT reserved FROM skus WHERE id=?").get(f.p.skus[0].id)
      ?.reserved,
    0,
  );
});
test("渠道商户变化不能用新商户的不存在结果释放原商户交易", async (t) => {
  const f = await setup(t),
    o = await f.order();
  await f.pay(`/orders/${o.id}/pay/wechat`, {});
  f.channel.mchId = "rotated-merchant";
  assert.equal((await f.pay(`/orders/${o.id}/payment/close`, {})).status, 409);
  assert.equal(f.channel.closes, 0);
  assert.equal(ownedOrder(f.db, o.id).status, "pending");
});
test("close接口遇到实际到账竞争时重查到账，不释放库存；渠道请求在SQLite事务外", async (t) => {
  const { assertTransaction } = await import("./db.ts");
  const { ChannelError } = await import("./wechat-pay.ts");
  const f = await setup(t),
    o = await f.order();
  const create = f.channel.create.bind(f.channel),
    query = f.channel.query.bind(f.channel);
  f.channel.create = async (input) => {
    assert.throws(() => assertTransaction(f.db, "外部下单"), /事务/);
    return create(input);
  };
  f.channel.query = async (number) => {
    assert.throws(() => assertTransaction(f.db, "外部查单"), /事务/);
    return query(number);
  };
  f.channel.close = async () => {
    assert.throws(() => assertTransaction(f.db, "外部关单"), /事务/);
    f.channel.paid();
    throw new ChannelError("ORDERPAID", "渠道已到账");
  };
  await f.pay(`/orders/${o.id}/pay/wechat`, {});
  const closed = await f.pay(`/orders/${o.id}/payment/close`, {});
  assert.equal(closed.status, 200);
  assert.equal(closed.data.payment_state, "paid");
  assert.deepEqual(
    {
      ...f.db
        .prepare("SELECT stock,reserved FROM skus WHERE id=?")
        .get(f.p.skus[0].id),
    },
    { stock: 7, reserved: 0 },
  );
});
test("并发争抢库存只有可容纳的订单预占成功，支付后库存不为负", async (t) => {
  const f = await setup(t),
    q = await f.quote(6);
  const placed = await Promise.all(
    ["stock-race-first", "stock-race-second"].map((requestKey) =>
      f.call("/orders", {
        method: "POST",
        token: f.u.token,
        body: { quoteId: q.id, requestKey },
      }),
    ),
  );
  assert.equal(placed.filter((r) => r.status === 200).length, 1);
  assert.equal(placed.filter((r) => r.status === 409).length, 1);
  const o = placed.find((r) => r.status === 200)!.data;
  await f.pay(`/orders/${o.id}/pay/wechat`, {});
  f.channel.paid();
  await f.pay(`/orders/${o.id}/payment/query`, {});
  assert.deepEqual(
    {
      ...f.db
        .prepare("SELECT stock,reserved FROM skus WHERE id=?")
        .get(f.p.skus[0].id),
    },
    { stock: 4, reserved: 0 },
  );
});
test("未处理售后独立阻止确认完成，本人收货只记录一次", async (t) => {
  const f = await setup(t),
    o = await f.order(2);
  await f.pay(`/orders/${o.id}/pay/wechat`, {});
  f.channel.paid();
  await f.pay(`/orders/${o.id}/payment/query`, {});
  const shipped = await f.pay(
    `/admin/orders/${o.id}/shipments`,
    {
      carrier: "顺丰速运",
      tracking: "SF123456",
      lines: [{ lineId: o.lines[0].id, quantity: 2 }],
      requestKey: "shipment-whole-2026",
      reason: "实际发货",
    },
    { cookie: f.cookie },
  );
  assert.equal(shipped.status, 200);
  const r = f.db
    .prepare(
      "INSERT INTO refunds(order_id,number,kind,status,goods_cents,shipping_cents,total_cents,lines,reason,policy_snapshot,request_key,request_id) VALUES(?,?,'goods','failed',100,0,100,'[]','测试售后','{}',?,?)",
    )
    .run(o.id, "test-refund", randomUUID(), randomUUID());
  const path = `/orders/${o.id}/shipments/${shipped.data.id}/receive`;
  assert.equal((await f.pay(path, {})).status, 409);
  assert.equal(ownedOrder(f.db, o.id).received_at, null);
  f.db
    .prepare("UPDATE refunds SET status='rejected' WHERE id=?")
    .run(r.lastInsertRowid);
  assert.equal((await f.pay(path, {})).data.status, "completed");
  const received = ownedOrder(f.db, o.id).received_at;
  assert.equal((await f.pay(path, {})).data.status, "completed");
  assert.equal(ownedOrder(f.db, o.id).received_at, received);
  assert.equal(
    f.db
      .prepare(
        "SELECT COUNT(*) n FROM audit_events WHERE action='shipment.receive' AND object_id=?",
      )
      .get(String(shipped.data.id))?.n,
    1,
  );
  const other = await f.user("principal");
  assert.equal((await f.pay(path, {}, { token: other.token })).status, 404);
});
