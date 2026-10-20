const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { createRequire, setData } = require("./miniprogram-runtime.cjs");

function runtime(name, request, token = "user-1") {
  const entry = path.resolve(
    __dirname,
    `../miniprogram/pages/${name}/index.js`,
  );
  const storage = new Map([["jyj_token", token]]);
  let definition;
  const context = {
    wx: { getStorageSync: (key) => storage.get(key) },
    Page: (value) => {
      definition = value;
    },
  };
  context.require = createRequire(entry, context, request);
  vm.runInNewContext(fs.readFileSync(entry, "utf8"), context, {
    filename: entry,
  });
  return {
    page: { ...definition, data: structuredClone(definition.data), setData },
    storage,
    state: context.require("../../utils/state"),
  };
}

test("购物袋已有一件商品，进入商城即读取并展示总件数", async () => {
  const calls = [];
  const { page } = runtime("mall", async (url) => {
    calls.push(url);
    if (url === "/mini/cart/count") return { count: 1 };
    if (url === "/public/categories") return [];
    return { items: [], identity: "D", page: 1, hasMore: false };
  });
  await page.onShow();
  assert.equal(page.data.cartCount, 1);
  assert.equal(calls.filter((url) => url === "/mini/cart/count").length, 1);
});

test("普通顾客的四个订单状态也读取全部订单数量", async () => {
  const counts = { pending: 1, paid: 2, shipped: 3, completed: 4 };
  const { page } = runtime("profile", async (url) => {
    if (url === "/me") return { id: 1, identity: "D", nickname: "顾客" };
    if (url === "/mini/orders/counts") return counts;
    throw new Error("不应读取其他列表：" + url);
  });
  await page.onShow();
  assert.deepEqual(page.data.orderCounts, counts);
});

test("商品列表仍在缓存时，加购或移除后角标按业务失效版本刷新", async () => {
  let count = 1,
    calls = 0;
  const { page, state } = runtime("mall", async (url) => {
    if (url === "/mini/cart/count") {
      calls++;
      return { count };
    }
    if (url === "/public/categories") return [];
    return { items: [], identity: "D", hasMore: false };
  });
  await page.onShow();
  await page.onShow();
  assert.equal(calls, 1);
  count = 2;
  state.invalidate();
  await page.onShow();
  assert.equal(page.data.cartCount, 2);
  count = 0;
  state.invalidate();
  await page.onShow();
  assert.equal(page.data.cartCount, 0);
  assert.equal(calls, 3);
});

test("切换账号后旧购物袋响应不能覆盖新账号，退出后清除角标", async () => {
  let resolveOld;
  const old = new Promise((resolve) => {
    resolveOld = resolve;
  });
  let calls = 0;
  const { page, storage } = runtime("mall", async (url) => {
    if (url === "/mini/cart/count") return ++calls === 1 ? old : { count: 2 };
    if (url === "/public/categories") return [];
    return { items: [], identity: "D", hasMore: false };
  });
  const pending = page.onShow();
  storage.set("jyj_token", "user-2");
  await page.onShow();
  resolveOld({ count: 50 });
  await pending;
  assert.equal(page.data.cartCount, 2);
  storage.delete("jyj_token");
  await page.onShow();
  assert.equal(page.data.cartCount, null);
  assert.equal(calls, 2);
});

test("未登录不请求本人数量，统计失败允许单独重试", async () => {
  let calls = 0;
  const { page, storage } = runtime(
    "profile",
    async (url) => {
      if (url === "/me") return { id: 1, identity: "D" };
      if (++calls === 1) throw new Error("网络中断");
      return { pending: 1, paid: 0, shipped: 0, completed: 0 };
    },
    "",
  );
  await page.onShow();
  assert.equal(calls, 0);
  storage.set("jyj_token", "user-1");
  await page.onShow();
  assert.equal(page.data.orderCounts, null);
  assert.match(page.data.orderCountsError, /暂不可读取/);
  await page.loadOrderCounts();
  assert.equal(page.data.orderCounts.pending, 1);
  assert.equal(page.data.orderCountsError, "");
});
