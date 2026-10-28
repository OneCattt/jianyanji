const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const ts = require("typescript");
const vue = require("vue");
const { parse, compileScript } = require("@vue/compiler-sfc");
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
function loadNames(read) {
  const module = { exports: {} };
  const output = ts.transpileModule(
    fs.readFileSync("web/admin-user-names.ts", "utf8"),
    {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
      },
    },
  ).outputText;
  vm.runInNewContext(output, {
    module,
    exports: module.exports,
    require: (id) => (id === "vue" ? vue : { api: read }),
    setTimeout,
    clearTimeout,
    AbortController,
    Map,
    Set,
  });
  return module.exports.createAdminUserNames(read);
}
test("100个一批合并重复名称，空昵称、缺失资料和现成昵称明确展示", async () => {
  const calls = [];
  const names = loadNames(async (ids) => {
    calls.push([...ids]);
    return {
      items: ids
        .filter((id) => id !== "3")
        .map((id) => ({
          id: Number(id),
          nickname: id === "2" ? "" : `客户${id}`,
        })),
    };
  });
  for (let i = 1; i <= 205; i++) {
    names.label(i);
    names.label(i);
  }
  assert.equal(names.label(999, "原有昵称"), "原有昵称（#999）");
  await pause(30);
  assert.deepEqual(
    calls.map((call) => call.length),
    [100, 100, 5],
  );
  assert.equal(new Set(calls.flat()).size, 205);
  assert.equal(names.label(1), "客户1（#1）");
  assert.equal(names.label(2), "未填写昵称（#2）");
  assert.equal(names.label(3), "资料不可用（#3）");
  names.reset();
});
test("名称失败可重试，登出后迟到响应不能污染下一会话", async () => {
  let resolveOld,
    fail = true;
  const names = loadNames(async (ids) => {
    if (ids[0] === "1") return new Promise((resolve) => (resolveOld = resolve));
    if (fail) throw new Error("模拟失败");
    return { items: [{ id: 2, nickname: "恢复成功" }] };
  });
  names.label(1);
  await pause(5);
  names.reset();
  resolveOld({ items: [{ id: 1, nickname: "旧管理员数据" }] });
  await pause(5);
  assert(!names.label(1).includes("旧管理员数据"));
  names.reset();
  names.label(2);
  await pause(5);
  assert(names.failed(2));
  fail = false;
  names.retry(2);
  await pause(5);
  assert.equal(names.label(2), "恢复成功（#2）");
  names.reset();
});
function addressSetup(request) {
  const descriptor = parse(
    fs.readFileSync("web/components/AdminAddressSelect.vue", "utf8"),
  ).descriptor;
  const content = compileScript(descriptor, {
    id: "address-select-test",
  }).content;
  const output = ts.transpileModule(content, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText;
  const module = { exports: {} },
    events = [],
    props = vue.reactive({ modelValue: "", userId: "1", required: true });
  vm.runInNewContext(output, {
    module,
    exports: module.exports,
    require: (id) =>
      id === "vue"
        ? { ...vue, useId: () => "address-test" }
        : id === "../client"
          ? { api: request }
          : { Select: {} },
    setTimeout,
    clearTimeout,
    AbortController,
    URLSearchParams,
  });
  const scope = vue.effectScope();
  const state = scope.run(() =>
    module.exports.default.setup(props, {
      expose() {},
      emit: (event, value) => events.push([event, value]),
    }),
  );
  return { state, props, events, stop: () => scope.stop() };
}
test("地址仅查询当前用户并分页，选择地址提交原ID，切用户立即清空且拒绝旧响应", async () => {
  const calls = [];
  let late;
  const setup = addressSetup(async (path) => {
    calls.push(path);
    if (path.includes("/users/2/"))
      return new Promise((resolve) => (late = resolve));
    const id = path.includes("page=2") ? 21 : 1;
    return {
      items: [
        {
          id,
          recipient: "收货人" + id,
          phone: "13800000000",
          province: "广东",
          city: "广州",
          district: "测试区",
          detail: "本地地址",
          is_default: 1,
        },
      ],
      total: 21,
    };
  });
  await pause(5);
  setup.state.choose("1");
  assert.deepEqual(setup.events.at(-1), ["update:modelValue", "1"]);
  setup.props.modelValue = "1";
  assert(setup.state.valid.value);
  assert(setup.state.options.value[0].label.includes("收货人1"));
  setup.state.move(1);
  await pause(5);
  assert(calls.at(-1).includes("page=2"));
  setup.state.choose("21");
  assert.deepEqual(setup.events.at(-1), ["update:modelValue", "21"]);
  setup.props.userId = "2";
  assert.equal(setup.state.selected.value, undefined);
  assert.deepEqual(setup.events.at(-1), ["update:modelValue", ""]);
  setup.props.userId = "3";
  await pause(5);
  late({ items: [{ id: 99, recipient: "迟到的旧用户地址" }], total: 1 });
  await pause(5);
  assert(
    !setup.state.options.value.some((row) => row.label.includes("旧用户")),
  );
  assert(calls.at(-1).includes("/users/3/"));
  setup.stop();
});
