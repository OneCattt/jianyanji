import { test, type TestContext } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { fixture } from "./test-fixture.ts";
import { transaction } from "./db.ts";
import { migrateRewards, reconcileRewardReversals } from "./rewards.ts";

async function setup(t: TestContext) {
  const f = await fixture(t),
    { db } = f;
  migrateRewards(db);
  migrateRewards(db);
  const c = Number(
    db.prepare("INSERT INTO categories(name) VALUES('奖励测试分类')").run()
      .lastInsertRowid,
  );
  const p = Number(
    db
      .prepare(
        "INSERT INTO products(category_id,name,subtitle,description,image,detail_image) VALUES(?,'奖励测试商品','','说明','/assets/x.png','/assets/x.png')",
      )
      .run(c).lastInsertRowid,
  );
  const sku = Number(
    db
      .prepare(
        "INSERT INTO skus(product_id,name,unit,retail_cents,store_cents,principal_cents,stock,active) VALUES(?,'规格','瓶',10000,10000,8000,100,1)",
      )
      .run(p).lastInsertRowid,
  );
  const principal = await f.user("principal"),
    store = await f.user("store"),
    visitor = await f.user("visitor");
  const cookie = await f.admin(),
    limited = await f.admin("operator");
  function call(
    path: string,
    body?: unknown,
    credential = cookie,
    method = body ? "POST" : "GET",
  ) {
    return f.call(path, {
      method,
      body,
      ...(credential.startsWith("jyj_admin=")
        ? { cookie: credential }
        : { token: credential }),
    });
  }
  async function settings() {
    const r = await call(
      "/admin/reward-settings",
      {
        storeRewardCents: 20000,
        principalRewardCents: 50000,
        settlementDay: 20,
        reason: "真实测试配置",
      },
      cookie,
      "PUT",
    );
    assert.equal(r.status, 200, r.data.message);
  }
  function order({
    kind = "goods",
    identity = "C",
    reward = 20000,
    quantity = 3,
    goods = quantity * 10000,
    days = 7,
    received = "2026-01-01T00:00:00.000Z",
    payment = "wechat",
    extra = {},
  }: {
    kind?: string;
    identity?: string;
    reward?: number;
    quantity?: number;
    goods?: number;
    days?: number;
    received?: string | null;
    payment?: string;
    extra?: Record<string, unknown>;
  } = {}) {
    const snapshot = {
      identity,
      principalId: principal.user.id,
      inviterId: visitor.user.id,
      rewardCents: reward,
      onboardingApproved: true,
      onboardingId: 1,
      afterSalesDays: days,
      ...extra,
    };
    const id = Number(
      db
        .prepare(
          "INSERT INTO orders(number,user_id,kind,status,payment_state,payment_method,total_cents,goods_cents,shipping_cents,snapshot,expires_at,received_at,request_key) VALUES(?,?,?,'completed','paid',?,?,?,?,?,?,?,?)",
        )
        .run(
          randomUUID(),
          store.user.id,
          kind,
          payment,
          goods + 1000,
          goods,
          1000,
          JSON.stringify(snapshot),
          "2027-01-01",
          received,
          randomUUID(),
        ).lastInsertRowid,
    );
    const line = Number(
      db
        .prepare(
          "INSERT INTO order_lines(order_id,sku_id,quantity,unit_cents,principal_cents,received_qty) VALUES(?,?,?,?,?,?)",
        )
        .run(id, sku, quantity, 10000, 8000, quantity).lastInsertRowid,
    );
    return { id, line };
  }
  function refund(
    o: { id: number; line: number },
    goods: number,
    qty = 1,
    status = "success",
  ) {
    return Number(
      db
        .prepare(
          "INSERT INTO refunds(order_id,number,status,goods_cents,shipping_cents,total_cents,lines,kind,reason,policy_snapshot,request_key,request_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        )
        .run(
          o.id,
          randomUUID(),
          status,
          goods,
          0,
          goods,
          JSON.stringify([
            {
              lineId: o.line,
              skuId: sku,
              quantity: qty,
              unitCents: 10000,
              principalCents: 8000,
            },
          ]),
          "goods",
          "测试退款",
          "{}",
          randomUUID(),
          randomUUID(),
        ).lastInsertRowid,
    );
  }
  async function register(o: { id: number }, amount = 6000) {
    const r = await call("/admin/rewards", {
      orderId: o.id,
      amountCents: amount,
      reason: "人工核对原交易",
    });
    assert.equal(r.status, 200, r.data.message);
    return r.data;
  }
  async function plan(
    rewardIds: number[],
    userId = principal.user.id,
    month = "2026-01",
  ) {
    const r = await call("/admin/reward-settlements", {
      userId,
      month,
      rewardIds,
      payee: "实际代理收款人",
      requestKey: randomUUID(),
      reason: "人工月结核算",
    });
    return r;
  }
  async function paid(id: number) {
    return call(`/admin/reward-settlements/${id}/result`, {
      status: "paid",
      paidAt: "2026-01-20T08:00:00.000Z",
      proof: "线下银行付款或抵扣凭据-001",
      reason: "人工核实完成",
    });
  }
  return {
    ...f,
    call,
    cookie,
    limited,
    principal,
    store,
    visitor,
    order,
    refund,
    register,
    settings,
    plan,
    paid,
  };
}
test("14 入驻两档快照、直接邀请人、配置不追改、重复来源与本人隔离", async (t) => {
  const f = await setup(t);
  const o = f.order({ kind: "package", identity: "C", goods: 100000 });
  const r = await f.register(o, 20000);
  assert.equal(r.userId, f.visitor.user.id);
  assert.equal(r.status, "eligible");
  await f.settings();
  await f.call(
    "/admin/reward-settings",
    {
      storeRewardCents: 30000,
      principalRewardCents: 60000,
      settlementDay: 25,
      reason: "调整后续业务",
    },
    f.cookie,
    "PUT",
  );
  assert.equal(
    (await f.call(`/admin/rewards/${r.id}`)).data.amountCents,
    20000,
  );
  const b = await f.register(
    f.order({ kind: "package", identity: "B", reward: 50000, goods: 100000 }),
    50000,
  );
  assert.equal(b.amountCents, 50000);
  assert.equal(
    (
      await f.call("/admin/rewards", {
        orderId: o.id,
        amountCents: 20000,
        reason: "重复",
      })
    ).status,
    409,
  );
  assert.equal(
    (await f.call(`/rewards/${r.id}`, undefined, f.principal.token)).status,
    404,
  );
  const own = await f.call("/rewards", undefined, f.visitor.token);
  assert.equal(own.data.items.length, 2);
  assert.equal("basis" in own.data.items[0], false);
  assert.equal(
    (await f.call("/admin/rewards", undefined, f.limited)).status,
    403,
  );
  assert.equal(
    (
      await f.call("/admin/rewards", {
        orderId: f.order({ extra: { afterSalesDays: undefined } }).id,
        amountCents: 6000,
        reason: "无原始期限",
      })
    ).status,
    400,
  );
});
test("15 原价差60元、不含运费、微信积分一致且当前改价不影响", async (t) => {
  const f = await setup(t);
  const a = await f.register(f.order());
  const b = await f.register(f.order({ payment: "points" }));
  assert.equal(a.amountCents, 6000);
  assert.equal(b.amountCents, 6000);
  f.db.exec("UPDATE skus SET principal_cents=7000");
  assert.equal((await f.call(`/admin/rewards/${a.id}`)).data.amountCents, 6000);
  assert.equal(
    (
      await f.call("/admin/rewards", {
        orderId: f.order().id,
        amountCents: 9000,
        reason: "错误当前价",
      })
    ).status,
    409,
  );
});
test("16 订货退一件冲回20元、失败不冲回、重复与事务回滚", async (t) => {
  const f = await setup(t),
    o = f.order(),
    r = await f.register(o);
  f.refund(o, 10000, 1, "failed");
  reconcileRewardReversals(f.db, o.id, f.manager, "failed-test");
  assert.equal((await f.call(`/admin/rewards/${r.id}`)).data.reversedCents, 0);
  f.db.exec("UPDATE refunds SET status='rejected'");
  f.refund(o, 10000);
  assert.throws(() =>
    transaction(f.db, () => {
      reconcileRewardReversals(f.db, o.id, f.manager, "rollback");
      throw new Error("外层失败");
    }),
  );
  assert.equal((await f.call(`/admin/rewards/${r.id}`)).data.reversedCents, 0);
  for (let n = 0; n < 2; n++)
    transaction(f.db, () =>
      reconcileRewardReversals(f.db, o.id, f.manager, "repeat"),
    );
  const d = (await f.call(`/admin/rewards/${r.id}`)).data;
  assert.equal(d.reversedCents, 2000);
  assert.equal(d.remainingCents, 4000);
  assert.equal(d.reversals.length, 1);
  assert.equal(d.debts.length, 0);
});
test("16 产品包累计比例、取整、全退补尾与退款完成乱序", async (t) => {
  const f = await setup(t),
    o = f.order({ kind: "package", goods: 100000 }),
    r = await f.register(o, 20000);
  for (const [g, target] of [
    [25000, 5000],
    [25000, 10000],
    [50000, 20000],
  ]) {
    f.refund(o, g);
    reconcileRewardReversals(f.db, o.id, f.manager, "package");
    assert.equal(
      (await f.call(`/admin/rewards/${r.id}`)).data.reversedCents,
      target,
    );
  }
  const tiny = f.order({ kind: "package", goods: 6, reward: 1 }),
    q = await f.register(tiny, 1);
  const early = f.refund(tiny, 1, 1, "processing");
  f.refund(tiny, 3);
  reconcileRewardReversals(f.db, tiny.id, f.manager, "round");
  assert.equal((await f.call(`/admin/rewards/${q.id}`)).data.reversedCents, 1);
  f.db.prepare("UPDATE refunds SET status='success' WHERE id=?").run(early);
  reconcileRewardReversals(f.db, tiny.id, f.manager, "out-of-order");
  f.refund(tiny, 2);
  reconcileRewardReversals(f.db, tiny.id, f.manager, "full");
  assert.equal((await f.call(`/admin/rewards/${q.id}`)).data.reversedCents, 1);
});
test("17 原售后期、未收货、待售后、实际退款未冲回拦截与真实付款凭证", async (t) => {
  const f = await setup(t);
  const r = await f.register(f.order());
  assert.equal((await f.plan([r.id])).status, 409);
  await f.settings();
  const noReceipt = await f.register(f.order({ received: null }));
  assert.equal((await f.plan([noReceipt.id])).status, 409);
  const inPeriod = await f.register(
    f.order({ received: new Date().toISOString() }),
  );
  assert.equal((await f.plan([inPeriod.id])).status, 409);
  const openOrder = f.order(),
    open = await f.register(openOrder);
  const ref = f.refund(openOrder, 10000, 1, "unknown");
  assert.equal((await f.plan([open.id])).status, 409);
  f.db.prepare("UPDATE refunds SET status='success' WHERE id=?").run(ref);
  assert.equal((await f.plan([open.id])).status, 409);
  reconcileRewardReversals(f.db, openOrder.id, f.manager, "sync");
  const p = await f.plan([open.id]);
  assert.equal(p.status, 200, p.data.message);
  assert.equal(p.data.gross_cents, 4000);
  assert.equal(
    (
      await f.call(`/admin/reward-settlements/${p.data.id}/result`, {
        status: "paid",
        reason: "缺付款凭据",
      })
    ).status,
    409,
  );
  assert.equal((await f.paid(p.data.id)).status, 200);
  assert.equal((await f.paid(p.data.id)).status, 200);
  assert.equal((await f.plan([r.id])).status, 409);
  assert.equal(
    (await f.call(`/admin/rewards/${open.id}`)).data.settledCents,
    4000,
  );
});
test("18 已付欠100后续80抵扣、剩20持续待收回、实收去重且不跨人", async (t) => {
  const f = await setup(t);
  await f.settings();
  const o = f.order({ kind: "package", goods: 100000, reward: 10000 }),
    r = await f.register(o, 10000);
  const p = await f.plan([r.id], f.visitor.user.id);
  assert.equal(p.status, 200, p.data.message);
  assert.equal((await f.paid(p.data.id)).status, 200);
  f.refund(o, 100000);
  transaction(f.db, () =>
    reconcileRewardReversals(f.db, o.id, f.manager, "paid-reversal"),
  );
  let debts = (await f.call("/admin/reward-debts")).data;
  assert.equal(debts[0].remainingCents, 10000);
  const other = await f.register(f.order());
  const unrelated = await f.plan([other.id], f.principal.user.id, "2026-02");
  assert.equal(unrelated.data.offset_cents, 0);
  const next = await f.register(
    f.order({ kind: "package", goods: 100000, reward: 8000 }),
    8000,
  );
  const q = await f.plan([next.id], f.visitor.user.id, "2026-02");
  assert.equal(q.data.gross_cents, 8000);
  assert.equal(q.data.offset_cents, 8000);
  assert.equal(q.data.net_cents, 0);
  assert.equal((await f.paid(q.data.id)).status, 200);
  debts = (await f.call("/admin/reward-debts")).data;
  assert.equal(debts[0].remainingCents, 2000);
  const body = {
    reversalId: debts[0].id,
    amountCents: 2000,
    receivedAt: "2026-02-25T00:00:00.000Z",
    proof: "实际到账流水-20",
    requestKey: randomUUID(),
    reason: "核实实际归还",
  };
  const attempts = await Promise.all([
    f.call("/admin/reward-recoveries", body),
    f.call("/admin/reward-recoveries", body),
  ]);
  assert.deepEqual(
    attempts.map((x) => x.status),
    [200, 200],
  );
  assert.equal(attempts[0].data.id, attempts[1].data.id);
  assert.equal((await f.call("/admin/reward-debts")).data.length, 0);
  assert.equal(
    (
      await f.call("/admin/reward-recoveries", {
        ...body,
        requestKey: randomUUID(),
      })
    ).status,
    409,
  );
  const detail = (await f.call(`/rewards/${r.id}`, undefined, f.visitor.token))
    .data;
  assert.equal(detail.offsets[0].amount_cents, 8000);
  assert.equal(detail.recoveries[0].amount_cents, 2000);
});
test("18 实收与待结算计划竞争时阻止过期抵扣，不超收；未明付款不可取消重付", async (t) => {
  const f = await setup(t);
  await f.settings();
  const o = f.order({ kind: "package", goods: 100000, reward: 10000 }),
    r = await f.register(o, 10000);
  const p = await f.plan([r.id], f.visitor.user.id);
  await f.paid(p.data.id);
  f.refund(o, 100000);
  reconcileRewardReversals(f.db, o.id, f.manager, "debt");
  const next = await f.register(
      f.order({ kind: "package", goods: 100000, reward: 8000 }),
      8000,
    ),
    q = await f.plan([next.id], f.visitor.user.id, "2026-02"),
    d = (await f.call("/admin/reward-debts")).data[0];
  assert.equal(
    (
      await f.call("/admin/reward-recoveries", {
        reversalId: d.id,
        amountCents: 3000,
        receivedAt: "2026-02-01T00:00:00.000Z",
        proof: "实际收回30元",
        requestKey: randomUUID(),
        reason: "核实到账",
      })
    ).status,
    200,
  );
  assert.equal((await f.paid(q.data.id)).status, 409);
  assert.equal(
    (await f.call("/admin/reward-debts")).data[0].remainingCents,
    7000,
  );
  assert.equal(
    (
      await f.call(`/admin/reward-settlements/${q.data.id}/result`, {
        status: "unknown",
        reason: "核实银行付款中",
      })
    ).status,
    200,
  );
  assert.equal(
    (
      await f.call(`/admin/reward-settlements/${q.data.id}/result`, {
        status: "cancelled",
        reason: "不能未明取消",
      })
    ).status,
    409,
  );
});

test("14/17 真实入驻收款审批链可人工登记，不自动生成奖励，售后期统一只读且历史不追改", async (t) => {
  const { membershipFixture } = await import("./membership-fixture.ts");
  const f = await membershipFixture(t);
  const applied = await f.apply("C", f.inviter.user.id);
  assert.equal(applied.status, 200, applied.data.message);
  const collected = await f.collect(applied.data.id);
  assert.equal(collected.status, 200, collected.data.message);
  const approved = await f.approve(applied.data.id, f.inviter.user.id);
  assert.equal(approved.status, 200, approved.data.message);
  assert.equal(
    f.db.prepare("SELECT COUNT(*) AS n FROM rewards WHERE is_deleted=0").get()!
      .n,
    0,
  );
  const registered = await f.call("/admin/rewards", {
    method: "POST",
    cookie: f.cookie,
    body: {
      orderId: approved.data.order.id,
      amountCents: 2000,
      reason: "依据入驻审批与银行原收款人工登记",
    },
  });
  assert.equal(registered.status, 200, registered.data.message);
  assert.equal(registered.data.userId, f.inviter.user.id);
  assert.equal(registered.data.basis.receiptId, approved.data.receipt.id);
  assert.equal(registered.data.status, "not_due");
  assert.equal(
    (await f.call("/admin/reward-settings", { cookie: f.cookie })).data
      .afterSalesDays,
    7,
  );
  assert.equal(
    (
      await f.call("/admin/aftersales-settings", {
        method: "PUT",
        cookie: f.cookie,
        body: {
          days: 30,
          policy: "仅新业务使用的新政策",
          reason: "统一售后政策调整",
        },
      })
    ).status,
    200,
  );
  assert.equal(
    (await f.call("/admin/reward-settings", { cookie: f.cookie })).data
      .afterSalesDays,
    30,
  );
  assert.equal(
    (await f.call(`/admin/rewards/${registered.data.id}`, { cookie: f.cookie }))
      .data.basis.afterSalesDays,
    7,
  );
  const own = (await f.call("/rewards", { token: f.inviter.token })).data;
  assert.equal(own.items[0].id, registered.data.id);
  assert.equal(
    (
      await f.call(`/rewards/${registered.data.id}`, {
        token: f.principal.token,
      })
    ).status,
    404,
  );
});
