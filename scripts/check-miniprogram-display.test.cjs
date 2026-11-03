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
  "checkout",
  "seeds",
  "orders",
  "wallet",
  "rewards",
  "profile",
  "storage",
  "offers",
  "pickup",
  "onboarding",
  "order",
  "courses",
  "refunds",
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

test("订单付款独立展示本单金额与余额，单一主操作不混入历史流水", () => {
  const data = {
    paymentMode: true,
    order: {
      number: "JY20261028",
      totalText: "888.00",
      goodsPointsText: "888.00",
      shippingPointsText: "0.00",
      kind: "goods",
    },
    wallet: { availableText: "2,547.85" },
    canPay: true,
    afterPayText: "1,659.85",
    ledger: [{ id: 1, title: "历史扣减" }],
  };
  const pending = render("wallet", data);
  assert.ok(pending.text.includes("确认支付 888.00 积分"));
  assert.ok(pending.text.includes("支付后剩余 1,659.85 积分"));
  assert.ok(!pending.hasClass("wallet-card"));
  assert.ok(!pending.hasClass("wallet-ledger"));
  assert.equal(
    pending.nodes.filter((node) => node.attr?.class === "wallet-primary")
      .length,
    1,
  );
  const shortfall = render("wallet", {
    ...data,
    insufficient: true,
    shortfallText: "20.00",
  });
  assert.ok(shortfall.text.includes("还差 20.00 积分"));
  assert.ok(shortfall.text.includes("联系总部充值"));
  assert.ok(!shortfall.text.includes("确认支付"));
  const paid = render("wallet", { ...data, paid: true, canPay: false });
  assert.ok(paid.text.includes("支付成功"));
  assert.ok(paid.text.includes("查看订单与配送"));
  assert.ok(!paid.text.includes("支付后剩余"));
  assert.ok(!paid.text.includes("确认支付"));
  const wallet = render("wallet", { ...data, paymentMode: false });
  assert.ok(wallet.hasClass("wallet-card"));
  assert.ok(wallet.hasClass("wallet-ledger"));
  assert.ok(!wallet.hasClass("payment-dock"));
});

test("个人中心新增菜单引用实际存在的图标，避免空白图标底座", () => {
  let definition;
  vm.runInNewContext(
    fs.readFileSync(path.join(root, "pages/profile/index.js"), "utf8"),
    { Page: (value) => (definition = value), require: () => ({}) },
  );
  const view = render("profile", definition.data);
  for (const node of view.nodes.filter((item) => item.tag === "wx-image")) {
    const src = node.attr?.src;
    if (src?.startsWith("/assets/"))
      assert.ok(fs.existsSync(path.join(root, src)), `图标缺失：${src}`);
  }
});

test("寄存空状态提供商城引导，隐藏列表结束提示、提货栏和默认展开的长规则", () => {
  const view = render("storage", {
    tab: "lots",
    lots: [],
    progress: [],
    lotsLoaded: true,
    lotsLoading: false,
    lotsHasMore: false,
    selectedCount: 0,
    error: "",
    showFreight: false,
  });
  assert.ok(view.hasClass("storage-empty"));
  assert.ok(view.text.includes("去商城逛逛"));
  for (const name of ["list-footer", "storage-dock", "storage-freight"])
    assert.ok(!view.hasClass(name), name);
});

test("寄存有货时保留提货操作，选择数量控制按钮禁用，读取失败不冒充空列表", () => {
  const data = {
    tab: "lots",
    lots: [{ id: 1, name: "精华", available_qty: 2 }],
    progress: [],
    lotsLoaded: true,
    lotsHasMore: false,
    selectedCount: 0,
    error: "",
  };
  for (const count of [0, 2]) {
    const view = render("storage", { ...data, selectedCount: count });
    assert.ok(view.hasClass("storage-dock"));
    const submit = view.nodes.find((node) =>
      node.attr?.class?.includes("storage-submit"),
    );
    assert.equal(Boolean(submit.attr.disabled), count === 0);
  }
  const failed = render("storage", {
    ...data,
    lots: [],
    error: "网络连接失败",
  });
  assert.ok(failed.hasClass("storage-error"));
  assert.ok(!failed.hasClass("storage-empty"));
});

test("寄存货品与提货记录以真实商品图展示，记录合计覆盖预览外商品", () => {
  const image = "/assets/figma/productSerum.png";
  const lots = render("storage", {
    tab: "lots",
    lots: [
      {
        id: 7,
        order_id: 80,
        name: "净肤嫩颜精华液",
        imageThumb: image,
        sku_name: "标准装",
        total_qty: 3,
        shipped_qty: 1,
        remaining_qty: 2,
        available_qty: 0,
        held_qty: 2,
        refund_held_qty: 0,
        selectedQuantity: 0,
        expires_on: "2027-10-28",
      },
    ],
  });
  assert.ok(
    lots.nodes.some(
      (node) => node.tag === "wx-image" && node.attr.src === image,
    ),
  );
  assert.match(lots.text, /当前可提/);
  const available = lots.nodes.find((node) =>
    node.attr?.class?.includes("storage-available"),
  );
  assert.ok(
    subtree(available).some(
      (node) => node.tag === "wx-text" && node.children.includes("0"),
    ),
  );
  assert.match(lots.text, /提货占用 2 件/);
  const plus = lots.nodes.find((node) => node.attr?.["data-step"] === "1");
  assert.ok(plus.attr.disabled);
  assert.ok(
    lots.nodes.some(
      (node) =>
        node.attr?.bindtap === "sourceOrder" && node.attr["data-id"] === 80,
    ),
  );
  const records = render("storage", {
    tab: "pickups",
    pickups: [
      {
        id: 9,
        number: "TH009",
        statusText: "待审核",
        quantity: 7,
        feeText: "10.00",
        moreKinds: 1,
        previewLines: [
          { id: 1, name: "精华液", imageThumb: image, quantity: 1 },
          { id: 2, name: "眼油", quantity: 2, is_gift: 1 },
        ],
      },
    ],
  });
  assert.match(records.text, /精华液/);
  assert.match(records.text, /眼油赠品/);
  assert.match(records.text, /另有 1 款商品/);
  assert.match(records.text, /共 7 件/);
  assert.match(records.text, /运费 10.00 积分/);
  assert.equal(
    records.nodes.filter((node) => node.attr?.class === "storage-product-cover")
      .length,
    2,
  );
  const detail = render("pickup", {
    pickup: {
      quantity: 1,
      address: {},
      lines: [
        { id: 1, name: "精华液", imageThumb: image, quantity: 1, lot_id: 7 },
      ],
    },
  });
  assert.ok(
    detail.nodes.some(
      (node) => node.tag === "wx-image" && node.attr.src === image,
    ),
  );
  assert.match(detail.text, /寄存商品 · 不重复收费/);
});

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

test("未完成订单显示笔数，零值隐藏、大数显示99+，已完成保留入口但不显示笔数", () => {
  const data = {
    orderShortcuts: [
      { tab: 1, name: "待核款", icon: "pending" },
      { tab: 2, name: "待发货", icon: "paid" },
      { tab: 3, name: "运输中", icon: "shipped" },
      { tab: 4, name: "已完成", icon: "completed" },
    ],
    orderCounts: { pending: 100, paid: 2, shipped: 0, completed: 100 },
  };
  const view = render("profile", data);
  const badges = view.nodes.filter((node) =>
    node.attr?.class?.includes("mine-order-badge"),
  );
  assert.deepEqual(
    badges.map((node) => node.children.join("")),
    ["99+", "2"],
  );
  assert.ok(view.nodes.some((node) => node.attr?.ariaLabel === "已完成"));
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
  const filled = render("cart", { loggedIn: true, items: [item], error: "" });
  assert.ok(!filled.hasClass("qx-empty"));
  assert.ok(!filled.hasClass("error"));
  assert.ok(!filled.text.includes("商品暂不可购买"));
  assert.ok(
    render("cart", { loggedIn: true, items: [], error: "" }).hasClass(
      "qx-empty",
    ),
  );
  assert.ok(
    render("cart", {
      loggedIn: true,
      items: [{ ...item, available: false }],
      error: "",
    }).text.includes("商品暂不可购买"),
  );
});

test("购物袋未登录显示登录引导，隐藏重试、空列表及结算栏，移除配送文案", () => {
  const guest = render("cart", { loggedIn: false, items: [], error: "" });
  assert.ok(guest.hasClass("cart-login"));
  assert.ok(guest.text.includes("登录后查看购物袋"));
  for (const name of ["cart-dock", "cart-retry", "qx-empty", "error"])
    assert.ok(!guest.hasClass(name), name);
  assert.ok(!guest.text.includes("统一顺丰/专配发货"));
  const member = render("cart", { loggedIn: true, items: [], error: "" });
  assert.ok(!member.hasClass("cart-login"));
  assert.ok(member.hasClass("cart-dock"));
  assert.ok(!member.text.includes("统一顺丰/专配发货"));
});

test("购物袋管理入口切换移除按钮，结算件数来自完整购物袋汇总", () => {
  const data = {
    loggedIn: true,
    items: [
      { skuId: 1, name: "测试商品", available: true, quantity: 1, stock: 10 },
    ],
    pieces: 41,
    kinds: 41,
    error: "",
  };
  const normal = render("cart", { ...data, managing: false });
  assert.ok(normal.text.includes("管理"));
  assert.ok(!normal.hasClass("cart-remove"));
  assert.ok(normal.text.includes("结算（41）"));
  const managing = render("cart", { ...data, managing: true });
  assert.ok(managing.text.includes("完成"));
  assert.ok(managing.hasClass("cart-remove"));
});

test("提交订单展示地址、商品小计及运费，缺少报价时不显示提交栏", () => {
  const empty = render("checkout", { address: null, quote: null, error: "" });
  assert.ok(empty.text.includes("请选择收货地址"));
  assert.ok(!empty.hasClass("checkout-dock"));
  const readyData = {
    address: {
      province: "广东省",
      city: "广州市",
      district: "荔湾区",
      detail: "测试地址",
      recipient: "测试收件人",
      phone: "13800000000",
    },
    quote: {
      pieces: 2,
      goodsText: "¥676.00",
      shippingText: "¥0.00",
      totalText: "¥676.00",
      shippingInstructions: "快递配送",
      lines: [
        {
          skuId: 1,
          name: "测试商品",
          quantity: 2,
          skuName: "标准装",
          priceText: "¥338.00",
        },
      ],
    },
    busy: true,
    error: "",
  };
  readyData.displayQuote = readyData.quote;
  const ready = render("checkout", readyData);
  assert.ok(ready.hasClass("checkout-address-detail"));
  assert.ok(ready.hasClass("checkout-dock"));
  assert.ok(ready.text.includes("共2件"));
  assert.ok(ready.text.includes("¥0.00"));
  assert.ok(ready.text.includes("不会立即扣款或扣积分"));
  assert.ok(
    ready.nodes.find((node) => node.attr?.class === "checkout-submit").attr
      .disabled,
  );
});

test("首页仅渲染后台配置的主图，图片加载成功后显示", () => {
  const waiting = render("home", { heroImage: "", heroImageReady: false });
  assert.ok(!waiting.hasClass("qx-hero-image"));
  const data = {
    heroImage: "https://example.com/brand.jpg",
    heroImageReady: false,
  };
  const loading = render("home", data);
  assert.ok(loading.hasClass("qx-hero-image"));
  assert.ok(!loading.hasClass("home-hero-image-ready"));
  const image = loading.nodes.find((node) =>
    node.attr?.class?.split(" ").includes("qx-hero-image"),
  );
  assert.equal(image.attr.src, data.heroImage);
  assert.equal(image.attr.bindload, "onHeroImageLoad");
  assert.equal(image.attr.binderror, "onHeroImageError");
  assert.ok(
    render("home", { ...data, heroImageReady: true }).hasClass(
      "home-hero-image-ready",
    ),
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

test("商品详情顶部使用列表图片，下方详情图片按原比例完整展示", () => {
  const view = render("product", {
    product: {
      name: "测试商品",
      imageHero: "left-image.jpg",
      detailImage: "right-original.jpg",
      detailImageDisplay: "right-detail.jpg",
      skus: [],
    },
    sku: {},
    quantity: 1,
    cartCount: null,
    error: "",
  });
  const hero = view.nodes.find((node) => node.attr?.class === "product-hero");
  assert.equal(
    hero.children.find((node) => node.tag === "wx-image").attr.src,
    "left-image.jpg",
  );
  const detail = view.nodes.find(
    (node) => node.attr?.class === "product-detail-image",
  );
  assert.equal(detail.attr.src, "right-detail.jpg");
  assert.equal(detail.attr.mode, "widthFix");
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

test("商品详情展示真实库存与购物袋角标，福利品隐藏零售价，不可售时禁用购买", () => {
  const sku = {
    id: 1,
    name: "标准装",
    unit: "件",
    stock: 10,
    available: true,
    priceCents: 33800,
    priceText: "¥338.00",
    retailCents: 33800,
    retailText: "¥338.00",
    productType: "regular",
  };
  const data = {
    product: { name: "测试商品", skus: [sku] },
    sku,
    quantity: 1,
    cartCount: 100,
    pendingAction: "",
    error: "",
  };
  const loaded = render("product", data);
  assert.ok(loaded.hasClass("product-overview"));
  assert.ok(loaded.text.includes("可用库存 10件"));
  assert.ok(
    loaded.nodes.some(
      (node) =>
        node.attr?.class === "product-cart-badge" &&
        node.children.join("") === "99+",
    ),
  );
  assert.ok(loaded.text.includes("零售价"));
  const welfare = render("product", {
    ...data,
    cartCount: null,
    sku: { ...sku, productType: "welfare", available: false },
  });
  assert.ok(!welfare.text.includes("零售价"));
  assert.ok(!welfare.hasClass("product-cart-badge"));
  for (const name of ["product-action-add", "product-action-buy"])
    assert.ok(
      welfare.nodes.find((node) => node.attr?.class === name).attr.disabled,
    );
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

function subtree(node) {
  if (!node || typeof node !== "object") return [];
  return [node, ...(node.children || []).flatMap(subtree)];
}

test("入驻配送方式和每项发货数量都在可滚动内容内，未配置产品包时不显示配送控件", () => {
  const view = render("onboarding", {
    selected: {
      lines: [
        { lineKey: "main", name: "精华", quantity: 3, shipQuantity: 1 },
        {
          lineKey: "gift",
          name: "洁面乳",
          quantity: 1,
          shipQuantity: 0,
          isGift: true,
        },
      ],
    },
    deliveryMode: "store",
    qualifications: [],
    user: { id: 1 },
  });
  const scroll = view.nodes.find((node) => node.tag === "wx-scroll-view");
  const inner = subtree(scroll);
  const controls = view.nodes.filter(
    (node) =>
      node.tag === "wx-radio" ||
      (node.tag === "wx-input" &&
        node.attr?.ariaLabel?.endsWith("本次发货数量")),
  );
  assert.equal(controls.length, 4);
  assert.ok(controls.every((node) => inner.includes(node)));
  assert.deepEqual(
    controls
      .filter((node) => node.tag === "wx-input")
      .map((node) => node.attr.value),
    [1, 0],
  );
  const empty = render("onboarding", { selected: null, qualifications: [] });
  assert.ok(!empty.nodes.some((node) => node.tag === "wx-radio"));
});

test("活动套餐加载、失败与空数据分开展示，已有商品数量和赠品仍可正确显示", () => {
  const base = { offers: [], storedItems: [], loading: false, error: "" };
  assert.match(render("offers", base).text, /暂无上架活动套餐/);
  const loading = render("offers", { ...base, loading: true });
  assert.match(loading.text, /正在读取活动套餐/);
  assert.ok(!loading.text.includes("暂无上架"));
  const failed = render("offers", { ...base, error: "网络连接失败" });
  assert.match(failed.text, /重新查询/);
  assert.ok(!failed.text.includes("暂无上架"));
  const filled = render("offers", {
    ...base,
    selectedOfferId: 7,
    busy: true,
    offers: [
      {
        id: 7,
        amountText: "980.00",
        lines: [
          {
            lineKey: "gift",
            name: "洁面乳",
            quantity: 2,
            shipQuantity: 1,
            isGift: true,
          },
        ],
      },
    ],
  });
  const input = filled.nodes.find((node) => node.tag === "wx-input");
  assert.equal(input.attr.value, 1);
  assert.ok(input.attr.disabled);
  assert.match(filled.text, /赠品/);
});

test("活动套餐先展示货品，选中套餐后才显示地址和配送控件", () => {
  const data = {
    offers: [
      {
        id: 7,
        name: "护理套餐",
        amountText: "888.00",
        lines: [{ name: "精华", quantity: 2, shipQuantity: 2 }],
      },
    ],
    selectedOfferId: 0,
    storedItems: [],
    loading: false,
    error: "",
  };
  const browse = render("offers", data);
  assert.match(browse.text, /护理套餐/);
  assert.match(browse.text, /888.00/);
  assert.ok(!browse.hasClass("offer-delivery"));
  assert.ok(!browse.nodes.some((node) => node.tag === "wx-input"));
  assert.ok(
    browse.nodes.some(
      (node) => node.tag === "wx-button" && node.attr.bindtap === "selectOffer",
    ),
  );
  const checkout = render("offers", { ...data, selectedOfferId: 7 });
  assert.ok(checkout.hasClass("offer-delivery"));
  assert.ok(checkout.nodes.some((node) => node.tag === "wx-input"));
  assert.ok(
    checkout.nodes.some(
      (node) => node.tag === "wx-button" && node.attr.bindtap === "buy",
    ),
  );
});

test("订单的分次配送入口放在内容列表内，保留所有配送单编号", () => {
  const view = render("order", {
    order: {
      pickups: [
        { id: 1, number: "TH001" },
        { id: 2, number: "TH002" },
      ],
      shipments: [],
      snapshot: { address: {} },
      displayLines: [],
    },
  });
  const list = view.nodes.find((node) => node.attr?.class === "jy-list");
  const links = view.nodes.filter(
    (node) => node.attr?.class === "jy-pickup-link",
  );
  assert.equal(links.length, 2);
  assert.ok(links.every((node) => subtree(list).includes(node)));
  assert.match(view.text, /TH001/);
  assert.match(view.text, /TH002/);
});

test("付款后订单详情在原订单商品之前展示合并提货及跨单寄存，不重复列收费", () => {
  const data = {
    hasCombined: true,
    combinedPickup: {
      id: 9,
      number: "TH009",
      count: 3,
      statusText: "待审核配送",
      lines: [
        {
          id: 101,
          name: "新购面霜",
          sku_name: "标准装",
          quantity: 1,
          sourceText: "本单新购商品",
        },
        {
          id: 102,
          name: "寄存精华",
          sku_name: "标准装",
          quantity: 2,
          sourceText: "寄存商品 · 不重复收费",
          is_gift: 1,
        },
      ],
    },
    order: {
      paid: true,
      count: 1,
      pickups: [],
      shipments: [],
      snapshot: { address: {} },
      displayLines: [
        {
          lineKey: "11",
          name: "新购面霜",
          quantity: 1,
          priceText: "174.30 积分",
        },
      ],
    },
  };
  const view = render("order", data);
  assert.match(view.text, /本次合并提货商品\s*· 共 3 件/);
  assert.match(view.text, /寄存精华（赠品）/);
  assert.match(view.text, /寄存商品 · 不重复收费/);
  assert.match(view.text, /×2/);
  assert.match(view.text, /待审核配送/);
  assert.match(view.text, /原订单商品 · 共 1 件/);
  assert.ok(view.text.indexOf("寄存精华") < view.text.indexOf("原订单商品"));
  assert.ok(!view.text.includes("积分扣减成功后由品牌仓安排发货"));
  assert.equal(view.text.match(/174.30 积分/g).length, 1);
  const link = view.nodes.find((node) => node.attr?.bindtap === "pickup");
  assert.equal(link.attr["data-id"], 9);
  const failed = render("order", {
    ...data,
    combinedPickup: null,
    combinedError: "网络连接失败",
  });
  assert.match(failed.text, /网络连接失败/);
  assert.match(failed.text, /查看我的寄存/);
  assert.match(failed.text, /原订单商品/);
  assert.ok(!failed.text.includes("共 3 件"));
});

test("提货详情按实际状态提供取消或收货，错误时可重试且不显示业务按钮", () => {
  for (const status of [
    "review",
    "approved",
    "shipped",
    "received",
    "cancelled",
  ]) {
    const view = render("pickup", {
      pickup: { status, address: {}, lines: [] },
    });
    assert.equal(
      view.text.includes("取消提货"),
      ["review", "approved"].includes(status),
    );
    assert.equal(view.text.includes("确认收到整个包裹"), status === "shipped");
  }
  const failed = render("pickup", {
    pickup: null,
    error: "网络连接失败",
    loading: false,
  });
  assert.match(failed.text, /重新查询/);
  assert.ok(!failed.hasClass("pickup-actions"));
});

test("空列表不重复提示已全部加载，课程与售后读取失败不显示成无记录", () => {
  for (const [page, data] of [
    ["orders", { orders: [], ordersLoaded: true }],
    ["rewards", { items: [], itemsLoaded: true }],
    ["wallet", { ledger: [], ledgerLoaded: true }],
  ])
    assert.ok(!render(page, data).text.includes("已全部加载"), page);
  assert.ok(
    !render("courses", {
      rights: [],
      loading: false,
      error: "网络失败",
    }).text.includes("课程权益尚未配置"),
  );
  assert.ok(
    !render("refunds", {
      items: [],
      loading: false,
      error: "网络失败",
    }).text.includes("暂无售后记录"),
  );
});
