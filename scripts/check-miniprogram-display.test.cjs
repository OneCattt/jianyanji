const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const vm = require("node:vm");
const { execFileSync } = require("node:child_process");
const test = require("node:test");

const root = path.resolve(__dirname, "../miniprogram");
const pages = ["home", "mall", "cart", "product"];
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
    ["-o", output, ...pages.map((page) => `pages/${page}/index.wxml`)],
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
    hasClass: (value) =>
      nodes.some((node) => node.attr?.class?.split(" ").includes(value)),
    text: nodes
      .flatMap((node) => node.children || [])
      .filter((node) => typeof node === "string")
      .join(""),
  };
}

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
