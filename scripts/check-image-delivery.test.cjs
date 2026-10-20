const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");
const { createRequire, setData } = require("./miniprogram-runtime.cjs");
const root = path.resolve(__dirname, "..");
const entry = path.join(root, "miniprogram/app.js");
const load = createRequire(entry, {}, () => {});
const { imageUrl } = load("./utils/image");
const source = fs.readFileSync(path.join(root, "web/image-url.ts"), "utf8");
const web = { exports: {} };
vm.runInNewContext(
  ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS },
  }).outputText,
  { exports: web.exports },
);
const original =
  "https://examplebucket.oss-cn-hangzhou.aliyuncs.com/products/catalog/20261020/123e4567-e89b-42d3-a456-426614174000.jpg";

test("小程序与后台使用一致的尺寸，支持已绑定 OSS 的自定义域名", () => {
  for (const url of [
    original,
    original.replace(
      "examplebucket.oss-cn-hangzhou.aliyuncs.com",
      "images.example.com/assets",
    ),
  ]) {
    for (const [scene, width] of Object.entries({
      thumb: 160,
      card: 400,
      detail: 1200,
    })) {
      const result = imageUrl(url, scene);
      assert.equal(result, web.exports.imageUrl(url, scene));
      assert.equal(
        new URL(result).searchParams.get("x-oss-process"),
        `image/resize,w_${width},m_lfit,limit_1/quality,q_80/format,webp`,
      );
    }
  }
});

test("保留本地素材、外部图片、签名和已有处理参数，不重复追加缩略图参数", () => {
  for (const url of [
    "",
    undefined,
    "/assets/photo.png",
    "https://example.com/photo.jpg",
    original + "?signature=keep",
    original + "#fragment",
    imageUrl(original),
  ]) {
    assert.equal(imageUrl(url), url);
    assert.equal(web.exports.imageUrl(url), url);
  }
});

test("商品和订单只增加展示地址，保留原图及订单快照", () => {
  const product = { image: original, detailImage: original, skus: [] };
  const decorated = load("./utils/catalog").decorate(product);
  assert.equal(decorated.image, original);
  assert.equal(decorated.detailImage, original);
  assert.equal(decorated.imageCard, imageUrl(original, "card"));
  assert.equal(decorated.detailImageDisplay, imageUrl(original, "detail"));
  const order = {
    snapshot: { lines: [{ image: original, quantity: 1, unitCents: 100 }] },
  };
  const result = load("./pages/order/view").decorate(order);
  assert.equal(result.displayLines[0].imageThumb, imageUrl(original, "thumb"));
  assert.equal(order.snapshot.lines[0].image, original);
  assert.equal(result.displayLines[0].image, original);
});

test("内容页展示缩略图，点击预览仍传全部原图，分享使用卡片尺寸", async () => {
  const filename = path.join(root, "miniprogram/pages/post/index.js");
  let definition, preview;
  const context = {
    Page: (value) => {
      definition = value;
    },
    wx: {
      previewImage: (value) => {
        preview = value;
      },
    },
  };
  const post = {
    title: "测试内容",
    cover: original,
    imageUrls: [original],
    products: [{ image: original }],
  };
  context.require = createRequire(filename, context, async () => post);
  vm.runInNewContext(fs.readFileSync(filename, "utf8"), context, { filename });
  const page = {
    ...definition,
    data: structuredClone(definition.data),
    setData,
  };
  page.onLoad({ id: "1" });
  await page.load();
  assert.equal(
    page.data.post.displayImages[0].displayUrl,
    imageUrl(original, "detail"),
  );
  assert.equal(
    page.data.post.products[0].imageThumb,
    imageUrl(original, "thumb"),
  );
  page.preview({ currentTarget: { dataset: { url: original } } });
  assert.equal(preview.current, original);
  assert.deepEqual(preview.urls, [original]);
  assert.equal(page.onShareAppMessage().imageUrl, imageUrl(original, "card"));
});
