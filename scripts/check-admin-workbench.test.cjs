const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");
const vue = require("vue");

// 执行真实组件的读取、清理及账号监听，保留Vue同步监听语义，只替换网络。
function runtime() {
  const source = fs
    .readFileSync(
      path.join(__dirname, "../web/components/AdminView.vue"),
      "utf8",
    )
    .split("</script>")[0]
    .replace(/^<script[^>]*>/, "");
  const ast = ts.createSourceFile(
    "AdminView.ts",
    source,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TS,
  );
  const selected = ast.statements
    .filter((statement) => {
      if (ts.isFunctionDeclaration(statement))
        return ["clearTodos", "loadTodos"].includes(statement.name?.text);
      if (ts.isVariableStatement(statement))
        return statement.declarationList.declarations.some(
          (item) => item.name.getText(ast) === "todoRevision",
        );
      return (
        ts.isExpressionStatement(statement) &&
        statement.getText(ast).startsWith("watch(") &&
        statement.getText(ast).includes("clearTodos")
      );
    })
    .map((statement) => statement.getText(ast))
    .join("\n");
  const pending = [];
  class ApiError extends Error {
    constructor(status) {
      super("会话失效");
      this.status = status;
    }
  }
  const context = {
    me: vue.ref({ id: 1 }),
    todos: vue.ref([]),
    todosAsOf: vue.ref(""),
    todosLoading: vue.ref(false),
    todosError: vue.ref(""),
    watch: vue.watch,
    ApiError,
    api: () =>
      new Promise((resolve, reject) => pending.push({ resolve, reject })),
  };
  context.report = () => {
    context.me.value = null;
  };
  const scope = vue.effectScope();
  scope.run(() =>
    vm.runInNewContext(
      ts.transpileModule(selected, {
        compilerOptions: { target: ts.ScriptTarget.ES2022 },
      }).outputText + ";this.load=loadTodos;",
      context,
    ),
  );
  return { context, pending, stop: () => scope.stop() };
}

test("换号后旧成功响应不能覆盖新账号待办", async () => {
  const r = runtime();
  try {
    const old = r.context.load();
    r.context.me.value = { id: 2 };
    const current = r.context.load();
    r.pending[1].resolve({ asOf: "current", items: [] });
    await current;
    r.pending[0].resolve({
      asOf: "old",
      items: [{ key: "pending_shipping", count: 37 }],
    });
    await old;
    assert.equal(r.context.todosAsOf.value, "current");
    assert.equal(r.context.todos.value.length, 0);
  } finally {
    r.stop();
  }
});

test("退出立即清空数据，旧失败响应不能污染新账号加载状态", async () => {
  const r = runtime();
  try {
    r.context.todos.value = [{ count: 37 }];
    const old = r.context.load();
    r.context.me.value = null;
    assert.equal(r.context.todos.value.length, 0);
    r.context.me.value = { id: 2 };
    const current = r.context.load();
    r.pending[0].reject(new Error("旧请求失败"));
    await old;
    assert.equal(r.context.todosError.value, "");
    assert.equal(r.context.todosLoading.value, true);
    r.pending[1].resolve({ asOf: "current", items: [] });
    await current;
    assert.equal(r.context.todosLoading.value, false);
  } finally {
    r.stop();
  }
});

test("同账号后发刷新优先，过期会话清理当前待办", async () => {
  const r = runtime();
  try {
    const old = r.context.load();
    const current = r.context.load();
    r.pending[1].resolve({ asOf: "current", items: [{ count: 2 }] });
    await current;
    r.pending[0].resolve({ asOf: "old", items: [{ count: 37 }] });
    await old;
    assert.equal(r.context.todos.value[0].count, 2);
    const expired = r.context.load();
    r.pending[2].reject(new r.context.ApiError(401));
    await expired;
    assert.equal(r.context.me.value, null);
    assert.equal(r.context.todos.value.length, 0);
  } finally {
    r.stop();
  }
});
