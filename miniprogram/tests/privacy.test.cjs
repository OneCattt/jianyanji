const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { setData } = require("../../scripts/miniprogram-runtime.cjs");
const root = path.resolve(__dirname, "..");
const tick = () => new Promise(setImmediate);

// 执行真实网络封装、授权模块和页面，仅替换微信宿主；不绕过隐私拦截。
function runtime(options = {}) {
  const storage = new Map(Object.entries(options.storage || {}));
  const network = [],
    navigation = [],
    loginCalls = [],
    platformResults = [],
    platformSettings = [],
    toasts = [];
  const content = {
    privacyOperator: "测试运营有限公司",
    privacyContact: "测试隐私负责人，privacy@example.test",
    ...options.content,
  };
  let definition, nativeListener;
  let needAuthorization = options.needAuthorization ?? false;
  const wx = {
    getStorageSync: (key) => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: (key) => storage.delete(key),
    onNeedPrivacyAuthorization: (listener) => {
      nativeListener = listener;
    },
    getPrivacySetting({ success, fail }) {
      platformSettings.push({ success, fail });
      if (options.deferPrivacySetting) return;
      if (options.platformFailure) fail();
      else
        success({
          needAuthorization,
          privacyContractName: "《测试隐私保护指引》",
        });
    },
    requirePrivacyAuthorize({ success, fail }) {
      if (options.authorizationFailure) return fail();
      if (!needAuthorization) return success({});
      assert.ok(nativeListener, "必须在启动时接入微信隐私授权通知");
      nativeListener((value) => {
        platformResults.push(value);
        if (value.event === "agree") {
          needAuthorization = false;
          success({});
        } else fail();
      });
    },
    openPrivacyContract({ fail }) {
      if (options.contractFailure) fail();
    },
    login(value) {
      loginCalls.push(value);
      if (!options.deferLogin) value.success({ code: "test-code" });
    },
    request(value) {
      network.push(value);
      if (options.deferRequest?.(value.url)) return;
      if (options.networkFailure) return value.fail();
      const route = new URL(value.url).pathname.replace(/^\/api/, "");
      const data =
        route === "/public/content"
          ? { content }
          : route === "/auth/wechat"
            ? {
                token: "new-token",
                user: { id: 7, nickname: "测试", identity: "D" },
              }
            : route === "/me"
              ? { id: 7, nickname: "测试", phone: "", identity: "D" }
              : route === "/mini/addresses"
                ? { items: [], nextPage: null, hasMore: false }
                : route === "/orders"
                  ? { id: 8 }
                  : {};
      value.success({ statusCode: 200, data });
    },
    navigateTo: (value) => {
      navigation.push({ kind: "to", ...value });
    },
    navigateBack(value = {}) {
      navigation.push({ kind: "back" });
      if (options.backFailure) value.fail?.();
      else value.success?.();
    },
    reLaunch(value) {
      navigation.push({ kind: "reLaunch", ...value });
    },
    redirectTo(value) {
      navigation.push({ kind: "redirect", ...value });
    },
    showToast: (value) => toasts.push(value),
    pageScrollTo() {},
    setClipboardData() {},
  };
  const modules = new Map();
  function load(file) {
    if (modules.has(file)) return modules.get(file).exports;
    const module = { exports: {} };
    modules.set(file, module);
    vm.runInNewContext(
      fs.readFileSync(file, "utf8"),
      {
        wx,
        module,
        exports: module.exports,
        Page: (value) => {
          definition = value;
        },
        App: (value) => {
          definition = value;
        },
        require: (name) => load(path.resolve(path.dirname(file), name + ".js")),
      },
      { filename: file },
    );
    return module.exports;
  }
  const privacy = load(path.join(root, "utils/privacy.js"));
  const api = load(path.join(root, "utils/api.js"));
  function accept() {
    privacy.recordConsent(content.privacyOperator, content.privacyContact);
  }
  function page(name) {
    load(path.join(root, "pages", name, "index.js"));
    return {
      ...definition,
      data: structuredClone(definition.data),
      setData,
      selectComponent: () => ({
        open: async () => ({ confirm: options.confirmWithdrawal ?? true }),
      }),
    };
  }
  async function policy(consent = true) {
    const instance = page("privacy");
    await instance.onLoad({ consent: consent ? "1" : "" });
    return instance;
  }
  function agree(instance) {
    // 模拟微信先接收到原生按钮点击，再调用 bindagreeprivacyauthorization。
    needAuthorization = false;
    instance.toggleAgreement({ detail: { value: ["privacy"] } });
    instance.agree();
  }
  return {
    privacy,
    api,
    wx,
    storage,
    content,
    network,
    navigation,
    loginCalls,
    platformResults,
    platformSettings,
    toasts,
    page,
    policy,
    agree,
    accept,
  };
}

test("未同意时公开内容不携带旧身份；地址、订单、积分等读写在网络前拦截", async () => {
  const r = runtime({ storage: { jyj_token: "old-token" } });
  for (const route of [
    "/public/content",
    "/public/posts?limit=20",
    "/mini/products?page=1",
    "/products/1",
    "/membership",
  ])
    await r.api.request(route);
  assert.equal(r.network.length, 5);
  assert.ok(r.network.every((request) => !request.header.Authorization));
  for (const [route, method] of [
    ["/me", "GET"],
    ["/mini/addresses", "GET"],
    ["/mini/orders", "GET"],
    ["/addresses", "POST"],
    ["/checkout/quote", "POST"],
    ["/orders", "POST"],
    ["/orders/7/pay/points", "POST"],
    ["/cart/1", "PUT"],
    ["/onboardings", "POST"],
    ["/auth/wechat", "POST"],
  ])
    await assert.rejects(r.api.request(route, method, {}), {
      code: "PRIVACY_REQUIRED",
    });
  assert.equal(r.network.length, 5);
  assert.equal(r.navigation.length, 0, "后台读取不能强弹授权");
});

test("首次登录先阅读政策，拒绝及系统返回均不登录、不留同意记录", async () => {
  for (const back of [false, true]) {
    const r = runtime();
    const login = r.api.login();
    const rejected = assert.rejects(login, { code: "PRIVACY_REQUIRED" });
    assert.equal(r.navigation[0].url, "/pages/privacy/index?consent=1");
    assert.equal(r.loginCalls.length, 0);
    const page = await r.policy();
    if (back) page.onUnload();
    else page.decline();
    await rejected;
    assert.equal(r.privacy.hasConsent(), false);
    assert.equal(r.loginCalls.length, 0);
    assert.equal(
      r.network.filter((request) => request.method !== "GET").length,
      0,
    );
    await r.api.request("/public/categories");
  }
});

test("主动勾选并同意后才进行微信登录，记录版本时间，重复点击共用一次登录", async () => {
  const r = runtime({ needAuthorization: true });
  const first = r.api.login(),
    second = r.api.login();
  assert.equal(first, second);
  const page = await r.policy();
  page.agree();
  assert.equal(r.privacy.hasConsent(), false, "未勾选不能同意");
  assert.equal(r.loginCalls.length, 0);
  r.agree(page);
  const user = await first;
  assert.equal(user.id, 7);
  assert.equal(r.loginCalls.length, 1);
  assert.equal(
    r.network.filter((request) => request.url.endsWith("/auth/wechat")).length,
    1,
  );
  const record = r.storage.get(r.privacy.CONSENT_KEY);
  assert.equal(record.version, r.privacy.POLICY_VERSION);
  assert.ok(Number.isFinite(Date.parse(record.agreedAt)));
  assert.equal(record.operator, r.content.privacyOperator);
  assert.equal(r.storage.get("jyj_token"), "new-token");
});

test("普通查看政策不表示同意，打开微信指引失败给出可重试提示", async () => {
  const r = runtime({ contractFailure: true });
  const page = await r.policy(false);
  r.agree(page);
  assert.equal(r.privacy.hasConsent(), false);
  page.openContract();
  assert.match(page.data.error, /隐私指引暂未打开/);
  page.onUnload();
});

test("运营主体、联系方式、网络或微信状态缺失时不能同意或登录", async () => {
  for (const options of [
    { content: { privacyOperator: "" } },
    { content: { privacyContact: "" } },
    { platformFailure: true },
    { networkFailure: true },
  ]) {
    const r = runtime(options);
    const login = r.api.login();
    const rejected = assert.rejects(login, { code: "PRIVACY_REQUIRED" });
    const page = await r.policy();
    r.agree(page);
    assert.equal(r.privacy.hasConsent(), false);
    assert.equal(r.loginCalls.length, 0);
    page.decline();
    await rejected;
  }
});

test("平台再次要求授权时，即使本地已同意也需原生按钮确认，拒绝不能绕过", async () => {
  for (const accepted of [true, false]) {
    const r = runtime({ needAuthorization: true });
    r.accept();
    r.privacy.installPrivacy();
    const login = r.api.login();
    const result = accepted
      ? login
      : assert.rejects(login, { code: "PRIVACY_REQUIRED" });
    await tick();
    assert.equal(r.loginCalls.length, 0);
    const page = await r.policy();
    if (accepted) r.agree(page);
    else page.decline();
    await result;
    assert.equal(r.platformResults[0].event, accepted ? "agree" : "disagree");
    if (accepted) assert.equal(r.platformResults[0].buttonId, "privacy-agree");
    assert.equal(r.loginCalls.length, accepted ? 1 : 0);
    if (!accepted) {
      assert.equal(r.privacy.hasConsent(), false);
      await assert.rejects(r.api.request("/me"), { code: "PRIVACY_REQUIRED" });
    }
  }
});

test("微信隐私授权失败不发起身份登录；旧政策启动清理旧会话但保留其他小程序存储", async () => {
  const r = runtime({ authorizationFailure: true });
  r.accept();
  await assert.rejects(r.api.login(), /微信隐私授权未完成/);
  assert.equal(r.loginCalls.length, 0);
  const old = runtime({
    storage: {
      jyj_privacy_consent: {
        version: "old",
        agreedAt: "old",
        operator: "原主体",
        contact: "旧联系",
      },
      jyj_token: "old-token",
      jyj_address: 9,
      jyj_checkout: [{ skuId: 1 }],
      jyj_sales_candidate: "sales",
      jyj_invitation_candidate: "inviter",
      unrelated: "keep",
    },
  });
  old.privacy.installPrivacy();
  assert.equal(old.privacy.hasConsent(), false);
  for (const key of [
    "jyj_token",
    "jyj_address",
    "jyj_checkout",
    "jyj_sales_candidate",
    "jyj_invitation_candidate",
  ])
    assert.equal(old.storage.has(key), false);
  assert.equal(old.storage.get("unrelated"), "keep");
});

test("撤回期间尚未完成的微信 code 及服务端登录均不能恢复令牌", async () => {
  for (const deferred of ["code", "server"]) {
    const r = runtime({
      deferLogin: deferred === "code",
      deferRequest: (url) =>
        deferred === "server" && url.endsWith("/auth/wechat"),
    });
    r.accept();
    const login = r.api.login();
    const rejected = assert.rejects(login, {
      code: deferred === "server" ? "SESSION_CHANGED" : "PRIVACY_REQUIRED",
    });
    await tick();
    r.privacy.revokeConsent();
    if (deferred === "code") r.loginCalls[0].success({ code: "late-code" });
    else
      r.network
        .at(-1)
        .success({ statusCode: 200, data: { token: "late-token", user: {} } });
    await rejected;
    assert.equal(r.storage.has("jyj_token"), false);
    assert.equal(r.privacy.hasConsent(), false);
    if (deferred === "code") assert.equal(r.network.length, 0);
  }
});

test("旧会话的成功和401响应均不能回写个人资料或清掉新会话", async () => {
  for (const statusCode of [200, 401]) {
    const r = runtime({
      deferRequest: () => true,
      storage: { jyj_token: "token-A" },
    });
    r.accept();
    const request = r.api.request("/me");
    const rejected = assert.rejects(request, {
      code: "SESSION_CHANGED",
      status: 409,
    });
    await tick();
    r.storage.set("jyj_token", "token-B");
    r.network[0].success({ statusCode, data: { nickname: "A的资料" } });
    await rejected;
    assert.equal(r.storage.get("jyj_token"), "token-B");
  }
});

test("撤回后在途业务响应不回写，实际地址保存和下单不发出新请求", async () => {
  const r = runtime({
    storage: { jyj_token: "token" },
    deferRequest: (url) => url.endsWith("/me"),
  });
  r.accept();
  const pending = r.api.request("/me");
  const rejected = assert.rejects(pending, {
    code: "SESSION_CHANGED",
    status: 409,
  });
  await tick();
  r.privacy.revokeConsent();
  r.network[0].success({ statusCode: 200, data: { nickname: "旧资料" } });
  await rejected;
  const address = r.page("addresses");
  address.data.form = { recipient: "测试", phone: "13800000000" };
  await address.save();
  assert.match(address.data.error, /同意隐私政策/);
  const checkout = r.page("checkout");
  checkout.data.quote = { id: 7 };
  await checkout.submit();
  assert.match(checkout.data.error, /同意隐私政策/);
  assert.equal(r.network.length, 1);
});

test("商品加购、立即购买和生成分享三个快捷登录入口均遵循拒绝结果", async () => {
  for (const method of ["add", "buy", "prepareSalesShare"]) {
    const r = runtime();
    const page = r.page("product");
    page.data.sku = { id: 1, available: true };
    const action = page[method]();
    const policy = await r.policy();
    policy.decline();
    await action;
    assert.equal(r.loginCalls.length, 0);
    assert.equal(
      r.network.filter((request) => request.method !== "GET").length,
      0,
    );
    assert.match(page.data.error, /暂未同意/);
  }
});

test("设置页撤回可取消；确认后即使退出网络失败也清理本机及原页面栈", async () => {
  for (const confirmWithdrawal of [false, true]) {
    const r = runtime({
      confirmWithdrawal,
      networkFailure: true,
      storage: {
        jyj_token: "token",
        jyj_address: 9,
        jyj_checkout: [{ skuId: 1 }],
        jyj_sales_candidate: "sales",
        jyj_invitation_candidate: "invite",
      },
    });
    r.accept();
    const page = r.page("settings");
    page.setUser({ id: 7, nickname: "测试", phone: "", identity: "D" });
    await page.withdrawPrivacy();
    assert.equal(r.privacy.hasConsent(), !confirmWithdrawal);
    assert.equal(r.storage.has("jyj_token"), !confirmWithdrawal);
    if (confirmWithdrawal) {
      assert.equal(page.data.user, null);
      assert.equal(page.data.phone, "");
      assert.equal(r.navigation.at(-1).kind, "reLaunch");
      assert.equal(r.navigation.at(-1).url, "/pages/home/index");
      assert.ok(![...r.storage.keys()].some((key) => key.startsWith("jyj_")));
    } else assert.equal(r.network.length, 0);
  }
});

test("普通退出保留同意但清除所有本机业务选择，网络失败不保留登录", async () => {
  const r = runtime({
    networkFailure: true,
    storage: { jyj_token: "token", jyj_address: 9, jyj_checkout: [{}] },
  });
  r.accept();
  const page = r.page("settings");
  page.setUser({ nickname: "测试", phone: "13800000000" });
  await page.onLogout();
  assert.equal(r.privacy.hasConsent(), true);
  assert.equal(r.storage.has("jyj_token"), false);
  assert.equal(r.storage.has("jyj_address"), false);
  assert.equal(r.storage.has("jyj_checkout"), false);
  assert.equal(page.data.user, null);
  assert.match(page.data.error, /服务端退出未确认/);
});

test("恢复旧令牌先检查微信状态，待重新授权或状态失败时不发个人业务", async () => {
  for (const options of [
    { needAuthorization: true },
    { platformFailure: true },
  ]) {
    const r = runtime({ ...options, storage: { jyj_token: "old-token" } });
    r.accept();
    r.privacy.installPrivacy();
    await assert.rejects(r.api.request("/me"), { code: "PRIVACY_REQUIRED" });
    await assert.rejects(r.api.request("/addresses", "POST", {}), {
      code: "PRIVACY_REQUIRED",
    });
    assert.equal(r.network.length, 0);
    assert.equal(r.privacy.hasConsent(), false);
    assert.equal(r.storage.has("jyj_token"), false);
    await r.api.request("/mini/products");
    assert.equal(r.network.length, 1);
    assert.equal(r.network[0].header.Authorization, undefined);
    assert.equal(r.navigation.length, 0, "后台恢复检查不主动弹出授权页");
  }
});

test("返回失败后再次拒绝重新授权，不能继续微信登录或恢复同意", async () => {
  const r = runtime({ needAuthorization: true, backFailure: true });
  r.accept();
  // 只注册全局监听；本轮先以无令牌状态主动登录，从 requirePrivacyAuthorize 触发重新确认。
  r.privacy.installPrivacy();
  const login = r.api.login();
  const rejected = assert.rejects(login, { code: "PRIVACY_REQUIRED" });
  await tick();
  const page = await r.policy();
  r.agree(page);
  await tick();
  assert.match(page.data.error, /已同意/);
  assert.equal(r.loginCalls.length, 0, "返回未完成前不能消费原生授权成功回调");
  page.decline();
  page.onUnload();
  await rejected;
  assert.equal(r.privacy.hasConsent(), false);
  assert.equal(r.storage.has("jyj_token"), false);
  assert.equal(r.loginCalls.length, 0);
});

test("已同意且微信状态有效时，真实地址保存及订单提交仍可完成", async () => {
  const r = runtime({ storage: { jyj_token: "token" } });
  r.accept();
  const page = r.page("settings");
  page.setUser({ id: 7, nickname: "原昵称", phone: "", identity: "D" });
  page.data.nickname = "新昵称";
  await page.onSave();
  assert.equal(r.network.filter((call) => call.method === "PATCH").length, 1);
  assert.equal(r.network.at(-1).data.nickname, "新昵称");
  assert.equal(r.toasts[0].title, "个人资料已保存");
  const address = r.page("addresses");
  address.add();
  address.data.form = {
    recipient: "收货人",
    phone: "13800000000",
    province: "浙江省",
    city: "杭州市",
    district: "西湖区",
    detail: "测试地址",
    isDefault: false,
  };
  await address.save();
  assert.equal(address.data.editing, false);
  assert.equal(address.data.error, "");
  const addressWrite = r.network.find(
    (call) => call.url.endsWith("/addresses") && call.method === "POST",
  );
  assert.equal(addressWrite.data.recipient, "收货人");
  const checkout = r.page("checkout");
  checkout.data.quote = { id: 7 };
  await checkout.submit();
  assert.equal(
    r.network.filter(
      (call) => call.url.endsWith("/orders") && call.method === "POST",
    ).length,
    1,
  );
  assert.equal(r.navigation.at(-1).kind, "redirect");
  assert.equal(r.navigation.at(-1).url, "/pages/order/index?id=8");
});

test("等待微信会话检查期间换号，旧页面不能借用新账号提交资料或读取身份商品", async () => {
  for (const [route, method] of [
    ["/addresses", "POST"],
    ["/mini/products", "GET"],
  ]) {
    const r = runtime({
      deferPrivacySetting: true,
      storage: { jyj_token: "token-A" },
    });
    r.accept();
    const pending = r.api.request(route, method, { recipient: "A的地址" });
    const rejected = assert.rejects(pending, {
      code: "SESSION_CHANGED",
      status: 409,
    });
    await tick();
    assert.equal(r.network.length, 0);
    r.privacy.clearLocalSession();
    r.accept();
    r.storage.set("jyj_token", "token-B");
    r.platformSettings[0].success({ needAuthorization: false });
    await rejected;
    assert.equal(r.network.length, 0);
    assert.equal(r.storage.get("jyj_token"), "token-B");
  }
});
