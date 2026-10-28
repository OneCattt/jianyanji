import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
const { chromium } = await import(
  process.env.PLAYWRIGHT_MODULE || "playwright"
);
const output = ".scratch/admin-detail-drawers";
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1920, height: 1000 } });
const errors = [],
  checks = [];
page.on("pageerror", (e) => errors.push(e.message));
const order = (id) => ({
  id,
  number: `JY-DRAWER-${id}`,
  kind: "goods",
  user_id: 1,
  status: "paid",
  payment_state: "paid",
  payment_method: "points",
  total_cents: 88800,
  goods_cents: 88800,
  shipping_cents: 0,
  received_at: null,
  create_time: "2026-10-28T00:00:00.000Z",
  storageRemainingQty: 0,
  pickups: [],
  snapshot: {
    address: {
      recipient: "本地验收",
      phone: "13800000000",
      province: "广东省",
      city: "广州市",
      district: "荔湾区",
      detail: "本地测试地址",
    },
    lines: [
      {
        skuId: 1,
        name: "净肤嫩颜精华液",
        skuName: "标准装",
        quantity: 2,
        unitCents: 44400,
      },
    ],
  },
  lines: [
    {
      id: 1,
      sku_id: 1,
      quantity: 2,
      shipped_qty: 0,
      refunded_unshipped: 0,
      held_unshipped: 0,
      received_qty: 0,
      refund_held: 0,
      refunded_qty: 0,
    },
  ],
  shipments: [
    {
      id: 1,
      carrier: "本地物流",
      tracking: "LOCAL1234",
      shipped_at: "2026-10-28",
      received_at: null,
      lines: [],
    },
  ],
});
const application = (id) => ({
  id,
  user_id: 1,
  nickname: "本地客户",
  target_identity: "C",
  status: "approved",
  review_reason: "本地核验说明",
  requested_inviter_id: 2,
  inviter_id: 2,
  principal_id: 3,
  package_snapshot: {
    name: "2980店主权益包",
    amountCents: 298000,
    conditions: "已核验资格",
    lines: [{ skuId: 1, quantity: 2, unitCents: 149000 }],
  },
  order: { id: 1, number: "JY-DRAWER-1", status: "paid", shipments: [] },
  receipt: null,
});
const reward = (id) => ({
  id,
  orderId: 1,
  orderNumber: "JY-DRAWER-1",
  userId: 1,
  kind: "store",
  amountCents: 50000,
  reversedCents: 0,
  settledCents: 0,
  remainingCents: 50000,
  eligible: true,
  reason: "本地验收",
  status: "eligible",
});
const pickup = {
  id: 1,
  user_id: 1,
  number: "TH-DRAWER-1",
  status: "review",
  newGoodsQuantity: 1,
  storedGoodsQuantity: 2,
  address: order(1).snapshot.address,
  lines: Array.from({ length: 24 }, (_, i) => ({
    id: i + 1,
    lot_id: i + 1,
    line_id: i + 1,
    order_id: 1,
    name: `本地提货商品${i + 1}`,
    sku_name: "标准装",
    quantity: 1,
    is_gift: false,
  })),
};
await page.route("**/api/**", async (route) => {
  const url = new URL(route.request().url()),
    path = url.pathname;
  let body = { items: [], total: 0, page: 1, pageSize: 20 };
  const list = (items) => ({
    items,
    total: items.length,
    page: 1,
    pageSize: 20,
  });
  if (path === "/api/admin/me")
    body = {
      id: 1,
      username: "local-review",
      displayName: "本地验收",
      enabled: true,
      permissions: [
        "orders:manage",
        "onboarding:manage",
        "finance:manage",
        "rewards:manage",
        "users:read",
        "audit:read",
        "content:manage",
        "catalog:manage",
        "staff:manage",
      ],
    };
  else if (path === "/api/admin/orders")
    body = list(Array.from({ length: 12 }, (_, i) => order(i + 1)));
  else if (/\/orders\/\d+\/payment$/.test(path))
    body = {
      order: order(Number(path.split("/")[4])),
      attempt: null,
      receipt: {
        method: "points",
        transaction_id: "LOCAL",
        cents: 88800,
        paid_at: "2026-10-28",
      },
      attempts: [],
      events: [],
    };
  else if (/\/orders\/\d+\/fulfillment$/.test(path))
    body = { order: order(Number(path.split("/")[4])) };
  else if (path === "/api/admin/onboardings")
    body = list(Array.from({ length: 12 }, (_, i) => application(i + 1)));
  else if (path === "/api/admin/membership-settings") body = { packages: [] };
  else if (path === "/api/admin/reward-settings") body = null;
  else if (path === "/api/admin/reward-debts") body = [];
  else if (path === "/api/admin/rewards")
    body = list(Array.from({ length: 12 }, (_, i) => reward(i + 1)));
  else if (/\/rewards\/\d+$/.test(path))
    body = reward(Number(path.split("/").at(-1)));
  else if (path === "/api/admin/user-options")
    body = list([{ id: 1, label: "本地客户 · #1", identity: "D" }]);
  else if (/\/wallets\/\d+$/.test(path))
    body = {
      balanceCents: 12345,
      availableCents: 12345,
      heldCents: 0,
      lots: [],
      ledger: [],
    };
  else if (path === "/api/admin/pickups") body = list([pickup]);
  else if (path === "/api/admin/pickups/1") body = pickup;
  else if (["/api/admin/staff", "/api/admin/categories"].includes(path))
    body = [];
  else if (path === "/api/admin/content") body = null;
  await route.fulfill({ json: body });
});
async function navigate(name) {
  if (await page.locator(".mobile-menu").isVisible())
    await page.locator(".mobile-menu").click();
  await page
    .getByRole("menuitem", { name, exact: true })
    .filter({ visible: true })
    .click();
}
async function drawerVisible(title) {
  const drawer = page.locator(".admin-drawer-wrap:visible");
  await page.waitForTimeout(400);
  if (!(await drawer.count())) {
    await page.screenshot({
      path: `${output}/before-missing-drawer.png`,
      fullPage: true,
    });
    assert.fail(`${title}应在当前视口右侧抽屉显示，实际仍在页面下方`);
  }
  await drawer
    .locator(".ant-drawer-title")
    .filter({ hasText: title })
    .waitFor();
  await page.waitForFunction(() => {
    const panel = document.querySelector(
      ".admin-drawer-wrap .ant-drawer-content-wrapper",
    );
    return (
      panel && Math.abs(panel.getBoundingClientRect().right - innerWidth) < 1
    );
  });
  const rect = await drawer
    .locator(".ant-drawer-content-wrapper")
    .boundingBox();
  assert(
    rect.y >= -1 && rect.y < 2 && rect.height <= page.viewportSize().height + 1,
  );
  await page.screenshot({ path: `${output}/current-drawer.png` });
  assert(
    Math.abs(rect.x + rect.width - page.viewportSize().width) < 2,
    JSON.stringify({ rect, viewport: page.viewportSize() }),
  );
  assert.equal(
    await page.locator("body").evaluate((el) => getComputedStyle(el).overflowY),
    "hidden",
  );
  return drawer;
}
try {
  await page.goto(process.env.ADMIN_TEST_BASE || "http://127.0.0.1:5173");
  await navigate("订单与发货");
  const trigger = page
    .getByRole("button", { name: "查看与处理", exact: true })
    .first();
  await trigger.click();
  let drawer = await drawerVisible("订单详情与处理");
  await drawer.getByText("JY-DRAWER-1", { exact: true }).waitFor();
  await drawer
    .getByRole("button", { name: "登记品牌仓发货", exact: true })
    .click();
  await page.locator(".admin-drawer-wrap:visible").nth(1).waitFor();
  await page.keyboard.press("Escape");
  await page.waitForTimeout(400);
  assert.equal(await page.locator(".ant-modal-wrap:visible").count(), 0);
  assert.equal(await page.locator(".admin-drawer-wrap:visible").count(), 1);
  await drawer.getByRole("button", { name: "关闭详情", exact: true }).click();
  await trigger.waitFor();
  checks.push(
    "订单点击后在右侧显示；发货操作弹窗可用，Esc仅关闭顶层，关闭详情返回原列表",
  );
  const laterRow = page
    .getByRole("button", { name: "查看与处理", exact: true })
    .nth(8);
  await laterRow.scrollIntoViewIfNeeded();
  const scrollBefore = await page.evaluate(() => window.scrollY);
  await laterRow.click();
  drawer = await drawerVisible("订单详情与处理");
  await drawer.getByText("JY-DRAWER-9", { exact: true }).waitFor();
  assert.equal(
    await drawer.getByText("JY-DRAWER-1", { exact: true }).count(),
    0,
  );
  await page.keyboard.press("Escape");
  await drawer.waitFor({ state: "detached" });
  assert.equal(await page.evaluate(() => window.scrollY), scrollBefore);
  checks.push(
    "长列表后段点击详情仍在视口内，关闭保留列表位置，切换记录不串用旧详情",
  );
  for (const width of [1024, 390]) {
    await page.setViewportSize({ width, height: 844 });
    await page
      .getByRole("button", { name: "查看与处理", exact: true })
      .first()
      .click();
    drawer = await drawerVisible("订单详情与处理");
    assert.equal(
      await drawer.evaluate((el) => el.scrollWidth > innerWidth),
      false,
    );
    await page.screenshot({ path: `${output}/orders-${width}.png` });
    await drawer.getByRole("button", { name: "关闭详情", exact: true }).click();
  }
  checks.push("1024与390像素视口内抽屉可见，标题与关闭区未越界");
  await page.setViewportSize({ width: 1920, height: 1000 });
  await navigate("产品包与入驻");
  await page
    .getByRole("button", { name: "核款与交付", exact: true })
    .first()
    .click();
  drawer = await drawerVisible("入驻申请");
  await drawer.getByText("2980店主权益包", { exact: false }).first().waitFor();
  await drawer.getByRole("button", { name: "关闭详情", exact: true }).click();
  checks.push("入驻核款与交付直接打开抽屉");
  await navigate("奖励与结算");
  await page.getByRole("button", { name: "明细", exact: true }).first().click();
  drawer = await drawerVisible("奖励追溯明细");
  await drawer.getByText("来源订单", { exact: true }).waitFor();
  await drawer.getByRole("button", { name: "关闭详情", exact: true }).click();
  checks.push("奖励明细直接打开抽屉");
  await navigate("积分充值收款");
  const picker = page.locator(".account-query .admin-user-select");
  await picker.locator(".ant-select-selector").click();
  await page
    .locator(".ant-select-item-option")
    .filter({ hasText: "本地客户" })
    .click();
  await page
    .getByRole("button", { name: "查询钱包和收款", exact: true })
    .click();
  drawer = await drawerVisible("积分钱包");
  await drawer.getByText("123.45", { exact: true }).first().waitFor();
  await drawer.getByRole("button", { name: "关闭详情", exact: true }).click();
  checks.push("钱包查询后结果在抽屉显示");
  await navigate("寄存与提货");
  await page.getByText("提货审核", { exact: true }).click();
  await page.getByRole("button", { name: "明细", exact: true }).first().click();
  drawer = await drawerVisible("提货详情");
  await drawer.getByText("本地提货商品24", { exact: false }).waitFor();
  await page.setViewportSize({ width: 1259, height: 570 });
  const area = drawer.locator(".ant-drawer-body");
  assert(await area.evaluate((el) => el.scrollHeight > el.clientHeight));
  await area.evaluate((el) => (el.scrollTop = el.scrollHeight));
  await drawer.getByRole("button", { name: "审核通过", exact: true }).waitFor();
  await page.screenshot({ path: `${output}/pickup-1259.png` });
  await drawer.getByRole("button", { name: "关闭详情", exact: true }).click();
  checks.push(
    "提货详情使用抽屉，1259×570下多商品内容独立滚动，关闭操作保持可见",
  );
  await page.setViewportSize({ width: 1440, height: 900 });
  for (const [menu, button, title] of [
    ["商品与分类", "新增商品", "新增商品"],
    ["品牌与经营说明", "编辑品牌资料", "编辑品牌资料"],
    ["品牌种草内容", "新建内容", "维护种草内容"],
    ["工作人员", "新增工作人员", "新增工作人员"],
  ]) {
    await navigate(menu);
    await page
      .getByRole("button", { name: button, exact: true })
      .first()
      .click();
    drawer = await drawerVisible(title);
    assert.equal(await page.locator(".ant-modal-wrap:visible").count(), 0);
    const body = drawer.locator(".ant-drawer-body");
    assert.equal(
      await body.evaluate((el) => el.scrollWidth > el.clientWidth + 1),
      false,
    );
    await page.screenshot({ path: `${output}/complex-${title}.png` });
    if (title === "新增商品") {
      const png = await page.evaluate(() => {
        const canvas = document.createElement("canvas");
        canvas.width = canvas.height = 1200;
        canvas.getContext("2d").fillRect(0, 0, 1200, 1200);
        return canvas.toDataURL("image/png").split(",")[1];
      });
      await drawer
        .locator('input[type="file"]')
        .first()
        .setInputFiles({
          name: "local-cover.png",
          mimeType: "image/png",
          buffer: Buffer.from(png, "base64"),
        });
      await page.locator(".admin-drawer-wrap:visible").nth(1).waitFor();
      await page.keyboard.press("Escape");
      await page.waitForFunction(
        () => document.querySelectorAll(".admin-drawer-wrap").length === 1,
      );
      assert.equal(await drawer.count(), 1);
      checks.push("商品图片裁切在上层抽屉，Esc关闭裁切后保留商品编辑");
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(() => {
      const panels = [
        ...document.querySelectorAll(
          ".admin-drawer-wrap .ant-drawer-content-wrapper",
        ),
      ];
      return panels.every((panel) => {
        const r = panel.getBoundingClientRect();
        return (
          r.x >= -1 &&
          Math.abs(r.right - innerWidth) < 1 &&
          r.width <= innerWidth
        );
      });
    });
    assert.equal(
      await body.evaluate((el) => el.scrollWidth > el.clientWidth + 1),
      false,
    );
    await page.screenshot({ path: `${output}/complex-${title}-390.png` });
    await page.keyboard.press("Escape");
    await drawer.waitFor({ state: "detached" });
    await page.setViewportSize({ width: 1440, height: 900 });
    checks.push(`${title}使用公共抽屉且无内容横向溢出`);
  }
  await page.getByRole("button", { name: "新增工作人员", exact: true }).click();
  drawer = await drawerVisible("新增工作人员");
  await drawer.getByLabel("登录账号", { exact: true }).fill("drawer_test");
  await drawer.getByLabel("工作人员姓名", { exact: true }).fill("本地验收");
  await drawer.getByLabel("初始密码", { exact: true }).fill("drawer_test_1234");
  await drawer.getByLabel("操作原因", { exact: true }).fill("本地模拟失败验证");
  let releaseRequest;
  const responseGate = new Promise((resolve) => {
    releaseRequest = resolve;
  });
  await page.route("**/api/admin/staff", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    await responseGate;
    await route.fulfill({ status: 400, json: { message: "本地模拟提交失败" } });
  });
  const requestStarted = page.waitForRequest(
    (req) => req.url().endsWith("/api/admin/staff") && req.method() === "POST",
  );
  await drawer.getByRole("button", { name: "保存设置", exact: true }).click();
  await requestStarted;
  assert.equal(await drawer.locator(".ant-drawer-close").count(), 0);
  assert(
    await drawer
      .getByRole("button", { name: "取消", exact: true })
      .isDisabled(),
  );
  await page.keyboard.press("Escape");
  assert.equal(await drawer.count(), 1);
  releaseRequest();
  await drawer
    .getByRole("button", { name: "取消", exact: true })
    .waitFor({ state: "visible" });
  await page.waitForFunction(
    () => !document.querySelector(".admin-drawer-wrap .ant-btn[disabled]"),
  );
  await drawer.getByLabel("工作人员姓名", { exact: true }).fill("失败后可修改");
  await page.keyboard.press("Escape");
  await drawer.waitFor({ state: "detached" });
  checks.push("复杂表单提交期间禁止关闭，失败后保留内容并恢复修改和关闭");
  await navigate("商品与分类");
  await page.getByRole("button", { name: "新增分类", exact: true }).click();
  await page.locator(".ant-modal-wrap:visible").waitFor();
  assert.equal(await page.locator(".admin-drawer-wrap:visible").count(), 0);
  await page.keyboard.press("Escape");
  checks.push("简单分类维护保留小弹窗");
  assert.deepEqual(errors, []);
  await writeFile(
    `${output}/browser-result.json`,
    JSON.stringify(
      { environment: "实际Vue页面与模拟HTTP", checks, errors },
      null,
      2,
    ) + "\n",
  );
  console.log(JSON.stringify({ passed: checks.length, errors }));
} finally {
  await browser.close();
}
