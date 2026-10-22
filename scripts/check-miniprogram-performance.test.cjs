const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { createRequire, setData } = require("./miniprogram-runtime.cjs");
const root = path.resolve(__dirname, "../miniprogram");
const tick = () => new Promise(setImmediate);
function deferred() {
  let resolve, reject;
  const promise = new Promise((r, j) => {
    resolve = r;
    reject = j;
  });
  return { promise, resolve, reject };
}

function runtime(name, request, token = "token") {
  let definition;
  const storage = new Map([["jyj_token", token]]),
    navigation = [];
  const wx = {
    getStorageSync: (key) => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: (key) => storage.delete(key),
    redirectTo: ({ url }) => navigation.push(url),
    navigateTo: (value) => {
      navigation.push(value.url);
      if (value.complete) value.complete();
    },
    stopPullDownRefresh() {},
  };
  const entry = path.join(
    root,
    "pages",
    name.includes("/") ? name + ".js" : name + "/index.js",
  );
  const context = {
    wx,
    Page: (p) => {
      definition = p;
    },
  };
  context.require = createRequire(entry, context, request);
  vm.runInNewContext(fs.readFileSync(entry, "utf8"), context, {
    filename: entry,
  });
  const page = {
    ...definition,
    data: structuredClone(definition.data),
    setData,
  };
  if (page.onLoad && !["profile", "rewards/detail"].includes(name))
    page.onLoad({});
  return { page, navigation, storage, context };
}

test("身份确认后，邀请与钱包并行，设置和订单无需等待这两个请求", async () => {
  const invitation = deferred(),
    wallet = deferred(),
    calls = [];
  const { page, navigation } = runtime("profile", (url) => {
    calls.push(url);
    if (url === "/me") return Promise.resolve({ id: 7, identity: "B" });
    if (url === "/mini/orders/counts")
      return Promise.resolve({ pending: 0, paid: 0, shipped: 0, completed: 0 });
    return url === "/invitations/mine" ? invitation.promise : wallet.promise;
  });
  const loading = page.onShow();
  await tick();
  assert.deepEqual(calls, [
    "/me",
    "/mini/orders/counts",
    "/invitations/mine",
    "/mini/wallet",
  ]);
  assert.equal(page.data.busy, false);
  page.onNavigate({ currentTarget: { dataset: { key: "settings" } } });
  page.onNavigate({ currentTarget: { dataset: { key: "orders" } } });
  assert.deepEqual(navigation, [
    "/pages/settings/index",
    "/pages/orders/index",
  ]);
  invitation.resolve({ shareCode: "code" });
  wallet.resolve({ availableCents: 1234, transfer: { configured: true } });
  await loading;
  assert.equal(page.data.availablePoints, "12.34");
  await page.onShow();
  assert.equal(calls.length, 4, "正常往返复用已读取资料和订单数量");
});

test("种草刷新保留原列表，默认请求二十条，失败后仍能阅读原内容", async () => {
  const pending = deferred();
  let url;
  const { page } = runtime("seeds", (value) => {
    url = value;
    return pending.promise;
  });
  page.setData({ items: [{ id: 9 }], loaded: true });
  const loading = page.onShow();
  assert.equal(page.data.items.length, 1);
  assert.match(url, /limit=20/);
  pending.reject(new Error("网络中断"));
  await loading;
  assert.equal(page.data.items[0].id, 9);
  assert.equal(page.data.loading, false);
});

test("商城首次二十条，触底才追加下一页，重复触底只发送一次，末页停止请求", async () => {
  const next = deferred(),
    calls = [];
  const { page } = runtime("mall", async (url) => {
    calls.push(url);
    if (url === "/public/categories") return [];
    if (url === "/mini/cart/count") return { count: 1 };
    if (url.includes("page=2")) return next.promise;
    return {
      items: Array.from({ length: 20 }, (_, i) => ({ id: i + 1, skus: [] })),
      identity: "D",
      hasMore: true,
      nextPage: 2,
    };
  });
  await page.onShow();
  assert.equal(page.data.products.length, 20);
  assert.equal(calls.length, 3);
  await page.onShow();
  assert.equal(calls.length, 3);
  const more = page.onReachBottom();
  await page.onReachBottom();
  assert.equal(calls.length, 4);
  next.resolve({
    items: [{ id: 21, skus: [] }],
    identity: "D",
    hasMore: false,
    nextPage: null,
  });
  await more;
  assert.equal(page.data.products.length, 21);
  assert.equal(page.data.products[20].id, 21);
  await page.onReachBottom();
  assert.equal(calls.length, 4);
});

test("加载下一页失败时保留内容和页码，重试仍读取同一页", async () => {
  let attempts = 0;
  const urls = [];
  const { page } = runtime("cash-refunds", async (url) => {
    urls.push(url);
    if (url.includes("page=2") && ++attempts === 1) throw new Error("网络中断");
    return {
      items: [
        {
          id: url.includes("page=2") ? 2 : 1,
          amount_cents: 100,
          status: "success",
        },
      ],
      hasMore: !url.includes("page=2"),
      nextPage: url.includes("page=2") ? null : 2,
    };
  });
  await page.onShow();
  await page.onReachBottom();
  assert.equal(page.data.items.length, 1);
  assert.equal(page.data.itemsNextPage, 2);
  await page.onReachBottom();
  assert.equal(page.data.items.length, 2);
  assert.equal(urls[1], urls[2]);
});

test("快速切换分类时旧响应不会覆盖新分类，分类条件传给服务端", async () => {
  const old = deferred(),
    latest = deferred();
  const { page } = runtime("mall", (url) =>
    url === "/public/categories"
      ? Promise.resolve([])
      : url.includes("categoryId=1")
        ? old.promise
        : latest.promise,
  );
  const first = page.selectCategory({ currentTarget: { dataset: { id: 1 } } });
  const second = page.selectCategory({ currentTarget: { dataset: { id: 2 } } });
  latest.resolve({
    items: [{ id: 2, skus: [] }],
    identity: "D",
    hasMore: false,
    nextPage: null,
  });
  await second;
  old.resolve({
    items: [{ id: 1, skus: [] }],
    identity: "D",
    hasMore: false,
    nextPage: null,
  });
  await first;
  assert.equal(page.data.products[0].id, 2);
  assert.equal(page.data.categoryId, 2);
});

test("订单和奖励切换筛选均重新查询服务端第一页", async () => {
  for (const name of ["orders", "rewards"]) {
    const calls = [];
    const { page } = runtime(name, async (url) => {
      calls.push(url);
      return { items: [], hasMore: false, nextPage: null };
    });
    await page.onShow();
    await page.select({ currentTarget: { dataset: { index: 2 } } });
    assert.match(calls[1], /tab=2.*limit=20&page=1/);
  }
});

test("商城连续滚动不写回视图位置，切换条件时仍可连续回到顶部", async () => {
  const { page } = runtime("mall", async (url) =>
    url === "/public/categories"
      ? []
      : { items: [], identity: "D", hasMore: false, nextPage: null },
  );
  const writes = [];
  page.setData = function (values, callback) {
    writes.push(structuredClone(values));
    setData.call(this, values);
    if (callback) callback();
  };
  for (let index = 1; index <= 120; index++)
    page.trackScroll({ detail: { scrollTop: index * 4 } });
  assert.equal(writes.length, 0, "手指和惯性滚动不能向视图写回 scroll-top");

  await page.resetProducts();
  assert.equal(page.data.productsScrollTop, 0);
  const firstPositions = writes
    .filter((values) => Object.hasOwn(values, "productsScrollTop"))
    .map((values) => values.productsScrollTop);
  assert.deepEqual(
    firstPositions,
    [480, 0],
    "仅在切换条件时更新已滚动位置并回顶",
  );

  writes.length = 0;
  page.trackScroll({ detail: { scrollTop: 240 } });
  assert.equal(writes.length, 0);
  await page.resetProducts();
  assert.equal(page.data.productsScrollTop, 0);
  assert.deepEqual(
    writes
      .filter((values) => Object.hasOwn(values, "productsScrollTop"))
      .map((values) => values.productsScrollTop),
    [240, 0],
    "上次绑定值已为零时，再次切换仍须触发回顶",
  );
});

test("商城搜索交给服务端，搜索分页与分类切换保留关键词，清空后恢复分类列表", async () => {
  const calls = [];
  const { page } = runtime("mall", async (url) => {
    calls.push(url);
    if (url === "/public/categories") return [{ id: 3, name: "修护系列" }];
    return {
      items: [{ id: url.includes("page=2") ? 21 : 1, skus: [] }],
      identity: "D",
      hasMore: !url.includes("page=2"),
      nextPage: url.includes("page=2") ? null : 2,
    };
  });
  await page.load();
  page.trackScroll({ detail: { scrollTop: 680 } });
  page.inputSearch({ detail: { value: "  修护 & 保湿  " } });
  assert.equal(calls.length, 2, "输入时不逐字发出请求");
  await page.search();
  assert.equal(page.data.productsScrollTop, 0);
  assert.match(
    calls.at(-1),
    /keyword=%E4%BF%AE%E6%8A%A4%20%26%20%E4%BF%9D%E6%B9%BF&limit=20&page=1/,
  );
  await page.onReachBottom();
  assert.match(calls.at(-1), /keyword=.*&limit=20&page=2/);
  assert.equal(page.data.products.length, 2);
  await page.selectCategory({ currentTarget: { dataset: { id: 3 } } });
  assert.equal(page.data.categoryName, "修护系列");
  assert.match(calls.at(-1), /categoryId=3&keyword=.*&limit=20&page=1/);
  assert.equal(page.data.products.length, 1);
  await page.inputSearch({ detail: { value: "" } });
  assert.equal(calls.at(-1), "/mini/products?categoryId=3&limit=20&page=1");
  assert.equal(page.data.keyword, "");
});

test("商城旧搜索不能覆盖新结果，首屏失败与下一页失败均可按原条件重试", async () => {
  const old = deferred();
  let fail = true;
  const calls = [];
  const { page } = runtime("mall", async (url) => {
    if (url === "/public/categories") return [];
    calls.push(url);
    if (url.includes("keyword=old")) return old.promise;
    if (fail) throw new Error("连接失败");
    return { items: [{ id: 8, skus: [] }], hasMore: true, nextPage: 2 };
  });
  page.setData({ searchValue: "old" });
  const first = page.search();
  page.setData({ searchValue: "new" });
  await page.search();
  assert.equal(page.data.productsLoaded, false);
  assert.equal(page.data.error, "连接失败");
  fail = false;
  await page.retry();
  assert.match(calls.at(-1), /keyword=new&limit=20&page=1/);
  old.resolve({ items: [{ id: 1, skus: [] }], hasMore: false, nextPage: null });
  await first;
  assert.equal(page.data.products[0].id, 8);
  fail = true;
  await page.onReachBottom();
  assert.equal(page.data.products.length, 1);
  assert.equal(page.data.productsNextPage, 2);
  fail = false;
  await page.retry();
  assert.match(calls.at(-1), /keyword=new&limit=20&page=2/);
});

test("切换账号后清除上一个账号列表，旧响应不能写回", async () => {
  const old = deferred();
  const { page, storage } = runtime("cash-refunds", () => old.promise);
  const loading = page.onShow();
  storage.set("jyj_token", "another-token");
  old.resolve({ items: [{ id: 1 }], hasMore: false, nextPage: null });
  await loading;
  assert.equal(page.data.items.length, 0);
});

test("购物袋结算从服务端取得全部已选项，包含尚未展示的商品", async () => {
  const { page, storage, navigation } = runtime("cart", async (url) => {
    assert.equal(url, "/mini/cart/selection");
    return {
      items: [
        { skuId: 1, quantity: 1 },
        { skuId: 21, quantity: 2 },
      ],
    };
  });
  page.setData({
    items: [{ skuId: 1, quantity: 1, selected: true, available: true }],
  });
  await page.checkout();
  assert.equal(storage.get("jyj_checkout").length, 2);
  assert.equal(navigation[0], "/pages/checkout/index");
});

test("结算页直接读取本人选中地址，不依赖地址列表第一页", async () => {
  const calls = [];
  const { page, storage } = runtime("checkout", async (url) => {
    calls.push(url);
    if (url === "/mini/address?id=99") return { address: { id: 99 } };
    if (url === "/checkout/quote")
      return {
        id: 1,
        lines: [],
        goodsCents: 100,
        shippingCents: 0,
        totalCents: 100,
      };
    throw Error(url);
  });
  storage.set("jyj_checkout", [{ skuId: 1, quantity: 1 }]);
  storage.set("jyj_address", 99);
  await page.onShow();
  assert.equal(page.data.address.id, 99);
  assert.deepEqual(calls, ["/mini/address?id=99", "/checkout/quote"]);
});

test("下单成功后清除本次结算商品缓存并跳转订单，不逐项调用购物袋接口", async () => {
  const { page, storage, navigation } = runtime(
    "checkout",
    async (url, method, body) => {
      assert.equal(url, "/orders");
      assert.equal(method, "POST");
      assert.equal(body.quoteId, 1);
      return { id: 9 };
    },
  );
  storage.set("jyj_checkout", [{ skuId: 1, quantity: 2 }]);
  page.setData({ quote: { id: 1 } });
  page.requestKey = "order-cart-regression";
  await page.submit();
  assert.equal(storage.has("jyj_checkout"), false);
  assert.deepEqual(navigation, ["/pages/order/index?id=9"]);
  assert.equal(page.data.error, "");
});

test("下单失败保留结算商品供重试，不跳转订单", async () => {
  const { page, storage, navigation } = runtime("checkout", async () => {
    throw Error("库存不足");
  });
  storage.set("jyj_checkout", [{ skuId: 1, quantity: 2 }]);
  page.setData({ quote: { id: 1 } });
  await page.submit();
  assert.equal(storage.has("jyj_checkout"), true);
  assert.deepEqual(navigation, []);
  assert.equal(page.data.error, "库存不足");
  assert.equal(page.data.busy, false);
});

test("业务变更使缓存失效，再次进入会读取最新记录", async () => {
  let calls = 0;
  const { page, context } = runtime("cash-refunds", async () => {
    calls++;
    return { items: [], hasMore: false, nextPage: null };
  });
  await page.onShow();
  await page.onShow();
  assert.equal(calls, 1);
  context.require("../../utils/state").invalidate();
  await page.onShow();
  assert.equal(calls, 2);
});

test("首页品牌内容先返回时即可展示，不等待精选商品；加载期间重复显示不重复请求", async () => {
  const product = deferred();
  let calls = 0;
  const { page } = runtime("home", async (url) => {
    calls++;
    return url === "/public/content"
      ? { content: { heroTitle: "已返回的品牌标题" } }
      : product.promise;
  });
  const loading = page.onShow();
  await tick();
  assert.equal(page.data.heroTitle, "已返回的品牌标题");
  await page.onShow();
  assert.equal(calls, 2);
  product.resolve({ items: [] });
  await loading;
});

for (const [name, field, method] of [
  ["orders", "orders", "load"],
  ["rewards", "items", "load"],
  ["addresses", "items", "load"],
  ["cart", "items", "load"],
  ["refunds", "items", "load"],
  ["wallet", "ledger", "loadLedger"],
  ["onboarding", "qualifications", "loadQualifications"],
  ["team", "orders", "load"],
]) {
  test(`${name} 实际页面首次只读二十条，触底后保留前页并追加`, async () => {
    const urls = [];
    const { page } = runtime(name, async (url) => {
      urls.push(url);
      const second = url.includes("page=2");
      const items = Array.from({ length: second ? 1 : 20 }, (_, index) => {
        const id = second ? 21 : index + 1;
        return {
          id,
          eventKey: "reward-" + id,
          application_id: id,
          status: "pending",
          kind: "credit",
          payment_state: "unpaid",
          total_cents: 100,
          goods_cents: 100,
          shipping_cents: 0,
          snapshot: { lines: [] },
          shipments: [],
          createTime: "2026-10-20T00:00:00Z",
          create_time: "2026-10-20T00:00:00Z",
          amountCents: 100,
          total_cents: 100,
          delta_cents: 100,
          lines: [],
        };
      });
      return {
        items,
        hasMore: !second,
        nextPage: second ? null : 2,
        kinds: 41,
        pieces: 41,
        totalCents: 4100,
      };
    });
    await page[method]();
    assert.equal(page.data[field].length, 20);
    assert.ok(urls.every((url) => url.includes("limit=20&page=1")));
    await page.onReachBottom();
    assert.equal(page.data[field].length, 21);
    assert.equal(page.data[field][20].id, 21);
  });
}

test("奖励详情各历史首屏二十条，触底只续取未结束分区，失败后同页重试", async () => {
  const calls = [];
  let failed = false;
  const { page } = runtime("rewards/detail", async (url) => {
    calls.push(url);
    const query = new URL("https://local" + url).searchParams;
    const second = query.get("page") === "2";
    if (second && !failed) {
      failed = true;
      throw new Error("网络中断");
    }
    const pages = {};
    for (const kind of query.get("parts").split(",")) {
      pages[kind] = {
        items: Array.from({ length: second ? 1 : 20 }, (_, i) => ({
          id: second ? 21 : i + 1,
          amount_cents: 100,
          debt_cents: 0,
          offset_cents: 0,
          recovered_cents: 0,
          remainingCents: 0,
          gross_cents: 100,
          net_cents: 100,
        })),
        hasMore: kind === "reversals" && !second,
        nextPage: kind === "reversals" && !second ? 2 : null,
      };
    }
    return {
      pages,
      ...(!second
        ? {
            item: {
              id: 7,
              kind: "margin",
              amountCents: 100,
              reversedCents: 0,
              settledCents: 0,
              remainingCents: 100,
            },
          }
        : {}),
    };
  });
  await page.onLoad({ id: 7 });
  for (const kind of [
    "reversals",
    "settlements",
    "debts",
    "offsets",
    "recoveries",
  ])
    assert.equal(page.data[kind].length, 20);
  assert.equal(calls.length, 1);
  await page.onReachBottom();
  assert.equal(page.data.reversals.length, 20);
  assert.equal(page.data.item.id, 7);
  await page.onReachBottom();
  assert.equal(calls[1], calls[2]);
  assert.match(calls[2], /page=2&parts=reversals$/);
  assert.equal(page.data.reversals.length, 21);
  assert.equal(page.data.settlements.length, 20);
  await page.onReachBottom();
  assert.equal(calls.length, 3);
});

test("奖励详情首屏失败后，页尾重试仍可读取第一页", async () => {
  const calls = [];
  const { page } = runtime("rewards/detail", async (url) => {
    calls.push(url);
    if (calls.length === 1) throw new Error("网络中断");
    const pages = {};
    for (const kind of [
      "reversals",
      "settlements",
      "debts",
      "offsets",
      "recoveries",
    ])
      pages[kind] = { items: [], hasMore: false, nextPage: null };
    return { pages, item: { id: 7, amountCents: 100 } };
  });
  await page.onLoad({ id: 7 });
  await page.onReachBottom();
  assert.equal(calls[0], calls[1]);
  assert.equal(page.data.item.id, 7);
  assert.equal(page.data.error, "");
});
