import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { membershipFixture } from "./membership-fixture.ts";
import { restoreOrderPoints, walletView } from "./wallet.ts";
import { transaction } from "./db.ts";

async function setup(t: Parameters<typeof membershipFixture>[0]) {
  const f = await membershipFixture(t);
  f.db
    .prepare("UPDATE users SET identity='C',principal_id=? WHERE id=?")
    .run(f.principal.user.id, f.u.user.id);
  async function credit(
    amount = 100000,
    source = randomUUID(),
    userId = f.u.user.id,
  ) {
    return f.call("/admin/receipts", {
      method: "POST",
      cookie: f.cookie,
      body: { ...f.receiptBody(amount, source), purpose: "points", userId },
    });
  }
  async function pay(id: number, token = f.u.token) {
    return f.call(`/orders/${id}/pay/points`, { method: "POST", token });
  }
  return { ...f, credit, pay };
}

test("08 公司到账1000元仅发1000积分，重复并发、逐原流水与本人隔离", async (t) => {
  const f = await setup(t),
    source = randomUUID();
  const results = await Promise.all([
    f.credit(100000, source),
    f.credit(100000, source),
  ]);
  assert.deepEqual(results.map((r) => r.status).sort(), [200, 409]);
  const own = await f.call("/wallet", { token: f.u.token });
  assert.equal(own.status, 200);
  assert.equal(own.data.balanceCents, 100000);
  assert.equal(own.data.availableCents, 100000);
  assert.equal(own.data.ledger.length, 1);
  assert.equal(own.data.lots.length, 1);
  assert.equal(own.data.lots[0].external_transaction_id, source);
  const traced = await f.call(
    "/admin/receipts?externalTransactionId=" + source,
    { cookie: f.cookie },
  );
  assert.equal(traced.status, 200);
  assert.equal(traced.data.length, 1);
  assert.equal(traced.data[0].external_transaction_id, source);
  assert.equal(
    (
      await f.call("/admin/receipts?externalTransactionId=nonexistent-source", {
        cookie: f.cookie,
      })
    ).data.length,
    0,
  );

  assert.equal(own.data.ledger[0].receipt_id, own.data.lots[0].receipt_id);
  const other = await f.call("/wallet", { token: f.principal.token });
  assert.equal(other.data.balanceCents, 0);
  assert.equal(other.data.ledger.length, 0);
  const absent = await f.call("/wallet");
  assert.equal(absent.status, 401);
  const forged = await f.call("/admin/receipts", {
    method: "POST",
    cookie: f.cookie,
    body: {
      ...f.receiptBody(),
      purpose: "points",
      userId: f.u.user.id,
      verified: false,
    },
  });
  assert.equal(forged.status, 400);
});

test("09 待付不扣、两个订单争余额仅一次成功、逐lot可追溯、重复点击不二扣", async (t) => {
  const f = await setup(t);
  await f.credit(15000);
  const a = await f.order(1),
    b = await f.order(1);
  assert.equal(walletView(f.db, f.u.user.id).balanceCents, 15000);
  const results = await Promise.all([f.pay(a.id), f.pay(b.id)]);
  assert.deepEqual(results.map((r) => r.status).sort(), [200, 409]);
  const success = results.find((r) => r.status === 200)!.data.order;
  assert.equal(success.payment_state, "paid");
  assert.equal(success.payment_method, "points");
  assert.equal(success.status, "paid");
  assert.equal(walletView(f.db, f.u.user.id).balanceCents, 4000);
  const repeated = await Promise.all([f.pay(success.id), f.pay(success.id)]);
  assert.deepEqual(
    repeated.map((r) => r.status),
    [200, 200],
  );
  assert.equal(walletView(f.db, f.u.user.id).balanceCents, 4000);
  const payment = f.db
    .prepare("SELECT * FROM payments WHERE order_id=?")
    .get(success.id)!;
  assert.equal(payment.method, "points");
  assert.equal(payment.cents, 11000);
  assert.equal(
    f.db.prepare("SELECT COUNT(*) AS n FROM wallet_spend_allocations").get()!.n,
    1,
  );
  assert.equal(
    f.db.prepare("SELECT stock FROM skus WHERE id=?").get(f.p.skus[0].id)!
      .stock,
    9,
  );
  const ship = await f.call(`/admin/orders/${success.id}/shipments`, {
    method: "POST",
    cookie: f.cookie,
    body: {
      carrier: "顺丰",
      tracking: "SF202600012",
      requestKey: randomUUID(),
      reason: "实际出库",
      lines: [{ lineId: success.lines[0].id, quantity: 1 }],
    },
  });
  assert.equal(ship.status, 200, ship.data.message);
  assert.equal(
    (
      await f.call(`/orders/${success.id}/shipments/${ship.data.id}/receive`, {
        method: "POST",
        token: f.u.token,
      })
    ).data.status,
    "completed",
  );
});

test("09 微信结果未明与过期身份拒绝切换，库存失败全事务回滚积分和流水", async (t) => {
  const f = await setup(t);
  await f.credit();
  const o = await f.order(1);
  f.db
    .prepare(
      "UPDATE orders SET payment_method='wechat',payment_state='unknown' WHERE id=?",
    )
    .run(o.id);
  assert.equal((await f.pay(o.id)).status, 409);
  assert.equal(walletView(f.db, f.u.user.id).balanceCents, 100000);
  f.db
    .prepare(
      "UPDATE orders SET payment_method=NULL,payment_state='unpaid' WHERE id=?",
    )
    .run(o.id);
  f.db
    .prepare(
      "UPDATE users SET identity_valid_until='2020-01-01T00:00:00Z' WHERE id=?",
    )
    .run(f.u.user.id);
  assert.equal((await f.pay(o.id)).status, 403);
  f.db
    .prepare("UPDATE users SET identity_valid_until=NULL WHERE id=?")
    .run(f.u.user.id);
  f.db.prepare("UPDATE skus SET reserved=0 WHERE id=?").run(f.p.skus[0].id);
  assert.equal((await f.pay(o.id)).status, 409);
  assert.equal(walletView(f.db, f.u.user.id).balanceCents, 100000);
  assert.equal(
    f.db.prepare("SELECT COUNT(*) AS n FROM wallet_spend_allocations").get()!.n,
    0,
  );
  assert.equal(f.db.prepare("SELECT COUNT(*) AS n FROM payments").get()!.n, 0);
});

test("08 纠错须原lot、原因和操作者；已消费或被占用积分不能扣成负数", async (t) => {
  const f = await setup(t);
  const c = await f.credit(10000),
    lot = c.data.lots[0];
  f.db.prepare("UPDATE wallet_lots SET held_cents=6000 WHERE id=?").run(lot.id);
  const path = `/admin/wallet-lots/${lot.id}/correct`,
    key = randomUUID();
  assert.equal(
    (
      await f.call(path, {
        method: "POST",
        cookie: f.cookie,
        body: {
          amountCents: 5000,
          reason: "核实重复业务差额",
          requestKey: key,
        },
      })
    ).status,
    409,
  );
  const body = {
    amountCents: 4000,
    reason: "原批次差额核实冲减",
    requestKey: key,
  };
  assert.equal(
    (await f.call(path, { method: "POST", cookie: f.cookie, body })).status,
    200,
  );
  assert.equal(
    (await f.call(path, { method: "POST", cookie: f.cookie, body })).status,
    200,
  );
  const w = walletView(f.db, f.u.user.id);
  assert.equal(w.balanceCents, 6000);
  assert.equal(w.availableCents, 0);
  assert.equal(w.ledger[0].create_user_id, f.manager);
  assert.equal(w.ledger[0].kind, "correction");
});

test("11 对接：跨收款lot消费原源返还、同源再消费、退款幂等与金额上限", async (t) => {
  const f = await setup(t);
  await f.credit(5000);
  await f.credit(20000);
  const o = await f.order(1);
  assert.equal((await f.pay(o.id)).status, 200);
  const allocations = f.db
    .prepare("SELECT * FROM wallet_spend_allocations ORDER BY id")
    .all();
  assert.deepEqual(
    allocations.map((a) => a.spent_cents),
    [5000, 6000],
  );
  transaction(f.db, () =>
    restoreOrderPoints(f.db, o.id, 7000, "refund:one", f.manager),
  );
  transaction(f.db, () =>
    restoreOrderPoints(f.db, o.id, 7000, "refund:one", f.manager),
  );
  const lots = walletView(f.db, f.u.user.id).lots;
  assert.deepEqual(
    lots.map((l) => l.remaining_cents),
    [5000, 16000],
  );
  assert.throws(() =>
    transaction(f.db, () =>
      restoreOrderPoints(f.db, o.id, 7001, "refund:one", f.manager),
    ),
  );
  assert.throws(() =>
    transaction(f.db, () =>
      restoreOrderPoints(f.db, o.id, 5000, "refund:two", f.manager),
    ),
  );
  const next = await f.order(1);
  assert.equal((await f.pay(next.id)).status, 200);
  assert.equal(
    f.db
      .prepare(
        "SELECT receipt_id FROM wallet_spend_allocations WHERE order_id=? ORDER BY id",
      )
      .get(next.id)!.receipt_id,
    allocations[0].receipt_id,
  );
  transaction(f.db, () =>
    restoreOrderPoints(f.db, o.id, 4000, "refund:two", f.manager),
  );
  assert.equal(walletView(f.db, f.u.user.id).balanceCents, 14000);
});

test("09 同订单并发支付、越权、已发起微信attempt均拒绝资金复用", async (t) => {
  const f = await setup(t);
  await f.credit();
  const o = await f.order(1);
  assert.equal((await f.pay(o.id, f.inviter.token)).status, 404);
  f.db
    .prepare(
      "INSERT INTO payment_attempts(order_id,number,state,app_id,mch_id,cents,openid) VALUES(?,?,'unknown','test-app','test-mch',11000,'test-openid')",
    )
    .run(o.id, randomUUID());
  assert.equal((await f.pay(o.id)).status, 409);
  assert.equal(walletView(f.db, f.u.user.id).balanceCents, 100000);
  f.db
    .prepare("UPDATE payment_attempts SET state='closed' WHERE order_id=?")
    .run(o.id);
  const results = await Promise.all([f.pay(o.id), f.pay(o.id)]);
  assert.deepEqual(
    results.map((r) => r.status),
    [200, 200],
  );
  assert.equal(walletView(f.db, f.u.user.id).balanceCents, 89000);
});
