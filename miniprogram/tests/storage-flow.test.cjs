const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const {
  createRequire,
  setData,
} = require("../../scripts/miniprogram-runtime.cjs");

function harness(name, request, initial = {}) {
  let definition;
  const navigation = [];
  const cache = { jyj_token: "storage-flow-user", ...initial };
  const wx = {
    getStorageSync: (key) => cache[key],
    setStorageSync: (key, value) => (cache[key] = value),
    removeStorageSync: (key) => delete cache[key],
    navigateBack() {},
    navigateTo: (options) => navigation.push(options),
    setNavigationBarTitle() {},
    redirectTo: (options) => navigation.push(options),
  };
  const entry = path.resolve(__dirname, `../pages/${name}/index.js`);
  const context = {
    wx,
    Page: (value) => (definition = value),
    getCurrentPages: () => [],
    setTimeout,
    clearTimeout,
  };
  const requirePage = createRequire(entry, context, request);
  vm.runInNewContext(
    fs.readFileSync(entry, "utf8"),
    { ...context, require: requirePage },
    { filename: entry },
  );
  const page = {
    ...definition,
    data: structuredClone(definition.data),
    setData,
  };
  page.onLoad?.({ select: "1" });
  return { page, cache, requirePage, navigation };
}
const change = (value, dataset = {}) => ({
  detail: { value },
  currentTarget: { dataset },
});
const offer = () => ({
  id: 3,
  amountCents: 10000,
  lines: [{ skuId: 1, quantity: 5 }],
});

test("修改发货数量后报价失败，不能继续按旧报价建单", async () => {
  let creates = 0;
  const { page } = harness("checkout", async (url) => {
    if (url === "/checkout/quote") throw Error("库存变化，请重试");
    if (url === "/orders") creates++;
    return { id: 4 };
  });
  page.items = [{ skuId: 1, quantity: 5 }];
  page.setData({ address: { id: 1 }, quote: { id: 9 } });
  await page.shippingQuantity(change("2", { index: 0 }));
  await page.submit();
  assert.equal(creates, 0);
  assert.equal(page.data.quote, null);
});

test("先发出的报价后返回时，不能覆盖较新的发货数量报价", async () => {
  const pending = [];
  const { page } = harness(
    "checkout",
    async (_url, _method, body) =>
      new Promise((resolve) =>
        pending.push({ resolve, body: structuredClone(body) }),
      ),
  );
  page.items = [{ skuId: 1, quantity: 5 }];
  page.setData({ address: { id: 1 }, quote: { id: 9 } });
  const first = page.shippingQuantity(change("2", { index: 0 }));
  const second = page.shippingQuantity(change("1", { index: 0 }));
  const response = (id, rows) => ({
    id,
    goodsCents: 100,
    shippingCents: 0,
    totalCents: 100,
    lines: rows,
  });
  pending[1].resolve(response(11, pending[1].body.items));
  await second;
  pending[0].resolve(response(10, pending[0].body.items));
  await first;
  assert.equal(page.data.quote.id, 11);
  assert.equal(page.data.quote.lines[0].shipQuantity, 1);
});

test("套餐选择部分发货，选地址或寄存返回后保持原数量", async () => {
  const { page } = harness("offers", async (url) =>
    url === "/offers" ? { items: [offer()] } : { address: { id: 1 } },
  );
  page.onLoad({ offerId: "3" });
  await page.onShow();
  page.quantity(change("2", { offer: 3, index: 0 }));
  await page.onShow();
  assert.equal(page.data.offers[0].lines[0].shipQuantity, 2);
  page.mode(change("store"));
  await page.onShow();
  assert.equal(page.data.offers[0].lines[0].shipQuantity, 0);
});

test("浏览套餐不读取地址，点击购买仅进入选定套餐确认页", async () => {
  const calls = [];
  const { page, navigation } = harness("offers", async (url) => {
    calls.push(url);
    if (url === "/offers") return { items: [offer()] };
    throw Error("浏览时不应请求收货地址或创建订单");
  });
  await page.onShow();
  assert.equal(page.data.offers.length, 1);
  assert.equal(page.data.error, "");
  assert.deepEqual(calls, ["/offers"]);
  page.selectOffer(change("", { id: 3 }));
  assert.equal(navigation[0].url, "/pages/offers/index?offerId=3");
  assert.deepEqual(calls, ["/offers"]);
});

test("套餐确认只提交选中套餐及发货数量，重复点击不重复建单", async () => {
  const calls = [];
  const { page, cache, navigation } = harness(
    "offers",
    async (url, method, body) => {
      calls.push({ url, method, body });
      if (url === "/offers") return { items: [offer(), { ...offer(), id: 9 }] };
      if (url.startsWith("/mini/address")) return { address: { id: 2 } };
      if (url === "/checkout/quote")
        return {
          id: 8,
          totalCents: 10000,
          lines: [{ quantity: 5, shipQuantity: 2 }],
        };
      if (url === "/orders") return { id: 20 };
      throw Error("非预期请求");
    },
    { jyj_pickup_selection: [{ lotId: 7, quantity: 1 }] },
  );
  page.selectComponent = () => ({ open: async () => ({ confirm: true }) });
  page.onLoad({ offerId: "3" });
  await page.onShow();
  assert.equal(page.data.offers.length, 1);
  assert.equal(page.data.offers[0].id, 3);
  page.quantity(change("2", { offer: 3, index: 0 }));
  const first = page.buy(change("", { id: 3 }));
  await page.buy(change("", { id: 3 }));
  await first;
  const quote = calls.find((call) => call.url === "/checkout/quote").body;
  assert.equal(quote.offerId, 3);
  assert.equal(quote.addressId, 2);
  assert.deepEqual(Array.from(quote.offerShipQuantities), [2]);
  assert.equal(quote.storedItems[0].lotId, 7);
  assert.equal(calls.filter((call) => call.url === "/orders").length, 1);
  assert.equal(cache.jyj_pickup_selection, undefined);
  assert.equal(navigation.at(-1).url, "/pages/wallet/index?orderId=20");
});

test("套餐报价失败或取消确认时不建单，保留配送与寄存选择", async () => {
  for (const failQuote of [true, false]) {
    let creates = 0;
    const { page, cache } = harness(
      "offers",
      async (url) => {
        if (url === "/offers") return { items: [offer()] };
        if (url.startsWith("/mini/address")) return { address: { id: 2 } };
        if (url === "/checkout/quote") {
          if (failQuote) throw Error("库存变化");
          return {
            id: 8,
            totalCents: 10000,
            lines: [{ quantity: 5, shipQuantity: 0 }],
          };
        }
        if (url === "/orders") creates++;
      },
      { jyj_pickup_selection: [{ lotId: 7, quantity: 1 }] },
    );
    page.selectComponent = () => ({ open: async () => ({ confirm: false }) });
    page.onLoad({ offerId: "3" });
    await page.onShow();
    page.mode(change("store"));
    await page.buy(change("", { id: 3 }));
    assert.equal(creates, 0);
    assert.equal(page.data.busy, false);
    assert.equal(page.data.offers[0].lines[0].shipQuantity, 0);
    assert.equal(cache.jyj_pickup_selection[0].lotId, 7);
    if (failQuote) assert.equal(page.data.error, "库存变化");
  }
});

test("权益包选择部分发货，更换地址返回后保持原数量", async () => {
  const { page, cache } = harness("onboarding", async (url) => {
    if (url === "/membership")
      return { packages: [{ ...offer(), targetIdentity: "C" }] };
    if (url === "/me") return { id: 1 };
    if (url.startsWith("/mini/address")) return { address: { id: 1 } };
    return { items: [], hasMore: false };
  });
  await page.onShow();
  page.shipQuantity(change("2", { index: 0 }));
  cache.jyj_address = 2;
  await page.onShow();
  assert.equal(page.data.selected.lines[0].shipQuantity, 2);
});

test("再次进入合并寄存可编辑原选择，并能清空全部选择", async () => {
  const { page, cache } = harness(
    "storage",
    async (url) =>
      url.startsWith("/mini/package-consumption")
        ? { items: [] }
        : { items: [{ id: 7, available_qty: 4 }], hasMore: false },
    { jyj_pickup_selection: [{ lotId: 7, quantity: 2 }] },
  );
  await page.onShow();
  assert.equal(page.data.selectedCount, 2);
  assert.equal(page.data.lots[0].selectedQuantity, 2);
  page.quantity(change("0", { id: 7 }));
  await page.submit();
  assert.equal(cache.jyj_pickup_selection.length, 0);
});

test("全部寄存后手动改发货数量，模式跟随实际数量", async () => {
  const { page } = harness("onboarding", async (url) => {
    if (url === "/membership")
      return { packages: [{ ...offer(), targetIdentity: "C" }] };
    if (url === "/me") return { id: 1 };
    if (url.startsWith("/mini/address")) return { address: { id: 1 } };
    return { items: [], hasMore: false };
  });
  await page.onShow();
  page.deliveryMode(change("store"));
  page.shipQuantity(change("1", { index: 0 }));
  assert.equal(page.data.deliveryMode, "ship");
  page.shipQuantity(change("0", { index: 0 }));
  assert.equal(page.data.deliveryMode, "store");
});

test("寄存订单展示实际履约状态并提供剩余寄存说明", () => {
  const { requirePage } = harness("order", async () => ({}));
  const { decorate } = requirePage("./view");
  const result = decorate({
    status: "shipped",
    payment_state: "paid",
    fulfillmentState: "stored",
    fulfillmentText: "部分已收货，余货寄存",
    storageRemainingQty: 2,
    snapshot: { lines: [] },
  });
  assert.equal(result.stateText, "部分已收货，余货寄存");
  assert.match(result.hint, /剩余2件寄存/);
});

const combinedOrder = () => ({
  id: 91,
  status: "paid",
  payment_state: "paid",
  payment_method: "points",
  total_cents: 18430,
  goods_cents: 17430,
  shipping_cents: 1000,
  snapshot: {
    lines: [{ skuId: 11, name: "新购面霜", quantity: 1, unitCents: 17430 }],
    storedItems: [{ lotId: 7, quantity: 2 }],
  },
  pickups: [{ id: 9, number: "TH9", status: "review" }],
});
const combinedPickup = () => ({
  id: 9,
  purchase_order_id: 91,
  number: "TH9",
  status: "review",
  lines: [
    {
      id: 101,
      order_id: 91,
      name: "新购面霜",
      sku_name: "标准装",
      quantity: 1,
    },
    {
      id: 102,
      order_id: 80,
      lot_id: 7,
      name: "原寄存精华",
      sku_name: "标准装",
      quantity: 2,
    },
  ],
});

test("提货商品预览只展示前两款，合计仍包含未展示商品及赠品", () => {
  const { requirePage } = harness("storage", async () => ({}));
  const { pickupView } = requirePage("../../utils/storage-view");
  const image =
    "https://example.com/products/catalog/20261028/00000000-0000-0000-0000-000000000000.png";
  const view = pickupView({
    status: "received",
    fee_cents: 1000,
    lines: [
      { id: 1, image, quantity: 1 },
      { id: 2, quantity: 2 },
      { id: 3, quantity: 4, is_gift: 1 },
    ],
  });
  assert.equal(view.previewLines.length, 2);
  assert.equal(view.moreKinds, 1);
  assert.equal(view.quantity, 7);
  assert.equal(view.lines[2].is_gift, 1);
  assert.match(view.previewLines[0].imageThumb, /resize,w_160/);
  assert.equal(view.feeText, "10.00");
  assert.equal(view.statusText, "已收货");
  assert.equal(view.statusMuted, true);
});

test("付款后的订单详情直接展示跨订单合并提货商品，原购买数量与金额不变", async () => {
  const calls = [];
  const { page, navigation } = harness("order", async (url) => {
    calls.push(url);
    if (url === "/orders/91/payment") return { order: combinedOrder() };
    if (url === "/pickups/9") return combinedPickup();
    return {};
  });
  page.onLoad({ id: 91 });
  await page.load();
  assert.equal(page.data.combinedPickup.count, 3);
  assert.equal(page.data.combinedPickup.lines[1].name, "原寄存精华");
  assert.equal(
    page.data.combinedPickup.lines[1].sourceText,
    "寄存商品 · 不重复收费",
  );
  assert.equal(page.data.combinedPickup.lines[0].sourceText, "本单新购商品");
  assert.equal(page.data.combinedPickup.statusText, "待审核配送");
  assert.equal(page.data.order.count, 1);
  assert.equal(page.data.order.amountText, "184.30 积分");
  assert.equal(calls.filter((url) => url.startsWith("/pickups/")).length, 1);
  page.pickup(change(null, { id: 9 }));
  assert.equal(navigation[0].url, "/pages/pickup/index?id=9");
});

test("未合并寄存的订单不增加提货明细请求", async () => {
  const calls = [];
  const order = combinedOrder();
  order.snapshot.storedItems = [];
  const { page } = harness("order", async (url) => {
    calls.push(url);
    return url.endsWith("/payment") ? { order } : {};
  });
  page.onLoad({ id: 91 });
  await page.load();
  assert.equal(page.data.hasCombined, false);
  assert.equal(page.data.combinedPickup, null);
  assert.equal(calls.filter((url) => url.startsWith("/pickups/")).length, 0);
});

test("合并提货查询失败不隐藏已付款订单，重试后显示实际取消状态及赠品", async () => {
  let failed = true;
  const { page } = harness("order", async (url) => {
    if (url.endsWith("/payment")) return { order: combinedOrder() };
    if (url === "/pickups/9") {
      if (failed) throw Error("提货单读取失败");
      const pickup = combinedPickup();
      pickup.status = "cancelled";
      pickup.lines[1].is_gift = 1;
      return pickup;
    }
    return {};
  });
  page.onLoad({ id: 91 });
  await page.load();
  assert.equal(page.data.order.paid, true);
  assert.equal(page.data.error, "");
  assert.equal(page.data.combinedError, "提货单读取失败");
  failed = false;
  await page.retryCombined();
  assert.equal(page.data.combinedError, "");
  assert.equal(page.data.combinedPickup.statusText, "已取消");
  assert.equal(page.data.combinedPickup.lines[1].is_gift, 1);
});

test("待支付合并单使用首发提货单，后续分次提货不混入本次明细", async () => {
  const calls = [];
  const order = combinedOrder();
  order.status = "pending";
  order.payment_state = "unpaid";
  order.pickups.push({ id: 12, status: "review" });
  const { page } = harness("order", async (url) => {
    calls.push(url);
    if (url.endsWith("/payment")) return { order };
    if (url === "/pickups/9")
      return { ...combinedPickup(), status: "awaiting_payment" };
    return {};
  });
  page.onLoad({ id: 91 });
  await page.load();
  assert.equal(page.data.combinedPickup.statusText, "待付款");
  assert.equal(page.data.combinedPickup.count, 3);
  assert.ok(!calls.includes("/pickups/12"));
});

test("拒绝将不属于本次新购订单的提货单展示为合并明细", async () => {
  const { page } = harness("order", async (url) => {
    if (url.endsWith("/payment")) return { order: combinedOrder() };
    if (url === "/pickups/9")
      return { ...combinedPickup(), purchase_order_id: 80 };
    return {};
  });
  page.onLoad({ id: 91 });
  await page.load();
  assert.equal(page.data.combinedPickup, null);
  assert.match(page.data.combinedError, /与本订单不一致/);
  assert.equal(page.data.order.paid, true);
});

test("寄存只提一件时，待发货列表只显示本次待发一件，全部订单保留原购买数量", async () => {
  const order = {
    id: 91,
    number: "JY91",
    kind: "goods",
    status: "paid",
    payment_state: "paid",
    payment_method: "points",
    total_cents: 50000,
    goods_cents: 50000,
    shipping_cents: 0,
    fulfillmentState: "awaiting_shipment",
    fulfillmentText: "待审核配送",
    storageRemainingQty: 5,
    snapshot: {
      lines: [
        {
          skuId: 11,
          name: "本次申请的商品",
          skuName: "标准装",
          quantity: 3,
          unitCents: 10000,
        },
        {
          skuId: 12,
          name: "仍寄存的另一商品",
          skuName: "标准装",
          quantity: 2,
          unitCents: 10000,
        },
      ],
    },
    lines: [
      {
        id: 101,
        sku_id: 11,
        is_gift: 0,
        quantity: 3,
        pending_ship_qty: 1,
        in_transit_qty: 0,
      },
      {
        id: 102,
        sku_id: 12,
        is_gift: 0,
        quantity: 2,
        pending_ship_qty: 0,
        in_transit_qty: 0,
      },
    ],
    shipments: [],
    pickups: [{ id: 9, status: "review" }],
  };
  const { page } = harness("orders", async () => ({
    items: [order],
    hasMore: false,
    nextPage: null,
  }));
  page.onLoad({ tab: 2 });
  await page.load();
  assert.equal(page.data.orders[0].displayLines.length, 1);
  assert.equal(page.data.orders[0].displayLines[0].quantity, 1);
  assert.equal(page.data.orders[0].count, 1);
  assert.equal(page.data.orders[0].originalCount, 5);
  page.onLoad({ tab: 0 });
  await page.load();
  assert.equal(page.data.orders[0].displayLines.length, 2);
  assert.equal(page.data.orders[0].count, 5);
  assert.equal(order.snapshot.lines[0].quantity, 3);
});

test("运输中只展示在途件数，同规格赠品独立计数且不会覆盖主品", async () => {
  const { page } = harness("orders", async () => ({
    items: [
      {
        id: 92,
        status: "paid",
        payment_state: "paid",
        payment_method: "points",
        total_cents: 30000,
        snapshot: {
          lines: [
            { skuId: 11, name: "主品", quantity: 3, unitCents: 10000 },
            {
              skuId: 11,
              name: "赠品",
              isGift: true,
              quantity: 2,
              unitCents: 0,
            },
          ],
        },
        lines: [
          { id: 101, sku_id: 11, is_gift: 0, in_transit_qty: 1 },
          { id: 102, sku_id: 11, is_gift: 1, in_transit_qty: 2 },
        ],
        shipments: [],
        pickups: [],
      },
    ],
    hasMore: false,
    nextPage: null,
  }));
  page.onLoad({ tab: 3 });
  await page.load();
  const row = page.data.orders[0];
  assert.equal(row.count, 3);
  assert.equal(row.originalCount, 5);
  assert.equal(row.displayLines[0].quantity, 1);
  assert.equal(row.displayLines[1].quantity, 2);
  assert.notEqual(row.displayLines[0].lineKey, row.displayLines[1].lineKey);
  assert.equal(row.stateText, "运输中");
});

test("结算建单成功后进入本单付款，重试导航不重复创建订单", async () => {
  let creates = 0;
  const { page, navigation } = harness("checkout", async (url) => {
    if (url === "/orders") {
      creates++;
      return { id: 9 };
    }
    return { id: 5 };
  });
  page.items = [{ skuId: 1, quantity: 1 }];
  page.setData({ address: { id: 1 }, quote: { id: 5 } });
  page.requestKey = "order-key";
  await page.submit();
  assert.equal(creates, 1);
  assert.equal(navigation.at(-1).url, "/pages/wallet/index?orderId=9");
  await page.submit();
  assert.equal(creates, 1);
  assert.equal(navigation.at(-1).url, "/pages/wallet/index?orderId=9");
});

test("纯寄存提货先核对地址和运费，余额不足不提交", async () => {
  const posts = [];
  const { page } = harness("storage", async (url, method, body) => {
    if (url === "/mini/address") return { address: { id: 1 } };
    if (url === "/pickups/quote") {
      posts.push({ url, method, body: structuredClone(body) });
      return {
        address: { id: 1 },
        shippingCents: 1000,
        availableCents: 500,
        shortfallCents: 500,
        shippingReason: "主理人未满1000元，收取10积分运费。",
      };
    }
    if (url === "/pickups") {
      posts.push({ url, method, body: structuredClone(body) });
      return { id: 9 };
    }
    return { items: [], hasMore: false, nextPage: null };
  });
  page.setData({ selectMode: false });
  page.selection = { 7: { lotId: 7, quantity: 2, name: "寄存商品" } };
  page.updateSelection();
  await page.openConfirm();
  assert.equal(page.data.confirmOpen, true);
  assert.equal(page.data.confirmFeeText, "10.00");
  assert.equal(page.data.confirmShortfallText, "5.00");
  assert.match(page.data.confirmShippingText, /10积分/);
  posts.length = 0;
  await page.confirmSubmit();
  assert.equal(posts.length, 0, "余额不足不能提交");
  page.setData({
    confirmQuote: { shippingCents: 1000 },
    confirmAvailableText: "20.00",
    confirmShortfallText: "0.00",
  });
  await page.confirmSubmit();
  assert.equal(posts.length, 1);
  assert.equal(posts[0].url, "/pickups");
  assert.deepEqual(posts[0].body.storedItems, [{ lotId: 7, quantity: 2 }]);
  assert.equal(posts[0].body.expectedFeeCents, 1000);
});

test("订单搜索把关键词与当前状态一起传给服务端", async () => {
  const urls = [];
  const { page } = harness("orders", async (url) => {
    urls.push(url);
    return { items: [], hasMore: false, nextPage: null };
  });
  page.setData({ keywordInput: "精华_100%" });
  await page.search();
  assert.ok(urls.at(-1).includes("keyword=" + encodeURIComponent("精华_100%")));
  page.setData({ tab: 2 });
  await page.load();
  assert.ok(urls.at(-1).includes("tab=2"));
  assert.ok(urls.at(-1).includes("keyword=" + encodeURIComponent("精华_100%")));
  page.clearSearch();
  assert.ok(!urls.at(-1).includes("keyword="));
});
