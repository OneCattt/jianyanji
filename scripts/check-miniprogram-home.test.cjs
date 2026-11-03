const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const { createRequire, setData } = require("./miniprogram-runtime.cjs");

function home(request) {
  const entry = path.resolve(__dirname, "../miniprogram/pages/home/index.js");
  const wx = { getStorageSync: () => "" };
  let definition;
  vm.runInNewContext(fs.readFileSync(entry, "utf8"), {
    wx,
    Page: (page) => (definition = page),
    require: createRequire(entry, { wx }, request),
  });
  return { ...definition, data: structuredClone(definition.data), setData };
}

test("首页等待后台图片期间不展示预置人像，图片加载成功后才显示", async () => {
  let resolveContent;
  const content = new Promise((resolve) => (resolveContent = resolve));
  const page = home((url) =>
    url === "/public/content" ? content : Promise.resolve({ items: [] }),
  );
  assert.equal(page.data.heroImage, "");
  assert.equal(page.data.heroImageReady, false);
  const loading = page.onShow();
  assert.equal(page.data.heroImage, "");
  resolveContent({
    content: {
      heroTitle: "品牌标题",
      heroSubtitle: "品牌副标题",
      heroImage: "https://example.com/brand.jpg",
    },
  });
  await loading;
  assert.equal(page.data.heroImage, "https://example.com/brand.jpg");
  assert.equal(page.data.heroImageReady, false);
  page.onHeroImageLoad();
  assert.equal(page.data.heroImageReady, true);
  // 内容刷新返回同一地址时不再次隐藏已加载图片。
  page._loadedAt = 0;
  await page.onShow();
  assert.equal(page.data.heroImageReady, true);
  page.onHeroImageError();
  assert.equal(page.data.heroImageReady, false);
});

test("后台未配置图片或内容加载失败时不回退到示例人像", async () => {
  for (const request of [
    async () => ({ content: null, items: [] }),
    async () => {
      throw Error("网络暂时不可用");
    },
  ]) {
    const page = home(request);
    await page.onShow();
    assert.equal(page.data.heroImage, "");
    assert.equal(page.data.heroImageReady, false);
  }
});
