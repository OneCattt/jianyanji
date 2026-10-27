const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");
const vue = require("vue");
const { parse, compileScript } = require("@vue/compiler-sfc");

// 执行真实商品编辑组件，只替换网络与列表加载，检查最终保存请求。
function editor() {
  const filename = path.resolve(
    __dirname,
    "../web/components/BusinessSettings.vue",
  );
  const { descriptor } = parse(fs.readFileSync(filename, "utf8"));
  const script = compileScript(descriptor, { id: "product-stock-regression" });
  const output = ts.transpileModule(script.content, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText;
  const calls = [];
  const module = { exports: {} };
  vm.runInNewContext(
    output,
    {
      module,
      exports: module.exports,
      require(name) {
        if (name === "vue") return { ...vue, onMounted() {} };
        if (name === "../client")
          return {
            async api(url, options) {
              if (options) calls.push({ url, ...options });
              return [];
            },
          };
        if (name === "../useAdminList")
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
  const component = module.exports.default.setup(
    { section: "products" },
    { expose() {} },
  );
  return { component, calls };
}

const product = () => ({
  id: 1,
  categoryId: 1,
  name: "精华",
  subtitle: "",
  description: "说明",
  image: "/assets/product.png",
  detailImage: "/assets/product.png",
  active: true,
  featured: false,
  skus: [
    {
      id: 2,
      name: "30ml",
      unit: "瓶",
      retailCents: 10000,
      storeCents: 7000,
      principalCents: 3500,
      productType: "regular",
      welfareCents: null,
      stock: 10,
      reserved: 0,
      active: true,
    },
  ],
});

test("只修改商品资料时不提交库存，避免旧编辑页覆盖销售后的库存", async () => {
  const { component, calls } = editor();
  await component.edit(product());
  component.form.value.name = "新的商品名称";
  await component.saveProduct();
  assert.equal(component.error.value, "");
  assert.equal(calls.length, 1);
  const sku = calls[0].body.skus[0];
  assert.equal(Object.hasOwn(sku, "stock"), false);
  assert.equal(Object.hasOwn(sku, "expectedStock"), false);
});

test("主动调整已有规格库存时提交新值和编辑前原值", async () => {
  const { component, calls } = editor();
  await component.edit(product());
  component.form.value.skus[0].stock = 12;
  await component.saveProduct();
  assert.equal(component.error.value, "");
  assert.equal(calls[0].body.skus[0].stock, 12);
  assert.equal(calls[0].body.skus[0].expectedStock, 10);
});

test("新建商品规格继续提交初始库存", async () => {
  const { component, calls } = editor();
  await component.edit();
  component.form.value.skus[0].stock = 8;
  await component.saveProduct();
  assert.equal(calls[0].body.skus[0].stock, 8);
  assert.equal(Object.hasOwn(calls[0].body.skus[0], "expectedStock"), false);
});
