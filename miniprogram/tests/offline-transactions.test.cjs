const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.resolve(__dirname, "..");
const pendingOrder = () => ({
  id: 7,
  number: "O7",
  kind: "goods",
  status: "pending",
  payment_state: "unpaid",
  payment_method: null,
  total_cents: 12000,
  goods_cents: 12000,
  shipping_cents: 0,
  snapshot: { lines: [] },
  shipments: [],
});

// 执行真实页面方法，仅替换微信宿主和网络，验证用户操作实际发出的请求。
function pageHarness(page, options = {}) {
  let order = { ...pendingOrder(), ...options.order };
  let definition;
  let componentDefinition;
  const calls = [],
    toasts = [],
    modals = [],
    navigation = [],
    clipboard = [];
  const content = {
    companyName: "品牌公司",
    bankName: "收款银行",
    bankAccount: "123456",
    transferNotes: "请注明订单号和用途",
    customerService: "品牌客服",
    ...options.content,
  };
  const request = async (url, method = "GET") => {
    calls.push({ url, method });
    if (url === "/wallet" || url === "/mini/wallet")
      return { availableCents: options.available ?? 20000, ledger: [] };
    if (url.startsWith("/mini/wallet/ledger?"))
      return {
        items: (options.ledger || []).filter(
          (i) => url.includes("kind=all") || url.includes("kind=" + i.kind),
        ),
        hasMore: false,
        nextPage: null,
      };
    if (url === "/me") return { identity: options.identity || "C" };
    if (url === "/public/content") return { content };
    if (url === "/orders/7") return order;
    if (url === "/orders/7/payment") return { order, receipt: null };
    if (url === "/orders/7/cancel") {
      order = { ...order, status: "closed" };
      return order;
    }
    if (url === "/orders/7/shipments/4/receive") return {};
    if (url === "/orders/7/pay/points") {
      if (options.failure) throw new Error(options.failure);
      order = options.unconfirmed
        ? order
        : {
            ...order,
            status: "paid",
            payment_state: "paid",
            payment_method: "points",
          };
      return { order };
    }
    throw new Error("出现未经约定的接口调用：" + method + " " + url);
  };
  const wx = {
    getStorageSync: () => "token",
    setStorageSync() {},
    setClipboardData: (value) => clipboard.push(value.data),
    showToast: (toast) => toasts.push(toast),
    navigateTo: (route) => navigation.push(route),
  };
  const modules = new Map();
  function load(filename) {
    if (modules.has(filename)) return modules.get(filename).exports;
    const module = { exports: {} };
    modules.set(filename, module);
    vm.runInNewContext(
      fs.readFileSync(filename, "utf8"),
      {
        module,
        exports: module.exports,
        wx,
        Page: (value) => {
          definition = value;
        },
        Component: (value) => {
          componentDefinition = value;
        },
        require: (name) => {
          const target = path.resolve(path.dirname(filename), name + ".js");
          return target === path.join(root, "utils/api.js")
            ? { request }
            : load(target);
        },
      },
      { filename },
    );
    return module.exports;
  }
  const view = load(path.join(root, "pages/order/view.js"));
  load(path.join(root, "components/brand-dialog/index.js"));
  const dialog = {
    ...componentDefinition.methods,
    data: structuredClone(componentDefinition.data),
    setData(value) {
      Object.assign(this.data, value);
    },
  };
  const open = dialog.open;
  dialog.open = function (config) {
    const result = open.call(this, config);
    modals.push({ ...config, success: (value) => this.finish(value) });
    if (!options.deferModal) this.confirm();
    return result;
  };
  load(
    path.join(root, "pages", page.includes("/") ? page : page + "/index") +
      ".js",
  );
  const instance = {
    ...definition,
    data: JSON.parse(JSON.stringify(definition.data)),
  };
  instance.setData = (value) => Object.assign(instance.data, value);
  instance.selectComponent = (selector) => {
    assert.equal(selector, "#brand-dialog");
    return dialog;
  };
  instance.onLoad({ id: 7, orderId: 7 });
  return {
    page: instance,
    calls,
    toasts,
    modals,
    navigation,
    clipboard,
    view,
    dialog,
    componentDefinition,
  };
}

test("线下订单显示真实核款状态；只有服务端已确认到账才显示已核款", async () => {
  const { page, calls, view } = pageHarness("order", { identity: "D" });
  await page.load();
  assert.equal(page.data.order.receiptText, "待核款");
  assert.equal(page.data.order.methodText, "线下付款");
  assert.equal(page.data.canUsePoints, false);
  assert.equal(page.data.transfer.bankAccount, "123456");
  assert.equal(
    calls.every((call) => call.method === "GET"),
    true,
  );
  assert.equal(calls.length, 3);
  const paid = view.decorate({
    ...pendingOrder(),
    status: "paid",
    payment_state: "paid",
    payment_method: "offline",
  });
  assert.equal(paid.receiptText, "已核款");
  assert.equal(paid.canPay, false);
});

test("收款资料不完整时不展示为已配置，刷新仅读取现有接口", async () => {
  const { page, calls } = pageHarness("order", {
    content: { bankAccount: "" },
  });
  await page.load();
  assert.equal(page.data.transferConfigured, false);
  assert.equal(
    calls.some((call) => /capabilities|query|close/.test(call.url)),
    false,
  );
});

test("取消订单只调用订单取消接口，连续点击仅提交一次", async () => {
  const { page, calls, modals } = pageHarness("order", { deferModal: true });
  await page.load();
  const first = page.cancel();
  await page.cancel();
  assert.equal(modals.length, 1);
  modals[0].success({ confirm: true });
  await first;
  assert.equal(calls.filter((call) => call.method === "POST").length, 1);
  assert.equal(
    calls.find((call) => call.method === "POST").url,
    "/orders/7/cancel",
  );
  assert.equal(page.data.order.receiptText, "已关闭");
});

test("历史未决交易不显示已核款、不允许改积分或从页面取消", async () => {
  for (const state of ["paying", "unknown", "review"]) {
    const { page, calls, navigation } = pageHarness("order", {
      order: { payment_state: state, payment_method: "wechat" },
    });
    await page.load();
    await page.cancel();
    page.points();
    assert.equal(page.data.order.receiptText, "待核查");
    assert.equal(page.data.canUsePoints, false);
    assert.equal(navigation.length, 0);
    assert.equal(
      calls.every((call) => call.method === "GET"),
      true,
    );
  }
});

test("积分不足不扣款，保留返回原订单整笔线下付款入口", async () => {
  const { page, calls, navigation, toasts } = pageHarness("wallet", {
    available: 11999,
  });
  await page.load();
  await page.pay();
  page.order();
  assert.equal(page.data.insufficient, true);
  assert.match(page.data.error, /整笔线下付款/);
  assert.equal(
    calls.some((call) => call.method === "POST"),
    false,
  );
  assert.equal(toasts.length, 0);
  assert.equal(navigation[0].url, "/pages/order/index?id=7");
});

test("直接进入钱包也不能用积分购买产品包、处理未决交易或使用游客身份付款", async () => {
  for (const options of [
    { order: { kind: "package" } },
    { order: { payment_state: "unknown" } },
    { identity: "D" },
    { order: { status: "closed" } },
  ]) {
    const { page, calls } = pageHarness("wallet", options);
    await page.load();
    await page.pay();
    assert.equal(page.data.canPay, false);
    assert.equal(
      calls.some((call) => call.method === "POST"),
      false,
    );
  }
});

test("整单积分付款连续点击仅扣减一次，确认成功后才能显示成功", async () => {
  const { page, calls, modals, toasts } = pageHarness("wallet", {
    deferModal: true,
  });
  await page.load();
  const first = page.pay();
  await page.pay();
  assert.equal(modals.length, 1);
  assert.equal(
    calls.some((call) => call.method === "POST"),
    false,
  );
  modals[0].success({ confirm: true });
  await first;
  assert.equal(calls.filter((call) => call.method === "POST").length, 1);
  assert.equal(
    calls.find((call) => call.method === "POST").url,
    "/orders/7/pay/points",
  );
  assert.equal(page.data.paid, true);
  assert.equal(page.data.canPay, false);
  assert.equal(toasts[0].title, "积分付款成功");
});

test("取消积分确认弹窗不会扣款且恢复可操作状态", async () => {
  const { page, calls, modals } = pageHarness("wallet", { deferModal: true });
  await page.load();
  const payment = page.pay();
  modals[0].success({ confirm: false });
  await payment;
  assert.equal(
    calls.some((call) => call.method === "POST"),
    false,
  );
  assert.equal(page.data.busy, false);
});

test("页面离开会关闭确认框，积分付款不会悬挂或在返回后自动执行", async () => {
  const { page, calls, dialog, componentDefinition } = pageHarness("wallet", {
    deferModal: true,
  });
  await page.load();
  const payment = page.pay();
  assert.equal(dialog.data.visible, true);
  componentDefinition.pageLifetimes.hide.call(dialog);
  await payment;
  assert.equal(dialog.data.visible, false);
  assert.equal(page.data.busy, false);
  dialog.confirm();
  assert.equal(
    calls.some((call) => call.method === "POST"),
    false,
  );
});

test("积分筛选显示当前选项，取消保留原筛选，选择后更新明细", async () => {
  const { page, dialog } = pageHarness("wallet", {
    deferModal: true,
    ledger: [
      {
        id: 1,
        kind: "credit",
        delta_cents: 100,
        create_time: "2026-10-20T00:00:00Z",
      },
      {
        id: 2,
        kind: "spend",
        delta_cents: -100,
        create_time: "2026-10-20T00:00:00Z",
      },
    ],
  });
  page.setData({
    filter: "credit",
    filterName: "品牌发放",
    ledger: [{ kind: "credit" }, { kind: "spend" }],
  });
  const cancelled = page.filter();
  assert.equal(dialog.data.mode, "select");
  assert.equal(dialog.data.selectedIndex, 1);
  dialog.cancel();
  await cancelled;
  assert.equal(page.data.filter, "credit");
  const selected = page.filter();
  dialog.select({ currentTarget: { dataset: { index: 2 } } });
  await selected;
  assert.equal(page.data.filter, "spend");
  assert.equal(page.data.ledger.length, 1);
  assert.equal(page.data.ledger[0].kind, "spend");
});

test("收货确认连续点击只开一个弹层，取消或离开页面不会确认收货", async () => {
  const { page, calls, dialog, modals } = pageHarness("order/logistics", {
    deferModal: true,
  });
  const event = { currentTarget: { dataset: { id: 4 } } };
  const receiving = page.receive(event);
  await page.receive(event);
  assert.equal(modals.length, 1);
  assert.equal(dialog.data.confirmText, "确认收货");
  dialog.cancel();
  await receiving;
  assert.equal(page.data.busy, false);
  assert.equal(
    calls.some((call) => call.method === "POST"),
    false,
  );
});

test("确认收货只提交当前包裹一次，关闭弹层后重复点击确认不再提交", async () => {
  const { page, calls, dialog } = pageHarness("order/logistics", {
    deferModal: true,
    order: { status: "shipped", payment_state: "paid" },
  });
  const receiving = page.receive({ currentTarget: { dataset: { id: 4 } } });
  dialog.confirm();
  dialog.confirm();
  await receiving;
  const writes = calls.filter((call) => call.method === "POST");
  assert.equal(writes.length, 1);
  assert.equal(writes[0].url, "/orders/7/shipments/4/receive");
  assert.equal(page.data.busy, false);
  assert.equal(page.data.error, "");
});

test("订单详情的复制、物流、售后和顾问入口调用对应操作", async () => {
  const { page, clipboard, navigation, dialog } = pageHarness("order", {
    deferModal: true,
  });
  await page.load();
  page.copy();
  page.logistics();
  page.aftersales();
  assert.deepEqual(clipboard, ["O7"]);
  assert.deepEqual(
    navigation.map((item) => item.url),
    ["/pages/order/logistics?id=7", "/pages/refunds/index?orderId=7"],
  );
  const contact = page.contact();
  assert.equal(dialog.data.mode, "contact");
  assert.equal(dialog.data.content, "品牌客服");
  assert.equal(clipboard.length, 1);
  dialog.confirm();
  await contact;
  assert.deepEqual(clipboard, ["O7", "品牌客服"]);
});

test("入驻确认取消时不提交申请或重新确认邀请关系", async () => {
  for (const action of ["submit", "reconfirm"]) {
    const { page, dialog, calls } = pageHarness("onboarding", {
      deferModal: true,
    });
    page.setData({
      user: {},
      selected: {},
      agreed: true,
      address: { id: 1 },
      inviterId: "2",
      current: { id: 1, target_identity: "C" },
    });
    page.resolveInvitation = async () => ({
      inviterName: "邀请人",
      principalName: "归属主理人",
      inviterId: 2,
      principalId: 3,
    });
    const applying = page[action]();
    // 邀请查询的 Promise 来自页面沙箱，等待这一轮微任务全部完成。
    await new Promise(setImmediate);
    assert.equal(dialog.data.visible, true, page.data.error);
    dialog.cancel();
    await applying;
    assert.equal(page.data.busy, false);
    assert.equal(
      calls.some((call) => call.method === "POST"),
      false,
    );
  }
});

test("使用品牌弹层的页面均注册并挂载组件", () => {
  for (const route of [
    "order/index",
    "order/logistics",
    "wallet/index",
    "onboarding/index",
  ]) {
    const file = path.join(root, "pages", route);
    const config = JSON.parse(fs.readFileSync(file + ".json", "utf8"));
    assert.equal(
      config.usingComponents["brand-dialog"],
      "/components/brand-dialog/index",
    );
    assert.match(
      fs.readFileSync(file + ".wxml", "utf8"),
      /<brand-dialog id="brand-dialog"\s*\/>/,
    );
    assert.doesNotMatch(
      fs.readFileSync(file + ".js", "utf8"),
      /wx\.show(?:Modal|ActionSheet)/,
    );
  }
});

test("积分扣减失败或服务端未确认时均不显示付款成功", async () => {
  for (const options of [
    { failure: "财务记录待核实" },
    { unconfirmed: true },
  ]) {
    const { page, toasts } = pageHarness("wallet", options);
    await page.load();
    await page.pay();
    assert.equal(page.data.paid, false);
    assert.equal(toasts.length, 0);
    assert.match(page.data.error, /核实/);
    assert.equal(page.data.busy, false);
  }
});

test("退款按原订单类型展示，积分退回原批次，现金按财务线下登记", () => {
  const { view } = pageHarness("order");
  for (const method of ["points", "offline"]) {
    const order = view.decorate({
      ...pendingOrder(),
      status: "refunded",
      payment_state: "refunded",
      payment_method: method,
    });
    assert.equal(order.canPay, false);
    assert.equal(order.receiptText, "已退款");
    assert.match(order.hint, method === "points" ? /原批次/ : /财务线下/);
    assert.equal(
      order.amountText,
      method === "points" ? "120.00 积分" : "¥120.00",
    );
  }
});

test("所有小程序页面均不包含线上付款、查单或关单调用", () => {
  const scan = (directory) => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const filename = path.join(directory, entry.name);
      if (entry.isDirectory()) scan(filename);
      else if (/\.(js|wxml)$/.test(entry.name))
        assert.doesNotMatch(
          fs.readFileSync(filename, "utf8"),
          /requestPayment|pay\/wechat|payment\/(query|close)|payments\/capabilities|payPackage/,
        );
    }
  };
  scan(path.join(root, "pages"));
});
