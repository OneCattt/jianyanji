const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const vm = require("node:vm");
const { execFileSync } = require("node:child_process");
const test = require("node:test");

const root = path.resolve(__dirname, "../miniprogram");
const compiler = path.join(
  process.env.WECHAT_COMPILER_DIR ||
    "/Applications/wechatwebdevtools.app/Contents/Resources/app.asar.unpacked/node_modules/wcc-exec",
  "wcc",
);
const directory = fs.mkdtempSync(
  path.join(os.tmpdir(), "jianyanji-addresses-"),
);
let compiled;
try {
  const output = path.join(directory, "addresses.js");
  execFileSync(compiler, ["-o", output, "pages/addresses/index.wxml"], {
    cwd: root,
  });
  compiled = fs.readFileSync(output, "utf8");
} finally {
  fs.rmSync(directory, { recursive: true, force: true });
}

// 用微信原生编译器渲染真实模板，避免只检查 editing 状态而漏掉界面不切换。
function runtime({ saveFails = false } = {}) {
  const calls = [];
  let definition;
  const context = {
    window: {},
    console,
    Page: (value) => (definition = value),
    require: () => ({
      request: async (url, method = "GET", data) => {
        calls.push({ url, method, data });
        if (method !== "GET" && saveFails) throw Error("地址保存失败");
        return [];
      },
    }),
  };
  vm.createContext(context);
  vm.runInContext(compiled, context);
  vm.runInContext(
    fs.readFileSync(path.join(root, "pages/addresses/index.js"), "utf8"),
    context,
  );
  const page = { ...definition, data: structuredClone(definition.data) };
  page.setData = (values) => {
    for (const [key, value] of Object.entries(values)) {
      const parts = key.split(".");
      let target = page.data;
      for (const part of parts.slice(0, -1)) target = target[part];
      target[parts.at(-1)] = value;
    }
  };
  const render = context.$gwx("pages/addresses/index.wxml");
  function nodes(node = render(page.data), result = []) {
    if (typeof node !== "object" || !node) return result;
    result.push(node);
    for (const child of node.children || []) nodes(child, result);
    return result;
  }
  function tap(handler, id) {
    const button = nodes().find((node) => node.attr?.bindtap === handler);
    assert.ok(button, `界面应显示 ${handler} 按钮`);
    return page[button.attr.bindtap]({ currentTarget: { dataset: { id } } });
  }
  return { page, calls, nodes, tap };
}

test("点击新增收货地址后显示输入表单，并能提交新增请求", async () => {
  const { page, calls, nodes, tap } = runtime();
  await tap("add");
  assert.ok(
    nodes().some((node) => node.attr?.bindtap === "save"),
    "点击新增后必须显示保存地址按钮",
  );
  assert.ok(nodes().some((node) => node.tag === "wx-input"));
  assert.ok(!nodes().some((node) => node.attr?.bindtap === "add"));
  page.input({
    currentTarget: { dataset: { key: "recipient" } },
    detail: { value: "测试收货人" },
  });
  page.region({ detail: { value: ["广东省", "佛山市", "南海区"] } });
  await tap("save");
  assert.equal(calls[0].url, "/addresses");
  assert.equal(calls[0].method, "POST");
  assert.equal(calls[0].data.recipient, "测试收货人");
  assert.equal(calls[0].data.district, "南海区");
  assert.ok(nodes().some((node) => node.attr?.bindtap === "add"));
});

test("点击已有地址的编辑按钮后显示原值，保存走更新接口", async () => {
  const { page, calls, nodes, tap } = runtime();
  page.setData({
    items: [
      {
        id: 7,
        recipient: "原收货人",
        phone: "13800138000",
        province: "广东省",
        city: "佛山市",
        district: "南海区",
        detail: "测试地址",
        is_default: 1,
      },
    ],
  });
  await tap("edit", 7);
  assert.ok(
    nodes().some(
      (node) => node.tag === "wx-input" && node.attr.value === "原收货人",
    ),
  );
  await tap("save");
  assert.equal(calls[0].url, "/addresses/7");
  assert.equal(calls[0].method, "PUT");
});

test("初始不显示错误；保存失败时保留表单并显示错误", async () => {
  const { page, nodes, tap } = runtime({ saveFails: true });
  assert.ok(
    !nodes().some((node) => node.attr?.class === "error"),
    "无错误时不渲染错误区域",
  );
  await tap("add");
  await tap("save");
  assert.equal(page.data.error, "地址保存失败");
  assert.equal(page.data.busy, false);
  assert.ok(
    nodes().some(
      (node) =>
        node.attr?.class === "error" && node.children.includes("地址保存失败"),
    ),
  );
  assert.ok(nodes().some((node) => node.attr?.bindtap === "save"));
});
