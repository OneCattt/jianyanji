const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const root = path.resolve(__dirname, "../miniprogram");

function createRuntime({ requestFails = false, navigationFails = false } = {}) {
  let route = "pages/home/index";
  let component;
  const calls = [];
  const callbacks = [];
  const wx = {
    switchTab(options) {
      calls.push(options.url);
      callbacks.push(() => {
        if (navigationFails) options.fail();
        else route = options.url.slice(1);
        options.complete();
      });
    },
    showToast() {},
  };
  vm.runInNewContext(
    fs.readFileSync(path.join(root, "custom-tab-bar/index.js"), "utf8"),
    {
      Component: (definition) => (component = definition),
      getCurrentPages: () => [{ route }],
      wx,
    },
  );

  function createTabBar() {
    const tabBar = {
      data: structuredClone(component.data),
      ...component.methods,
      setData(values) {
        Object.assign(this.data, values);
      },
    };
    component.lifetimes.attached.call(tabBar);
    return tabBar;
  }

  async function showPage(name, tabBar) {
    route = `pages/${name}/index`;
    let page;
    vm.runInNewContext(
      fs.readFileSync(path.join(root, route + ".js"), "utf8"),
      {
        Page: (definition) => (page = definition),
        require: (module) => {
          if (module === "../../utils/catalog") return { decorate: (p) => p };
          return {
            request: async (url) => {
              if (requestFails) throw Error("接口不可用");
              if (url === "/public/categories") return [];
              return { content: null, items: [], identity: "D" };
            },
          };
        },
        wx,
      },
    );
    page.data = structuredClone(page.data);
    page.setData = (values) => Object.assign(page.data, values);
    page.getTabBar = () => tabBar;
    await page.onShow();
    return page;
  }

  return {
    calls,
    createTabBar,
    showPage,
    tap(tabBar, index) {
      tabBar.switchTab({ currentTarget: { dataset: { index } } });
    },
    finishNavigation() {
      callbacks.shift()();
    },
  };
}

test("首页与商城往返时，各页同步自己的导航状态", async () => {
  const runtime = createRuntime();
  const home = runtime.createTabBar();
  await runtime.showPage("home", home);
  runtime.tap(home, 1);

  // 每个页签独立持有组件，组件挂载时页面栈可能仍是来源页。
  const mall = runtime.createTabBar();
  runtime.finishNavigation();
  await runtime.showPage("mall", mall);
  runtime.tap(mall, 0);
  assert.equal(runtime.calls.at(-1), "/pages/home/index", "商城必须能返回首页");
  assert.equal(mall.data.selected, 1, "商城显示后必须选中商城");
  runtime.finishNavigation();
  await runtime.showPage("home", home);
  assert.equal(home.data.selected, 0);
  runtime.tap(home, 1);
  runtime.finishNavigation();
  await runtime.showPage("mall", mall);
  assert.equal(mall.data.selected, 1);
  assert.equal(home.data.switching, false);
  assert.equal(mall.data.switching, false);
  assert.equal(runtime.calls.length, 3);
});

for (const [name, selected] of [
  ["home", 0],
  ["mall", 1],
]) {
  test(`${name} 接口失败时仍同步页签且可切换`, async () => {
    const runtime = createRuntime({ requestFails: true });
    const tabBar = runtime.createTabBar();
    tabBar.setData({ selected: selected === 0 ? 1 : 0 });
    const page = await runtime.showPage(name, tabBar);
    assert.equal(tabBar.data.selected, selected);
    assert.equal(page.data.error, "接口不可用");
    runtime.tap(tabBar, selected === 0 ? 1 : 0);
    assert.equal(runtime.calls.length, 1);
    runtime.finishNavigation();
    assert.equal(tabBar.data.switching, false);
  });
}

test("当前页不重复跳转，切换期间拦截重复点击", () => {
  const runtime = createRuntime();
  const tabBar = runtime.createTabBar();
  runtime.tap(tabBar, 0);
  assert.equal(runtime.calls.length, 0);
  runtime.tap(tabBar, 1);
  runtime.tap(tabBar, 1);
  assert.equal(runtime.calls.length, 1);
  runtime.finishNavigation();
  assert.equal(tabBar.data.switching, false);
});

test("切换失败后释放锁，允许再次点击", () => {
  const runtime = createRuntime({ navigationFails: true });
  const tabBar = runtime.createTabBar();
  runtime.tap(tabBar, 1);
  runtime.finishNavigation();
  assert.equal(tabBar.data.switching, false);
  runtime.tap(tabBar, 1);
  assert.equal(runtime.calls.length, 2);
});
