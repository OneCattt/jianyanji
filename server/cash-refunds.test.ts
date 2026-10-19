import { test } from "node:test";
import assert from "node:assert/strict";
import { commerceFixture } from "./commerce-fixture.ts";
async function credit(
  f: Awaited<ReturnType<typeof commerceFixture>>,
  amountCents: number,
  key: string,
) {
  f.db.prepare("UPDATE users SET identity='C' WHERE id=?").run(f.u.user.id);
  const r = await f.call("/admin/receipts", {
    method: "POST",
    cookie: f.cookie,
    body: {
      userId: f.u.user.id,
      purpose: "points",
      sourceChannel: "bank",
      accountRef: "测试公司账户",
      externalTransactionId: key,
      amountCents,
      receivedAt: new Date(Date.now() - 1000).toISOString(),
      evidence: "测试实际到账凭证",
      reason: "测试核款",
      verified: true,
    },
  });
  assert.equal(r.status, 200);
  return r.data.id;
}
async function apply(
  f: Awaited<ReturnType<typeof commerceFixture>>,
  receiptId: number,
  amountCents: number,
  key = "cash-request-" + Math.random().toString(36).slice(2),
) {
  return f.call("/admin/cash-refunds", {
    method: "POST",
    cookie: f.cookie,
    body: {
      userId: f.u.user.id,
      receiptId,
      amountCents,
      reason: "人工申请未用积分退现金",
      requestKey: key,
    },
  });
}
const success = () => ({
  outcome: "success",
  recipient: "原购买者",
  paidAt: new Date(Date.now() - 1000).toISOString(),
  proof: "已核实的线下退款流水",
  confirmed: true,
  confirmedNoPayment: false,
  reason: "核实实际退款完成",
});
test("1000积分退600办理中只可花400，重复确认只扣一次且现金不超原款", async (t) => {
  const f = await commerceFixture(t),
    receiptId = await credit(f, 100000, "receipt-cash-1000");
  const a = await apply(f, receiptId, 60000, "cash-refund-same-2026");
  assert.equal(a.status, 200);
  assert.equal(
    (await apply(f, receiptId, 60000, "cash-refund-same-2026")).data.id,
    a.data.id,
  );
  let wallet = (await f.call("/wallet", { token: f.u.token })).data;
  assert.equal(wallet.availableCents, 40000);
  assert.equal(wallet.heldCents, 60000);
  assert.equal((await apply(f, receiptId, 50000)).status, 409);
  const o = await f.order(5);
  assert.equal(
    (
      await f.call("/orders/" + o.id + "/pay/points", {
        method: "POST",
        token: f.u.token,
      })
    ).status,
    409,
  );
  const body = success();
  await f.call("/admin/cash-refunds/" + a.data.id + "/resolve", {
    method: "POST",
    cookie: f.cookie,
    body,
  });
  await f.call("/admin/cash-refunds/" + a.data.id + "/resolve", {
    method: "POST",
    cookie: f.cookie,
    body,
  });
  wallet = (await f.call("/wallet", { token: f.u.token })).data;
  assert.equal(wallet.availableCents, 40000);
  assert.equal(wallet.heldCents, 0);
  assert.equal(
    f.db
      .prepare("SELECT cash_refunded_cents FROM company_receipts WHERE id=?")
      .get(receiptId)?.cash_refunded_cents,
    60000,
  );
  assert.equal(
    f.db
      .prepare(
        "SELECT count(*) AS n FROM wallet_ledger WHERE kind='cash_refund' AND receipt_id=?",
      )
      .get(receiptId)?.n,
    1,
  );
});
test("结果未知保留占用，明确失败核实未付款后释放；本人记录不能越权查看", async (t) => {
  const f = await commerceFixture(t),
    receiptId = await credit(f, 100000, "receipt-cash-unknown");
  const r = await apply(f, receiptId, 60000);
  const url = "/admin/cash-refunds/" + r.data.id + "/resolve";
  const body = {
    ...success(),
    outcome: "unknown",
    confirmed: false,
    proof: "",
    paidAt: "",
    recipient: "",
    reason: "银行结果待核查",
  };
  assert.equal(
    (await f.call(url, { method: "POST", cookie: f.cookie, body })).status,
    200,
  );
  assert.equal(
    (await f.call("/wallet", { token: f.u.token })).data.heldCents,
    60000,
  );
  assert.equal(
    (
      await f.call(url, {
        method: "POST",
        cookie: f.cookie,
        body: { ...body, outcome: "failed", confirmedNoPayment: true },
      })
    ).status,
    409,
  );
  assert.equal(
    (
      await f.call(url, {
        method: "POST",
        cookie: f.cookie,
        body: {
          ...body,
          outcome: "failed",
          confirmedNoPayment: true,
          proof: "公司核实转账失败凭证",
        },
      })
    ).status,
    200,
  );
  assert.equal(
    (await f.call("/wallet", { token: f.u.token })).data.availableCents,
    100000,
  );
  const stranger = await f.user("cash-stranger");
  assert.equal(
    (await f.call("/cash-refunds", { token: stranger.token })).data.length,
    0,
  );
});
test("跨两笔收款消费后退积分仍回原来源，再退现金受原款余额限制", async (t) => {
  const f = await commerceFixture(t),
    r1 = await credit(f, 20000, "receipt-source-one"),
    r2 = await credit(f, 20000, "receipt-source-two");
  const o = await f.order(3);
  assert.equal(
    (
      await f.call("/orders/" + o.id + "/pay/points", {
        method: "POST",
        token: f.u.token,
      })
    ).status,
    200,
  );
  assert.equal(
    (await f.call("/wallet", { token: f.u.token })).data.availableCents,
    9000,
  );
  const refund = await f.call("/admin/refunds", {
    method: "POST",
    cookie: f.cookie,
    body: {
      orderId: o.id,
      items: [{ lineId: o.lines[0].id, quantity: 1 }],
      shippingCents: 0,
      reason: "部分商品退积分",
      requestKey: "points-refund-first-2026",
    },
  });
  assert.equal(refund.status, 200);
  await f.call("/admin/refunds/" + refund.data.id + "/review", {
    method: "POST",
    cookie: f.cookie,
    body: { approve: true, identityDecision: null, reason: "同意退积分" },
  });
  for (let i = 0; i < 2; i++)
    assert.equal(
      (
        await f.call("/admin/refunds/" + refund.data.id + "/points", {
          method: "POST",
          cookie: f.cookie,
          body: { reason: "原积分退回" },
        })
      ).status,
      200,
    );
  assert.equal(
    (await f.call("/wallet", { token: f.u.token })).data.availableCents,
    19000,
  );
  assert.equal((await apply(f, r1, 11000)).status, 409);
  const cash = await apply(f, r1, 10000);
  assert.equal(cash.status, 200);
  assert.equal(
    (
      await f.call("/admin/cash-refunds/" + cash.data.id + "/resolve", {
        method: "POST",
        cookie: f.cookie,
        body: success(),
      })
    ).status,
    200,
  );
  assert.equal((await apply(f, r1, 1)).status, 409);
  assert.equal(
    f.db
      .prepare("SELECT cash_refunded_cents FROM company_receipts WHERE id=?")
      .get(r1)?.cash_refunded_cents,
    10000,
  );
  assert.equal(
    f.db
      .prepare("SELECT cash_refunded_cents FROM company_receipts WHERE id=?")
      .get(r2)?.cash_refunded_cents,
    0,
  );
});

test("并发余额退款只能占用一次额度，并发确认成功仅记一笔扣回", async (t) => {
  const f = await commerceFixture(t),
    receiptId = await credit(f, 100000, "receipt-concurrent-cash");
  const requests = await Promise.all([
    apply(f, receiptId, 60000, "concurrent-cash-first"),
    apply(f, receiptId, 60000, "concurrent-cash-second"),
  ]);
  assert.deepEqual(requests.map((r) => r.status).sort(), [200, 409]);
  const accepted = requests.find((r) => r.status === 200)!;
  const body = success(),
    url = "/admin/cash-refunds/" + accepted.data.id + "/resolve";
  const results = await Promise.all([
    f.call(url, { method: "POST", cookie: f.cookie, body }),
    f.call(url, { method: "POST", cookie: f.cookie, body }),
  ]);
  assert.deepEqual(
    results.map((r) => r.status),
    [200, 200],
  );
  const wallet = (await f.call("/wallet", { token: f.u.token })).data;
  assert.equal(wallet.balanceCents, 40000);
  assert.equal(wallet.availableCents, 40000);
  assert.equal(wallet.heldCents, 0);
  const receipt = f.db
    .prepare("SELECT * FROM company_receipts WHERE id=?")
    .get(receiptId)!;
  assert.equal(receipt.cash_refunded_cents, 60000);
  assert.equal(receipt.cash_held_cents, 0);
  assert.equal(
    f.db
      .prepare(
        "SELECT count(*) AS n FROM wallet_ledger WHERE kind='cash_refund' AND receipt_id=?",
      )
      .get(receiptId)?.n,
    1,
  );
});

test("未用积分退现金与订单积分支付竞争同一批余额，只有一方能成功", async (t) => {
  const f = await commerceFixture(t),
    receiptId = await credit(f, 40000, "receipt-spend-cash-race"),
    o = await f.order(3);
  const [cash, pay] = await Promise.all([
    apply(f, receiptId, 30000),
    f.call("/orders/" + o.id + "/pay/points", {
      method: "POST",
      token: f.u.token,
    }),
  ]);
  assert.deepEqual([cash.status, pay.status].sort(), [200, 409]);
  if (cash.status === 200) {
    assert.equal(
      (
        await f.call("/admin/cash-refunds/" + cash.data.id + "/resolve", {
          method: "POST",
          cookie: f.cookie,
          body: success(),
        })
      ).status,
      200,
    );
    assert.equal(
      (await f.call("/wallet", { token: f.u.token })).data.availableCents,
      10000,
    );
    assert.equal(
      f.db
        .prepare("SELECT stock,reserved FROM skus WHERE id=?")
        .get(f.p.skus[0].id)?.stock,
      10,
    );
    assert.equal(
      f.db
        .prepare("SELECT count(*) AS n FROM payments WHERE order_id=?")
        .get(o.id)?.n,
      0,
    );
  } else {
    assert.equal(
      (await f.call("/wallet", { token: f.u.token })).data.availableCents,
      9000,
    );
    assert.equal(
      f.db
        .prepare("SELECT stock,reserved FROM skus WHERE id=?")
        .get(f.p.skus[0].id)?.stock,
      7,
    );
    assert.equal(
      f.db
        .prepare("SELECT cash_held_cents FROM company_receipts WHERE id=?")
        .get(receiptId)?.cash_held_cents,
      0,
    );
  }
  assert.equal(
    (await f.call("/wallet", { token: f.u.token })).data.heldCents,
    0,
  );
});
