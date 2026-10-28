const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");
const vue = require("vue");
const { parse, compileScript } = require("@vue/compiler-sfc");
function setup(name, props = {}, request = async () => ({})) {
  const filename = path.resolve(
    __dirname,
    "../web/components/" + name + ".vue",
  );
  const script = compileScript(
    parse(fs.readFileSync(filename, "utf8")).descriptor,
    { id: "product-search" },
  );
  const output = ts.transpileModule(script.content, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText;
  const module = { exports: {} },
    events = [],
    cleanup = [];
  const scope = vue.effectScope();
  vm.runInNewContext(
    output,
    {
      module,
      exports: module.exports,
      Error,
      AbortController,
      URLSearchParams,
      setTimeout,
      clearTimeout,
      crypto: require("node:crypto").webcrypto,
      require(id) {
        if (id === "vue")
          return {
            ...vue,
            onMounted() {},
            onBeforeUnmount(fn) {
              cleanup.push(fn);
            },
          };
        if (id === "ant-design-vue")
          return { Radio: { Group: {}, Button: {} } };
        if (id === "../client") return { api: request };
        if (id === "../admin-user-names")
          return {
            useAdminUserNames: () => ({ label: (id) => `测试用户（#${id}）` }),
          };
        if (id === "../content-client")
          return {
            contentApi: {
              save: (id, body) => request("/posts/" + id, { body }),
            },
          };
        if (id === "../useAdminList")
          return {
            useAdminList: () => ({
              rows: vue.ref([]),
              total: vue.ref(0),
              pagination: {},
              loading: vue.ref(false),
              load: async () => [],
            }),
          };
        return {};
      },
    },
    { filename },
  );
  const state = scope.run(() =>
    module.exports.default.setup(props, {
      expose() {},
      emit: (event, ...args) => events.push([event, ...args]),
    }),
  );
  return {
    state,
    events,
    stop() {
      cleanup.forEach((fn) => fn());
      scope.stop();
    },
  };
}
const row = (
  id,
  label = `客户${id} · 138****0000 · #${id} · 普通顾客`,
  identity = "D",
) => ({ id, label, identity });
const result = (items, total = items.length, page = 1) => ({
  items,
  total,
  page,
});
const settle = async () => {
  await Promise.resolve();
  await vue.nextTick();
  await Promise.resolve();
};
function picker(props = {}, api = async () => result([])) {
  const input = vue.reactive({
    modelValue: "",
    identity: "",
    disabledIds: [],
    ...props,
  });
  return { ...setup("AdminUserSelect", input, api), props: input };
}
test("用户选择服务端翻页，支持搜索首屏以外用户并保留已选名称", async () => {
  const calls = [];
  const p = picker({}, async (url) => {
    const q = new URL("http://local" + url).searchParams;
    calls.push(q);
    if (q.get("q")) return result([row(88, "远页客户 · #88")]);
    return q.get("page") === "2"
      ? result([row(21)], 21, 2)
      : result(
          Array.from({ length: 20 }, (_, i) => row(i + 1)),
          21,
        );
  });
  await p.state.load();
  await p.state.load(true);
  assert.equal(p.state.rows.value.length, 21);
  p.state.query.value = "远页客户";
  await p.state.load();
  p.state.choose("88");
  p.props.modelValue = "88";
  await settle();
  p.state.query.value = "";
  await p.state.load();
  assert.equal(
    p.state.options.value.find((o) => o.value === "88").label,
    "远页客户 · #88",
  );
  assert.equal(p.events[0][1], "88");
  p.state.choose(undefined);
  assert.equal(p.events.at(-1)[1], "");
  assert.equal(calls[1].get("page"), "2");
  p.stop();
});
test("初始编号独立回显，归属身份限制和自选禁用同时生效", async () => {
  const p = picker(
    { modelValue: "88", identity: "B", disabledIds: ["88"] },
    async (url) => {
      const q = new URL("http://local" + url).searchParams;
      assert.equal(q.get("identity"), "B");
      assert.equal(q.get("id"), "88");
      return result([row(88, "19800店主 · #88", "B")]);
    },
  );
  await settle();
  assert.equal(p.state.options.value[0].disabled, true);
  assert.equal(p.state.validSelection.value, false);
  p.state.choose("88");
  assert.equal(p.events.length, 0);
  p.stop();
});
test("搜索防抖期间迟到响应和卸载后的响应不覆盖新状态", async () => {
  const pending = [];
  const p = picker(
    {},
    (url) => new Promise((resolve) => pending.push({ url, resolve })),
  );
  const first = p.state.load();
  p.state.search("新用户");
  pending[0].resolve(result([row(1)]));
  await first;
  assert.equal(p.state.rows.value.length, 0);
  await new Promise((resolve) => setTimeout(resolve, 280));
  pending[1].resolve(result([row(2)]));
  await settle();
  assert.equal(p.state.rows.value[0].id, 2);
  const last = p.state.load();
  p.stop();
  pending[2].resolve(result([row(3)]));
  await last;
  assert.equal(p.state.rows.value[0].id, 2);
});
test("切换身份筛选清除原候选，失败可重试且不会误报无结果", async () => {
  let fail = false;
  const calls = [];
  const p = picker({}, async (url) => {
    calls.push(url);
    if (fail) throw Error("网络异常");
    return result([row(2)]);
  });
  await p.state.load();
  p.props.identity = "B";
  await settle();
  assert.equal(p.state.rows.value.length, 0);
  fail = true;
  await p.state.load();
  assert.equal(p.state.error.value, "网络异常");
  fail = false;
  await p.state.load();
  assert.equal(p.state.error.value, "");
  assert.ok(calls.at(-1).includes("identity=B"));
  p.stop();
});
test("切换退款申请人立即清除原钱包、收款和受理表单", async () => {
  const p = setup("CashRefundOperations", {}, async () => ({}));
  p.state.userId.value = "1";
  p.state.wallet.value = { availableCents: 10000 };
  p.state.receipts.value = [{ id: 8 }];
  p.state.receiptId.value = 8;
  p.state.creating.value = true;
  p.state.userId.value = "2";
  assert.equal(p.state.wallet.value, null);
  assert.equal(p.state.receipts.value.length, 0);
  assert.equal(p.state.receiptId.value, 0);
  assert.equal(p.state.creating.value, false);
  p.stop();
});
test("切换寄存用户清除原地址、寄存批次与归属人，不串用旧选择", () => {
  const p = setup("StorageOperations", {
    staff: { permissions: ["orders:manage"] },
  });
  p.state.userId.value = "1";
  p.state.addressId.value = "3";
  p.state.manualItems.value = [{ lotId: 9, quantity: 2 }];
  p.state.principal.value = "8";
  p.state.userId.value = "2";
  assert.equal(p.state.addressId.value, "");
  assert.equal(p.state.principal.value, "");
  assert.equal(p.state.manualItems.value[0].lotId, 0);
  p.stop();
});
test("资格办理仍向原接口提交数值归属编号", async () => {
  const calls = [];
  const p = setup("QualificationOperations", {}, async (url, options) =>
    calls.push({ url, options }),
  );
  p.state.action.value = "identity";
  Object.assign(p.state.form.value, {
    userId: "88",
    identity: "C",
    principalId: "21",
    reason: "核实",
    evidence: "资格记录",
  });
  await p.state.submit();
  assert.equal(calls[0].url, "/admin/users/88/identity");
  assert.equal(calls[0].options.body.principalId, 21);
  p.stop();
});
test("切换资格办理用户清除上一人的地址、归属、邀请和确认", () => {
  const p = setup("QualificationOperations");
  Object.assign(p.state.form.value, {
    userId: "1",
    addressId: "3",
    principalId: "8",
    inviterId: "9",
    confirmed: true,
  });
  p.state.form.value.userId = "2";
  for (const field of ["addressId", "principalId", "inviterId"])
    assert.equal(p.state.form.value[field], "");
  assert.equal(p.state.form.value.confirmed, false);
  p.stop();
});
