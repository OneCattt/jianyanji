const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const {
  createRequire,
  setData,
} = require("../../scripts/miniprogram-runtime.cjs");
function harness() {
  let definition;
  const cache = { jyj_token: "user-one" },
    calls = [];
  const wx = {
    getStorageSync: (key) => cache[key],
    setStorageSync: (key, value) => (cache[key] = value),
    navigateBack: () => calls.push({ back: true }),
  };
  const entry = path.resolve(__dirname, "../pages/storage/index.js");
  const request = async (url) => {
    calls.push({ url });
    if (url.startsWith("/mini/package-consumption")) return { items: [] };
    const page = Number(new URL("http://test" + url).searchParams.get("page"));
    return {
      items: [
        { id: page === 2 ? 22 : 1, name: "测试护肤品", available_qty: 5 },
      ],
      hasMore: page !== 2,
      nextPage: page === 2 ? null : 2,
    };
  };
  const context = {
    wx,
    Page: (page) => (definition = page),
    getCurrentPages: () => [],
    setTimeout,
    clearTimeout,
  };
  vm.runInNewContext(
    fs.readFileSync(entry, "utf8"),
    { ...context, require: createRequire(entry, context, request) },
    { filename: entry },
  );
  const page = {
    ...definition,
    data: structuredClone(definition.data),
    setData,
  };
  page.onLoad({ select: "1" });
  return { page, cache, calls };
}
test("寄存跨页选择保留完整数量，合并购买提交全部已选批次", async () => {
  const { page, cache } = harness();
  await page.loadLots(true);
  page.quantity({
    currentTarget: { dataset: { id: 1 } },
    detail: { value: "2" },
  });
  await page.onReachBottom();
  page.quantity({
    currentTarget: { dataset: { id: 22 } },
    detail: { value: "3" },
  });
  assert.equal(page.data.selectedCount, 5);
  assert.equal(page.data.lots.length, 2);
  await page.submit();
  assert.deepEqual(JSON.parse(JSON.stringify(cache.jyj_pickup_selection)), [
    { lotId: 1, quantity: 2 },
    { lotId: 22, quantity: 3 },
  ]);
});
test("无效或超量提货不能进入已选集合", async () => {
  const { page } = harness();
  await page.loadLots(true);
  for (const value of ["6", "-1", "1.5"]) {
    page.quantity({ currentTarget: { dataset: { id: 1 } }, detail: { value } });
    assert.equal(page.data.selectedCount, 0);
    assert.match(page.data.error, /不超过可提/);
  }
});
test("账号切换清空上一账号的寄存选择", async () => {
  const { page, cache } = harness();
  await page.onShow();
  page.quantity({
    currentTarget: { dataset: { id: 1 } },
    detail: { value: "2" },
  });
  cache.jyj_token = "user-two";
  await page.onShow();
  assert.equal(page.data.selectedCount, 0);
  assert.equal(page.data.selectedItems.length, 0);
});

test("寄存步进器遵守可提数量，跨页选择仍按完整集合提交", async () => {
  const { page, cache } = harness();
  await page.loadLots(true);
  const step = (id, amount) =>
    page.stepQuantity({ currentTarget: { dataset: { id, step: amount } } });
  step(1, -1);
  assert.equal(page.data.selectedCount, 0);
  step(1, 1);
  step(1, 1);
  await page.onReachBottom();
  step(22, 1);
  assert.equal(page.data.selectedCount, 3);
  for (let i = 0; i < 10; i++) step(22, 1);
  assert.equal(page.data.lots[1].selectedQuantity, 5);
  page.data.lots[0].expired = true;
  step(1, 1);
  assert.equal(page.data.lots[0].selectedQuantity, 2);
  page.data.busy = true;
  step(22, -1);
  assert.equal(page.data.selectedCount, 7);
  page.data.busy = false;
  await page.submit();
  assert.equal(
    cache.jyj_pickup_selection.reduce((sum, item) => sum + item.quantity, 0),
    7,
  );
});
