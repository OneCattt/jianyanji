const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { createRequire, setData } = require("./miniprogram-runtime.cjs");

function runtime(token = "token", intercept, options = {}) {
  const storage = new Map([["jyj_token", token]]),
    calls = [],
    navigation = [],
    previews = [],
    toasts = [];
  const product = {
    id: 1,
    name: "清欣精华",
    subtitle: "15ml",
    image: "/assets/figma/productSerum.png",
    detailImage: "/assets/figma/productHero.png",
    skus: [1, 2].map((id) => ({
      id,
      name: id === 1 ? "标准装" : "组合装",
      unit: "件",
      priceCents: id * 33800,
      retailCents: id * 33800,
      stock: 10,
      available: true,
    })),
  };
  let definition,
    count = 2;
  async function request(url, method = "GET", body) {
    calls.push({ url, method, body });
    if (intercept) await intercept(url, method);
    if (url === "/products/1") return structuredClone(product);
    if (url === "/mini/cart/count") return { count };
    if (url === "/me") {
      if (options.meFailure) throw Error("身份读取失败");
      return { id: 7, identity: options.identity || "B" };
    }
    if (url === "/invitations/mine") return { shareCode: "sales-1" };
    if (url.startsWith("/cart/")) {
      count += body.quantity;
      return {};
    }
    throw Error("未预期的请求 " + url);
  }
  const entry = path.resolve(
    __dirname,
    "../miniprogram/pages/product/index.js",
  );
  const context = {
    Page: (value) => (definition = value),
    wx: {
      getStorageSync: (key) => storage.get(key),
      setStorageSync: (key, value) => storage.set(key, value),
      getWindowInfo: () => ({ statusBarHeight: 44 }),
      getMenuButtonBoundingClientRect: () => ({ top: 50, height: 32 }),
      switchTab: ({ url }) => navigation.push(url),
      navigateTo: ({ url }) => navigation.push(url),
      navigateBack: ({ fail }) => fail(),
      previewImage: (value) => previews.push(value),
      showToast: (value) => toasts.push(value.title),
    },
  };
  context.require = createRequire(entry, context, request);
  context
    .require("../../utils/privacy")
    .recordConsent("测试品牌方", "测试联系方式");
  vm.runInNewContext(fs.readFileSync(entry, "utf8"), context);
  const page = {
    ...definition,
    data: structuredClone(definition.data),
    setData,
  };
  page.onLoad({ id: "1" });
  return { page, product, calls, storage, navigation, previews, toasts };
}
const event = (key, value) => ({
  currentTarget: { dataset: { [key]: value } },
});

test("商品预览返回保留规格和数量，库存减少时收敛到可用数量", async () => {
  const r = runtime();
  await r.page.onShow();
  assert.equal(r.page.data.navTop, 44);
  assert.equal(r.page.data.product.imageHero, r.product.image);
  r.page.select(event("id", 2));
  r.page.change(event("delta", 1));
  r.page.previewImage();
  assert.equal(r.previews[0].current, r.product.image);
  r.page.previewDetailImage();
  assert.equal(r.previews[1].current, r.product.detailImage);
  await r.page.onShow();
  assert.equal(r.page.data.sku.id, 2);
  assert.equal(r.page.data.quantity, 2);
  r.product.skus[1].stock = 1;
  await r.page.onShow();
  assert.equal(r.page.data.quantity, 1);
  assert.equal(r.page.data.cartCount, 2);
});

test("加购重复点击只提交一次，成功更新角标，订货沿用已选规格及数量", async () => {
  let release;
  const gate = new Promise((resolve) => (release = resolve));
  const r = runtime("token", (url, method) =>
    method === "PUT" ? gate : undefined,
  );
  await r.page.onShow();
  r.page.select(event("id", 2));
  r.page.change(event("delta", 1));
  const adding = r.page.add();
  await r.page.add();
  r.page.select(event("id", 1));
  assert.equal(r.page.data.sku.id, 2, "提交期间不能改变本次规格");
  release();
  await adding;
  assert.equal(r.calls.filter((c) => c.method === "PUT").length, 1);
  assert.equal(r.page.data.cartCount, 4);
  assert.equal(r.page.data.pendingAction, "");
  await r.page.buy();
  assert.equal(r.storage.get("jyj_checkout")[0].skuId, 2);
  assert.equal(r.storage.get("jyj_checkout")[0].quantity, 2);
  assert.equal(r.navigation.at(-1), "/pages/checkout/index");
});

test("匿名分享不登录，代理专属分享在退出或换号后不沿用，导航入口可用", async () => {
  const guest = runtime("");
  await guest.page.onShow();
  assert.equal(guest.calls.length, 1, "匿名浏览只读取公开商品");
  assert.equal(
    guest.page.onShareAppMessage().path,
    "/pages/product/index?id=1",
  );
  guest.page.home();
  guest.page.cart();
  guest.page.back();
  assert.deepEqual(guest.navigation, [
    "/pages/home/index",
    "/pages/cart/index",
    "/pages/home/index",
  ]);
  const agent = runtime();
  await agent.page.onShow();
  await agent.page.prepareSalesShare();
  assert.match(agent.page.onShareAppMessage().path, /salesCode=sales-1/);
  agent.storage.set("jyj_token", "other-token");
  assert.ok(!agent.page.onShareAppMessage().path.includes("salesCode"));
  await agent.page.onShow();
  assert.equal(agent.page.data.ownSalesCode, "");
});

test("普通顾客与身份读取失败都不开放销售分享，普通分享无销售码", async () => {
  const customer = runtime("token", null, { identity: "D" });
  await customer.page.onShow();
  assert.equal(customer.page.data.canSalesShare, false);
  assert.ok(!customer.page.onShareAppMessage().path.includes("salesCode"));
  const invitationCalls = customer.calls.filter((call) =>
    call.url.startsWith("/invitations/mine"),
  ).length;
  await customer.page.prepareSalesShare();
  assert.equal(
    customer.calls.filter((call) => call.url.startsWith("/invitations/mine"))
      .length,
    invitationCalls,
    "无销售资格不请求邀请码",
  );
  const failed = runtime("token", null, { meFailure: true });
  await failed.page.onShow();
  assert.equal(failed.page.data.canSalesShare, false);
  assert.equal(failed.page.data.identityReady, false);
  assert.match(failed.page.data.identityError, /身份读取失败/);
  assert.ok(!failed.page.onShareAppMessage().path.includes("salesCode"));
});
