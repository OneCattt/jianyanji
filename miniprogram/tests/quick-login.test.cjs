const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { setData } = require("../../scripts/miniprogram-runtime.cjs");
const root = path.resolve(__dirname, "..");
const tick = () => new Promise(setImmediate);

function runtime(options = {}) {
  const storage = new Map(),
    network = [],
    navigation = [];
  let definition,
    sheet,
    needAuthorization = !!options.needAuthorization;
  let user = {
    id: 7,
    nickname: "微信用户",
    phone: "",
    identity: "D",
    ...options.user,
  };
  const wx = {
    getStorageSync: (key) => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: (key) => storage.delete(key),
    getPrivacySetting: ({ success, fail }) =>
      options.platformFailure ? fail() : success({ needAuthorization }),
    requirePrivacyAuthorize: ({ success, fail }) =>
      needAuthorization ? fail() : success({}),
    login(value) {
      if (options.deferLogin) wx.pendingLogin = value;
      else value.success({ code: "login-code" });
    },
    request(value) {
      network.push(value);
      if (options.deferRequest?.(value)) return;
      let data;
      const url = new URL(value.url).pathname.replace(/^\/api/, "");
      if (url === "/public/content")
        data = {
          content: {
            privacyOperator: "测试品牌",
            privacyContact: "测试隐私联系",
          },
        };
      else if (url === "/auth/wechat") {
        if (options.phoneFailure && value.data.phoneCode)
          return value.success({
            statusCode: 502,
            data: { message: "微信授权暂时不可用" },
          });
        if (value.data.phoneCode)
          user = { ...user, phone: "13800138000", phoneVerified: true };
        data = { token: "test-token", user };
      } else if (url === "/me/wechat-phone") {
        assert.equal(value.data.code, "phone-code");
        user = { ...user, phone: "13800138000", phoneVerified: true };
        data = user;
      } else if (url === "/me" && value.method === "PATCH") {
        if (options.profileFailure)
          return value.success({
            statusCode: 500,
            data: { message: "保存失败" },
          });
        user = { ...user, ...value.data };
        data = user;
      } else if (url === "/me") data = user;
      else data = {};
      value.success({ statusCode: 200, data });
    },
    navigateTo: (value) => navigation.push(value.url),
    showToast() {},
    openPrivacyContract() {},
  };
  const cache = new Map();
  const context = {
    wx,
    getCurrentPages: () => [{ selectComponent: () => sheet }],
    Page: (value) => (definition = value),
    Component: (value) => (definition = value),
  };
  function load(file) {
    if (cache.has(file)) return cache.get(file).exports;
    const module = { exports: {} };
    cache.set(file, module);
    vm.runInNewContext(
      fs.readFileSync(file, "utf8"),
      {
        ...context,
        module,
        exports: module.exports,
        require: (name) => load(path.resolve(path.dirname(file), name + ".js")),
      },
      { filename: file },
    );
    return module.exports;
  }
  const api = load(path.join(root, "utils/api.js"));
  const privacy = load(path.join(root, "utils/privacy.js"));
  load(path.join(root, "components/login-sheet/index.js"));
  sheet = {
    ...definition.methods,
    data: structuredClone(definition.data),
    setData,
  };
  const lifecycle = definition;
  function accept() {
    needAuthorization = false;
    sheet.toggleAgreement({ detail: { value: ["privacy"] } });
    sheet.agree();
  }
  function page(name) {
    load(path.join(root, "pages", name, "index.js"));
    return { ...definition, data: structuredClone(definition.data), setData };
  }
  return {
    api,
    privacy,
    sheet,
    wx,
    storage,
    network,
    navigation,
    lifecycle,
    accept,
    page,
  };
}

test("登录在当前页面板完成，主动同意前不换取身份，重复触发共用一次面板", async () => {
  const r = runtime({ needAuthorization: true });
  const first = r.api.login(),
    second = r.api.login();
  assert.equal(first, second);
  await tick();
  assert.equal(r.sheet.data.stage, "consent");
  assert.equal(r.network.length, 1);
  assert.deepEqual(r.navigation, []);
  r.sheet.agree();
  assert.equal(r.privacy.hasConsent(), false);
  r.accept();
  assert.equal(r.sheet.data.stage, "login");
  await r.sheet.phoneLogin({ detail: { code: "phone-code" } });
  assert.equal(r.sheet.data.stage, "profile");
  assert.equal(r.sheet.data.user.phone, "13800138000");
  await r.sheet.saveProfile({ detail: { value: { nickname: "客户昵称" } } });
  const user = await first;
  assert.equal(user.id, 7);
  assert.equal(user.nickname, "客户昵称");
  assert.equal(user.phoneVerified, true);
  const login = r.network.filter((value) => value.url.endsWith("/auth/wechat"));
  assert.equal(login.length, 1);
  assert.equal(login[0].data.phoneCode, "phone-code");
  const update = r.network.find((value) => value.method === "PATCH");
  assert.deepEqual(Object.keys(update.data), ["nickname"]);
  assert.equal(r.storage.get("jyj_token"), "test-token");
});

test("取消登录不收集身份，公开浏览不受影响", async () => {
  const r = runtime();
  const result = assert.rejects(r.api.login(), { code: "LOGIN_CANCELLED" });
  await tick();
  r.sheet.cancel();
  await result;
  assert.equal(r.privacy.hasConsent(), false);
  assert.equal(r.storage.has("jyj_token"), false);
  assert.equal(r.network.filter((value) => value.method !== "GET").length, 0);
  await r.api.request("/public/products");
});

test("手机号拒绝后停留面板，可选择普通微信登录并稍后完善昵称", async () => {
  const r = runtime();
  const result = r.api.login();
  await tick();
  r.accept();
  r.sheet.phoneLogin({ detail: { errMsg: "getPhoneNumber:fail user deny" } });
  assert.equal(r.sheet.data.stage, "login");
  assert.match(r.sheet.data.error, /授权未完成/);
  await r.sheet.plainLogin();
  r.sheet.skipProfile();
  const user = await result;
  assert.equal(user.phone, "");
  assert.equal(
    r.network.find((value) => value.url.endsWith("/auth/wechat")).data
      .phoneCode,
    undefined,
  );
});

test("微信能力失败不写入登录，重试需用户再次授权", async () => {
  const r = runtime({ phoneFailure: true });
  const result = r.api.login();
  await tick();
  r.accept();
  await r.sheet.phoneLogin({ detail: { code: "phone-code" } });
  assert.equal(r.storage.has("jyj_token"), false);
  assert.equal(r.sheet.data.stage, "login");
  assert.equal(
    r.network.filter((value) => value.url.endsWith("/auth/wechat")).length,
    1,
  );
  const cancelled = assert.rejects(result, { code: "LOGIN_CANCELLED" });
  r.sheet.cancel();
  await cancelled;
});

test("已有昵称和手机号登录后直接续办，不覆盖资料或再次要求完善", async () => {
  const r = runtime({ user: { nickname: "老客户", phone: "13900139000" } });
  const result = r.api.login();
  await tick();
  r.accept();
  await r.sheet.plainLogin();
  const user = await result;
  assert.equal(user.nickname, "老客户");
  assert.equal(user.phone, "13900139000");
  assert.equal(r.network.filter((value) => value.method === "PATCH").length, 0);
});

test("昵称保存失败保留成功登录，不重复消耗手机号授权，可稍后继续", async () => {
  const r = runtime({ profileFailure: true });
  const result = r.api.login();
  await tick();
  r.accept();
  await r.sheet.phoneLogin({ detail: { code: "phone-code" } });
  await r.sheet.saveProfile({ detail: { value: { nickname: "本次昵称" } } });
  assert.match(r.sheet.data.error, /已登录，昵称保存未完成/);
  assert.equal(r.storage.get("jyj_token"), "test-token");
  r.sheet.skipProfile();
  await result;
  assert.equal(
    r.network.filter((value) => value.url.endsWith("/auth/wechat")).length,
    1,
  );
});

test("离开页面后晚到的微信凭证不恢复登录，也不自动执行原操作", async () => {
  const r = runtime({ deferLogin: true });
  const rejected = assert.rejects(r.api.login(), { code: "LOGIN_CANCELLED" });
  await tick();
  r.accept();
  const action = r.sheet.plainLogin();
  await tick();
  r.lifecycle.lifetimes.detached.call(r.sheet);
  r.wx.pendingLogin.success({ code: "late-code" });
  await action;
  await rejected;
  assert.equal(r.storage.has("jyj_token"), false);
  assert.equal(
    r.network.filter((value) => value.url.endsWith("/auth/wechat")).length,
    0,
  );
});

test("已登录用户绑定手机号只更新本人资料，不重新创建会话", async () => {
  const r = runtime({ user: { nickname: "老客户" } });
  r.privacy.recordConsent("测试品牌", "测试隐私联系");
  r.storage.set("jyj_token", "existing-token");
  const result = r.api.login("visitor", { mode: "phone" });
  await tick();
  await r.sheet.phoneLogin({ detail: { code: "phone-code" } });
  const user = await result;
  assert.equal(user.id, 7);
  assert.equal(user.nickname, "老客户");
  assert.equal(r.storage.get("jyj_token"), "existing-token");
  assert.equal(
    r.network.filter((value) => value.url.endsWith("/auth/wechat")).length,
    0,
  );
});

test("立即购买完成登录后继续结算，重新读取商品身份价，不再要求点击第二次", async () => {
  const r = runtime({ user: { nickname: "客户", phone: "13800138000" } });
  const page = r.page("product");
  page.data.product = { id: 9 };
  page.data.sku = { id: 12, available: true };
  let refreshes = 0;
  page.onShow = async () => {
    refreshes++;
    page.data.sku = { id: 12, available: true };
  };
  const buying = page.buy();
  await tick();
  r.accept();
  await r.sheet.plainLogin();
  await buying;
  assert.equal(refreshes, 1);
  assert.equal(r.storage.get("jyj_checkout")[0].skuId, 12);
  assert.deepEqual(r.navigation, ["/pages/checkout/index"]);
});

test("个人资料表单使用最终昵称值，不提交旧手机号", async () => {
  const r = runtime();
  r.privacy.recordConsent("测试品牌", "测试隐私联系");
  r.storage.set("jyj_token", "token");
  const page = r.page("settings");
  page.setUser({ id: 7, nickname: "旧昵称", phone: "13900139000" });
  await page.onSave({ detail: { value: { nickname: "最终昵称" } } });
  const update = r.network.find((value) => value.method === "PATCH");
  assert.equal(update.data.nickname, "最终昵称");
  assert.deepEqual(Object.keys(update.data), ["nickname"]);
});
