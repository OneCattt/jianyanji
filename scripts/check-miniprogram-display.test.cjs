const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const vm = require("node:vm");
const { execFileSync } = require("node:child_process");
const test = require("node:test");

const root = path.resolve(__dirname, "../miniprogram");
const pages = [
  "home",
  "mall",
  "cart",
  "product",
  "seeds",
  "orders",
  "wallet",
  "rewards",
  "profile",
];
const directory = fs.mkdtempSync(path.join(os.tmpdir(), "jianyanji-display-"));
const context = { window: {}, console };
vm.createContext(context);
try {
  const compiler = path.join(
    process.env.WECHAT_COMPILER_DIR ||
      "/Applications/wechatwebdevtools.app/Contents/Resources/app.asar.unpacked/node_modules/wcc-exec",
    "wcc",
  );
  const output = path.join(directory, "display.js");
  execFileSync(
    compiler,
    [
      "-o",
      output,
      "templates/list-footer.wxml",
      ...pages.map((page) => `pages/${page}/index.wxml`),
    ],
    { cwd: root },
  );
  vm.runInContext(fs.readFileSync(output, "utf8"), context);
} finally {
  fs.rmSync(directory, { recursive: true, force: true });
}

function render(page, data) {
  const nodes = [];
  function visit(node) {
    if (!node || typeof node !== "object") return;
    nodes.push(node);
    for (const child of node.children || []) visit(child);
  }
  visit(context.$gwx(`pages/${page}/index.wxml`)(data));
  return {
    nodes,
    hasClass: (value) =>
      nodes.some((node) => node.attr?.class?.split(" ").includes(value)),
    text: nodes
      .flatMap((node) => node.children || [])
      .filter((node) => typeof node === "string")
      .join(""),
  };
}

test("购物袋角标隐藏零值、显示数量及99+，读屏保留真实件数", () => {
  for (const count of [null, 0, 1, 99, 100]) {
    const view = render("mall", {
      cartCount: count,
      products: [],
      categories: [],
    });
    const badges = view.nodes.filter((node) =>
      node.attr?.class?.includes("qx-cart-badge"),
    );
    assert.equal(badges.length, count > 0 ? 1 : 0);
    if (count > 0)
      assert.equal(
        badges[0].children.join(""),
        count > 99 ? "99+" : String(count),
      );
    assert.ok(
      view.nodes.some(
        (node) =>
          node.attr?.ariaLabel ===
          (count === null ? "打开购物袋" : `购物袋，共${count}件商品`),
      ),
    );
  }
});

test("四个订单入口按各自状态显示笔数，零值隐藏、大数显示99+", () => {
  const data = {
    orderShortcuts: [
      { tab: 1, name: "待核款", icon: "pending" },
      { tab: 2, name: "待发货", icon: "paid" },
      { tab: 3, name: "运输中", icon: "shipped" },
      { tab: 4, name: "已完成", icon: "completed" },
    ],
    orderCounts: { pending: 1, paid: 2, shipped: 0, completed: 100 },
  };
  const view = render("profile", data);
  const badges = view.nodes.filter((node) =>
    node.attr?.class?.includes("mine-order-badge"),
  );
  assert.deepEqual(
    badges.map((node) => node.children.join("")),
    ["1", "2", "99+"],
  );
  assert.ok(
    view.nodes.some((node) => node.attr?.ariaLabel === "已完成，100笔订单"),
  );
  assert.ok(
    !render("profile", { ...data, orderCounts: null }).hasClass(
      "mine-order-badge",
    ),
  );
});

test("商城有可售商品时不显示未上架或不可购买提示，也不留空错误条", () => {
  const view = render("mall", {
    categories: [],
    categoryId: 0,
    identityName: "游客",
    error: "",
    products: [
      { id: 1, available: true, name: "测试商品", priceText: "¥100.00" },
    ],
  });
  assert.ok(!view.hasClass("qx-empty"));
  assert.ok(!view.hasClass("error"));
  assert.ok(!view.text.includes("暂不可购买"));
  const unavailable = render("mall", {
    categories: [],
    products: [{ id: 1, available: false }],
    error: "",
  });
  assert.ok(unavailable.text.includes("暂不可购买"));
});

test("商城空列表与接口失败分别展示对应状态", () => {
  const empty = render("mall", { products: [], categories: [], error: "" });
  assert.ok(empty.text.includes("商品尚未上架"));
  assert.ok(!empty.hasClass("error"));
  const failed = render("mall", {
    products: [],
    categories: [],
    error: "连接失败",
  });
  assert.ok(failed.hasClass("error"));
  assert.ok(!failed.hasClass("qx-empty"));
});

test("购物袋按真实商品状态显示空列表和不可购买提示", () => {
  const item = {
    skuId: 1,
    available: true,
    name: "测试商品",
    quantity: 1,
    stock: 5,
  };
  const filled = render("cart", { items: [item], error: "" });
  assert.ok(!filled.hasClass("qx-empty"));
  assert.ok(!filled.hasClass("error"));
  assert.ok(!filled.text.includes("商品暂不可购买"));
  assert.ok(render("cart", { items: [], error: "" }).hasClass("qx-empty"));
  assert.ok(
    render("cart", {
      items: [{ ...item, available: false }],
      error: "",
    }).text.includes("商品暂不可购买"),
  );
});

test("首页无精选商品时不留空商品卡，无错误时不留错误条", () => {
  const empty = render("home", { featured: null, content: null, error: "" });
  assert.ok(!empty.hasClass("qx-feature"));
  assert.ok(!empty.hasClass("error"));
  assert.ok(
    render("home", { featured: { id: 1 }, error: "" }).hasClass("qx-feature"),
  );
});

test("商品详情未读取成功时不显示空详情与购买栏", () => {
  const failed = render("product", {
    product: null,
    sku: null,
    error: "商品读取失败",
  });
  assert.ok(!failed.hasClass("qx-fixed"));
  assert.ok(!failed.hasClass("qx-detail-section"));
  assert.ok(failed.text.includes("商品读取失败"));
  const loaded = render("product", {
    product: { skus: [] },
    sku: {},
    error: "",
  });
  assert.ok(loaded.hasClass("qx-fixed"));
  assert.ok(!loaded.hasClass("error"));
});

test("列表请求期间不把加载中误显示成空数据，种草分页状态只渲染一次", () => {
  assert.ok(
    !render("mall", {
      products: [],
      productsLoading: true,
      error: "",
    }).text.includes("商品尚未上架"),
  );
  assert.ok(
    !render("cart", { items: [], itemsLoading: true, error: "" }).text.includes(
      "购物袋暂无商品",
    ),
  );
  assert.ok(
    !render("orders", {
      orders: [],
      ordersLoading: true,
      error: "",
      tabs: [],
      tab: 0,
    }).text.includes("暂无"),
  );
  assert.ok(
    !render("wallet", {
      ledger: [],
      ledgerLoading: true,
      error: "",
      wallet: {},
    }).text.includes("暂无该类型"),
  );
  assert.ok(
    !render("rewards", {
      items: [],
      itemsLoading: true,
      error: "",
      tabs: [],
      tab: 0,
    }).text.includes("暂无"),
  );
  const text = render("seeds", {
    items: Array.from({ length: 20 }, (_, id) => ({
      id,
      title: "种草",
      summary: "摘要",
    })),
    loaded: true,
    loading: false,
    nextBefore: null,
    error: "",
  }).text;
  assert.equal((text.match(/已全部加载/g) || []).length, 1);
});
