import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { transaction } from "./db.ts";
import { applyPackageRefundIdentity } from "./membership.ts";
import { ownedOrder } from "./orders.ts";

import { membershipFixture } from "./membership-fixture.ts";

test("07 无邀请入驻：未核款不得通过、核款不发积分、审批快照、实际发货收货", async (t) => {
  const f = await membershipFixture(t),
    a = await f.apply();
  assert.equal(a.status, 200, a.data.message);
  const id = a.data.id;
  assert.equal((await f.approve(id)).status, 409);
  const paid = await f.collect(id);
  assert.equal(paid.status, 200, paid.data.message);
  assert.equal(paid.data.order.kind, "package");
  assert.equal(paid.data.order.payment_method, "offline");
  assert.equal(paid.data.order.status, "paid");
  assert.equal((await f.call("/me", { token: f.u.token })).data.identity, "D");
  assert.equal(
    (await f.call("/wallet", { token: f.u.token })).data.balanceCents,
    0,
  );
  assert.equal(
    f.db
      .prepare("SELECT stock,reserved FROM skus WHERE id=?")
      .get(f.p.skus[0].id)!.stock,
    8,
  );
  const approved = await f.approve(id);
  assert.equal(approved.status, 200, approved.data.message);
  assert.equal(approved.data.status, "approved");
  assert.equal((await f.call("/me", { token: f.u.token })).data.identity, "C");
  const snapshot = approved.data.order.snapshot;
  assert.equal(snapshot.rewardCents, 2000);
  assert.equal(snapshot.inviterId, null);
  assert.equal(snapshot.principalId, f.principal.user.id);
  assert.equal(snapshot.onboardingApproved, true);
  assert.equal(snapshot.afterSalesDays, 7);
  assert.equal(snapshot.afterSalesPolicy, "测试售后政策");
  assert.equal(
    (
      await f.call("/admin/reward-settings", {
        method: "PUT",
        cookie: f.cookie,
        body: { ...f.rewards, storeRewardCents: 4000 },
      })
    ).status,
    200,
  );
  assert.equal(
    (await f.call(`/onboardings/${id}`, { token: f.u.token })).data.order
      .snapshot.rewardCents,
    2000,
  );
  const o = approved.data.order,
    ship = await f.call(`/admin/orders/${o.id}/shipments`, {
      method: "POST",
      cookie: f.cookie,
      body: {
        carrier: "顺丰",
        tracking: "SF202600001",
        requestKey: randomUUID(),
        reason: "核对实际出库",
        lines: o.lines.map((l: { id: number; quantity: number }) => ({
          lineId: l.id,
          quantity: l.quantity,
        })),
      },
    });
  assert.equal(ship.status, 200, ship.data.message);
  const receive = await f.call(
    `/orders/${o.id}/shipments/${ship.data.id}/receive`,
    { method: "POST", token: f.u.token },
  );
  assert.equal(receive.status, 200, receive.data.message);
  assert.equal(receive.data.status, "completed");
  assert.ok(receive.data.received_at);
});

test("07 店主邀请与归属分别审核、主理人档位和审批前后价格", async (t) => {
  const f = await membershipFixture(t);
  const before = await f.quote(1);
  assert.equal(before.lines[0].unitCents, 10000);
  const a = await f.apply("B", f.inviter.user.id);
  assert.equal(a.status, 200, a.data.message);
  assert.equal((await f.collect(a.data.id, 40000)).status, 200);
  const reviewed = await f.approve(a.data.id, f.inviter.user.id);
  assert.equal(reviewed.status, 200, reviewed.data.message);
  assert.equal(reviewed.data.inviter_id, f.inviter.user.id);
  assert.equal(reviewed.data.principal_id, f.principal.user.id);
  assert.equal(reviewed.data.reward_cents, 5000);
  const after = await f.quote(1);
  assert.equal(after.lines[0].unitCents, 8000);
  assert.equal(reviewed.data.order.snapshot.lines[0].unitCents, 10000);
});

test("07 核款失败回滚库存与收款、重复用途流水不发积分、本人申请隔离", async (t) => {
  const f = await membershipFixture(t),
    a = await f.apply(),
    id = a.data.id;
  f.db.prepare("UPDATE skus SET reserved=9 WHERE id=?").run(f.p.skus[0].id);
  const source = randomUUID();
  assert.equal((await f.collect(id, 20000, source)).status, 409);
  assert.equal(
    f.db.prepare("SELECT COUNT(*) AS n FROM company_receipts").get()!.n,
    0,
  );
  f.db.prepare("UPDATE skus SET reserved=0 WHERE id=?").run(f.p.skus[0].id);
  const attempts = await Promise.all([
    f.collect(id, 20000, source),
    f.collect(id, 20000, source),
  ]);
  assert.deepEqual(attempts.map((r) => r.status).sort(), [200, 409]);
  assert.equal(
    f.db.prepare("SELECT COUNT(*) AS n FROM company_receipts").get()!.n,
    1,
  );
  assert.equal(
    f.db.prepare("SELECT stock FROM skus WHERE id=?").get(f.p.skus[0].id)!
      .stock,
    8,
  );
  assert.equal((await f.approve(id)).status, 200);
  const duplicate = await f.call("/admin/receipts", {
    method: "POST",
    cookie: f.cookie,
    body: {
      ...f.receiptBody(20000, source),
      purpose: "points",
      userId: f.u.user.id,
    },
  });
  assert.equal(duplicate.status, 409);
  assert.equal(
    (await f.call("/wallet", { token: f.u.token })).data.balanceCents,
    0,
  );
  assert.equal(
    (await f.call(`/onboardings/${id}`, { token: f.principal.token })).status,
    404,
  );
});

test("07 配置缺失不使用零档位、拒绝不合条件与无审查权限", async (t) => {
  const f = await membershipFixture(t);
  f.db.exec("UPDATE reward_settings SET is_deleted=1 WHERE id=1");
  assert.equal((await f.apply()).status, 409);
  f.db.exec("UPDATE reward_settings SET is_deleted=0 WHERE id=1");
  const a = await f.apply();
  const limited = await f.admin("operator");
  assert.equal(
    (
      await f.call(`/admin/onboardings/${a.data.id}/receipt`, {
        method: "POST",
        cookie: limited,
        body: f.receiptBody(),
      })
    ).status,
    403,
  );
  const rejected = await f.call(`/admin/onboardings/${a.data.id}/review`, {
    method: "POST",
    cookie: f.cookie,
    body: { decision: "rejected", reason: "经营条件未达标，请补充资料" },
  });
  assert.equal(rejected.status, 200);
  assert.equal(rejected.data.review_reason, "经营条件未达标，请补充资料");
  assert.equal((await f.call("/me", { token: f.u.token })).data.identity, "D");
});

test("12 对接：产品包实际退款取消本次身份，后续身份保留，重复同结论幂等", async (t) => {
  const f = await membershipFixture(t),
    a = await f.apply();
  await f.collect(a.data.id);
  const approved = await f.approve(a.data.id);
  assert.equal(approved.status, 200);
  const o = ownedOrder(f.db, approved.data.order_id);
  // 成功事实由售后流程提供；本单退款不能取消后来取得的另一身份。
  f.db
    .prepare("UPDATE users SET identity='B',onboarding_id=999 WHERE id=?")
    .run(f.u.user.id);
  f.db
    .prepare(
      "INSERT INTO refunds(order_id,number,status,goods_cents,shipping_cents,total_cents,lines,kind,reason,policy_snapshot,request_key,request_id) VALUES(?,?,'success',20000,0,20000,'[]','package','已实际退款','{}',?,?)",
    )
    .run(o.id, randomUUID(), randomUUID(), randomUUID());
  transaction(f.db, () =>
    applyPackageRefundIdentity(
      f.db,
      o,
      true,
      null,
      f.manager,
      "实物产品包已全部退款",
    ),
  );
  transaction(f.db, () =>
    applyPackageRefundIdentity(f.db, o, true, null, f.manager, "重复通知"),
  );
  assert.equal((await f.call("/me", { token: f.u.token })).data.identity, "B");
  assert.equal(
    f.db
      .prepare("SELECT cash_refunded_cents FROM company_receipts WHERE id=?")
      .get(approved.data.receipt_id)!.cash_refunded_cents,
    20000,
  );
  assert.equal(
    f.db.prepare("SELECT COUNT(*) AS n FROM onboarding_identity_events").get()!
      .n,
    1,
  );
});

test("12 产品包部分实际退款保留身份、全退取消本次身份且原现金累计不超收款", async (t) => {
  const f = await membershipFixture(t),
    a = await f.apply();
  await f.collect(a.data.id);
  const approved = await f.approve(a.data.id),
    o = approved.data.order;
  async function refundOne(key: string) {
    const r = await f.call("/admin/refunds", {
      method: "POST",
      cookie: f.cookie,
      body: {
        orderId: o.id,
        items: [{ lineId: o.lines[0].id, quantity: 1 }],
        shippingCents: 0,
        reason: "产品包部分商品未发货申请退款",
        requestKey: key,
      },
    });
    assert.equal(r.status, 200, r.data.message);
    const review = await f.call(`/admin/refunds/${r.data.id}/review`, {
      method: "POST",
      cookie: f.cookie,
      body: {
        approve: true,
        identityDecision: "retain",
        reason: "品牌方审核剩余产品包保留身份条件",
      },
    });
    assert.equal(review.status, 200, review.data.message);
    const paid = await f.call(`/admin/refunds/${r.data.id}/offline`, {
      method: "POST",
      cookie: f.cookie,
      body: {
        recipient: "原产品包付款人",
        paidAt: "2026-01-02T08:00:00.000Z",
        proof: "银行实际退款凭证-" + r.data.id,
        confirmed: true,
        reason: "财务核实原公司货款已实际退回",
      },
    });
    assert.equal(paid.status, 200, paid.data.message);
    assert.equal(paid.data.status, "success");
    return paid;
  }
  await refundOne(randomUUID());
  assert.equal((await f.call("/me", { token: f.u.token })).data.identity, "C");
  assert.equal(
    f.db
      .prepare("SELECT cash_refunded_cents FROM company_receipts WHERE id=?")
      .get(approved.data.receipt_id)!.cash_refunded_cents,
    10000,
  );
  await refundOne(randomUUID());
  assert.equal((await f.call("/me", { token: f.u.token })).data.identity, "D");
  const state = (
    await f.call(`/onboardings/${a.data.id}`, { token: f.u.token })
  ).data;
  assert.equal(state.status, "revoked");
  assert.equal(state.identityEvents.length, 2);
  assert.equal(state.identityEvents[0].prior_status, "approved");
  assert.equal(state.order.status, "refunded");
  assert.equal(
    f.db
      .prepare("SELECT cash_refunded_cents FROM company_receipts WHERE id=?")
      .get(approved.data.receipt_id)!.cash_refunded_cents,
    20000,
  );
});
