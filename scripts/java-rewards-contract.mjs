import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";

// 主线程在独立 MySQL 验收库中准备来源订单与登录凭据，脚本仅通过 HTTP 执行业务。
// 示例：BASE_URL=http://127.0.0.1:3187 JAVA_REWARDS_TEST_ISOLATED_DB=1 \
//       REWARDS_FIXTURE=/tmp/java-rewards-fixture.json node scripts/java-rewards-contract.mjs
assert.equal(
  process.env.JAVA_REWARDS_TEST_ISOLATED_DB,
  "1",
  "必须显式确认目标服务使用独立测试数据库",
);
assert.ok(process.env.BASE_URL, "必须配置 BASE_URL，脚本不会启动服务");
assert.ok(process.env.REWARDS_FIXTURE, "必须提供主线程准备的独立测试夹具 JSON");
const base = new URL(process.env.BASE_URL);
assert.ok(["http:", "https:"].includes(base.protocol));
const fixture = JSON.parse(await readFile(process.env.REWARDS_FIXTURE, "utf8"));
assert.equal(fixture.isolated, true);
assert.equal(
  new URL(fixture.baseUrl).origin,
  base.origin,
  "夹具必须属于当前测试服务",
);
assert.match(fixture.runId, /^java-rewards-[a-z0-9-]{8,60}$/);
for (const field of [
  "adminCookie",
  "limitedCookie",
  "ownerToken",
  "otherToken",
])
  assert.ok(fixture[field], `缺少 ${field}`);
assert.notEqual(fixture.ownerId, fixture.otherOwnerId);
assert.ok(Number.isSafeInteger(fixture.ownerId) && fixture.ownerId > 0);
assert.ok(
  Number.isSafeInteger(fixture.otherOwnerId) && fixture.otherOwnerId > 0,
);
const reason = `独立奖励验收 ${fixture.runId}`;
const key = () => randomUUID();
const actualTime = new Date(Date.now() - 60000).toISOString();
const checks = [];

async function request(
  path,
  {
    body,
    method = body ? "POST" : "GET",
    token,
    cookie = fixture.adminCookie,
    status = 200,
  } = {},
) {
  const response = await fetch(new URL(`/api${path}`, base), {
    method,
    headers: {
      Origin: base.origin,
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(token
        ? { Authorization: `Bearer ${token}` }
        : cookie
          ? { Cookie: cookie }
          : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(20000),
  });
  const text = await response.text();
  let data;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    assert.fail(`${method} ${path} 返回非 JSON，HTTP ${response.status}`);
  }
  assert.equal(
    response.status,
    status,
    `${method} ${path}: ${JSON.stringify(data)}`,
  );
  return data;
}
async function check(name, run) {
  await run();
  checks.push(name);
  console.log(`通过：${name}`);
}

// 先核实全部来源编号及订单前缀，再进行任何写入，防止夹具误指向用户真实订单。
const sourceSpecs = {
  debt: {
    amount: 10000,
    owner: fixture.ownerId,
    kind: "onboarding",
    identity: "C",
  },
  offset: {
    amount: 8000,
    owner: fixture.ownerId,
    kind: "onboarding",
    identity: "C",
  },
  stale: {
    amount: 8000,
    owner: fixture.ownerId,
    kind: "onboarding",
    identity: "C",
  },
  other: { amount: 6000, owner: fixture.otherOwnerId, kind: "margin" },
  principal: {
    amount: 50000,
    owner: fixture.ownerId,
    kind: "onboarding",
    identity: "B",
  },
};
const sources = {};
for (const [name, spec] of Object.entries(sourceSpecs)) {
  const orderId = fixture.orders?.[name]?.id;
  assert.ok(
    Number.isSafeInteger(orderId) && orderId > 0,
    `缺少 ${name} 来源订单`,
  );
  const source = await request(`/admin/rewards/source/${orderId}`);
  assert.ok(
    source.orderNumber.startsWith(`${fixture.runId}-`),
    `${name} 不是本次独立测试订单`,
  );
  assert.equal(source.amountCents, spec.amount);
  assert.equal(source.userId, spec.owner);
  assert.equal(source.kind, spec.kind);
  if (spec.identity) assert.equal(source.snapshot.identity, spec.identity);
  assert.equal(
    source.snapshot.afterSalesDays,
    0,
    "夹具应已收货且使用零天售后期，无需修改时钟",
  );
  sources[name] = source;
}
assert.equal(
  new Set(Object.values(sources).map((source) => source.orderId)).size,
  5,
  "来源订单不可重复",
);
assert.equal(
  sources.debt.snapshot.paymentMethod,
  "offline",
  "欠款场景使用独立产品包公司转款，不调用真实支付渠道",
);
assert.ok(
  Array.isArray(fixture.orders.debt.refundItems) &&
    fixture.orders.debt.refundItems.length > 0,
);

await check("奖励参数严格边界与后台权限", async () => {
  await request("/admin/rewards", {
    cookie: fixture.limitedCookie,
    status: 403,
  });
  await request("/admin/rewards", { cookie: null, status: 401 });
  const settings = {
    storeRewardCents: 20000,
    principalRewardCents: 50000,
    settlementDay: 20,
    reason,
  };
  await request("/admin/reward-settings", {
    method: "PUT",
    body: { ...settings, unexpected: true },
    status: 400,
  });
  await request("/admin/reward-settings", {
    method: "PUT",
    body: { ...settings, settlementDay: 29 },
    status: 400,
  });
  await request("/admin/reward-settings", {
    method: "PUT",
    body: { ...settings, storeRewardCents: 0 },
    status: 400,
  });
  await request("/admin/reward-settings", {
    method: "PUT",
    body: { ...settings, storeRewardCents: "20000" },
    status: 400,
  });
  await request("/admin/reward-settings", { method: "PUT", body: settings });
  const result = await request("/admin/reward-settings");
  assert.equal(result.storeRewardCents, 20000);
  assert.equal(result.principalRewardCents, 50000);
  assert.equal(result.settlementDay, 20);
  assert.ok(Object.hasOwn(result, "afterSalesDays"));
});

const rewards = {};
await check("登记原快照、两档入驻与订货价差、金额边界和并发去重", async () => {
  await request("/admin/rewards", {
    body: { orderId: sources.debt.orderId, amountCents: 10001, reason },
    status: 409,
  });
  await request("/admin/rewards", {
    body: {
      orderId: sources.debt.orderId,
      amountCents: 10000,
      reason,
      userId: fixture.otherOwnerId,
    },
    status: 400,
  });
  const debtBody = {
    orderId: sources.debt.orderId,
    amountCents: 10000,
    reason,
  };
  // 相同来源重复登记的原语义是一个成功、另一个冲突；最多仅两个并发请求。
  const attempts = await Promise.all(
    [0, 1].map(async () => {
      const response = await fetch(new URL("/api/admin/rewards", base), {
        method: "POST",
        headers: {
          Origin: base.origin,
          Cookie: fixture.adminCookie,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(debtBody),
        signal: AbortSignal.timeout(20000),
      });
      return { status: response.status, data: await response.json() };
    }),
  );
  assert.deepEqual(attempts.map((item) => item.status).sort(), [200, 409]);
  rewards.debt = attempts.find((item) => item.status === 200).data;
  for (const name of ["offset", "stale", "other", "principal"]) {
    rewards[name] = await request("/admin/rewards", {
      body: {
        orderId: sources[name].orderId,
        amountCents: sources[name].amountCents,
        reason,
      },
    });
  }
  for (const [name, reward] of Object.entries(rewards)) {
    assert.equal(reward.userId, sourceSpecs[name].owner);
    assert.equal(reward.status, "eligible");
    assert.deepEqual(reward.basis, sources[name].snapshot);
  }
  const after = await request(`/admin/rewards/${rewards.debt.id}`);
  assert.equal(after.amountCents, 10000, "配置变化不影响历史来源奖励");
});

await check("本人查询隔离、详情与汇总、后台列表", async () => {
  const own = await request("/rewards", { token: fixture.ownerToken });
  assert.ok(own.items.some((item) => item.id === rewards.debt.id));
  assert.equal(
    own.items.some((item) => item.id === rewards.other.id),
    false,
  );
  assert.equal(Object.hasOwn(own.items[0], "basis"), false);
  assert.equal(Object.hasOwn(own.items[0], "userId"), false);
  const detail = await request(`/rewards/${rewards.debt.id}`, {
    token: fixture.ownerToken,
  });
  for (const field of [
    "reversals",
    "settlements",
    "debts",
    "offsets",
    "recoveries",
  ])
    assert.ok(Array.isArray(detail[field]));
  await request(`/rewards/${rewards.debt.id}`, {
    token: fixture.otherToken,
    status: 404,
  });
  const admin = await request("/admin/rewards");
  assert.ok(admin.some((item) => item.id === rewards.debt.id && item.basis));
  assert.match(own.summary.month, /^\d{4}-\d{2}$/);
});

async function plan(
  reward,
  month,
  userId = fixture.ownerId,
  overrides = {},
  status = 200,
) {
  return request("/admin/reward-settlements", {
    body: {
      userId,
      month,
      rewardIds: [reward.id],
      payee: `测试收款人 ${fixture.runId}`,
      requestKey: key(),
      reason,
      ...overrides,
    },
    status,
  });
}
async function paid(settlement, status = 200) {
  return request(`/admin/reward-settlements/${settlement.id}/result`, {
    body: {
      status: "paid",
      paidAt: actualTime,
      proof: `测试付款核实 ${fixture.runId}`,
      reason,
    },
    status,
  });
}
async function state(settlement, status, expected = 200) {
  return request(`/admin/reward-settlements/${settlement.id}/result`, {
    body: { status, reason },
    status: expected,
  });
}
let first;
await check("月结计划幂等、重复月份、真实付款依据与付款幂等", async () => {
  const requestKey = key();
  first = await plan(rewards.debt, "2001-01", fixture.ownerId, { requestKey });
  assert.equal(typeof first.plan, "string", "创建响应保持原计划 JSON 文本合同");
  assert.equal(first.gross_cents, 10000);
  const retry = await plan(rewards.debt, "2001-01", fixture.ownerId, {
    requestKey,
  });
  assert.equal(retry.id, first.id);
  await plan(
    rewards.debt,
    "2001-01",
    fixture.ownerId,
    { requestKey, payee: "其他人" },
    409,
  );
  await plan(rewards.debt, "2001-01", fixture.ownerId, {}, 409);
  await plan(rewards.debt, "2001-02", fixture.otherOwnerId, {}, 404);
  await plan(
    rewards.debt,
    "2001-02",
    fixture.ownerId,
    { rewardIds: [rewards.debt.id, rewards.debt.id] },
    400,
  );
  await request(`/admin/reward-settlements/${first.id}/result`, {
    body: { status: "paid", reason },
    status: 409,
  });
  await paid(first);
  await paid(first);
  await state(first, "failed", 409);
  assert.equal(
    (await request(`/admin/rewards/${rewards.debt.id}`)).settledCents,
    10000,
  );
});

let debt;
await check("真实产品包退款调用奖励冲回、重复核对与已付待收回", async () => {
  const refund = await request("/admin/refunds", {
    body: {
      orderId: sources.debt.orderId,
      items: fixture.orders.debt.refundItems,
      shippingCents: 0,
      reason,
      requestKey: key(),
      exceptionReason: "独立测试已付奖励全退冲回",
    },
  });
  assert.equal(
    refund.goods_cents,
    sources.debt.snapshot.goodsCents,
    "本场景必须全部货款退款",
  );
  await request(`/admin/refunds/${refund.id}/review`, {
    body: { approve: true, identityDecision: "revoke", reason },
  });
  await request(`/admin/refunds/${refund.id}/inspect`, {
    body: {
      returnedAt: actualTime,
      inspection: reason,
      restock: false,
      reason,
    },
  });
  await request(`/admin/refunds/${refund.id}/offline`, {
    body: {
      recipient: `测试购买者 ${fixture.runId}`,
      paidAt: actualTime,
      proof: reason,
      confirmed: true,
      reason,
    },
  });
  // 先验证售后跨模块钩子确实已冲回，再核对幂等，避免手动 reconcile 掩盖漏接。
  const reversed = await request(`/admin/rewards/${rewards.debt.id}`);
  assert.equal(reversed.reversedCents, 10000);
  assert.equal(reversed.reversals.length, 1);
  assert.equal(reversed.debts[0].remainingCents, 10000);
  for (let i = 0; i < 2; i++)
    await request(`/admin/rewards/${rewards.debt.id}/reconcile`, {
      body: { reason },
    });
  assert.equal(
    (await request(`/admin/rewards/${rewards.debt.id}`)).reversals.length,
    1,
  );
  debt = (await request("/admin/reward-debts")).find(
    (item) => item.reward_id === rewards.debt.id,
  );
  assert.equal(debt.remainingCents, 10000);
});

await check("欠款只抵扣同一人后续奖励，全额抵扣也须付款核实", async () => {
  const other = await plan(rewards.other, "2001-02", fixture.otherOwnerId);
  assert.equal(other.offset_cents, 0);
  await state(other, "cancelled");
  const offset = await plan(rewards.offset, "2001-02");
  assert.equal(offset.gross_cents, 8000);
  assert.equal(offset.offset_cents, 8000);
  assert.equal(offset.net_cents, 0);
  assert.equal(
    (await request("/admin/reward-debts")).find((item) => item.id === debt.id)
      .remainingCents,
    10000,
  );
  await paid(offset);
  assert.equal(
    (await request("/admin/reward-debts")).find((item) => item.id === debt.id)
      .remainingCents,
    2000,
  );
});

await check("并发实收幂等、过期抵扣计划阻断、未明付款禁止取消", async () => {
  const stale = await plan(rewards.stale, "2001-03");
  assert.equal(stale.offset_cents, 2000);
  const recovery = {
    reversalId: debt.id,
    amountCents: 500,
    receivedAt: actualTime,
    proof: reason,
    requestKey: key(),
    reason,
  };
  const attempts = await Promise.all([
    request("/admin/reward-recoveries", { body: recovery }),
    request("/admin/reward-recoveries", { body: recovery }),
  ]);
  assert.equal(attempts[0].id, attempts[1].id);
  await request("/admin/reward-recoveries", {
    body: { ...recovery, amountCents: 501 },
    status: 409,
  });
  await request("/admin/reward-recoveries", {
    body: { ...recovery, requestKey: key(), amountCents: 1501 },
    status: 409,
  });
  await paid(stale, 409);
  await state(stale, "unknown");
  await state(stale, "cancelled", 409);
  await state(stale, "failed");
  await state(stale, "cancelled");
  const fresh = await plan(rewards.stale, "2001-03");
  assert.equal(fresh.offset_cents, 1500);
  assert.equal(fresh.net_cents, 6500);
  await paid(fresh);
  assert.equal(
    (await request("/admin/reward-debts")).some((item) => item.id === debt.id),
    false,
  );
  await request("/admin/reward-recoveries", {
    body: { ...recovery, requestKey: key(), amountCents: 1 },
    status: 409,
  });
  assert.equal(
    (await request("/admin/reward-recoveries", { body: recovery })).id,
    attempts[0].id,
  );
  const detail = await request(`/rewards/${rewards.debt.id}`, {
    token: fixture.ownerToken,
  });
  assert.equal(
    detail.offsets.reduce((sum, item) => sum + item.amount_cents, 0),
    9500,
  );
  assert.equal(
    detail.recoveries.reduce((sum, item) => sum + item.amount_cents, 0),
    500,
  );
  const settlements = await request("/admin/reward-settlements");
  assert.ok(
    settlements.some(
      (item) => item.id === fresh.id && typeof item.plan === "object",
    ),
  );
  assert.equal(
    (await request("/rewards", { token: fixture.ownerToken })).summary
      .debtCents,
    0,
  );
});

console.log(
  JSON.stringify({ runId: fixture.runId, passed: checks.length, checks }),
);
