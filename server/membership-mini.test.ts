import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { randomUUID } from "node:crypto";
import { membershipFixture } from "./membership-fixture.ts";

// 使用原生页面真实脚本与真实 createApp API；这里只替代微信宿主，不模拟业务响应。
function nativePage(
  path: string,
  f: Awaited<ReturnType<typeof membershipFixture>>,
) {
  const events: { type: string; value: unknown }[] = [];
  const storage: Record<string, unknown> = {
    jyj_token: f.u.token,
    jyj_address: f.addressId,
  };
  const request = async (path: string, method = "GET", body?: unknown) => {
    const r = await f.call(path, {
      method,
      body,
      token: String(storage.jyj_token || ""),
    });
    if (r.status >= 400)
      throw Object.assign(new Error(r.data.message), { status: r.status });
    return r.data;
  };
  let config: Record<string, unknown> = {};
  runInNewContext(readFileSync(path, "utf8"), {
    Page: (v: Record<string, unknown>) => {
      config = v;
    },
    require: (name: string) =>
      name.endsWith("/api")
        ? { request }
        : { money: (v: number) => "¥" + (v / 100).toFixed(2) },
    wx: {
      getStorageSync: (k: string) => storage[k],
      showToast: (v: unknown) => events.push({ type: "toast", value: v }),
      navigateTo: (v: unknown) => events.push({ type: "navigate", value: v }),
      switchTab: (v: unknown) => events.push({ type: "tab", value: v }),
      showModal: (v: { success: (r: { confirm: boolean }) => void }) =>
        v.success({ confirm: true }),
      showActionSheet: (v: { success: (r: { tapIndex: number }) => void }) =>
        v.success({ tapIndex: 1 }),
      stopPullDownRefresh: () => {},
      navigateBack: () => {},
    },
    getCurrentPages: () => [{}],
  });
  // 页面宿主只暴露动态 Page 数据；业务断言均来自真实接口和持久化记录。
  const page = config as {
    data: Record<string, any>;
    setData: (value: Record<string, unknown>) => void;
    [key: string]: any;
  };
  page.setData = (v) => Object.assign(page.data, v);
  return { page, events, storage };
}

test("原生加入弹层提交真实申请、显示待核款结果与公司说明，不冒充身份通过", async (t) => {
  const f = await membershipFixture(t),
    { page } = nativePage("miniprogram/pages/onboarding/index.js", f);
  page.onLoad({});
  await page.onShow();
  assert.equal(page.data.selected.name, "店主入驻产品包");
  assert.equal(page.data.selected.lines[0].name, "测试精华");
  await page.submit();
  assert.match(page.data.error, /协议/);
  assert.equal(
    (await f.call("/onboardings", { token: f.u.token })).data.length,
    0,
  );
  page.agreement({ detail: { value: ["yes"] } });
  await page.submit();
  assert.equal(page.data.current.status, "submitted");
  assert.equal(page.data.current.receipt, null);
  assert.equal(page.data.showTransfer, true);
  assert.equal((await f.call("/me", { token: f.u.token })).data.identity, "D");
});

test("原生钱包确认整单付款、真实余额刷新与本人现金退款查询入口", async (t) => {
  const f = await membershipFixture(t);
  f.db
    .prepare("UPDATE users SET identity='C',principal_id=? WHERE id=?")
    .run(f.principal.user.id, f.u.user.id);
  const c = await f.call("/admin/receipts", {
    method: "POST",
    cookie: f.cookie,
    body: {
      ...f.receiptBody(20000, randomUUID()),
      purpose: "points",
      userId: f.u.user.id,
    },
  });
  assert.equal(c.status, 200);
  const order = await f.order(1),
    { page, events, storage } = nativePage(
      "miniprogram/pages/wallet/index.js",
      f,
    );
  page.onLoad({ orderId: String(order.id) });
  await page.load();
  assert.equal(page.data.wallet.availableText, "200.00");
  await page.pay();
  assert.equal(page.data.paid, true);
  assert.equal(page.data.wallet.availableText, "90.00");
  assert.equal(page.data.ledger[0].kind, "spend");
  page.filter();
  assert.ok(
    page.data.visible.every((e: { kind: string }) => e.kind === "credit"),
  );
  page.cashRefunds();
  assert.equal(
    (events.find((e) => e.type === "navigate")!.value as { url: string }).url,
    "/pages/cash-refunds/index",
  );
  delete storage.jyj_token;
  await page.load();
  assert.equal(page.data.wallet, null);
  assert.match(page.data.error, /请先登录/);
});
