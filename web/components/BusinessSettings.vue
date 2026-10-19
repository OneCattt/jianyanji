<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from "vue";
import { api } from "../client";
const props = defineProps<{ section: string }>();
const error = ref(""),
  notice = ref(""),
  busy = ref(false),
  reason = ref("");
const loading = ref(false),
  loaded = ref(false),
  loadFailed = ref(false);
const productNameInput = ref<HTMLInputElement | null>(null);
const content = ref({
  brandName: "清欣简妍肌",
  introduction: "",
  heroTitle: "让肌肤，回到\n它本来的光",
  heroSubtitle: "专业调肤方案 · 精准护理 · 长效陪伴",
  heroImage: "/assets/figma/Home-imgImage.png",
  customerService: "",
  purchaseNotes: "",
  companyName: "",
  bankName: "",
  bankAccount: "",
  transferNotes: "",
});
const contentLabels: Record<string, string> = {
  brandName: "品牌名称",
  introduction: "品牌介绍",
  heroTitle: "首页主标题",
  heroSubtitle: "首页副标题",
  heroImage: "首页图片地址",
  customerService: "客服联系方式",
  purchaseNotes: "购买说明",
  companyName: "公司收款户名",
  bankName: "开户银行",
  bankAccount: "公司收款账号",
  transferNotes: "转款说明",
};
const contentGroups = [
  {
    title: "品牌与首页",
    description: "维护品牌介绍与购买端首页展示内容。",
    keys: [
      "brandName",
      "introduction",
      "heroTitle",
      "heroSubtitle",
      "heroImage",
    ],
  },
  {
    title: "客服与购买说明",
    description: "让购买者清楚了解咨询方式与购买须知。",
    keys: ["customerService", "purchaseNotes"],
  },
  {
    title: "公司收款资料",
    description:
      "收款资料请使用公司真实信息；未填写时购买端会显示“尚未配置”。展示说明不代表到账确认。",
    keys: ["companyName", "bankName", "bankAccount", "transferNotes"],
  },
] as const;
interface Sku {
  id?: number;
  name: string;
  unit: string;
  retail: string;
  store: string;
  principal: string;
  stock: number;
  active: boolean;
  reserved?: number;
}
interface Product {
  id?: number;
  categoryId: number;
  name: string;
  subtitle: string;
  description: string;
  image: string;
  detailImage: string;
  active: boolean;
  featured: boolean;
  skus: Sku[];
}
interface ProductRecord extends Omit<Product, "skus"> {
  id: number;
  skus: (Omit<Sku, "retail" | "store" | "principal"> & {
    retailCents: number | null;
    storeCents: number | null;
    principalCents: number | null;
    reserved: number;
  })[];
}
const query = ref(""),
  statusFilter = ref(""),
  categoryFilter = ref(0);
const categories = ref<
    { id: number; name: string; sort: number; active: number }[]
  >([]),
  products = ref<ProductRecord[]>([]),
  editing = ref(false);
const categoryNames = computed(
  () => new Map(categories.value.map((c) => [c.id, c.name])),
);
const activeCount = computed(
  () => products.value.filter((p) => p.active).length,
);
const hasFilters = computed(() =>
  Boolean(query.value || statusFilter.value || categoryFilter.value),
);
const filteredProducts = computed(() => {
  const term = query.value.trim().toLocaleLowerCase();
  return products.value.filter(
    (p) =>
      (!statusFilter.value ||
        (statusFilter.value === "active" ? p.active : !p.active)) &&
      (!categoryFilter.value || p.categoryId === categoryFilter.value) &&
      (!term ||
        [
          p.name,
          p.subtitle,
          categoryNames.value.get(p.categoryId) || "",
          ...p.skus.map((s) => s.name),
        ].some((value) => value.toLocaleLowerCase().includes(term))),
  );
});
function resetFilters() {
  query.value = "";
  statusFilter.value = "";
  categoryFilter.value = 0;
}
const blank = (): Product => ({
  categoryId: 0,
  name: "",
  subtitle: "",
  description: "",
  image: "",
  detailImage: "",
  active: false,
  featured: false,
  skus: [
    {
      name: "标准装",
      unit: "件",
      retail: "",
      store: "",
      principal: "",
      stock: 0,
      active: true,
    },
  ],
});
const form = ref<Product>(blank()),
  category = ref({ id: 0, name: "", sort: 0, active: true });
const amount = (value: number | null) =>
  value === null ? "" : (value / 100).toFixed(2);
function toCents(v: string) {
  if (v === "") return null;
  if (!/^\d+(\.\d{1,2})?$/.test(v))
    throw new Error("价格需为正金额，最多两位小数");
  const [a, b = ""] = v.split(".");
  return Number(a) * 100 + Number(b.padEnd(2, "0"));
}
async function load() {
  loading.value = true;
  loadFailed.value = false;
  error.value = "";
  try {
    if (props.section === "content") {
      const v = await api<typeof content.value | null>("/admin/content");
      if (v) content.value = v;
    } else {
      [categories.value, products.value] = await Promise.all([
        api<typeof categories.value>("/admin/categories"),
        api<ProductRecord[]>("/admin/products"),
      ]);
    }
    loaded.value = true;
  } catch (e) {
    error.value = (e as Error).message;
    loadFailed.value = true;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
async function act(work: () => Promise<void>) {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await work();
    notice.value = "保存成功";
    reason.value = "";
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function edit(p?: ProductRecord) {
  form.value = p
    ? {
        ...p,
        skus: p.skus.map((s) => ({
          ...s,
          retail: amount(s.retailCents),
          store: amount(s.storeCents),
          principal: amount(s.principalCents),
        })),
      }
    : blank();
  editing.value = true;
  await nextTick();
  productNameInput.value?.focus({ preventScroll: true });
  productNameInput.value?.scrollIntoView({ block: "center" });
}
function saveProduct() {
  return act(async () => {
    const p = form.value;
    await api("/admin/products" + (p.id ? "/" + p.id : ""), {
      method: p.id ? "PUT" : "POST",
      body: {
        categoryId: Number(p.categoryId),
        name: p.name,
        subtitle: p.subtitle,
        description: p.description,
        image: p.image,
        detailImage: p.detailImage,
        active: p.active,
        featured: p.featured,
        reason: reason.value,
        skus: p.skus.map((s) => ({
          ...(s.id ? { id: s.id } : {}),
          name: s.name,
          unit: s.unit,
          retailCents: toCents(s.retail),
          storeCents: toCents(s.store),
          principalCents: toCents(s.principal),
          stock: Number(s.stock),
          active: s.active,
        })),
      },
    });
    editing.value = false;
  });
}
</script>
<template>
  <section class="business-settings" :aria-busy="loading || busy">
    <div v-if="error" class="message error feedback" role="alert">
      <span>{{ error }}</span>
      <button
        v-if="loadFailed"
        type="button"
        :disabled="loading || busy"
        @click="load"
      >
        重新加载
      </button>
    </div>
    <p v-if="notice" class="message success" role="status">{{ notice }}</p>
    <p v-if="loading" class="loading-note" role="status">
      正在加载{{ section === "content" ? "品牌资料" : "商品与分类" }}…
    </p>

    <form
      v-if="section === 'content'"
      class="content-layout"
      @submit.prevent="
        act(async () => {
          await api('/admin/content', {
            method: 'PUT',
            body: { ...content, reason },
          });
        })
      "
    >
      <fieldset
        class="form-controls content-sections"
        :disabled="busy || loading || !loaded"
      >
        <section
          v-for="group in contentGroups"
          :key="group.title"
          class="surface content-group"
        >
          <header class="panel-heading">
            <div>
              <h3>{{ group.title }}</h3>
              <p>{{ group.description }}</p>
            </div>
          </header>
          <div class="field-grid">
            <label
              v-for="key in group.keys"
              :key="key"
              :class="{
                'full-field': [
                  'introduction',
                  'heroTitle',
                  'heroImage',
                  'purchaseNotes',
                  'bankAccount',
                  'transferNotes',
                ].includes(key),
              }"
            >
              {{ contentLabels[key] }}
              <textarea
                v-if="
                  [
                    'introduction',
                    'heroTitle',
                    'purchaseNotes',
                    'transferNotes',
                  ].includes(key)
                "
                v-model="content[key]"
                :rows="key === 'heroTitle' ? 2 : 3"
                :required="['introduction', 'heroTitle'].includes(key)"
              />
              <input
                v-else
                v-model="content[key]"
                :required="
                  ['brandName', 'heroSubtitle', 'heroImage'].includes(key)
                "
              />
              <img
                v-if="key === 'heroImage' && content.heroImage"
                :src="content.heroImage"
                alt="首页图片预览"
                class="brand-image"
              />
            </label>
          </div>
        </section>
        <footer class="surface save-bar">
          <label
            >修改原因<input
              v-model="reason"
              required
              maxlength="200"
              placeholder="说明本次调整的内容"
          /></label>
          <button class="primary" :disabled="busy">
            {{ busy ? "保存中…" : "保存品牌资料" }}
          </button>
        </footer>
      </fieldset>
    </form>

    <template v-else>
      <header class="catalog-heading">
        <div>
          <h3>
            商品与规格 <span class="count">{{ products.length }}</span>
          </h3>
          <p>
            已上架 {{ activeCount }} 件<span class="separator">/</span>已下架
            {{ products.length - activeCount }} 件<span class="separator"
              >·</span
            >维护商品信息、身份价格与库存
          </p>
        </div>
        <button
          class="primary"
          type="button"
          :disabled="busy || loading || !loaded"
          @click="edit()"
        >
          <span aria-hidden="true">＋</span> 新增商品
        </button>
      </header>

      <details class="surface category-panel" open>
        <summary>
          <span
            >商品分类 <span class="count">{{ categories.length }}</span></span
          ><span class="summary-hint">分类名称、排序与启停</span>
        </summary>
        <div class="category-content">
          <div class="category-list" aria-label="选择分类进行编辑">
            <button
              v-for="c in categories"
              :key="c.id"
              class="category-chip"
              type="button"
              :aria-pressed="category.id === c.id"
              :disabled="busy || loading"
              @click="category = { ...c, active: Boolean(c.active) }"
            >
              {{ c.name }} <span>{{ c.active ? "启用" : "停用" }}</span>
            </button>
            <p v-if="!categories.length && loaded" class="muted-note">
              尚未配置分类，请先新增分类，再配置商品。
            </p>
          </div>
          <form
            @submit.prevent="
              act(async () => {
                await api(
                  '/admin/categories' + (category.id ? '/' + category.id : ''),
                  {
                    method: category.id ? 'PUT' : 'POST',
                    body: {
                      name: category.name,
                      sort: Number(category.sort),
                      active: category.active,
                      reason,
                    },
                  },
                );
                category = { id: 0, name: '', sort: 0, active: true };
              })
            "
          >
            <fieldset
              class="form-controls category-form"
              :disabled="busy || loading || !loaded"
            >
              <label
                >分类名称<input v-model="category.name" required maxlength="40"
              /></label>
              <label
                >排序<input
                  v-model.number="category.sort"
                  type="number"
                  min="0"
                  max="9999"
                  required
              /></label>
              <label class="check-label"
                ><input v-model="category.active" type="checkbox" />启用</label
              >
              <label class="category-reason"
                >修改原因<input v-model="reason" required maxlength="200"
              /></label>
              <div class="button-row">
                <button class="secondary" :disabled="busy">
                  {{ category.id ? "保存分类" : "新增分类" }}</button
                ><button
                  v-if="category.id"
                  type="button"
                  @click="category = { id: 0, name: '', sort: 0, active: true }"
                >
                  取消编辑
                </button>
              </div>
            </fieldset>
          </form>
        </div>
      </details>

      <form
        v-if="editing"
        class="surface product-editor"
        @submit.prevent="saveProduct"
      >
        <header class="panel-heading editor-heading">
          <div>
            <h3>{{ form.id ? "编辑商品" : "新增商品" }}</h3>
            <p>
              {{
                form.id
                  ? form.name
                  : "填写商品与规格信息，勾选「上架」后保存即可上架。"
              }}
            </p>
          </div>
          <button type="button" :disabled="busy" @click="editing = false">
            取消
          </button>
        </header>
        <fieldset class="form-controls" :disabled="busy || loading">
          <section class="editor-section">
            <h4>基本信息</h4>
            <div class="field-grid">
              <label
                >商品名<input
                  ref="productNameInput"
                  v-model="form.name"
                  required
                  maxlength="80"
              /></label>
              <label
                >分类<select v-model.number="form.categoryId" required>
                  <option :value="0" disabled>请选择分类</option>
                  <option v-for="c in categories" :key="c.id" :value="c.id">
                    {{ c.name }}{{ c.active ? "" : "（停用）" }}
                  </option>
                </select></label
              >
              <p v-if="!categories.length" class="field-help full-field">
                请先在上方「商品分类」中新增分类。
              </p>
              <label class="full-field"
                >副标题<input v-model="form.subtitle" maxlength="160"
              /></label>
              <label class="full-field"
                >商品说明<textarea
                  v-model="form.description"
                  required
                  rows="3"
                />
              </label>
            </div>
            <div class="publishing-options">
              <label class="check-label"
                ><input v-model="form.active" type="checkbox" />上架</label
              >
              <label class="check-label"
                ><input
                  v-model="form.featured"
                  type="checkbox"
                />首页臻选</label
              >
              <span>{{
                form.active
                  ? "保存后商品为已上架状态"
                  : "保存后商品为已下架状态"
              }}</span>
            </div>
          </section>
          <section class="editor-section">
            <h4>商品图片</h4>
            <div class="field-grid">
              <div class="image-field">
                <div class="image-placeholder image-preview">
                  <img
                    v-if="form.image"
                    :src="form.image"
                    alt="列表图片预览"
                  /><span v-else>列表图</span>
                </div>
                <label
                  >列表图片地址<input
                    v-model="form.image"
                    required
                    placeholder="https://… 或 /assets/…"
                /></label>
              </div>
              <div class="image-field">
                <div class="image-placeholder image-preview">
                  <img
                    v-if="form.detailImage"
                    :src="form.detailImage"
                    alt="详情图片预览"
                  /><span v-else>详情图</span>
                </div>
                <label
                  >详情图片地址<input
                    v-model="form.detailImage"
                    required
                    placeholder="https://… 或 /assets/…"
                /></label>
              </div>
            </div>
          </section>
          <section class="editor-section">
            <div class="panel-heading">
              <div>
                <h4>
                  规格与价格 <span class="count">{{ form.skus.length }}</span>
                </h4>
                <p>价格留空表示该身份暂不可购买。主理人价不得高于店主价。</p>
              </div>
              <button
                class="secondary"
                type="button"
                @click="
                  form.skus.push({
                    name: '',
                    unit: '件',
                    retail: '',
                    store: '',
                    principal: '',
                    stock: 0,
                    active: true,
                  })
                "
              >
                添加规格
              </button>
            </div>
            <div class="sku-list">
              <fieldset v-for="(s, i) in form.skus" :key="i" class="sku-card">
                <legend>规格 {{ i + 1 }}</legend>
                <div class="sku-form">
                  <label>名称<input v-model="s.name" required /></label>
                  <label>单位<input v-model="s.unit" required /></label>
                  <label
                    >零售价（元）<input
                      v-model="s.retail"
                      inputmode="decimal"
                      placeholder="暂不可购买"
                  /></label>
                  <label
                    >店主价（元）<input
                      v-model="s.store"
                      inputmode="decimal"
                      placeholder="暂不可购买"
                  /></label>
                  <label
                    >主理人价（元）<input
                      v-model="s.principal"
                      inputmode="decimal"
                      placeholder="暂不可购买"
                  /></label>
                  <label
                    >总库存<input
                      v-model.number="s.stock"
                      type="number"
                      :min="s.reserved || 0"
                      required
                  /></label>
                </div>
                <div class="sku-footer">
                  <label class="check-label"
                    ><input v-model="s.active" type="checkbox" />可售</label
                  ><span v-if="s.reserved" class="field-help"
                    >已占用 {{ s.reserved
                    }}{{ s.unit }}，总库存不得低于已占用库存。</span
                  ><button
                    v-if="form.skus.length > 1"
                    type="button"
                    @click="form.skus.splice(i, 1)"
                  >
                    移除规格
                  </button>
                </div>
              </fieldset>
            </div>
          </section>
          <footer class="save-bar editor-save">
            <label
              >修改原因<input
                v-model="reason"
                required
                maxlength="200"
                placeholder="说明本次商品、价格或库存调整"
            /></label>
            <div class="button-row">
              <button type="button" @click="editing = false">取消</button
              ><button class="primary" :disabled="busy">
                {{ busy ? "保存中…" : "保存商品" }}
              </button>
            </div>
          </footer>
        </fieldset>
      </form>

      <section class="surface catalog-panel">
        <header class="catalog-toolbar">
          <div>
            <h3>商品列表</h3>
            <p>
              显示 {{ filteredProducts.length }} / {{ products.length }} 件商品
            </p>
          </div>
          <div class="catalog-filters">
            <label class="search-field"
              ><span class="sr-only">搜索商品</span
              ><input
                v-model="query"
                type="search"
                placeholder="搜索商品、规格或分类"
            /></label>
            <label
              ><span class="sr-only">按分类筛选</span
              ><select v-model.number="categoryFilter">
                <option :value="0">全部分类</option>
                <option v-for="c in categories" :key="c.id" :value="c.id">
                  {{ c.name }}
                </option>
              </select></label
            >
            <label
              ><span class="sr-only">按状态筛选</span
              ><select v-model="statusFilter">
                <option value="">全部状态</option>
                <option value="active">已上架</option>
                <option value="inactive">已下架</option>
              </select></label
            >
            <button v-if="hasFilters" type="button" @click="resetFilters">
              重置筛选
            </button>
          </div>
        </header>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">商品</th>
                <th scope="col">状态</th>
                <th scope="col">规格 / 可用库存</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="p in filteredProducts"
                :key="p.id"
                :class="{ 'editing-row': editing && form.id === p.id }"
              >
                <td>
                  <div class="product-cell">
                    <div class="product-thumbnail image-placeholder">
                      <img
                        v-if="p.image"
                        :src="p.image"
                        :alt="p.name"
                        loading="lazy"
                      /><span v-else>暂无图片</span>
                    </div>
                    <div class="product-copy">
                      <strong>{{ p.name }}</strong>
                      <p v-if="p.subtitle">{{ p.subtitle }}</p>
                      <span>{{ categoryNames.get(p.categoryId) || "—" }}</span
                      ><span v-if="p.featured" class="featured-label"
                        >首页臻选</span
                      >
                    </div>
                  </div>
                </td>
                <td>
                  <span
                    class="status-pill"
                    :class="{ 'is-active': p.active }"
                    >{{ p.active ? "已上架" : "已下架" }}</span
                  >
                </td>
                <td>
                  <div v-for="s in p.skus" :key="s.id" class="stock-line">
                    <span>{{ s.name }}</span
                    ><strong
                      >{{ s.stock - s.reserved
                      }}<small>{{ s.unit }}</small></strong
                    >
                  </div>
                </td>
                <td>
                  <button
                    class="edit-button"
                    type="button"
                    :disabled="busy || loading"
                    :aria-label="'编辑商品：' + p.name"
                    @click="edit(p)"
                  >
                    {{ editing && form.id === p.id ? "正在编辑" : "编辑商品" }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div
          v-if="!filteredProducts.length && !loading && loaded && !loadFailed"
          class="empty-state"
        >
          <span class="empty-symbol" aria-hidden="true">{{
            products.length ? "⌕" : "＋"
          }}</span>
          <h4>{{ products.length ? "没有符合条件的商品" : "尚未配置商品" }}</h4>
          <p>
            {{
              products.length
                ? "试试其他商品名称、分类或上架状态。"
                : "先配置商品分类，再添加图片、规格、价格与库存。"
            }}
          </p>
          <button
            v-if="products.length"
            class="secondary"
            type="button"
            @click="resetFilters"
          >
            重置筛选
          </button>
          <button
            v-else
            class="primary"
            type="button"
            :disabled="busy"
            @click="edit()"
          >
            新增商品
          </button>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.business-settings {
  display: grid;
  gap: 16px;
  min-width: 0;
  color: var(--admin-ink);
  font-size: 13px;
}
.business-settings :is(h3, h4, p) {
  margin: 0;
}
.business-settings h3 {
  font-size: 15px;
  font-weight: 600;
}
.business-settings h4 {
  font-size: 13px;
  font-weight: 600;
}
.business-settings p {
  line-height: 1.65;
}
.business-settings .surface {
  min-width: 0;
  margin: 0;
  padding: 0;
  background: white;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
.form-controls {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}
.business-settings label {
  display: grid;
  min-width: 0;
  gap: 6px;
}
.business-settings :is(input, select, textarea) {
  min-width: 0;
  width: 100%;
}
.business-settings textarea {
  resize: vertical;
}
.business-settings .check-label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  white-space: nowrap;
}
.business-settings .check-label input {
  width: 15px;
  height: 15px;
  margin: 0;
  padding: 0;
  accent-color: var(--admin-accent);
}
.business-settings .message {
  margin: 0;
}
.feedback,
.catalog-heading,
.panel-heading,
.catalog-toolbar,
.save-bar,
.sku-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.feedback,
.catalog-toolbar,
.sku-footer {
  flex-wrap: wrap;
}
.loading-note,
.field-help,
.muted-note,
.summary-hint {
  color: var(--admin-muted);
}
.panel-heading p,
.catalog-heading p,
.catalog-toolbar p {
  color: var(--admin-muted);
  font-size: 12px;
  margin-top: 5px;
}
.count {
  margin-left: 5px;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--admin-bg);
  color: var(--admin-muted);
  font-size: 12px;
  font-weight: 500;
}
.separator {
  margin: 0 8px;
  color: var(--admin-border);
}
.button-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.content-layout {
  max-width: 1000px;
}
.content-sections,
.sku-list {
  display: grid;
  gap: 16px;
}
.business-settings .content-group,
.editor-section {
  padding: 20px;
}
.content-group .panel-heading,
.editor-section > h4 {
  margin-bottom: 16px;
}
.field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 16px;
}
.full-field {
  grid-column: 1 / -1;
}
.brand-image {
  width: 160px;
  height: 100px;
  object-fit: cover;
  border-radius: 6px;
}
.business-settings .save-bar {
  padding: 16px 20px;
  align-items: end;
}
.save-bar > label {
  flex: 1;
}
.save-bar button {
  flex-shrink: 0;
}
.category-panel summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
  cursor: pointer;
  list-style: none;
  font-weight: 600;
}
.category-panel summary::-webkit-details-marker {
  display: none;
}
.category-panel summary::after {
  content: "＋";
  color: var(--admin-muted);
}
.category-panel[open] summary::after {
  content: "−";
}
.summary-hint {
  margin-left: auto;
  font-size: 12px;
  font-weight: 400;
}
.category-content {
  padding: 0 20px 18px;
}
.category-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
.business-settings .category-chip {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 7px 10px;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  background: white;
}
.category-chip span {
  color: var(--admin-muted);
  font-size: 11px;
}
.business-settings .category-chip[aria-pressed="true"] {
  border-color: var(--admin-accent);
  background: var(--admin-accent-soft);
  color: var(--admin-accent);
}
.category-form {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) 80px auto minmax(150px, 1.2fr) auto;
  gap: 12px;
  align-items: end;
  padding-top: 16px;
  border-top: 1px solid var(--admin-border);
}
.category-form .check-label {
  min-height: 38px;
}
.business-settings .product-editor {
  border-color: var(--admin-accent);
  overflow: hidden;
}
.editor-heading {
  padding: 18px 20px;
  background: var(--admin-accent-soft);
  border-bottom: 1px solid var(--admin-border);
}
.editor-heading h3,
.edit-button {
  color: var(--admin-accent);
}
.editor-heading > div {
  min-width: 0;
  overflow-wrap: anywhere;
}
.editor-section + .editor-section {
  border-top: 1px solid var(--admin-border);
}
.publishing-options {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px;
  padding: 12px 14px;
  margin-top: 16px;
  background: var(--admin-bg);
  border-radius: 6px;
}
.publishing-options > span {
  color: var(--admin-muted);
  font-size: 12px;
}
.image-field,
.product-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.image-field label {
  flex: 1;
}
.image-placeholder {
  display: grid;
  place-items: center;
  background: var(--admin-bg);
  color: var(--admin-muted);
  font-size: 11px;
}
.image-preview,
.product-thumbnail {
  flex-shrink: 0;
  width: 64px;
  height: 72px;
  border-radius: 6px;
  overflow: hidden;
}
.image-preview img,
.product-thumbnail img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.sku-list {
  margin-top: 16px;
}
.sku-card {
  min-width: 0;
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
.sku-card legend {
  padding: 0 6px;
  color: var(--admin-muted);
  font-size: 12px;
}
.sku-form {
  display: grid;
  grid-template-columns: 1.4fr 0.6fr repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.sku-footer {
  margin-top: 12px;
  font-size: 12px;
}
.sku-footer button {
  margin-left: auto;
  color: var(--admin-accent);
}
.editor-save {
  border-top: 1px solid var(--admin-border);
  background: var(--admin-bg);
}
.catalog-panel {
  overflow: hidden;
}
.catalog-toolbar {
  padding: 18px 20px;
}
.catalog-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.search-field {
  width: 225px;
}
.table-wrap {
  max-width: 100%;
  overflow: auto;
}
.table-wrap table {
  min-width: 660px;
}
.product-cell {
  min-width: 220px;
}
.product-thumbnail {
  width: 48px;
  height: 56px;
}
.product-copy {
  max-width: 320px;
  white-space: normal;
  overflow-wrap: anywhere;
}
.product-copy strong {
  display: block;
}
.product-copy p,
.product-copy > span {
  color: var(--admin-muted);
  font-size: 12px;
  margin-top: 3px;
}
.product-copy .featured-label {
  display: inline-block;
  margin-left: 8px;
  color: var(--admin-accent);
  font-size: 11px;
}
.status-pill {
  display: inline-flex;
  padding: 4px 8px;
  background: var(--admin-bg);
  color: var(--admin-muted);
  border-radius: 4px;
  font-size: 12px;
  white-space: nowrap;
}
.status-pill.is-active,
.editing-row {
  background: var(--admin-accent-soft);
  color: var(--admin-accent);
}
.stock-line {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  min-width: 130px;
  padding: 3px 0;
}
.stock-line strong,
.edit-button {
  white-space: nowrap;
}
.stock-line small {
  margin-left: 4px;
}
.empty-state {
  display: grid;
  justify-items: center;
  gap: 10px;
  padding: 32px 20px;
  text-align: center;
}
.empty-state p {
  color: var(--admin-muted);
}
.empty-symbol {
  color: var(--admin-accent);
  font-size: 25px;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
@media (max-width: 1180px) {
  .category-form {
    grid-template-columns: minmax(0, 1fr) 80px auto;
  }
  .category-reason {
    grid-column: span 2;
  }
  .sku-form {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 800px) {
  .catalog-filters {
    width: 100%;
  }
  .catalog-filters .search-field {
    flex: 1;
    min-width: 180px;
  }
}
@media (max-width: 540px) {
  .catalog-heading,
  .panel-heading {
    flex-wrap: wrap;
  }
  .field-grid,
  .category-form {
    grid-template-columns: 1fr;
  }
  .category-reason {
    grid-column: auto;
  }
  .summary-hint {
    display: none;
  }
  .sku-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .business-settings .save-bar {
    align-items: stretch;
    flex-direction: column;
  }
  .save-bar .button-row {
    justify-content: flex-end;
  }
  .business-settings .content-group,
  .editor-section {
    padding: 16px;
  }
}
</style>
