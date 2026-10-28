const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");
const vue = require("vue");
const { parse, compileScript } = require("@vue/compiler-sfc");
function setup(
  name,
  props = {},
  request = async () => ({}),
  listLoad = async () => [],
) {
  const filename = path.resolve(
    __dirname,
    "../web/components/" + name + ".vue",
  );
  const script = compileScript(
    parse(fs.readFileSync(filename, "utf8")).descriptor,
    { id: "product-search" },
  );
  const output = ts.transpileModule(script.content, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText;
  const module = { exports: {} },
    events = [],
    cleanup = [];
  const scope = vue.effectScope();
  vm.runInNewContext(
    output,
    {
      module,
      exports: module.exports,
      Error,
      AbortController,
      URLSearchParams,
      setTimeout,
      clearTimeout,
      crypto: require("node:crypto").webcrypto,
      require(id) {
        if (id === "vue")
          return {
            ...vue,
            onMounted() {},
            onBeforeUnmount(fn) {
              cleanup.push(fn);
            },
          };
        if (id === "ant-design-vue")
          return { Radio: { Group: {}, Button: {} } };
        if (id === "../client") return { api: request };
        if (id === "../admin-user-names")
          return {
            useAdminUserNames: () => ({ label: (id) => `测试用户（#${id}）` }),
          };
        if (id === "../content-client")
          return {
            contentApi: {
              save: (id, body) => request("/posts/" + id, { body }),
            },
          };
        if (id === "../useAdminList")
          return {
            useAdminList: () => ({
              rows: vue.ref([]),
              total: vue.ref(0),
              pagination: {},
              loading: vue.ref(false),
              load: listLoad,
            }),
          };
        return {};
      },
    },
    { filename },
  );
  const state = scope.run(() =>
    module.exports.default.setup(props, {
      expose() {},
      emit: (event, ...args) => events.push([event, ...args]),
    }),
  );
  return {
    state,
    events,
    stop() {
      cleanup.forEach((fn) => fn());
      scope.stop();
    },
  };
}
function picker(props = {}, request) {
  return setup(
    "AdminProductSelect",
    vue.reactive({ kind: "sku", max: 10, disabledIds: [], ...props }),
    request,
  );
}
const row = (id, label = "修护精华 · 规格" + id) => ({
  id,
  label,
  active: true,
});
const page = (items, total = items.length, number = 1) => ({
  items,
  total,
  page: number,
  pageSize: 20,
});
const settle = async () => {
  await Promise.resolve();
  await vue.nextTick();
  await Promise.resolve();
};
const plain = (value) => JSON.parse(JSON.stringify(value));

test("服务端分页与关键词搜索可选择首屏之外的商品，已选名称不随关键词丢失", async () => {
  const calls = [],
    props = vue.reactive({
      modelValue: 0,
      kind: "sku",
      max: 10,
      disabledIds: [],
    });
  const p = setup("AdminProductSelect", props, async (url) => {
    const q = new URL("http://local" + url).searchParams;
    calls.push(q);
    if (q.get("q")) return page([row(88, "远页商品 · 30ml")]);
    return q.get("page") === "2"
      ? page([row(21)], 21, 2)
      : page(
          Array.from({ length: 20 }, (_, i) => row(i + 1)),
          21,
        );
  });
  await p.state.load();
  await p.state.load(true);
  assert.equal(p.state.rows.value.length, 21);
  assert.equal(calls[1].get("page"), "2");
  p.state.query.value = "远页商品";
  await p.state.load();
  p.state.choose(88);
  props.modelValue = 88;
  await settle();
  p.state.query.value = "";
  await p.state.load();
  assert.equal(
    p.state.options.value.find((o) => o.value === 88).label,
    "远页商品 · 30ml",
  );
  assert.deepEqual(plain(p.events[0]), ["update:modelValue", 88]);
  p.stop();
});
test("旧请求迟到不能覆盖新搜索结果，包括防抖等待期间", async () => {
  const pending = [];
  const p = picker(
    {},
    (url) => new Promise((resolve) => pending.push({ url, resolve })),
  );
  const first = p.state.load();
  p.state.search("新商品");
  pending[0].resolve(page([row(1, "过时结果")]));
  await first;
  assert.equal(p.state.rows.value.length, 0);
  await new Promise((resolve) => setTimeout(resolve, 280));
  assert.match(pending[1].url, /q=/);
  pending[1].resolve(page([row(2, "新结果")]));
  await settle();
  assert.equal(p.state.rows.value[0].id, 2);
  p.stop();
});
test("已停用的旧选择可回显且不能当作在售规格新选，清空提交未选择值", async () => {
  const p = picker({ modelValue: 9 }, async (url) => {
    assert.match(url, /ids=9/);
    return page([{ ...row(9, "原精华 · 已停用"), active: false }]);
  });
  await settle();
  assert.equal(p.state.options.value[0].label, "原精华 · 已停用");
  assert.equal(p.state.options.value[0].disabled, true);
  p.state.choose(undefined);
  assert.deepEqual(plain(p.events[0]), ["update:modelValue", 0]);
  p.stop();
});
test("多选商品最多十项，搜索后保留选择，超过上限不发送变更", async () => {
  const selected = Array.from({ length: 10 }, (_, i) => i + 1);
  const p = picker(
    { modelValue: selected, kind: "product", multiple: true },
    async () => page(selected.map((id) => row(id))),
  );
  await settle();
  p.state.rows.value = [row(11)];
  assert.equal(
    p.state.options.value.find((o) => o.value === 11).disabled,
    true,
  );
  p.state.choose([...selected, 11]);
  assert.equal(p.events.length, 0);
  p.state.choose([1, 2]);
  assert.deepEqual(plain(p.events[0]), ["update:modelValue", [1, 2]]);
  p.stop();
});
test("搜索失败保留已选项，重试可以恢复", async () => {
  let fail = true;
  const p = picker({ modelValue: 2 }, async (url) => {
    if (url.includes("ids=")) return page([row(2)]);
    if (fail) throw Error("网络异常");
    return page([]);
  });
  await settle();
  await p.state.load();
  assert.equal(p.state.error.value, "网络异常");
  assert.equal(p.state.options.value[0].value, 2);
  fail = false;
  await p.state.load();
  assert.equal(p.state.error.value, "");
  assert.equal(p.state.rows.value.length, 0);
  p.stop();
});
test("切换提货用户后清理缓存，重新按该用户查询并保留来源限制", async () => {
  const calls = [],
    props = vue.reactive({
      kind: "lot",
      userId: "7",
      modelValue: 5,
      max: 10,
      disabledIds: [],
    });
  const p = setup("AdminProductSelect", props, async (url) => {
    calls.push(url);
    return page(url.includes("userId=7") ? [row(5, "用户7寄存")] : []);
  });
  await settle();
  props.userId = "8";
  await settle();
  assert.ok(calls.some((url) => url.includes("userId=8")));
  assert.equal(p.state.cache.value[5], undefined);
  p.stop();
});
test("套餐不允许未选择商品时提交，已选商品按原 skuId 保存", async () => {
  const calls = [],
    p = setup(
      "CommerceConfiguration",
      { staff: { permissions: [] } },
      async (url, options) => {
        calls.push({ url, options });
        return {};
      },
    );
  p.state.loaded.value = true;
  p.state.offers.value = [
    {
      id: 1,
      name: "买赠",
      amountCents: 10000,
      active: true,
      lines: [{ skuId: 0, quantity: 1, unitCents: 10000, isGift: false }],
    },
  ];
  await p.state.save("offers");
  assert.equal(calls.length, 0);
  assert.match(p.state.error.value, /选择/);
  p.state.offers.value[0].lines[0].skuId = 88;
  p.state.reason.value = "调整商品";
  await p.state.save("offers");
  assert.equal(calls[0].options.body.offers[0].lines[0].skuId, 88);
  p.stop();
});
test("内容关联商品直接保存多选ID，取消编辑不修改原商品关联", async () => {
  const calls = [],
    p = setup("BrandContentOperations", {}, async (url, options) =>
      calls.push({ url, options }),
    );
  const original = {
    id: 3,
    type: "article",
    title: "护理",
    summary: "",
    body: "",
    cover: "",
    imageUrls: [],
    video_url: "",
    duration_seconds: 0,
    productIds: [1, 2],
  };
  p.state.edit(original);
  p.state.form.value.productIds.push(9);
  assert.deepEqual(original.productIds, [1, 2]);
  await p.state.save();
  assert.deepEqual(plain(calls[0].options.body.productIds), [1, 2, 9]);
  p.stop();
});
test("人工提货保存原寄存批次与数量，不接受未选商品", async () => {
  const calls = [],
    p = setup(
      "StorageOperations",
      { staff: { permissions: ["orders:manage"] } },
      async (url, options) => calls.push({ url, options }),
    );
  p.state.open("manual");
  p.state.userId.value = "7";
  p.state.addressId.value = "3";
  await p.state.submit();
  assert.equal(calls.length, 0);
  p.state.manualItems.value = [
    { lotId: 15, quantity: 2 },
    { lotId: 28, quantity: 1 },
  ];
  await p.state.submit();
  assert.deepEqual(plain(calls[0].options.body.storedItems), [
    { lotId: 15, quantity: 2 },
    { lotId: 28, quantity: 1 },
  ]);
  p.stop();
});

test("卸载组件后迟到请求不写入状态", async () => {
  let resolve;
  const p = picker(
    {},
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  const request = p.state.load();
  p.stop();
  resolve(page([row(4)]));
  await request;
  assert.equal(p.state.rows.value.length, 0);
});

test("订单配送入口识别有效提货占用，取消后新购仍可直接发货", async () => {
  const line = {
    id: 11,
    sku_id: 1,
    quantity: 3,
    shipped_qty: 0,
    refunded_unshipped: 0,
    held_unshipped: 0,
    storage_remaining: 2,
    pickup_held: 1,
  };
  const order = {
    id: 7,
    status: "paid",
    payment_state: "paid",
    lines: [line],
    snapshot: { lines: [] },
    pickups: [{ id: 9, number: "TH9", status: "approved" }],
  };
  const { state, stop } = setup(
    "PaymentOperations",
    { financeOnly: false, canOperate: true },
    async () => ({ order: structuredClone(order) }),
  );
  try {
    await state.select(7);
    assert.equal(state.canShip.value, false);
    assert.equal(state.activePickups.value[0].id, 9);
    line.pickup_held = 0;
    order.pickups[0].status = "cancelled";
    await state.select(7);
    assert.equal(state.canShip.value, true);
    assert.equal(state.activePickups.value.length, 0);
  } finally {
    stop();
  }
});

test("配送从订单内打开，写入后刷新原订单并保留搜索及状态筛选", async () => {
  let status = "approved";
  const order = () => ({
    id: 7,
    payment_state: "paid",
    status: "paid",
    lines: [],
    snapshot: { lines: [] },
    pickups: [{ id: 9, number: "TH9", status }],
  });
  let loads = 0;
  const p = setup(
    "PaymentOperations",
    { financeOnly: false, canOperate: true },
    async () => ({ order: order() }),
    async () => {
      loads++;
    },
  );
  await p.state.select(7);
  p.state.query.value = "原关键词";
  p.state.status.value = "paid";
  p.state.openPickup(9);
  assert.equal(p.state.pickupId.value, 9);
  assert.equal(p.events.length, 0);
  status = "shipped";
  await p.state.pickupChanged();
  assert.equal(p.state.selected.value.id, 7);
  assert.equal(p.state.selected.value.pickups[0].status, "shipped");
  assert.equal(p.state.pickupId.value, 9);
  assert.equal(p.state.query.value, "原关键词");
  assert.equal(p.state.status.value, "paid");
  assert.equal(loads, 1);
  p.stop();
});
test("配送成功但订单刷新失败时提示已成功，并停用旧履约操作", async () => {
  let fail = false;
  const order = {
    id: 7,
    payment_state: "paid",
    status: "paid",
    lines: [
      {
        id: 1,
        quantity: 2,
        shipped_qty: 0,
        refunded_unshipped: 0,
        held_unshipped: 0,
      },
    ],
    snapshot: { lines: [] },
  };
  const p = setup(
    "PaymentOperations",
    { financeOnly: false, canOperate: true },
    async () => {
      if (fail) throw Error("读取失败");
      return { order };
    },
  );
  await p.state.select(7);
  assert.equal(p.state.canShip.value, true);
  fail = true;
  await p.state.pickupChanged();
  assert.match(p.state.error.value, /配送办理已成功/);
  assert.equal(p.state.canShip.value, false);
  p.state.openPickup(9);
  assert.equal(p.state.pickupId.value, null);
  fail = false;
  await p.state.select(7);
  assert.equal(p.state.canShip.value, true);
  p.stop();
});
test("配送窗口按状态和权限办理，提交失败保留输入，成功使用返回单据且不会重复发货", async () => {
  let fail = true,
    posts = 0;
  const pickup = { id: 9, status: "approved", lines: [] };
  const p = setup(
    "AdminPickupDrawer",
    { id: 9, canOperate: true, canFinance: false },
    async (url, options) => {
      if (!options) return { ...pickup };
      posts++;
      assert.equal(url, "/admin/pickups/9/ship");
      if (fail) throw Error("发货失败");
      assert.deepEqual(plain(options.body), {
        reason: "实际出库",
        carrier: "顺丰",
        tracking: "SF123456",
      });
      return { ...pickup, status: "shipped" };
    },
  );
  await p.state.load();
  p.state.openAction("approve");
  assert.equal(p.state.action.value, "");
  p.state.openAction("ship");
  p.state.reason.value = "实际出库";
  p.state.carrier.value = "顺丰";
  p.state.tracking.value = "SF123456";
  await p.state.submit();
  assert.equal(p.state.action.value, "ship");
  assert.equal(p.state.tracking.value, "SF123456");
  assert.equal(p.events.length, 0);
  fail = false;
  await p.state.submit();
  assert.equal(p.state.selected.value.status, "shipped");
  assert.equal(p.state.action.value, "");
  assert.equal(p.events[0][0], "changed");
  p.state.openAction("ship");
  await p.state.submit();
  assert.equal(posts, 2);
  p.stop();
});
test("只读与财务权限不能审核发货，退款成功后不再显示重复退款入口", async () => {
  const p = setup(
    "AdminPickupDrawer",
    { id: 9, canOperate: false, canFinance: true },
    async () => ({
      id: 9,
      status: "shipped",
      fee_cents: 1000,
      fee_refunded: true,
    }),
  );
  p.state.selected.value = { id: 9, status: "review" };
  assert.equal(p.state.allowedActions.value.length, 0);
  p.state.openAction("approve");
  assert.equal(p.state.action.value, "");
  p.state.selected.value = {
    id: 9,
    status: "shipped",
    fee_cents: 1000,
    fee_refunded: false,
  };
  assert.deepEqual(plain(p.state.allowedActions.value), ["refund-fee"]);
  p.state.openAction("refund-fee");
  p.state.reason.value = "核实退回运费";
  await p.state.submit();
  assert.equal(p.state.allowedActions.value.length, 0);
  p.stop();
});
test("配送审核和取消沿用原接口，跨订单商品完整保留，不重复发送忙碌中的提交", async () => {
  let finish,
    posts = 0;
  const p = setup(
    "AdminPickupDrawer",
    { id: 9, canOperate: true },
    async (url, options) => {
      if (!options)
        return {
          id: 9,
          status: "review",
          lines: [
            { id: 1, order_id: 7, quantity: 2 },
            { id: 2, order_id: 8, quantity: 3 },
          ],
        };
      posts++;
      if (url.endsWith("/review")) {
        assert.equal(options.body.approve, true);
        return { ...p.state.selected.value, status: "approved" };
      }
      assert.equal(url, "/admin/pickups/9/cancel");
      return new Promise(
        (resolve) =>
          (finish = () =>
            resolve({
              ...p.state.selected.value,
              status: "cancelled",
              fee_refunded: true,
            })),
      );
    },
  );
  await p.state.load();
  p.state.openAction("approve");
  p.state.reason.value = "核实通过";
  await p.state.submit();
  assert.equal(p.state.selected.value.lines.length, 2);
  p.state.openAction("cancel");
  p.state.reason.value = "客户申请取消";
  const first = p.state.submit();
  await p.state.submit();
  assert.equal(posts, 2);
  finish();
  await first;
  assert.equal(p.state.allowedActions.value.length, 0);
  p.stop();
});
