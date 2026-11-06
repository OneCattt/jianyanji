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

// 个人中心含昵称与积分余额，所有分享分支必须明确指定公开品牌封面。
test("个人中心的有效邀请使用独立品牌图，保留邀请人的分享码", () => {
  for (const identity of ["B", "C"]) {
    const entry = path.join(root, "pages/profile/index.js");
    const wx = { getStorageSync: () => "test-token" };
    let page;
    vm.runInNewContext(fs.readFileSync(entry, "utf8"), {
      wx,
      Page: (definition) => (page = definition),
      require: createRequire(entry, { wx }, () =>
        assert.fail("分享不应临时读取私密资料"),
      ),
    });
    page.data = {
      ...page.data,
      isAgent: true,
      user: { id: 7, identity, nickname: "私密昵称" },
      shareCode: "invite-code",
      availablePoints: "1,371.40",
    };
    const share = page.onShareAppMessage();
    assert.equal(share.path, "/pages/onboarding/index?shareCode=invite-code");
    assert.equal(share.title, "邀你加入清欣简妍肌");
    assert.equal(share.imageUrl, "/assets/brand/invitation-cover-v1.jpg");
    assert.ok(fs.existsSync(path.join(root, share.imageUrl)));
  }
});

test("个人中心未取得有效邀请信息时仍用固定公开封面，不截取个人资料", () => {
  for (const data of [
    { user: null, isAgent: false, shareCode: "" },
    { user: { id: 7, identity: "D" }, isAgent: false, shareCode: "" },
    { user: { id: 7, identity: "B" }, isAgent: true, shareCode: "" },
  ]) {
    const entry = path.join(root, "pages/profile/index.js");
    const wx = { getStorageSync: () => "test-token" };
    let page;
    vm.runInNewContext(fs.readFileSync(entry, "utf8"), {
      wx,
      Page: (definition) => (page = definition),
      require: createRequire(entry, { wx }, () =>
        assert.fail("默认分享不应请求网络"),
      ),
    });
    page.data = { ...page.data, ...data, availablePoints: "1,371.40" };
    const share = page.onShareAppMessage();
    assert.equal(share.path, "/pages/home/index");
    assert.equal(share.imageUrl, "/assets/brand/app-icon.png");
    assert.ok(fs.existsSync(path.join(root, share.imageUrl)));
  }
});

test("2980店主个人中心显示升级入口并携带B目标，B与普通顾客保持原入口", async () => {
  const entry = path.join(root, "pages/profile/index.js");
  const navigation = [];
  const wx = {
    getStorageSync: () => "test-token",
    navigateTo(value) {
      navigation.push(value);
      value.complete && value.complete();
    },
  };
  let page;
  vm.runInNewContext(fs.readFileSync(entry, "utf8"), {
    wx,
    Page: (definition) => (page = definition),
    require: createRequire(entry, { wx }, () => {
      assert.fail("入口标题与目标不应临时请求网络");
    }),
  });
  page.data = { ...page.data, navigating: false, busy: false };
  page.setData = (value) => Object.assign(page.data, value);
  const onboardingItem = () =>
    page.data.menuItems.find((item) => item.key === "onboarding");

  page.setUser({ id: 7, identity: "C", nickname: "店主" });
  assert.equal(onboardingItem().title, "升级19800店主");
  assert.match(onboardingItem().hint, /19800/);
  await page.onNavigate({ currentTarget: { dataset: { key: "onboarding" } } });
  assert.equal(navigation[0].url, "/pages/onboarding/index?target=B");

  page.setUser({ id: 7, identity: "B", nickname: "店主" });
  assert.equal(onboardingItem().title, "代理入驻");
  page.setUser({ id: 7, identity: "D", nickname: "顾客" });
  assert.equal(onboardingItem().title, "代理入驻");
});
