const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const { createRequire } = require("./miniprogram-runtime.cjs");

const root = path.resolve(__dirname, "../miniprogram");
const app = JSON.parse(fs.readFileSync(path.join(root, "app.json"), "utf8"));

for (const tab of app.tabBar.list) {
  test(`${tab.text}：未登录、数据尚未加载时即可转发公开入口`, () => {
    const entry = path.join(root, tab.pagePath + ".js");
    const wx = { getStorageSync: () => "" };
    let page;
    vm.runInNewContext(fs.readFileSync(entry, "utf8"), {
      wx,
      Page: (definition) => (page = definition),
      require: createRequire(entry, { wx }, () => {
        assert.fail("转发公开入口不应依赖网络或登录");
      }),
    });

    assert.equal(
      typeof page.onShareAppMessage,
      "function",
      `${tab.pagePath} 缺少转发事件，右上角转发入口不可用`,
    );
    const share = page.onShareAppMessage();
    const destination =
      tab.pagePath === "pages/profile/index"
        ? "pages/home/index"
        : tab.pagePath;
    assert.equal(share.path, "/" + destination);
    assert.ok(app.pages.includes(destination));
    assert.match(share.title, /清欣简妍肌/);
  });
}
