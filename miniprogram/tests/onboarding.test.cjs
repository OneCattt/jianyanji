const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const {
  createRequire,
  setData,
} = require("../../scripts/miniprogram-runtime.cjs");
const tick = () => new Promise(setImmediate);
const change = (value) => ({ detail: { value } });
const pending = () => {
  let resolve, reject;
  const promise = new Promise((a, b) => {
    resolve = a;
    reject = b;
  });
  return { promise, resolve, reject };
};
const relation = (id = 20, principal = 20) => ({
  inviterId: id,
  inviterName: "测试邀请人" + id,
  principalId: principal,
  principalName: "测试归属" + principal,
  inviterRelationVersion: 1,
});
const packages = ["C", "B"].map((targetIdentity) => ({
  targetIdentity,
  name: targetIdentity + "测试产品包",
  amountCents: targetIdentity === "C" ? 298000 : 1980000,
  valueCents: targetIdentity === "C" ? 461400 : 2478600,
  selfUseLabel: "配置的订货权益",
  benefits: "配置的文字权益",
  conditions: "真实入驻条件",
  agreement: "入驻协议",
  rightsImage: "",
  lines: [
    {
      skuId: 1,
      lineKey: "main",
      name: "测试商品",
      quantity: 3,
      unitCents: 100,
      storageEnabled: true,
    },
  ],
}));

function runtime(options = {}) {
  const cache = { jyj_token: "token", ...options.cache },
    calls = [],
    toasts = [],
    navigation = [];
  let page,
    current = options.current;
  const request = async (url, method = "GET", body) => {
    calls.push({ url, method, body });
    if (options.request) {
      const result = options.request(url, method, body);
      if (result !== undefined) return result;
    }
    if (url === "/membership") return { packages: structuredClone(packages) };
    if (url === "/me") return { id: 7, identity: "D" };
    if (url.startsWith("/mini/address"))
      return {
        address: { id: 1, recipient: "测试客户", phone: "13800138000" },
      };
    if (url.startsWith("/mini/onboardings"))
      return { items: current ? [current] : [] };
    if (url.startsWith("/mini/qualifications"))
      return { items: [], hasMore: false, nextPage: null };
    if (url.startsWith("/invitations/candidate"))
      return relation(
        Number(
          new URL("https://example.test" + url).searchParams.get("inviterId"),
        ) || 20,
        url.includes("targetIdentity=B")
          ? 7
          : Number(
              new URL("https://example.test" + url).searchParams.get(
                "inviterId",
              ),
            ) || 20,
      );
    if (url === "/onboardings/9/order") return { id: 90 };
    if (url === "/onboardings") {
      current = {
        id: 9,
        status: "submitted",
        target_identity: body.targetIdentity,
        inviter_id: body.inviterId,
        principal_id: body.principalId,
        relation_confirmed: 1,
        package_snapshot: structuredClone(
          packages.find((p) => p.targetIdentity === body.targetIdentity),
        ),
      };
      return current;
    }
    return {};
  };
  const wx = {
    getStorageSync: (key) => cache[key],
    setStorageSync: (key, v) => (cache[key] = v),
    removeStorageSync: (key) => delete cache[key],
    showToast: (v) => toasts.push(v),
    hideKeyboard() {},
    createSelectorQuery() {
      const query = {
        select: () => query,
        boundingClientRect: () => query,
        exec: (callback) => callback([null, null]),
      };
      return query;
    },
    getWindowInfo: () => ({ windowHeight: 844, statusBarHeight: 44 }),
    getMenuButtonBoundingClientRect: () => ({ bottom: 82 }),
    navigateTo(value) {
      navigation.push(value);
      if (value && value.success) value.success();
    },
  };
  const entry = path.resolve(__dirname, "../pages/onboarding/index.js");
  vm.runInNewContext(fs.readFileSync(entry, "utf8"), {
    wx,
    Page: (v) => (page = v),
    require: createRequire(entry, { wx }, request),
  });
  page = {
    ...page,
    data: structuredClone(page.data),
    setData,
    selectComponent: () => ({ open: async () => ({ confirm: true }) }),
  };
  page.onLoad(options.query || {});
  return { page, cache, calls, toasts, navigation, wx };
}

test("显式邀请编号覆盖旧分享候选，二维码scene按已有参数解析", () => {
  const a = runtime({
    query: { inviterId: "30" },
    cache: { jyj_invitation_candidate: "old-share" },
  });
  assert.equal(a.page.data.inviterId, "30");
  assert.equal(a.page.data.shareCode, "");
  const b = runtime({
    query: { scene: encodeURIComponent("shareCode=shared-code") },
  });
  assert.equal(b.page.data.shareCode, "shared-code");
  const c = runtime({ query: { scene: encodeURIComponent("inviterId=20") } });
  assert.equal(c.page.data.inviterId, "20");
});

test("手动自邀在发请求前拦截，失败保留原输入", async () => {
  const { page, calls } = runtime();
  await page.onShow();
  page.onInviter(change("7"));
  assert.equal(await page.resolveInvitation(), null);
  assert.equal(
    calls.filter((c) => c.url.startsWith("/invitations/candidate")).length,
    0,
  );
  assert.equal(page.data.invitationStatus, "failed");
  assert.match(page.data.invitationError, /不能填写自己的邀请人编号/);
  assert.equal(page.data.inviterId, "7");
  assert.equal(page.data.canApply, false);
});

test("同一邀请核验合并，旧编号的迟到结果不能覆盖新编号", async () => {
  const a = pending(),
    b = pending();
  const { page, calls } = runtime({
    request: (url) =>
      url.includes("inviterId=20")
        ? a.promise
        : url.includes("inviterId=30")
          ? b.promise
          : undefined,
  });
  await page.onShow();
  page.onInviter(change("20"));
  const first = page.resolveInvitation();
  page.resolveInvitation();
  assert.equal(page.data.invitationStatus, "verifying");
  assert.equal(calls.filter((c) => c.url.includes("inviterId=20")).length, 1);
  page.onInviter(change("30"));
  const second = page.resolveInvitation();
  b.resolve(relation(30));
  await second;
  a.resolve(relation(20));
  await first;
  assert.equal(page.data.relation.inviterId, 30);
  assert.equal(page.data.inviterId, "30");
  assert.equal(page.data.invitationStatus, "verified");
});

test("服务端验证成功后仍须本人确认与同意协议，改编号后确认立即失效", async () => {
  const { page } = runtime();
  await page.onShow();
  page.onInviter(change("20"));
  await page.resolveInvitation();
  page.agreement(change(["yes"]));
  assert.equal(page.data.canApply, false);
  assert.match(page.data.applyHint, /确认邀请/);
  page.confirmInvitation(change(["confirm"]));
  assert.equal(page.data.invitationStatus, "confirmed");
  assert.equal(page.data.canApply, true);
  page.onInviter(change("30"));
  assert.equal(page.data.invitationStatus, "empty");
  assert.equal(page.data.relation, null);
  assert.equal(page.data.canApply, false);
});

test("切换身份保留邀请编号，同档重复点击不重验，新档按服务端规则重验", async () => {
  const { page, calls } = runtime();
  await page.onShow();
  page.onInviter(change("20"));
  await page.resolveInvitation();
  page.confirmInvitation(change(["confirm"]));
  const count = calls.length;
  await page.changeTarget({ currentTarget: { dataset: { target: "C" } } });
  assert.equal(calls.length, count);
  await page.changeTarget({ currentTarget: { dataset: { target: "B" } } });
  assert.equal(page.data.inviterId, "20");
  assert.equal(page.data.selected.amountCents, 1980000);
  assert.equal(page.data.selected.benefits, "配置的文字权益");
  assert.equal(page.data.invitationStatus, "verified");
  assert.equal(page.data.canApply, false);
  assert.ok(calls.some((c) => c.url.includes("targetIdentity=B")));
});

test("服务端拒绝邀请后保留编号，不允许提交", async () => {
  const { page, calls } = runtime({
    request: (url) =>
      url.startsWith("/invitations/candidate")
        ? Promise.reject(Error("邀请人资格无效"))
        : undefined,
  });
  await page.onShow();
  page.onInviter(change("20"));
  await page.resolveInvitation();
  page.agreement(change(["yes"]));
  await page.submit();
  assert.equal(page.data.inviterId, "20");
  assert.equal(page.data.invitationStatus, "failed");
  assert.equal(page.data.canApply, false);
  assert.equal(calls.filter((c) => c.method === "POST").length, 0);
});

test("已提交申请锁定身份和邀请关系，新链接不能覆盖当前申请", async () => {
  const current = {
    id: 9,
    status: "submitted",
    target_identity: "C",
    inviter_id: 20,
    principal_id: 20,
    relation_confirmed: 1,
    package_snapshot: structuredClone(packages[0]),
  };
  const { page, calls } = runtime({ current, query: { inviterId: "30" } });
  await page.onShow();
  assert.equal(page.data.inviterId, "20");
  assert.equal(page.data.invitationLocked, true);
  page.onInviter(change("30"));
  await page.changeTarget({ currentTarget: { dataset: { target: "B" } } });
  await page.submit();
  assert.equal(page.data.inviterId, "20");
  assert.equal(page.data.target, "C");
  assert.equal(page.data.canApply, false);
  assert.equal(calls.filter((c) => c.method === "POST").length, 0);
});

test("申请只提交一次，成功后自动准备唯一订单并进入付款页", async () => {
  const post = pending();
  const { page, calls, navigation, toasts } = runtime({
    request: (url, method) => {
      if (url.startsWith("/invitations/candidate")) return relation(20, 40);
      if (url === "/onboardings" && method === "POST") return post.promise;
    },
  });
  await page.onShow();
  page.onInviter(change("20"));
  await page.resolveInvitation();
  page.confirmInvitation(change(["confirm"]));
  page.agreement(change(["yes"]));
  const first = page.submit();
  page.submit();
  await tick();
  const submits = calls.filter((c) => c.url === "/onboardings");
  assert.equal(submits.length, 1);
  assert.equal(submits[0].body.principalId, 40);
  post.resolve({
    id: 9,
    status: "submitted",
    target_identity: "C",
    inviter_id: 20,
    principal_id: 40,
    relation_confirmed: 1,
    package_snapshot: structuredClone(packages[0]),
  });
  await first;
  assert.equal(page.data.current.id, 9);
  assert.equal(page.data.current.order.id, 90);
  assert.equal(page.data.canApply, false);
  assert.equal(page.data.error, "");
  assert.equal(page.data.busy, false);
  assert.equal(navigation.length, 1);
  assert.equal(navigation[0].url, "/pages/wallet/index?orderId=90");
  assert.equal(calls.filter((c) => c.url === "/onboardings/9/order").length, 1);
  assert.equal(toasts.length, 0);
});

test("准备订单失败保留原申请，继续付款复用申请和唯一订单", async () => {
  let prepareFailures = 1;
  const { page, calls, navigation } = runtime({
    request: (url) => {
      if (url === "/onboardings/9/order" && prepareFailures-- > 0)
        throw Error("库存查询失败");
    },
  });
  await page.onShow();
  page.onInviter(change("20"));
  await page.resolveInvitation();
  page.confirmInvitation(change(["confirm"]));
  page.agreement(change(["yes"]));
  await page.submit();
  assert.equal(page.data.current.id, 9);
  assert.equal(page.data.current.status, "submitted");
  assert.match(page.data.error, /购买信息已保存.*付款准备失败/);
  assert.equal(navigation.length, 0);
  assert.equal(page.data.primaryActionName, "pay");
  await page.purchaseWithPoints();
  assert.equal(navigation.length, 1);
  assert.equal(navigation[0].url, "/pages/wallet/index?orderId=90");
  assert.equal(calls.filter((c) => c.url === "/onboardings").length, 1);
  assert.equal(calls.filter((c) => c.url === "/onboardings/9/order").length, 2);
});

test("待付款恢复复用原申请，连续点击只准备一次并跳转一次", async () => {
  const current = {
    id: 9,
    status: "submitted",
    target_identity: "C",
    inviter_id: 20,
    principal_id: 20,
    relation_confirmed: 1,
    package_snapshot: structuredClone(packages[0]),
  };
  const { page, calls, navigation } = runtime({ current });
  await page.onShow();
  await Promise.all([page.purchaseWithPoints(), page.purchaseWithPoints()]);
  assert.equal(calls.filter((c) => c.url === "/onboardings").length, 0);
  assert.equal(calls.filter((c) => c.url === "/onboardings/9/order").length, 1);
  assert.equal(navigation.length, 1);
  assert.equal(navigation[0].url, "/pages/wallet/index?orderId=90");
  assert.equal(page.data.busy, false);
});

test("待付款关系确认失效时主操作先引导确认，不直接准备付款", async () => {
  const current = {
    id: 9,
    status: "submitted",
    target_identity: "C",
    inviter_id: 20,
    principal_id: 20,
    relation_confirmed: 0,
    package_snapshot: structuredClone(packages[0]),
  };
  const { page, calls } = runtime({ current });
  await page.onShow();
  assert.equal(page.data.primaryActionName, "guide");
  await page.primaryAction();
  assert.match(page.data.notice, /勾选确认/);
  assert.equal(calls.filter((c) => c.method === "POST").length, 0);
});

const approvedC = {
  id: 8,
  status: "approved",
  target_identity: "C",
  inviter_id: 20,
  principal_id: 20,
  relation_confirmed: 1,
  package_snapshot: structuredClone(packages[0]),
  order: { id: 80, status: "completed" },
};

test("有效2980店主选择19800可升级，旧C记录仍可查询交付订单", async () => {
  const { page, calls, navigation } = runtime({
    current: structuredClone(approvedC),
    query: { inviterId: "20" },
    request: (url) => (url === "/me" ? { id: 7, identity: "C" } : undefined),
  });
  await page.onShow();
  await page.changeTarget({ currentTarget: { dataset: { target: "B" } } });
  page.updateState({ invitationStatus: "confirmed", agreed: true });
  assert.equal(page.data.target, "B");
  assert.equal(page.data.selected.targetIdentity, "B");
  assert.equal(page.data.canApply, true);
  assert.equal(page.data.primaryActionName, "submit");
  assert.match(page.data.primaryText, /升级19800/);
  assert.equal(page.data.current.order.id, 80);
  page.order();
  assert.equal(navigation.at(-1).url, "/pages/order/index?id=80");
  await page.submit();
  const submitted = calls.find(
    (call) => call.url === "/onboardings" && call.method === "POST",
  );
  assert.equal(submitted.body.targetIdentity, "B");
  assert.equal(navigation.at(-1).url, "/pages/wallet/index?orderId=90");
});

test("个人中心升级入口携带B目标仅决定初始选择，不跳过邀请与资格校验", async () => {
  const { page } = runtime({
    query: { target: "B" },
    request: (url) => (url === "/me" ? { id: 7, identity: "C" } : undefined),
  });
  await page.onShow();
  assert.equal(page.data.target, "B");
  assert.equal(page.data.selected.targetIdentity, "B");
  assert.equal(page.data.canApply, false);
  assert.match(page.data.applyHint, /邀请/);
  const invalid = runtime({
    query: { target: "X" },
    request: (url) => (url === "/me" ? { id: 7, identity: "C" } : undefined),
  });
  await invalid.page.onShow();
  assert.equal(invalid.page.data.target, "C");
});

test("无旧入驻记录的2980店主同样可以选择19800购买", async () => {
  const { page, calls } = runtime({
    query: { inviterId: "20" },
    request: (url) => (url === "/me" ? { id: 7, identity: "C" } : undefined),
  });
  await page.onShow();
  await page.changeTarget({ currentTarget: { dataset: { target: "B" } } });
  page.updateState({ invitationStatus: "confirmed", agreed: true });
  assert.equal(page.data.target, "B");
  assert.equal(page.data.primaryActionName, "submit");
  assert.equal(page.data.canApply, true);
  await page.submit();
  assert.equal(
    calls.find((call) => call.url === "/onboardings").body.targetIdentity,
    "B",
  );
});

test("19800店主不能重复购买B，2980店主不能重复购买C", async () => {
  for (const identity of ["B", "C"]) {
    const current = {
      ...structuredClone(approvedC),
      status: "approved",
      target_identity: identity,
      package_snapshot: structuredClone(
        packages.find((item) => item.targetIdentity === identity),
      ),
      order: { id: 80, status: "completed" },
    };
    const { page, calls } = runtime({
      current,
      request: (url) => (url === "/me" ? { id: 7, identity } : undefined),
    });
    await page.onShow();
    assert.equal(page.data.canApply, false);
    assert.equal(page.data.primaryActionName, "order");
    await page.submit();
    assert.equal(
      calls.some((call) => call.url === "/onboardings"),
      false,
    );
  }
});

test("待付B申请恢复时使用B快照并继续付款，不改回历史C", async () => {
  const current = {
    ...structuredClone(approvedC),
    id: 9,
    status: "submitted",
    target_identity: "B",
    package_snapshot: structuredClone(packages[1]),
    order: { id: 90, status: "pending", payment_state: "unpaid" },
  };
  const { page, calls, navigation } = runtime({
    current,
    request: (url) => (url === "/me" ? { id: 7, identity: "C" } : undefined),
  });
  await page.onShow();
  assert.equal(page.data.target, "B");
  assert.equal(page.data.primaryActionName, "pay");
  assert.equal(page.data.selected.targetIdentity, "B");
  await page.purchaseWithPoints();
  assert.equal(calls.filter((call) => call.url === "/onboardings").length, 0);
  assert.equal(
    calls.filter((call) => call.url === "/onboardings/9/order").length,
    1,
  );
  assert.equal(navigation.at(-1).url, "/pages/wallet/index?orderId=90");
});

test("升级选择19800后刷新和地址返回仍保留B目标", async () => {
  const { page, cache } = runtime({
    current: structuredClone(approvedC),
    query: { inviterId: "20" },
    request: (url) => (url === "/me" ? { id: 7, identity: "C" } : undefined),
  });
  await page.onShow();
  await page.changeTarget({ currentTarget: { dataset: { target: "B" } } });
  page.updateState({ invitationStatus: "confirmed", agreed: true });
  await page.load(true);
  assert.equal(page.data.target, "B");
  assert.equal(page.data.selected.targetIdentity, "B");
  cache.jyj_address = 2;
  await page.onShow();
  assert.equal(page.data.target, "B");
  assert.equal(page.data.selected.targetIdentity, "B");
});

test("升级19800时无效B邀请阻断提交，不出现已开通主操作", async () => {
  const { page, calls } = runtime({
    current: structuredClone(approvedC),
    query: { inviterId: "20" },
    request: (url) => {
      if (url === "/me") return { id: 7, identity: "C" };
      if (url.startsWith("/invitations/candidate"))
        return Promise.reject(Error("邀请人资格无效"));
    },
  });
  await page.onShow();
  await page.changeTarget({ currentTarget: { dataset: { target: "B" } } });
  assert.equal(page.data.invitationStatus, "failed");
  assert.equal(page.data.canApply, false);
  assert.equal(page.data.primaryActionName, "submit");
  assert.equal(page.data.primaryDisabled, true);
  await page.submit();
  assert.equal(
    calls.some((call) => call.url === "/onboardings"),
    false,
  );
});

test("返回地址页不清空相同邀请的本人确认及产品数量，键盘弹出保持独立滚动", async () => {
  const { page, cache } = runtime();
  await page.onShow();
  page.onInviter(change("20"));
  await page.resolveInvitation();
  page.confirmInvitation(change(["confirm"]));
  page.agreement(change(["yes"]));
  page.shipQuantity({
    currentTarget: { dataset: { index: 0 } },
    detail: { value: "1" },
  });
  cache.jyj_address = 2;
  await page.onShow();
  assert.equal(page.data.selected.lines[0].shipQuantity, 1);
  assert.equal(page.data.invitationStatus, "confirmed");
  assert.equal(page.data.agreed, true);
  page.focusField({ currentTarget: { dataset: { anchor: "ob-invitation" } } });
  page.keyboardChange({ detail: { height: 300 } });
  assert.equal(page.data.keyboardHeight, 300);
  assert.equal(page.data.scrollAnchor, "");
  page.keyboardChange({ detail: { height: 0 } });
  assert.equal(page.data.keyboardHeight, 0);
});

test("切换账号不沿用原本人确认，旧会话响应不能覆盖新账号", async () => {
  const oldUser = pending();
  let me = 0;
  const { page, cache, calls } = runtime({
    query: { inviterId: "20" },
    request: (url) => {
      if (url === "/me")
        return ++me === 1 ? oldUser.promise : { id: 8, identity: "D" };
    },
  });
  const first = page.onShow();
  await tick();
  cache.jyj_token = "new-token";
  await page.onShow();
  assert.equal(page.data.user.id, 8);
  oldUser.resolve({ id: 7, identity: "D" });
  await first;
  assert.equal(page.data.user.id, 8);
  page.confirmInvitation(change(["confirm"]));
  page.agreement(change(["yes"]));
  cache.jyj_token = "third-token";
  await page.submit();
  assert.equal(calls.filter((c) => c.method === "POST").length, 0);
  assert.equal(page.data.invitationStatus, "verified");
  assert.equal(page.data.canApply, false);
});

test("离开页面后未结束的邀请请求不回写", async () => {
  const response = pending();
  const { page } = runtime({
    request: (url) =>
      url.startsWith("/invitations/") ? response.promise : undefined,
  });
  await page.onShow();
  page.onInviter(change("20"));
  const verifying = page.resolveInvitation();
  page.onUnload();
  response.resolve(relation());
  await verifying;
  assert.equal(page.data.relation, null);
});

test("两档真实金额格式同步，积分购买沿用现有钱包入口", async () => {
  for (const target of ["C", "B"]) {
    const { page, navigation, calls } = runtime();
    await page.onShow();
    await page.changeTarget({ currentTarget: { dataset: { target } } });
    assert.equal(
      page.data.selected.amountText,
      target === "C" ? "¥2980" : "¥19800",
    );
    assert.equal(
      page.data.selected.valueText,
      target === "C" ? "¥4614" : "¥24786",
    );
    assert.equal(
      page.data.selected.pointsText,
      target === "C" ? "2980" : "19800",
    );
    page.updateState({
      current: { id: 9, status: "submitted" },
      invitationLocked: true,
    });
    await page.purchaseWithPoints();
    assert.equal(navigation[0].url, "/pages/wallet/index?orderId=90");
    assert.equal(
      calls.filter((call) => call.url === "/onboardings/9/order").length,
      1,
    );
  }
});

test("提交结果未明时保留幂等键，重试成功后不能重复申请", async () => {
  let attempt = 0;
  const { page, calls } = runtime({
    request: (url, method) => {
      if (url === "/onboardings" && method === "POST" && ++attempt === 1)
        return Promise.reject(Error("连接中断，结果未明"));
    },
  });
  await page.onShow();
  page.onInviter(change("20"));
  await page.resolveInvitation();
  page.confirmInvitation(change(["confirm"]));
  page.agreement(change(["yes"]));
  await page.submit();
  assert.equal(page.data.current, null);
  assert.match(page.data.error, /结果未明/);
  await page.submit();
  const submissions = calls.filter((c) => c.url === "/onboardings");
  assert.equal(submissions.length, 2);
  assert.equal(submissions[0].body.requestKey, submissions[1].body.requestKey);
  assert.equal(page.data.current.status, "submitted");
  await page.submit();
  assert.equal(calls.filter((c) => c.url === "/onboardings").length, 2);
});

test("后台使待付申请确认失效后，本人须重新核验并确认才可恢复支付", async () => {
  let current = {
    id: 9,
    status: "submitted",
    target_identity: "C",
    inviter_id: 20,
    principal_id: 20,
    relation_confirmed: 0,
    package_snapshot: structuredClone(packages[0]),
  };
  const { page, calls } = runtime({
    request: (url, method, body) => {
      if (url.startsWith("/mini/onboardings")) return { items: [current] };
      if (url === "/onboardings/9/confirm") {
        assert.equal(body.inviterId, 20);
        assert.equal(body.principalId, 20);
        current = { ...current, relation_confirmed: 1 };
        return current;
      }
    },
  });
  await page.onShow();
  assert.equal(page.data.invitationStatus, "verified");
  assert.equal(page.data.invitationLocked, false);
  await page.purchaseWithPoints();
  await page.reconfirm();
  assert.equal(calls.filter((c) => c.method === "POST").length, 0);
  page.confirmInvitation(change(["confirm"]));
  await page.reconfirm();
  assert.equal(page.data.invitationLocked, true);
  assert.equal(calls.filter((c) => c.method === "POST").length, 1);
});

test("点击已可见的邀请输入框及弹出键盘，不应强制跳到输入行", async () => {
  const { page, wx } = runtime();
  await page.onShow();
  wx.createSelectorQuery = () => {
    const query = {
      select: () => query,
      boundingClientRect: () => query,
      exec: (callback) =>
        callback([
          { top: 180, bottom: 500 },
          { top: 330, bottom: 374 },
        ]),
    };
    return query;
  };
  const jumps = [];
  page.setData = function (values, callback) {
    if (values.scrollAnchor) jumps.push(values.scrollAnchor);
    setData.call(this, values, callback);
  };
  page.focusField({
    currentTarget: { dataset: { anchor: "ob-inviter-form" } },
  });
  assert.equal(jumps.length, 0, "点击已可见输入框不应先强制跳转");
  page.keyboardChange({ detail: { height: 260 } });
  assert.equal(jumps.length, 0, "键盘出现后输入框仍可见，不应再次强制跳转");
});

test("键盘遮挡输入框时才滚动，重复高度和收起键盘不反复定位", async () => {
  const { page, wx } = runtime();
  await page.onShow();
  let measures = 0;
  wx.createSelectorQuery = () => {
    const query = {
      select: () => query,
      boundingClientRect: () => query,
      exec: (callback) => {
        measures++;
        callback([
          { top: 180, bottom: 400 },
          { top: 390, bottom: 434 },
        ]);
      },
    };
    return query;
  };
  const jumps = [];
  page.setData = function (values, callback) {
    if (values.scrollAnchor) jumps.push(values.scrollAnchor);
    setData.call(this, values, callback);
  };
  page.focusField({
    currentTarget: { dataset: { anchor: "ob-inviter-form" } },
  });
  assert.equal(jumps.length, 0);
  page.keyboardChange({ detail: { height: 260 } });
  assert.equal(jumps.length, 1);
  assert.equal(jumps[0], "ob-inviter-form");
  page.keyboardChange({ detail: { height: 260 } });
  assert.equal(measures, 1);
  assert.equal(jumps.length, 1);
  page.keyboardChange({ detail: { height: 0 } });
  assert.equal(jumps.length, 1);
  assert.equal(page.data.scrollAnchor, "");
});

test("键盘收起后，迟到的测量不能把页面拉走", async () => {
  const { page, wx } = runtime();
  await page.onShow();
  let measured;
  wx.createSelectorQuery = () => {
    const query = {
      select: () => query,
      boundingClientRect: () => query,
      exec: (callback) => {
        measured = callback;
      },
    };
    return query;
  };
  page.focusField({
    currentTarget: { dataset: { anchor: "ob-inviter-form" } },
  });
  page.keyboardChange({ detail: { height: 260 } });
  page.keyboardChange({ detail: { height: 0 } });
  measured([
    { top: 180, bottom: 400 },
    { top: 390, bottom: 434 },
  ]);
  assert.equal(page.data.scrollAnchor, "");
});
