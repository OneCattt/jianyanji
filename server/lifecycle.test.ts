import { test, type TestContext } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { commerceFixture } from "./commerce-fixture.ts";
import type { RefundHooks } from "./aftersales.ts";
import { reconcileRewardReversals } from "./rewards.ts";
import type {
  PayChannel,
  ChannelTrade,
  RefundInput,
  RefundResult,
} from "./wechat-pay.ts";

/** 仅供本文件显式注入的微信测试渠道；没有网络调用或真实收付款。 */
class LifecycleWechat implements PayChannel {
  appId = "lifecycle-test-app";
  mchId = "lifecycle-test-merchant";
  trades = new Map<string, ChannelTrade>();
  refunds = new Map<string, RefundResult>();
  refundInputs: RefundInput[] = [];
  async create(v: { number: string; cents: number; openid: string }) {
    this.trades.set(v.number, {
      out_trade_no: v.number,
      appid: this.appId,
      mchid: this.mchId,
      trade_state: "SUCCESS",
      transaction_id: "test-wx-" + v.number,
      amount: { total: v.cents, currency: "CNY" },
      payer: { openid: v.openid },
    });
    return {
      timeStamp: "1",
      nonceStr: "explicit-lifecycle-test",
      package: "prepay_id=lifecycle-test",
      signType: "RSA" as const,
      paySign: "test-only-no-real-signature",
    };
  }
  async query(number: string) {
    const trade = this.trades.get(number);
    assert.ok(trade, "渠道查询必须对应真实 HTTP 发起的测试支付");
    return trade;
  }
  async close(number: string) {
    const trade = await this.query(number);
    trade.trade_state = "CLOSED";
  }
  notification(): ChannelTrade {
    throw new Error("生命周期用例只通过支付查询确认，不模拟未验签通知");
  }
  async refund(v: RefundInput) {
    const payment = await this.query(v.outTradeNo);
    assert.equal(v.transactionId, payment.transaction_id);
    assert.equal(v.totalCents, payment.amount!.total);
    assert.ok(v.refundCents > 0 && v.refundCents <= v.totalCents);
    this.refundInputs.push(v);
    const result: RefundResult = {
      status: "success",
      outRefundNo: v.outRefundNo,
      outTradeNo: v.outTradeNo,
      totalCents: v.totalCents,
      refundCents: v.refundCents,
      refundId: "test-refund-" + v.outRefundNo,
    };
    this.refunds.set(v.outRefundNo, result);
    return result;
  }
  async queryRefund(number: string) {
    const result = this.refunds.get(number);
    assert.ok(result, "不得伪造不存在的渠道退款");
    return result;
  }
}

interface LifecycleOrder {
  id: number;
  number: string;
  status: string;
  payment_state: string;
  payment_method: string;
  received_at: string | null;
  goods_cents: number;
  total_cents: number;
  snapshot: {
    afterSalesDays: number;
    afterSalesPolicy: string;
    principalId: number;
    identity: string;
  };
  lines: {
    id: number;
    quantity: number;
    refunded_qty: number;
    refund_held: number;
  }[];
}

async function lifecycle(
  t: TestContext,
  channel?: LifecycleWechat,
  refunds?: RefundHooks,
) {
  // commerceFixture 最终使用真实 fixture/createApp/内存 SQLite；外部渠道仅在调用方显式指定时启用。
  const f = await commerceFixture(t, {
    ...(channel ? { payments: { adapter: channel } } : {}),
    ...(refunds ? { refunds } : {}),
  });
  const principal = await f.user("lifecycle-existing-principal");
  const inviter = await f.user("lifecycle-existing-inviter");
  // 初始条件仅准备两名既有代理；本用例购买者始终从 D 开始，经真实入驻审核取得身份。
  f.db
    .prepare("UPDATE users SET identity='B' WHERE id=?")
    .run(principal.user.id);
  f.db
    .prepare("UPDATE users SET identity='C',principal_id=? WHERE id=?")
    .run(principal.user.id, inviter.user.id);
  const admin = async (
    path: string,
    body?: unknown,
    method = body ? "POST" : "GET",
  ) => f.call(path, { cookie: f.cookie, method, body });
  const user = async (path: string, body?: unknown, token = f.u.token) =>
    f.call(path, { token, method: body ? "POST" : "GET", body });
  async function ok(result: ReturnType<typeof f.call>) {
    const r = await result;
    assert.equal(r.status, 200, JSON.stringify(r.data));
    return r.data;
  }
  await ok(
    admin(
      "/admin/reward-settings",
      {
        storeRewardCents: 20000,
        principalRewardCents: 50000,
        settlementDay: 20,
        reason: "配置生命周期验收档位，非生产金额",
      },
      "PUT",
    ),
  );
  await ok(
    admin(
      "/admin/membership-settings",
      {
        packages: (["C", "B"] as const).map((targetIdentity) => ({
          targetIdentity,
          name: "生命周期实物产品包",
          amountCents: 100000,
          valueCents: 150000,
          selfUseLabel: "代理订货价",
          benefits: "产品与培训",
          conditions: "人工核对经营条件",
          agreement: "公司转款购买实物，不发订货积分",
          rightsImage: "",
          active: true,
          lines: [{ skuId: f.p.skus[0].id, quantity: 4, unitCents: 25000 }],
        })),
        reason: "准备生命周期真实持久化业务配置",
      },
      "PUT",
    ),
  );
  const receipt = (amountCents: number) => ({
    sourceChannel: "bank",
    accountRef: "生命周期测试公司账户",
    externalTransactionId: "test-bank-" + randomUUID(),
    amountCents,
    receivedAt: new Date().toISOString(),
    evidence: "测试人工核对到账资料，不代表真实银行到账",
    verified: true,
    reason: "生命周期验收模拟工作人员核实到账",
  });
  async function shipAndReceive(o: LifecycleOrder): Promise<LifecycleOrder> {
    const shipment = await ok(
      admin(`/admin/orders/${o.id}/shipments`, {
        carrier: "顺丰",
        tracking: "SF" + randomUUID().replaceAll("-", ""),
        requestKey: randomUUID(),
        reason: "生命周期验收登记实际出库",
        lines: o.lines.map((l) => ({
          lineId: l.id,
          quantity: l.quantity - l.refunded_qty,
        })),
      }),
    );
    const received = await ok(
      user(`/orders/${o.id}/shipments/${shipment.id}/receive`, {}),
    );
    assert.equal(received.status, "completed");
    assert.ok(received.received_at);
    return received;
  }
  async function onboard() {
    assert.equal((await ok(user("/me"))).identity, "D");
    const application = await ok(
      user("/onboardings", {
        targetIdentity: "C",
        inviterId: inviter.user.id,
        addressId: f.addressId,
        agreementAccepted: true,
        requestKey: randomUUID(),
      }),
    );
    const paid = await ok(
      admin(`/admin/onboardings/${application.id}/receipt`, receipt(100000)),
    );
    assert.equal(paid.order.payment_state, "paid");
    assert.equal(
      (await ok(user("/wallet"))).balanceCents,
      0,
      "产品包货款不得再发等额积分",
    );
    const received = await shipAndReceive(paid.order);
    assert.equal(
      (await ok(user("/me"))).identity,
      "D",
      "收款和收货不自动批准身份",
    );
    const approved = await ok(
      admin(`/admin/onboardings/${application.id}/review`, {
        decision: "approved",
        inviterId: inviter.user.id,
        principalId: principal.user.id,
        relationshipsConfirmed: true,
        conditionsMet: true,
        reason: "收款交付后分别核对邀请与归属再审批",
      }),
    );
    assert.equal((await ok(user("/me"))).identity, "C");
    assert.equal(approved.order.id, received.id);
    assert.equal(
      (await ok(admin("/admin/rewards"))).length,
      0,
      "审批不得自动生成奖励",
    );
    const reward = await ok(
      admin("/admin/rewards", {
        orderId: received.id,
        amountCents: 20000,
        reason: "依据已核款交付且审批通过的原快照人工登记邀请奖励",
      }),
    );
    assert.equal(reward.userId, inviter.user.id);
    assert.notEqual(reward.userId, principal.user.id);
    return {
      application: approved,
      order: approved.order as LifecycleOrder,
      reward,
    };
  }
  async function fundPoints(amountCents: number) {
    return ok(
      admin("/admin/receipts", {
        ...receipt(amountCents),
        userId: f.u.user.id,
        purpose: "points",
      }),
    );
  }
  async function buy(quantity: number, method: "points" | "wechat" = "points") {
    const order = await f.order(quantity);
    assert.equal(order.snapshot.identity, "C");
    assert.equal(order.snapshot.principalId, principal.user.id);
    if (method === "points") {
      const paid = await ok(user(`/orders/${order.id}/pay/points`, {}));
      assert.equal(paid.order.payment_state, "paid");
      assert.equal(paid.order.payment_method, "points");
    } else {
      await ok(user(`/orders/${order.id}/pay/wechat`, {}));
      await ok(
        user(`/orders/${order.id}/payment/query`, {
          reason: "查询显式测试渠道的付款事实",
        }),
      );
    }
    return (await ok(user(`/orders/${order.id}`))) as LifecycleOrder;
  }
  async function registerMargin(order: LifecycleOrder) {
    const amountCents = order.lines.reduce((n, l) => n + l.quantity * 2000, 0);
    const reward = await ok(
      admin("/admin/rewards", {
        orderId: order.id,
        amountCents,
        reason: "核对原店主单价100元、原主理人价80元，不含运费",
      }),
    );
    assert.equal(reward.userId, principal.user.id);
    assert.equal(reward.amountCents, amountCents);
    return reward;
  }
  function afterSalesDeadline(order: LifecycleOrder) {
    assert.ok(order.received_at);
    return (
      Date.parse(order.received_at) + order.snapshot.afterSalesDays * 86400000
    );
  }
  let businessNow: number | undefined;
  function advancePastAfterSales(order: LifecycleOrder) {
    // 月结至少晚于原售后截止一天，后续退款持续使用该时钟，绝不回拨到政策内。
    const now = Math.max(Date.now(), afterSalesDeadline(order) + 86400000);
    if (businessNow === undefined) {
      businessNow = now;
      t.mock.method(Date, "now", () => businessNow!);
    } else {
      businessNow = now;
    }
    assert.ok(Date.now() >= afterSalesDeadline(order) + 86400000);
  }
  async function rejectExpiredRefundWithoutException(order: LifecycleOrder) {
    assert.ok(Date.now() >= afterSalesDeadline(order) + 86400000);
    const before = await ok(user(`/orders/${order.id}`));
    const refundsBefore = await ok(user("/refunds"));
    const expired = await admin("/admin/refunds", {
      orderId: order.id,
      items: [{ lineId: order.lines[0].id, quantity: 1 }],
      shippingCents: 0,
      reason: "验证月结后超期退款未获财务特批必须拒绝",
      requestKey: randomUUID(),
    });
    assert.equal(expired.status, 409);
    assert.match(expired.data.message, /超过原订单售后期限/);
    assert.deepEqual(await ok(user(`/orders/${order.id}`)), before);
    assert.deepEqual(await ok(user("/refunds")), refundsBefore);
  }
  async function settle(userId: number, rewardId: number, month: string) {
    const planned = await ok(
      admin("/admin/reward-settlements", {
        userId,
        month,
        rewardIds: [rewardId],
        payee: "测试代理实际收款人",
        requestKey: randomUUID(),
        reason: "生命周期人工核对月结应付抵扣净付",
      }),
    );
    const result = {
      status: "paid",
      paidAt: new Date(Date.now()).toISOString(),
      proof: "测试人工核实的线下转账或全额抵扣凭据-" + planned.id,
      reason: "仅模拟线下核实资料，不调用真实银行打款",
    };
    await ok(admin(`/admin/reward-settlements/${planned.id}/result`, result));
    await ok(admin(`/admin/reward-settlements/${planned.id}/result`, result));
    return planned;
  }
  async function refund(
    order: LifecycleOrder,
    quantity: number,
    mode: "points" | "offline" | "wechat",
    shippingCents = 0,
  ) {
    const exceptionReason =
      order.received_at && Date.now() > afterSalesDeadline(order)
        ? "生命周期验收：财务核对原订单及已付奖励后特批超期退货，后续按原快照冲回并追偿"
        : undefined;
    const r = await ok(
      admin("/admin/refunds", {
        orderId: order.id,
        items: [{ lineId: order.lines[0].id, quantity }],
        shippingCents,
        reason: "生命周期真实售后接口受理退货",
        requestKey: randomUUID(),
        ...(exceptionReason ? { exceptionReason } : {}),
      }),
    );
    assert.equal(r.policy.days, order.snapshot.afterSalesDays);
    assert.equal(r.policy.policy, order.snapshot.afterSalesPolicy);
    assert.equal(r.policy.exceptionReason, exceptionReason ?? null);
    if (exceptionReason) {
      const audit = f.db
        .prepare(
          "SELECT reason,request_id FROM audit_events WHERE action='refund.request' AND object_type='refund' AND object_id=? AND is_deleted=0",
        )
        .get(String(r.id));
      assert.ok(audit);
      assert.ok(String(audit.reason).includes(exceptionReason));
      assert.ok(audit.request_id);
    }
    await ok(
      admin(`/admin/refunds/${r.id}/review`, {
        approve: true,
        identityDecision: mode === "offline" ? "retain" : null,
        reason: "人工审核商品退货，部分产品包暂保留身份，全退应由系统强制撤销",
      }),
    );
    await ok(
      admin(`/admin/refunds/${r.id}/inspect`, {
        returnedAt: new Date().toISOString(),
        inspection: "验收测试退回实物，确认数量与原订单相符",
        restock: true,
        reason: "生命周期退货验收",
      }),
    );
    const resultBody =
      mode === "offline"
        ? {
            recipient: "测试原产品包付款人",
            paidAt: new Date().toISOString(),
            proof: "测试人工核对线下退款凭证-" + r.id,
            confirmed: true,
            reason: "模拟人工资料确认，不代表执行真实银行退款",
          }
        : { reason: "执行原积分返还或显式微信测试渠道退款" };
    const completed = await ok(
      admin(`/admin/refunds/${r.id}/${mode}`, resultBody),
    );
    assert.equal(completed.status, "success", JSON.stringify(completed));
    assert.ok(completed.completed_at);
    assert.deepEqual(completed.policy, r.policy);
    const repeat = await ok(
      admin(`/admin/refunds/${r.id}/${mode}`, resultBody),
    );
    assert.equal(repeat.status, "success");
    return completed;
  }
  const rewardDetail = (id: number) => ok(admin(`/admin/rewards/${id}`));
  return {
    ...f,
    admin,
    user,
    ok,
    principal,
    inviter,
    onboard,
    fundPoints,
    buy,
    registerMargin,
    shipAndReceive,
    advancePastAfterSales,
    rejectExpiredRefundWithoutException,
    settle,
    refund,
    rewardDetail,
  };
}

test("连续业务：产品包核款交付审批→邀请奖励→积分订货价差→售后期结束一天后月结→超期特批部分及全退自动冲回→次月抵扣与实收", async (t) => {
  const f = await lifecycle(t);
  const onboarding = await f.onboard();
  const funded = await f.fundPoints(100000);
  assert.equal(funded.balanceCents, 100000);
  let goods = await f.buy(5);
  const margin = await f.registerMargin(goods);
  assert.equal(margin.status, "not_due");
  goods = await f.shipAndReceive(goods);
  assert.equal((await f.rewardDetail(margin.id)).eligible, false);
  f.advancePastAfterSales(goods);
  const first = await f.settle(f.principal.user.id, margin.id, "2026-01");
  assert.equal(first.gross_cents, 10000);
  assert.equal(first.offset_cents, 0);
  assert.equal(first.net_cents, 10000);
  const invitationPaid = await f.settle(
    f.inviter.user.id,
    onboarding.reward.id,
    "2026-01",
  );
  assert.equal(invitationPaid.net_cents, 20000);
  const beforeRefund = (await f.ok(f.user("/wallet"))).balanceCents;
  assert.equal(beforeRefund, 49000);
  await f.rejectExpiredRefundWithoutException(goods);
  assert.equal((await f.rewardDetail(margin.id)).reversedCents, 0);
  assert.equal((await f.ok(f.user("/refunds"))).length, 0);
  const partial = await f.refund(goods, 2, "points");
  assert.equal(partial.goods_cents, 20000);
  let detail = await f.rewardDetail(margin.id);
  assert.equal(
    detail.reversedCents,
    4000,
    "售后成功 hook 自动冲回原两件价差，未调用奖励补核接口",
  );
  assert.equal(
    detail.debts.reduce(
      (n: number, d: { remainingCents: number }) => n + d.remainingCents,
      0,
    ),
    4000,
  );
  assert.equal(
    (await f.ok(f.user("/wallet"))).balanceCents,
    beforeRefund + 20000,
  );
  const full = await f.refund(goods, 3, "points", 1000);
  assert.equal(full.total_cents, 31000);
  detail = await f.rewardDetail(margin.id);
  assert.equal(detail.amountCents, 10000);
  assert.equal(detail.settledCents, 10000);
  assert.equal(detail.reversedCents, 10000);
  assert.equal(detail.reversals.length, 2);
  assert.equal((await f.ok(f.user("/wallet"))).balanceCents, 100000);
  const originalOrder = await f.ok(f.user(`/orders/${goods.id}`));
  assert.equal(originalOrder.status, "refunded");
  assert.equal(originalOrder.lines[0].refunded_qty, 5);
  assert.equal(originalOrder.lines[0].refund_held, 0);
  assert.equal(
    (await f.rewardDetail(onboarding.reward.id)).reversedCents,
    0,
    "商品退款不冲回另一来源的入驻奖励",
  );
  let next = await f.buy(4);
  const later = await f.registerMargin(next);
  next = await f.shipAndReceive(next);
  f.advancePastAfterSales(next);
  const second = await f.settle(f.principal.user.id, later.id, "2026-02");
  assert.equal(second.gross_cents, 8000);
  assert.equal(second.offset_cents, 8000);
  assert.equal(second.net_cents, 0);
  const debts = await f.ok(f.admin("/admin/reward-debts"));
  assert.equal(
    debts.reduce(
      (n: number, d: { remainingCents: number }) => n + d.remainingCents,
      0,
    ),
    2000,
  );
  assert.ok(
    debts.every((d: { user_id: number }) => d.user_id === f.principal.user.id),
  );
  for (const d of debts) {
    const body = {
      reversalId: d.id,
      amountCents: d.remainingCents,
      receivedAt: new Date().toISOString(),
      proof: "测试人工核实剩余实际到账20元",
      requestKey: randomUUID(),
      reason: "模拟人工实收资料确认，不把欠款承诺当作到账",
    };
    const a = await f.ok(f.admin("/admin/reward-recoveries", body));
    const b = await f.ok(f.admin("/admin/reward-recoveries", body));
    assert.equal(a.id, b.id);
  }
  assert.equal((await f.ok(f.admin("/admin/reward-debts"))).length, 0);
  detail = await f.rewardDetail(margin.id);
  assert.equal(
    detail.offsets.reduce(
      (n: number, o: { amount_cents: number }) => n + o.amount_cents,
      0,
    ),
    8000,
  );
  assert.equal(
    detail.recoveries.reduce(
      (n: number, o: { amount_cents: number }) => n + o.amount_cents,
      0,
    ),
    2000,
  );
  const own = await f.ok(
    f.user(`/rewards/${margin.id}`, undefined, f.principal.token),
  );
  assert.equal(own.reversedCents, 10000);
  assert.equal(
    (await f.user(`/rewards/${margin.id}`, undefined, f.inviter.token)).status,
    404,
  );
  assert.equal(
    (await f.ok(f.user("/wallet"))).balanceCents,
    59000,
    "奖励抵扣与追回不得动订货积分",
  );
  const audits = f.db
    .prepare(
      "SELECT request_id FROM audit_events WHERE action IN ('reward.reverse','refund.points','reward.recovery.received') AND is_deleted=0",
    )
    .all();
  assert.ok(audits.length >= 5);
  assert.ok(audits.every((a) => Boolean(a.request_id)));
});

test("连续产品包售后：售后期结束一天后月结→超期未特批拒绝→特批分次货款比例冲回→全退强制撤销本次身份→原奖励、付款与积分历史保留", async (t) => {
  const f = await lifecycle(t),
    onboarding = await f.onboard();
  await f.fundPoints(20000);
  const spent = await f.buy(1);
  assert.equal(spent.payment_method, "points");
  const balance = (await f.ok(f.user("/wallet"))).balanceCents;
  f.advancePastAfterSales(onboarding.order);
  const paid = await f.settle(
    f.inviter.user.id,
    onboarding.reward.id,
    "2026-01",
  );
  assert.equal(paid.net_cents, 20000);
  await f.rejectExpiredRefundWithoutException(onboarding.order);
  assert.equal((await f.rewardDetail(onboarding.reward.id)).reversedCents, 0);
  for (const [quantity, expected] of [
    [1, 5000],
    [1, 10000],
    [2, 20000],
  ]) {
    await f.refund(onboarding.order, quantity, "offline");
    const r = await f.rewardDetail(onboarding.reward.id);
    assert.equal(r.reversedCents, expected);
    assert.equal(r.amountCents, 20000);
    assert.equal(r.settledCents, 20000);
    assert.equal(
      (await f.ok(f.user("/me"))).identity,
      expected === 20000 ? "D" : "C",
    );
  }
  const history = await f.ok(
    f.user(`/onboardings/${onboarding.application.id}`),
  );
  assert.equal(history.status, "revoked");
  assert.equal(history.order.status, "refunded");
  const original = await f.rewardDetail(onboarding.reward.id);
  assert.equal(original.reversals.length, 3);
  assert.equal(original.settlements[0].status, "paid");
  assert.equal(
    original.basis.onboardingApproved,
    true,
    "奖励保留原审批快照，不受撤销后订单快照覆盖",
  );
  assert.equal(
    original.debts.reduce(
      (n: number, d: { remainingCents: number }) => n + d.remainingCents,
      0,
    ),
    20000,
  );
  const receipts = await f.ok(f.admin("/admin/receipts"));
  assert.equal(
    receipts.find(
      (r: { id: number }) => r.id === onboarding.application.receipt.id,
    ).cash_refunded_cents,
    100000,
  );
  assert.equal((await f.ok(f.user("/wallet"))).balanceCents, balance);
  assert.equal(
    (await f.ok(f.user(`/orders/${spent.id}`))).payment_method,
    "points",
  );
  assert.equal(
    (await f.ok(f.user("/rewards", undefined, f.inviter.token))).items[0].id,
    onboarding.reward.id,
  );
  const applicationRow = f.db
    .prepare("SELECT is_deleted FROM onboardings WHERE id=?")
    .get(onboarding.application.id)!;
  const rewardRow = f.db
    .prepare("SELECT is_deleted FROM rewards WHERE id=?")
    .get(onboarding.reward.id)!;
  assert.equal(applicationRow.is_deleted, 0);
  assert.equal(rewardRow.is_deleted, 0);
});

test("连续微信分支：显式支付adapter真实HTTP落支付流水→发货收货→售后HTTP原路退款及奖励冲回", async (t) => {
  const channel = new LifecycleWechat(),
    f = await lifecycle(t, channel);
  await f.onboard();
  let goods = await f.buy(2, "wechat");
  assert.equal(goods.payment_state, "paid");
  assert.equal(goods.payment_method, "wechat");
  const margin = await f.registerMargin(goods);
  goods = await f.shipAndReceive(goods);
  const completed = await f.refund(goods, 1, "wechat");
  assert.equal(completed.total_cents, 10000);
  assert.equal(channel.refundInputs.length, 1);
  const payment = f.db
    .prepare(
      "SELECT transaction_id,out_trade_no FROM payments WHERE order_id=? AND is_deleted=0",
    )
    .get(goods.id)!;
  assert.equal(payment.out_trade_no, channel.refundInputs[0].outTradeNo);
  assert.equal(payment.transaction_id, channel.refundInputs[0].transactionId);
  const reward = await f.rewardDetail(margin.id);
  assert.equal(reward.amountCents, 4000);
  assert.equal(reward.reversedCents, 2000);
  assert.equal(reward.remainingCents, 2000);
  assert.equal(reward.debts.length, 0);
  assert.equal((await f.ok(f.user("/wallet"))).balanceCents, 0);
});

test("11 真实退款hook末尾故障：积分返还与奖励冲回全部回滚，恢复后原退款单重试且不重复入账", async (t) => {
  let failHook = true;
  let hookCalls = 0;
  let observedMutationBeforeFailure = false;
  const f = await lifecycle(t, undefined, {
    reverseRewards(db, orderId, actor, requestId) {
      hookCalls++;
      // 不替换主线程的真实积分返还 hook；真实售后先返积分、更新退款，再进入此 hook。
      reconcileRewardReversals(db, orderId, actor, requestId);
      if (failHook) {
        const refund = db
          .prepare(
            "SELECT status FROM refunds WHERE order_id=? AND is_deleted=0",
          )
          .get(orderId)!;
        const reward = db
          .prepare(
            "SELECT reversed_cents FROM rewards WHERE order_id=? AND is_deleted=0",
          )
          .get(orderId)!;
        const returned = db
          .prepare(
            "SELECT SUM(delta_cents) AS cents FROM wallet_ledger WHERE order_id=? AND kind='order_refund' AND is_deleted=0",
          )
          .get(orderId)!;
        assert.equal(refund.status, "success");
        assert.equal(reward.reversed_cents, 2000);
        assert.equal(returned.cents, 10000);
        observedMutationBeforeFailure = true;
        // 在真实冲回 SAVEPOINT 已释放后抛错，确认最外层事务仍能回滚积分和奖励。
        throw new Error("生命周期测试故障：真实退款hook执行后失败");
      }
    },
  });
  const onboarding = await f.onboard();
  await f.fundPoints(50000);
  let goods = await f.buy(2);
  const margin = await f.registerMargin(goods);
  goods = await f.shipAndReceive(goods);
  const refund = await f.ok(
    f.admin("/admin/refunds", {
      orderId: goods.id,
      items: [{ lineId: goods.lines[0].id, quantity: 1 }],
      shippingCents: 0,
      requestKey: randomUUID(),
      reason: "真实接口受理，验证积分退款故障恢复",
    }),
  );
  await f.ok(
    f.admin(`/admin/refunds/${refund.id}/review`, {
      approve: true,
      identityDecision: null,
      reason: "审核商品退货",
    }),
  );
  await f.ok(
    f.admin(`/admin/refunds/${refund.id}/inspect`, {
      returnedAt: new Date().toISOString(),
      inspection: "已核对实际退货一瓶",
      restock: true,
      reason: "独立完成退货验收，再验证退款事务回滚",
    }),
  );
  // 受理、审批和验收已分别提交；退款失败必须回到此刻，而非抹掉先前合法的售后记录。
  const state = () =>
    Object.fromEntries(
      [
        "refunds",
        "orders",
        "order_lines",
        "payments",
        "skus",
        "returned_inventory",
        "wallet_lots",
        "wallet_spend_allocations",
        "wallet_ledger",
        "company_receipts",
        "users",
        "onboardings",
        "onboarding_identity_events",
        "rewards",
        "reward_reversals",
        "reward_settlements",
        "reward_offsets",
        "reward_recoveries",
      ].map((table) => [
        table,
        f.db.prepare(`SELECT * FROM ${table} ORDER BY id`).all(),
      ]),
    );
  const before = state();
  const beforeWallet = await f.ok(f.user("/wallet"));
  const beforeIdentity = await f.ok(f.user("/me"));
  const reverseAuditCount = () =>
    f.db
      .prepare(
        "SELECT COUNT(*) AS n FROM audit_events WHERE action='reward.reverse' AND is_deleted=0",
      )
      .get()!.n;
  const beforeAuditCount = reverseAuditCount();
  const url = `/admin/refunds/${refund.id}/points`;
  const body = { reason: "按原付款积分批次完成同一售后单退款" };
  const failed = await f.admin(url, body);
  assert.equal(failed.status, 500);
  assert.equal(failed.data.code, "INTERNAL_ERROR");
  assert.ok(failed.data.requestId);
  assert.equal(failed.headers.get("x-request-id"), failed.data.requestId);
  assert.equal(
    observedMutationBeforeFailure,
    true,
    "必须确认真实返积分和冲回已执行，不能用提前拒绝冒充回滚测试",
  );
  assert.equal(hookCalls, 1);
  assert.deepEqual(
    state(),
    before,
    "退款、货款来源、余额流水、商品占用、身份及奖励均须完整回滚",
  );
  assert.equal(
    reverseAuditCount(),
    beforeAuditCount,
    "事务回滚后不能残留奖励冲回成功审计",
  );
  assert.deepEqual(await f.ok(f.user("/wallet")), beforeWallet);
  assert.deepEqual(await f.ok(f.user("/me")), beforeIdentity);
  const pending = (await f.ok(f.user("/refunds"))).find(
    (r: { id: number }) => r.id === refund.id,
  );
  assert.equal(pending.status, "approved");
  assert.equal(pending.completed_at, null);
  assert.equal((await f.rewardDetail(margin.id)).reversedCents, 0);
  assert.equal((await f.rewardDetail(onboarding.reward.id)).reversedCents, 0);
  // 只移除注入故障，不改退款表、不重建 App、不换单号，原 HTTP 路由同单恢复。
  failHook = false;
  const recovered = await f.ok(f.admin(url, body));
  assert.equal(recovered.id, refund.id);
  assert.equal(recovered.number, refund.number);
  assert.equal(recovered.status, "success");
  assert.ok(recovered.completed_at);
  assert.equal(hookCalls, 2);
  const wallet = await f.ok(f.user("/wallet"));
  assert.equal(wallet.balanceCents, beforeWallet.balanceCents + 10000);
  const ledger = wallet.ledger.filter(
    (l: { kind: string; order_id: number }) =>
      l.kind === "order_refund" && l.order_id === goods.id,
  );
  assert.equal(ledger.length, 1);
  assert.equal(ledger[0].delta_cents, 10000);
  const reward = await f.rewardDetail(margin.id);
  assert.equal(reward.reversedCents, 2000);
  assert.equal(reward.reversals.length, 1);
  assert.equal(reward.reversals[0].refund_id, refund.id);
  const order = await f.ok(f.user(`/orders/${goods.id}`));
  assert.equal(order.lines[0].refund_held, 0);
  assert.equal(order.lines[0].refunded_qty, 1);
  assert.deepEqual(await f.ok(f.user("/me")), beforeIdentity);
  assert.equal((await f.rewardDetail(onboarding.reward.id)).reversedCents, 0);
  const afterSuccess = state();
  const repeat = await f.ok(f.admin(url, body));
  assert.equal(repeat.id, refund.id);
  assert.equal(repeat.status, "success");
  assert.equal(hookCalls, 2, "成功后的重复请求不得重跑退款副作用");
  assert.deepEqual(
    state(),
    afterSuccess,
    "同单重复确认不得重复返积分或冲回奖励",
  );
});
