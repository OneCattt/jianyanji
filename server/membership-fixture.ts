import { randomUUID } from "node:crypto";
import assert from "node:assert/strict";
import { commerceFixture } from "./commerce-fixture.ts";
export async function membershipFixture(
  t: Parameters<typeof commerceFixture>[0],
) {
  const f = await commerceFixture(t),
    principal = await f.user("principal"),
    inviter = await f.user("store");
  f.db
    .prepare("UPDATE users SET identity='B' WHERE id=?")
    .run(principal.user.id);
  f.db
    .prepare("UPDATE users SET identity='C',principal_id=? WHERE id=?")
    .run(principal.user.id, inviter.user.id);
  const rewards = {
    storeRewardCents: 2000,
    principalRewardCents: 5000,
    settlementDay: 20,
    reason: "配置真实验收档位",
  };
  assert.equal(
    (
      await f.call("/admin/reward-settings", {
        method: "PUT",
        cookie: f.cookie,
        body: rewards,
      })
    ).status,
    200,
  );
  const packages = (["C", "B"] as const).map((targetIdentity, i) => ({
    targetIdentity,
    name: i ? "主理人入驻产品包" : "店主入驻产品包",
    amountCents: (i + 1) * 20000,
    valueCents: (i + 1) * 30000,
    selfUseLabel: "专属代理订货价",
    benefits: "产品、课程与调肤事业支持",
    conditions: "品牌方核实实际经营与产品学习条件",
    agreement: "确认公司转款、真实商品交付与售后规则",
    rightsImage: "",
    active: true,
    lines: [{ skuId: f.p.skus[0].id, quantity: (i + 1) * 2, unitCents: 10000 }],
  }));
  assert.equal(
    (
      await f.call("/admin/membership-settings", {
        method: "PUT",
        cookie: f.cookie,
        body: { packages, reason: "配置实物产品包" },
      })
    ).status,
    200,
  );
  async function apply(
    targetIdentity: "B" | "C" = "C",
    inviterId: number | null = null,
    token = f.u.token,
    addressId = f.addressId,
  ) {
    return f.call("/onboardings", {
      method: "POST",
      token,
      body: {
        targetIdentity,
        inviterId,
        addressId,
        agreementAccepted: true,
        requestKey: randomUUID(),
      },
    });
  }
  const receiptBody = (
    amountCents = 20000,
    externalTransactionId = randomUUID(),
  ) => ({
    sourceChannel: "bank",
    accountRef: "公司收款账户-验收",
    externalTransactionId,
    amountCents,
    receivedAt: "2026-01-01T08:00:00.000Z",
    evidence: "银行原流水凭证-已人工核实",
    reason: "工作人员核实实际到账",
    verified: true,
  });
  async function collect(id: number, amount = 20000, source = randomUUID()) {
    return f.call(`/admin/onboardings/${id}/receipt`, {
      method: "POST",
      cookie: f.cookie,
      body: receiptBody(amount, source),
    });
  }
  async function approve(id: number, inviterId: number | null = null) {
    return f.call(`/admin/onboardings/${id}/review`, {
      method: "POST",
      cookie: f.cookie,
      body: {
        decision: "approved",
        inviterId,
        principalId: principal.user.id,
        relationshipsConfirmed: true,
        conditionsMet: true,
        reason: "分别核实收款、经营条件及邀请归属",
      },
    });
  }
  return {
    ...f,
    principal,
    inviter,
    rewards,
    packages,
    apply,
    receiptBody,
    collect,
    approve,
  };
}
