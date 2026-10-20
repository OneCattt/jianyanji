const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { createRequire, setData } = require("./miniprogram-runtime.cjs");
const tick = () => new Promise(setImmediate);

// 在真实页面与分页工具上运行，只替换微信宿主及远端购物袋。
function runtime(count = 41, intercept) {
  let definition;
  let items = Array.from({ length: count }, (_, index) => ({
    skuId: index + 1,
    name: "测试商品 " + (index + 1),
    quantity: 1,
    stock: 999,
    priceCents: 70000,
    selected: true,
    available: true,
  }));
  const calls = [],
    toasts = [],
    storage = new Map(),
    navigation = [];
  function summary() {
    const selected = items.filter((item) => item.selected);
    return {
      totalKinds: items.length,
      kinds: selected.length,
      pieces: selected.reduce((n, item) => n + item.quantity, 0),
      totalCents: selected.reduce(
        (n, item) => n + item.quantity * item.priceCents,
        0,
      ),
      allSelected: items.length > 0 && selected.length === items.length,
    };
  }
  async function request(url, method = "GET", body) {
    calls.push({ url, method, body });
    if (intercept) await intercept(url, method, body);
    if (url.startsWith("/mini/cart?")) {
      const page = Number(new URLSearchParams(url.split("?")[1]).get("page"));
      return {
        ...summary(),
        items: structuredClone(items.slice((page - 1) * 20, page * 20)),
        hasMore: items.length > page * 20,
        nextPage: items.length > page * 20 ? page + 1 : null,
      };
    }
    if (url === "/mini/cart/selection")
      return {
        items: items
          .filter((item) => item.selected)
          .map(({ skuId, quantity }) => ({ skuId, quantity })),
      };
    if (url === "/cart/selection") {
      for (const item of items)
        if (!body.skuId || item.skuId === body.skuId)
          item.selected = body.selected;
    } else {
      const id = Number(url.split("/")[2]);
      if (method === "POST") items = items.filter((item) => item.skuId !== id);
      else
        Object.assign(
          items.find((item) => item.skuId === id),
          body,
        );
    }
    return { ok: true, ...summary() };
  }
  const entry = path.resolve(__dirname, "../miniprogram/pages/cart/index.js");
  const context = {
    wx: {
      getStorageSync: (key) =>
        key === "jyj_token" ? "cart-user" : storage.get(key),
      setStorageSync: (key, value) => storage.set(key, value),
      showToast: (value) => toasts.push(value.title),
      hideKeyboard() {},
      showModal() {},
      stopPullDownRefresh() {},
      navigateTo: ({ url }) => navigation.push(url),
    },
    Page: (value) => (definition = value),
  };
  context.require = createRequire(entry, context, request);
  vm.runInNewContext(fs.readFileSync(entry, "utf8"), context, {
    filename: entry,
  });
  return {
    page: { ...definition, data: structuredClone(definition.data), setData },
    calls,
    toasts,
    storage,
    navigation,
    remote: () => items,
  };
}
const event = (id, extra = {}, value) => ({
  currentTarget: { dataset: { id, ...extra } },
  detail: { value },
});

test("全选与取消只发一次批量请求，覆盖尚未展示的21项，金额采用整袋汇总", async () => {
  const r = runtime();
  r.remote()[30].selected = false;
  await r.page.onShow();
  assert.equal(r.page.data.items.length, 20);
  assert.equal(r.page.data.allSelected, false);
  await r.page.toggleAll();
  assert.equal(r.remote().filter((item) => item.selected).length, 41);
  assert.equal(r.page.data.kinds, 41);
  assert.equal(r.page.data.totalText, "¥28700.00");
  assert.equal(r.page.data.items.length, 20);
  assert.equal(r.page.data.itemsNextPage, 2);
  await r.page.toggleAll();
  assert.ok(r.remote().every((item) => !item.selected));
  assert.equal(r.page.data.totalText, "¥0.00");
  assert.equal(r.page.data.allSelected, false);
  assert.equal(r.calls.length, 3, "两次全选操作均不重新请求列表或逐项发送");
});

test("直接输入48件，确认和失焦只提交一次，结算等待数量保存后读取服务端选择", async () => {
  let release;
  const gate = new Promise((resolve) => (release = resolve));
  const r = runtime(41, async (url, method) => {
    if (method === "PUT") await gate;
  });
  await r.page.onShow();
  await r.page.onReachBottom();
  r.page.startQuantityEdit(event(21));
  r.page.editQuantity(event(21, {}, "48"));
  const save = r.page.confirmQuantity(event(21, {}, "48"));
  const blur = r.page.commitQuantity(event(21, {}, "48"));
  const checkout = r.page.checkout();
  await tick();
  assert.equal(r.calls.filter((call) => call.method === "PUT").length, 1);
  assert.ok(!r.calls.some((call) => call.url === "/mini/cart/selection"));
  release();
  await Promise.all([save, blur, checkout]);
  assert.equal(r.remote()[20].quantity, 48);
  assert.equal(r.page.data.items.length, 40);
  assert.equal(r.page.data.itemsNextPage, 3);
  assert.equal(r.storage.get("jyj_checkout")[20].quantity, 48);
  assert.equal(r.navigation[0], "/pages/checkout/index");
  assert.equal(
    r.calls.filter((call) => call.url.startsWith("/mini/cart?")).length,
    2,
  );
});

test("空值、零、小数、超库存和四位数量均不写入，非法输入后仍能移除商品", async () => {
  const r = runtime(1);
  r.remote()[0].stock = 50;
  await r.page.onShow();
  for (const value of ["", "0", "-1", "1.5", "1e2", "51", "1000"]) {
    r.page.startQuantityEdit(event(1));
    await r.page.commitQuantity(event(1, {}, value));
    assert.equal(r.page.data.items[0].quantity, 1);
  }
  assert.equal(r.toasts.length, 7);
  assert.equal(r.calls.length, 1);
  await r.page.change(event(1, { action: "remove" }));
  assert.equal(r.page.data.items.length, 0);
  assert.equal(r.page.data.allSelected, false);
  assert.equal(r.page.data.itemsHasMore, false);
});

test("数量保存失败会恢复原值，错误不会永久锁住后续操作", async () => {
  const r = runtime(1, async (url, method) => {
    if (method === "PUT" && url !== "/cart/selection") throw Error("库存不足");
  });
  await r.page.onShow();
  r.page.startQuantityEdit(event(1));
  const ok = await r.page.commitQuantity(event(1, {}, "48"));
  assert.equal(ok, false);
  assert.equal(r.page.data.items[0].quantity, 1);
  assert.equal(r.page.data.error, "库存不足");
  await r.page.toggleAll();
  assert.equal(r.page.data.kinds, 0);
  assert.equal(r.page.data.busy, false);
});

test("删除第二页中的商品后继续滚动，不遗漏被偏移分页前移的第41项", async () => {
  const r = runtime(61);
  await r.page.onShow();
  await r.page.onReachBottom();
  await r.page.change(event(21, { action: "remove" }));
  assert.equal(r.page.data.items.length, 39);
  assert.equal(r.page.data.itemsNextPage, 2);
  await r.page.onReachBottom();
  await r.page.onReachBottom();
  assert.equal(r.page.data.items.length, 60);
  assert.equal(new Set(r.page.data.items.map((item) => item.skuId)).size, 60);
  assert.deepEqual(
    Array.from(r.page.data.items, (item) => item.skuId),
    r.remote().map((item) => item.skuId),
  );
  assert.equal(r.page.data.itemsHasMore, false);
  await r.page.change(event(61, { action: "remove" }));
  assert.equal(
    r.page.data.itemsHasMore,
    false,
    "已全部展示后删除，不应重新触发分页",
  );
});

test("左滑只展开移除入口，纵向滚动不展开；右滑关闭且不自动删除", async () => {
  const r = runtime(1);
  await r.page.onShow();
  const point = (x, y) => ({
    currentTarget: { dataset: { id: 1 } },
    touches: [{ clientX: x, clientY: y }],
  });
  r.page.touchStart(point(200, 100));
  r.page.touchMove(point(195, 180));
  r.page.touchEnd();
  assert.equal(r.page.data.swipeSkuId, 0);
  r.page.touchStart(point(200, 100));
  r.page.touchMove(point(100, 104));
  r.page.touchEnd();
  assert.equal(r.page.data.swipeSkuId, 1);
  assert.equal(r.remote().length, 1);
  r.page.touchStart(point(100, 100));
  r.page.touchMove(point(170, 102));
  r.page.touchEnd();
  assert.equal(r.page.data.swipeSkuId, 0);
  assert.equal(r.calls.length, 1);
});
