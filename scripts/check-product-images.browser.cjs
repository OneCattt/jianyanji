// 启动 npm run dev:web 后执行；PLAYWRIGHT_MODULE 可指定已有 Playwright 模块路径。
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const base = process.env.ADMIN_TEST_BASE || "http://127.0.0.1:5173";
const artifactDir = process.env.ADMIN_TEST_ARTIFACTS;

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1100 },
  });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (message) => {
    if (
      message.type() === "warning" &&
      message.text().includes("Failed to resolve component")
    )
      errors.push(message.text());
  });
  try {
    await page.goto(base);
    const fixture = Buffer.from(
      await page.evaluate(() => {
        const c = document.createElement("canvas");
        c.width = 1000;
        c.height = 1600;
        const ctx = c.getContext("2d");
        ctx.fillStyle = "#f0eae2";
        ctx.fillRect(0, 0, 1000, 1600);
        ctx.fillStyle = "#6e2c2c";
        ctx.fillRect(0, 0, 1000, 200);
        ctx.fillStyle = "#359ac1";
        ctx.fillRect(300, 450, 400, 700);
        ctx.fillStyle = "white";
        ctx.fillRect(300, 1100, 400, 80);
        return c.toDataURL("image/png").split(",")[1];
      }),
      "base64",
    );
    const oldImage = "/test-existing.png";
    const oldDetail = "/test-existing-detail.png";
    const product = {
      id: 5,
      categoryId: 1,
      name: "测试氨基酸洁颜乳 / 80g",
      subtitle: "洁面",
      description: "本地行为测试商品",
      image: oldImage,
      detailImage: oldDetail,
      active: true,
      featured: true,
      skus: [
        {
          id: 10,
          name: "标准装",
          unit: "支",
          retailCents: 5880,
          storeCents: 4116,
          principalCents: 2058,
          welfareCents: null,
          productType: "regular",
          stock: 9,
          reserved: 1,
          active: true,
        },
      ],
    };
    let policies = 0,
      uploads = 0,
      failUpload = false,
      failSave = false;
    let uploadedBytes, saved;
    const saves = [];
    await page.route("**/test-*", async (route) => {
      if (new URL(route.request().url()).pathname === "/test-oss") {
        uploads++;
        if (failUpload) {
          await route.fulfill({
            status: 500,
            headers: { "x-oss-request-id": "test-failure" },
          });
          return;
        }
        const request = route.request();
        const body = request.postDataBuffer();
        const boundary = /boundary=(.+)$/.exec(
          request.headers()["content-type"],
        )[1];
        const fileHeader = body.indexOf(Buffer.from('filename="'));
        const start = body.indexOf(Buffer.from("\r\n\r\n"), fileHeader) + 4;
        const end = body.indexOf(Buffer.from(`\r\n--${boundary}`), start);
        assert.ok(start > 3 && end > start);
        uploadedBytes = body.subarray(start, end);
        await route.fulfill({ status: 204 });
      } else await route.fulfill({ contentType: "image/png", body: fixture });
    });
    await page.route("**/api/**", async (route) => {
      const request = route.request(),
        url = new URL(request.url());
      let data = {};
      if (url.pathname.endsWith("/admin/me"))
        data = {
          id: 1,
          username: "test",
          displayName: "本地验收",
          enabled: true,
          permissions: ["catalog:manage"],
        };
      else if (url.pathname.endsWith("/admin/categories"))
        data = [{ id: 1, name: "洁面", sort: 0, active: 1 }];
      else if (url.pathname.endsWith("/admin/image-uploads/policy")) {
        policies++;
        assert.equal(request.postDataJSON().purpose, "catalog");
        data = {
          host: `${base}/test-oss`,
          url: `/test-uploaded-${policies}.webp`,
          key: `test-${policies}`,
          fields: { key: `test-${policies}` },
          requestId: "local-image-test",
        };
      } else if (url.pathname.endsWith("/admin/products/5")) {
        saves.push(request.postDataJSON());
        if (failSave) {
          await route.fulfill({
            status: 503,
            json: { message: "测试保存失败" },
          });
          return;
        }
        saved = request.postDataJSON();
        Object.assign(product, {
          image: saved.image,
          detailImage: saved.detailImage,
        });
      } else if (url.pathname.endsWith("/admin/products"))
        data = { items: [product], total: 1, page: 1, pageSize: 20 };
      await route.fulfill({ json: data });
    });
    await page.reload();
    await page.getByRole("menuitem", { name: "商品与分类" }).click();
    await page
      .getByRole("button", { name: `编辑商品：${product.name}` })
      .click();
    const form = page.getByRole("dialog", { name: "编辑商品", exact: true });
    const listUploader = form
      .locator(".image-uploader")
      .filter({ hasText: "列表图片" });
    const detailUploader = form
      .locator(".image-uploader")
      .filter({ hasText: "详情图片" });
    const file = { name: "poster.png", mimeType: "image/png", buffer: fixture };
    await listUploader.locator('input[type="file"]').setInputFiles(file);
    let editor = page.getByRole("dialog", { name: "制作商品列表封面" });
    await editor.getByText("原图 1000 × 1600 px", { exact: false }).waitFor();
    assert.equal(policies, 0, "进入裁剪不能提前请求上传");
    assert.ok(
      await form.getByRole("button", { name: "保存商品" }).isDisabled(),
    );
    assert.ok(
      (await listUploader.locator("img").getAttribute("src")).endsWith(
        oldImage,
      ),
    );
    const zoom = editor.getByRole("slider", { name: "主体大小" });
    const initialCanvas = await editor
      .locator(".crop-canvas")
      .evaluate((c) => c.toDataURL());
    await zoom.focus();
    await zoom.press("End");
    await editor
      .getByText("当前裁剪区域不足 600px", { exact: false })
      .waitFor();
    assert.notEqual(
      await editor.locator(".crop-canvas").evaluate((c) => c.toDataURL()),
      initialCanvas,
      "缩放应改变实际预览画面",
    );
    await zoom.press("Home");
    const canvas = editor.locator(".crop-canvas");
    const before = await canvas.evaluate((c) => c.toDataURL());
    const rect = await canvas.boundingBox();
    await page.mouse.move(rect.x + rect.width / 2, rect.y + rect.height / 2);
    await page.mouse.down();
    await page.mouse.move(
      rect.x + rect.width / 2,
      rect.y + rect.height / 2 + 50,
    );
    await page.mouse.up();
    assert.notEqual(
      await canvas.evaluate((c) => c.toDataURL()),
      before,
      "拖动应改变实际画面",
    );
    await editor.getByRole("button", { name: "重置裁剪" }).click();
    if (artifactDir) {
      fs.mkdirSync(artifactDir, { recursive: true });
      await page.screenshot({
        path: path.join(artifactDir, "cover-editor.png"),
      });
      await page.setViewportSize({ width: 390, height: 844 });
      assert.ok(
        await editor.evaluate((element) => {
          const rect = element.getBoundingClientRect();
          return (
            rect.left >= 0 &&
            rect.right <= innerWidth + 1 &&
            document.documentElement.scrollWidth <= innerWidth
          );
        }),
        "窄屏裁剪窗口不能横向溢出",
      );
      await editor
        .getByRole("button", { name: "确认上传" })
        .scrollIntoViewIfNeeded();
      await page.screenshot({
        path: path.join(artifactDir, "cover-editor-narrow.png"),
      });
      await page.setViewportSize({ width: 1440, height: 1100 });
    }
    await editor.getByRole("button", { name: "取消", exact: true }).click();
    assert.equal(policies, 0);
    assert.ok(
      (await listUploader.locator("img").getAttribute("src")).endsWith(
        oldImage,
      ),
    );
    assert.ok(await form.getByRole("button", { name: "保存商品" }).isEnabled());
    console.log("通过：选图与拖动、键盘缩放、低像素提示；取消保留旧图且不上传");

    await listUploader.locator('input[type="file"]').setInputFiles({
      name: "broken.png",
      mimeType: "image/png",
      buffer: Buffer.from("invalid image"),
    });
    editor = page.getByRole("dialog", { name: "制作商品列表封面" });
    await editor.getByText("图片无法读取", { exact: false }).waitFor();
    assert.equal(policies, 0);
    assert.ok(
      await editor.getByRole("button", { name: "确认上传" }).isDisabled(),
    );
    await editor.getByRole("button", { name: "取消", exact: true }).click();
    assert.ok(
      (await listUploader.locator("img").getAttribute("src")).endsWith(
        oldImage,
      ),
    );
    console.log("通过：损坏图片不上传、不替换旧图，可取消返回");

    await listUploader.locator('input[type="file"]').setInputFiles(file);
    editor = page.getByRole("dialog", { name: "制作商品列表封面" });
    await editor.getByText("原图 1000 × 1600 px", { exact: false }).waitFor();
    failUpload = true;
    await editor.getByRole("button", { name: "确认上传" }).click();
    await editor.getByText("图片上传失败", { exact: false }).waitFor();
    assert.equal(uploads, 1);
    assert.ok(
      (await listUploader.locator("img").getAttribute("src")).endsWith(
        oldImage,
      ),
    );
    assert.ok(
      await form.getByRole("button", { name: "保存商品" }).isDisabled(),
    );
    failUpload = false;
    await editor.getByRole("button", { name: "重新上传" }).click();
    await editor.waitFor({ state: "hidden" });
    assert.equal(uploads, 2);
    assert.equal(saves.length, 0, "上传不能自动保存商品");
    assert.ok(
      (await listUploader.locator("img").getAttribute("src")).endsWith(
        "/test-uploaded-2.webp",
      ),
    );
    const coverInfo = await page.evaluate(
      async (bytes) => {
        const bitmap = await createImageBitmap(
          new Blob([new Uint8Array(bytes)]),
        );
        const result = { width: bitmap.width, height: bitmap.height };
        bitmap.close();
        return result;
      },
      [...uploadedBytes],
    );
    assert.deepEqual(coverInfo, { width: 1000, height: 1000 });
    console.log(
      "通过：失败保留旧图并可重试；实际上传正方形像素，没有放大低像素输入",
    );

    await detailUploader.locator('input[type="file"]').setInputFiles(file);
    const detail = page.getByRole("dialog", { name: "确认商品详情图片" });
    await detail.getByText("原图 1000 × 1600 px", { exact: false }).waitFor();
    assert.equal(await detail.getByRole("slider").count(), 0);
    assert.equal(policies, 2);
    await detail.getByRole("button", { name: "取消", exact: true }).click();
    assert.ok(
      (await detailUploader.locator("img").getAttribute("src")).endsWith(
        oldDetail,
      ),
    );
    await detailUploader.locator('input[type="file"]').setInputFiles(file);
    await detail.getByText("原图 1000 × 1600 px", { exact: false }).waitFor();
    await detail.getByRole("button", { name: "确认上传" }).click();
    await detail.waitFor({ state: "hidden" });
    const detailInfo = await page.evaluate(
      async (bytes) => {
        const bitmap = await createImageBitmap(
          new Blob([new Uint8Array(bytes)]),
        );
        const result = { width: bitmap.width, height: bitmap.height };
        bitmap.close();
        return result;
      },
      [...uploadedBytes],
    );
    assert.deepEqual(detailInfo, { width: 1000, height: 1600 });
    console.log("通过：详情图完整预览和确认，实际上传保留原比例");

    await form
      .getByPlaceholder("说明本次商品、价格或库存调整")
      .fill("调整商品封面");
    failSave = true;
    await form.getByRole("button", { name: "保存商品" }).click();
    await page.getByText("测试保存失败", { exact: true }).waitFor();
    assert.ok(await form.isVisible());
    failSave = false;
    await form.getByRole("button", { name: "保存商品" }).click();
    await form.waitFor({ state: "hidden" });
    assert.equal(saved.image, "/test-uploaded-2.webp");
    assert.equal(saved.detailImage, "/test-uploaded-3.webp");
    assert.ok(!("stock" in saved.skus[0]), "资料编辑不能覆盖实时库存");
    assert.equal(product.image, saved.image);
    console.log("通过：保存失败保留表单；再次保存提交新图片且保留实时库存");
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
