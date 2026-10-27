<script setup lang="ts">
import { useAdminList } from "../useAdminList";
import FeedbackToast from "./FeedbackToast.vue";
import ImageUploader from "./ImageUploader.vue";
import { imageUrl } from "../image-url";
import SkuPricingFields from "./SkuPricingFields.vue";
import { computed, nextTick, onMounted, ref } from "vue";
import { api } from "../client";
import AdminDialog from "./AdminDialog.vue";
import AdminForm from "./AdminForm.vue";
import AdminSelect from "./AdminSelect.vue";
const props = defineProps<{ section: string }>();
const error = ref(""),
  notice = ref(""),
  busy = ref(false),
  reason = ref("");
const loading = ref(false),
  loaded = ref(false),
  loadFailed = ref(false);
const imageUploads = ref<Record<string, boolean>>({});
const uploadingImage = computed(() =>
  Object.values(imageUploads.value).some(Boolean),
);
const productNameInput = ref<{
  focus: (options?: FocusOptions) => void;
} | null>(null);
const content = ref({
  brandName: "清欣简妍肌",
  introduction: "",
  heroTitle: "让肌肤，回到\n它本来的光",
  heroSubtitle: "专业调肤方案 · 精准护理 · 长效陪伴",
  heroImage: "/assets/figma/Home-imgImage.png",
  customerService: "",
  purchaseNotes: "",
  privacyOperator: "",
  privacyContact: "",
  companyName: "",
  bankName: "",
  bankAccount: "",
  transferNotes: "",
});
const contentForm = ref({ ...content.value });
const editingContent = ref(false),
  editingCategory = ref(false);
const contentLabels: Record<string, string> = {
  brandName: "品牌名称",
  introduction: "品牌介绍",
  heroTitle: "首页主标题",
  heroSubtitle: "首页副标题",
  heroImage: "首页图片",
  customerService: "客服联系方式",
  purchaseNotes: "购买说明",
  privacyOperator: "运营主体全称",
  privacyContact: "隐私联络方式",
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
    title: "隐私保护",
    description:
      "运营主体全称须与微信小程序主体核对；请填写可有效受理个人信息查阅、更正、删除及账号注销请求的联系方式。两项需一并填写或一并清空，资料缺失会阻止小程序首次隐私授权，匿名浏览仍可进行。",
    keys: ["privacyOperator", "privacyContact"],
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
  store?: string;
  principal?: string;
  storageEnabled?: boolean;
  storageExclusion?: string;
  storageLocked?: number;
  productType: "regular" | "welfare";
  welfare: string;
  stock: number;
  /** 编辑前的总库存，仅主动调整库存时提交，用于防止覆盖期间的销售扣减。 */
  expectedStock?: number;
  active: boolean;
  reserved?: number;
}
interface Product {
  id?: number;
  categoryId: number | "";
  name: string;
  subtitle: string;
  description: string;
  image: string;
  detailImage: string;
  active: boolean;
  featured: boolean;
  skus: Sku[];
}
interface ProductRecord extends Omit<Product, "skus" | "categoryId"> {
  id: number;
  categoryId: number;
  skus: (Omit<Sku, "retail" | "welfare"> & {
    retailCents: number | null;
    storeCents: number | null;
    principalCents: number | null;
    welfareCents: number | null;
    reserved: number;
  })[];
}
const query = ref(""),
  statusFilter = ref(""),
  categoryFilter = ref(0);
const categories = ref<
    { id: number; name: string; sort: number; active: number }[]
  >([]),
  editing = ref(false);
const categoryNames = computed(
  () => new Map(categories.value.map((c) => [c.id, c.name])),
);
const hasFilters = computed(() =>
  Boolean(query.value || statusFilter.value || categoryFilter.value),
);
const {
  rows: products,
  total: productTotal,
  pagination: productPagination,
  loading: listLoading,
  load: loadProducts,
} = useAdminList<ProductRecord>(
  "/admin/products",
  () => ({
    q: query.value,
    active: statusFilter.value
      ? statusFilter.value === "active"
        ? "1"
        : "0"
      : "",
    categoryId: categoryFilter.value || "",
  }),
  error,
);
const filteredProducts = computed(() => products.value);
function resetFilters() {
  query.value = "";
  statusFilter.value = "";
  categoryFilter.value = 0;
}

const blank = (): Product => ({
  categoryId: "",
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
      productType: "regular",
      welfare: "",
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
  notice.value = "";
  try {
    if (props.section === "content") {
      const v = await api<typeof content.value | null>("/admin/content");
      // 旧品牌资料没有隐私字段时补空值，保证表单始终成对提交。
      if (v)
        content.value = {
          ...v,
          privacyOperator: v.privacyOperator ?? "",
          privacyContact: v.privacyContact ?? "",
        };
    } else {
      [categories.value, products.value] = await Promise.all([
        api<typeof categories.value>("/admin/categories"),
        loadProducts(),
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
    reason.value = "";
    await load();
    if (!loadFailed.value) notice.value = "保存成功";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
function resetEditorFeedback() {
  error.value = "";
  notice.value = "";
  reason.value = "";
}
function editContent() {
  resetEditorFeedback();
  contentForm.value = { ...content.value };
  editingContent.value = true;
}
function saveContent() {
  if (uploadingImage.value) return;
  return act(async () => {
    if (
      Boolean(contentForm.value.privacyOperator.trim()) !==
      Boolean(contentForm.value.privacyContact.trim())
    )
      throw new Error("运营主体全称与隐私联络方式需完整填写或全部留空");
    await api("/admin/content", {
      method: "PUT",
      body: { ...contentForm.value, reason: reason.value },
    });
    content.value = { ...contentForm.value };
    editingContent.value = false;
  });
}
function editCategory(c?: (typeof categories.value)[number]) {
  resetEditorFeedback();
  category.value = c
    ? { ...c, active: Boolean(c.active) }
    : { id: 0, name: "", sort: 0, active: true };
  editingCategory.value = true;
}
function saveCategory() {
  return act(async () => {
    if (
      !Number.isInteger(category.value.sort) ||
      category.value.sort < 0 ||
      category.value.sort > 9999
    )
      throw new Error("分类排序须为 0 至 9999 的整数");
    await api(
      "/admin/categories" + (category.value.id ? "/" + category.value.id : ""),
      {
        method: category.value.id ? "PUT" : "POST",
        body: {
          name: category.value.name,
          sort: Number(category.value.sort),
          active: category.value.active,
          reason: reason.value,
        },
      },
    );
    editingCategory.value = false;
  });
}
async function edit(p?: ProductRecord) {
  resetEditorFeedback();
  form.value = p
    ? {
        ...p,
        skus: p.skus.map((s) => ({
          ...s,
          expectedStock: s.stock,
          retail: amount(s.retailCents),
          store: amount(s.storeCents),
          principal: amount(s.principalCents),
          productType: s.productType,
          welfare: amount(s.welfareCents),
        })),
      }
    : blank();
  editing.value = true;
  await nextTick();
  productNameInput.value?.focus({ preventScroll: true });
}
function saveProduct() {
  if (uploadingImage.value) return;
  return act(async () => {
    const p = form.value;
    if (
      p.skus.some(
        (sku) =>
          !Number.isSafeInteger(sku.stock) || sku.stock < (sku.reserved || 0),
      )
    )
      throw new Error("总库存须为不低于已占用库存的整数");
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
        skus: p.skus.map((s) => {
          const stockChanged = !s.id || s.stock !== s.expectedStock;
          return {
            ...(s.id ? { id: s.id } : {}),
            name: s.name,
            unit: s.unit,
            retailCents: toCents(s.retail),
            storeCents: toCents(s.store || ""),
            principalCents: toCents(s.principal || ""),
            storageEnabled: Boolean(s.storageEnabled),
            storageExclusion: s.storageExclusion || "none",
            productType: s.productType,
            welfareCents: toCents(s.welfare),
            ...(stockChanged ? { stock: Number(s.stock) } : {}),
            ...(s.id && stockChanged ? { expectedStock: s.expectedStock } : {}),
            active: s.active,
          };
        }),
      },
    });
    editing.value = false;
  });
}

const productColumns = [
  { title: "商品", key: "c0", width: 220 },
  { title: "状态", key: "c1", width: 160 },
  { title: "规格 / 可用库存", key: "c2", width: 160 },
  { title: "操作", key: "c3", width: 200 },
];
</script>
<template>
  <section class="business-settings" :aria-busy="loading || busy">
    <FeedbackToast v-model:notice="notice" v-model:error="error">
      <a-button
        html-type="button"
        v-if="loadFailed"
        :disabled="loading || busy"
        @click="load"
      >
        重新加载
      </a-button>
    </FeedbackToast>
    <p v-if="loading" class="loading-note" role="status">
      正在加载{{ section === "content" ? "品牌资料" : "商品与分类" }}…
    </p>

    <template v-if="section === 'content'">
      <Teleport defer to="#admin-page-actions">
        <a-button
          html-type="button"
          type="primary"
          :disabled="busy || loading || !loaded"
          @click="editContent"
        >
          编辑品牌资料
        </a-button>
      </Teleport>
      <div class="content-layout content-sections">
        <a-card
          size="small"
          v-for="group in contentGroups"
          :key="group.title"
          class="content-group"
        >
          <header class="panel-heading">
            <div>
              <h3>{{ group.title }}</h3>
              <p>{{ group.description }}</p>
            </div>
          </header>
          <div class="field-grid">
            <div v-for="key in group.keys" :key="key">
              <h4>{{ contentLabels[key] }}</h4>
              <p v-if="key !== 'heroImage'">{{ content[key] || "尚未配置" }}</p>
              <img
                v-if="key === 'heroImage' && content.heroImage"
                :src="imageUrl(content.heroImage, 'detail')"
                alt="首页图片预览"
                class="brand-image"
              />
            </div>
          </div>
        </a-card>
      </div>
      <AdminDialog
        v-if="editingContent"
        title="编辑品牌资料"
        :busy="busy || uploadingImage"
        wide
        @close="editingContent = false"
      >
        <AdminForm class="content-layout" @submit="saveContent">
          <fieldset
            class="form-controls content-sections"
            :disabled="busy || loading || !loaded"
          >
            <a-card
              size="small"
              v-for="group in contentGroups"
              :key="group.title"
              class="content-group"
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
                      'privacyContact',
                      'bankAccount',
                      'transferNotes',
                    ].includes(key),
                  }"
                >
                  {{ key === "heroImage" ? "" : contentLabels[key] }}
                  <a-textarea
                    v-if="
                      [
                        'introduction',
                        'heroTitle',
                        'purchaseNotes',
                        'privacyContact',
                        'transferNotes',
                      ].includes(key)
                    "
                    v-model:value="contentForm[key]"
                    :rows="key === 'heroTitle' ? 2 : 3"
                    :required="['introduction', 'heroTitle'].includes(key)"
                    :maxlength="key === 'privacyContact' ? 300 : undefined"
                  />
                  <ImageUploader
                    v-else-if="key === 'heroImage'"
                    v-model="contentForm.heroImage"
                    label="首页图片"
                    purpose="content"
                    required
                    :disabled="busy || loading"
                    @busy-change="imageUploads.hero = $event"
                  />
                  <a-input
                    v-else
                    v-model:value="contentForm[key]"
                    :required="
                      ['brandName', 'heroSubtitle', 'heroImage'].includes(key)
                    "
                    :maxlength="key === 'privacyOperator' ? 100 : undefined"
                  />
                </label>
              </div>
            </a-card>
          </fieldset>
          <footer class="surface save-bar">
            <label
              >修改原因<a-input
                v-model:value="reason"
                :disabled="busy || loading"
                required
                :maxlength="200"
                placeholder="说明本次调整的内容"
            /></label>
            <div class="dialog-actions">
              <a-button
                html-type="button"
                :disabled="busy || uploadingImage"
                @click="editingContent = false"
              >
                取消
              </a-button>
              <a-button
                html-type="submit"
                type="primary"
                :disabled="busy || uploadingImage"
              >
                {{ busy ? "保存中…" : "保存品牌资料" }}
              </a-button>
            </div>
          </footer>
        </AdminForm>
      </AdminDialog>
    </template>

    <template v-else>
      <Teleport defer to="#admin-page-actions">
        <a-button
          html-type="button"
          :disabled="busy || loading || !loaded"
          @click="editCategory()"
        >
          新增分类
        </a-button>
        <a-button
          html-type="button"
          type="primary"
          :disabled="busy || loading || !loaded"
          @click="edit()"
        >
          <span aria-hidden="true">＋</span> 新增商品
        </a-button>
      </Teleport>
      <p class="catalog-summary">
        共 {{ productTotal }} 件商品 · 本页 {{ products.length }} 件
      </p>

      <a-collapse class="surface category-panel"
        ><a-collapse-panel key="details"
          ><template #header>
            <span
              >商品分类 <span class="count">{{ categories.length }}</span></span
            ><span class="summary-hint">点击分类编辑名称、排序与启停</span>
          </template>
          <div class="category-content">
            <div class="category-list" aria-label="选择分类进行编辑">
              <a-button
                html-type="button"
                v-for="c in categories"
                :key="c.id"
                class="category-chip"
                :aria-pressed="editingCategory && category.id === c.id"
                :disabled="busy || loading"
                @click="editCategory(c)"
                :aria-label="'编辑分类：' + c.name"
              >
                {{ c.name }} <span>{{ c.active ? "启用" : "停用" }}</span>
              </a-button>
              <p v-if="!categories.length && loaded" class="muted-note">
                尚未配置分类，请先新增分类，再配置商品。
              </p>
            </div>
          </div></a-collapse-panel
        ></a-collapse
      >

      <AdminDialog
        v-if="editingCategory"
        :title="category.id ? '编辑分类' : '新增分类'"
        :busy="busy"
        @close="editingCategory = false"
      >
        <AdminForm @submit="saveCategory">
          <fieldset
            class="form-controls field-grid"
            :disabled="busy || loading || !loaded"
          >
            <label
              >分类名称<a-input
                v-model:value="category.name"
                required
                :maxlength="40"
            /></label>
            <label
              >排序<a-input-number
                class="number-field"
                :precision="0"
                pattern="[0-9]+"
                v-model:value="category.sort"
                :min="0"
                :max="9999"
                required
            /></label>
            <div class="confirmation-field check-label">
              <a-checkbox v-model:checked="category.active">启用</a-checkbox>
            </div>
            <label class="full-field"
              >修改原因<a-input
                v-model:value="reason"
                required
                :maxlength="200"
            /></label>
            <div class="dialog-actions full-field">
              <a-button
                html-type="button"
                :disabled="busy"
                @click="editingCategory = false"
              >
                取消
              </a-button>
              <a-button html-type="submit" type="primary" :disabled="busy">
                {{ busy ? "保存中…" : "保存分类" }}
              </a-button>
            </div>
          </fieldset>
        </AdminForm>
      </AdminDialog>

      <AdminDialog
        v-if="editing"
        :title="form.id ? '编辑商品' : '新增商品'"
        :busy="busy || uploadingImage"
        wide
        @close="editing = false"
      >
        <AdminForm class="surface product-editor" @submit="saveProduct">
          <fieldset class="form-controls" :disabled="busy || loading">
            <section class="editor-section">
              <h4>基本信息</h4>
              <div class="field-grid">
                <label
                  >商品名<a-input
                    ref="productNameInput"
                    v-model:value="form.name"
                    required
                    :maxlength="80"
                /></label>
                <label
                  >分类<AdminSelect v-model.number="form.categoryId" required>
                    <option value="" disabled>请选择分类</option>
                    <option v-for="c in categories" :key="c.id" :value="c.id">
                      {{ c.name }}{{ c.active ? "" : "（停用）" }}
                    </option>
                  </AdminSelect></label
                >
                <p v-if="!categories.length" class="field-help full-field">
                  请取消当前编辑，在「商品分类」中新增分类后再添加商品。
                </p>
                <label class="full-field"
                  >副标题<a-input
                    v-model:value="form.subtitle"
                    :maxlength="160"
                /></label>
                <label class="full-field"
                  >商品说明<a-textarea
                    v-model:value="form.description"
                    required
                    :rows="3"
                  />
                </label>
              </div>
              <div class="publishing-options">
                <div class="confirmation-field check-label">
                  <a-checkbox v-model:checked="form.active">上架</a-checkbox>
                </div>
                <div class="confirmation-field check-label">
                  <a-checkbox v-model:checked="form.featured"
                    >首页臻选</a-checkbox
                  >
                </div>
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
                <ImageUploader
                  v-model="form.image"
                  label="列表图片"
                  purpose="catalog"
                  edit-mode="cover"
                  :product-name="form.name"
                  required
                  :disabled="busy || loading"
                  @busy-change="imageUploads.list = $event"
                />
                <ImageUploader
                  v-model="form.detailImage"
                  label="详情图片"
                  purpose="catalog"
                  edit-mode="detail"
                  required
                  :disabled="busy || loading"
                  @busy-change="imageUploads.detail = $event"
                />
              </div>
            </section>
            <section class="editor-section">
              <div class="panel-heading">
                <div>
                  <h4>
                    规格与价格 <span class="count">{{ form.skus.length }}</span>
                  </h4>
                  <p>
                    普通商品分别设置普通顾客、2980店主、19800店主售价；福利品统一价。对应价格未配置时，该身份不可购买。
                  </p>
                </div>
                <a-button
                  html-type="button"
                  @click="
                    form.skus.push({
                      name: '',
                      unit: '件',
                      retail: '',
                      productType: 'regular',
                      welfare: '',
                      stock: 0,
                      active: true,
                    })
                  "
                >
                  添加规格
                </a-button>
              </div>
              <div class="sku-list">
                <fieldset v-for="(s, i) in form.skus" :key="i" class="sku-card">
                  <legend>规格 {{ i + 1 }}</legend>
                  <div class="sku-form">
                    <label
                      >名称<a-input v-model:value="s.name" required
                    /></label>
                    <label
                      >单位<a-input v-model:value="s.unit" required
                    /></label>
                    <SkuPricingFields
                      v-model:product-type="s.productType"
                      v-model:retail="s.retail"
                      v-model:welfare="s.welfare"
                      v-model:store="s.store"
                      v-model:principal="s.principal"
                    />
                    <label
                      >寄存限制<AdminSelect v-model="s.storageExclusion"
                        ><option value="none">普通商品</option>
                        <option value="flash">秒杀（禁寄存）</option>
                        <option value="sample">小样（禁寄存）</option>
                        <option value="near_expiry">
                          临期（禁寄存）
                        </option></AdminSelect
                      ></label
                    >
                    <label
                      ><a-checkbox
                        v-model:checked="s.storageEnabled"
                        :disabled="
                          s.storageExclusion !== undefined &&
                          s.storageExclusion !== 'none'
                        "
                        >允许寄存</a-checkbox
                      ></label
                    >
                    <p v-if="s.storageLocked" class="field-help">
                      用户寄存锁定 {{ s.storageLocked }}
                      {{ s.unit }}（独立于可售库存）
                    </p>
                    <label
                      >可售库存池<a-input-number
                        class="number-field"
                        :precision="0"
                        pattern="[0-9]+"
                        v-model:value="s.stock"
                        :min="s.reserved || 0"
                        required
                    /></label>
                  </div>
                  <div class="sku-footer">
                    <div class="confirmation-field check-label">
                      <a-checkbox v-model:checked="s.active">可售</a-checkbox>
                    </div>
                    <span v-if="s.reserved" class="field-help"
                      >已占用 {{ s.reserved
                      }}{{ s.unit }}，总库存不得低于已占用库存。</span
                    ><a-button
                      html-type="button"
                      v-if="form.skus.length > 1"
                      type="button"
                      @click="form.skus.splice(i, 1)"
                    >
                      移除规格
                    </a-button>
                  </div>
                </fieldset>
              </div>
            </section>
          </fieldset>
          <footer class="save-bar editor-save">
            <label
              >修改原因<a-input
                v-model:value="reason"
                :disabled="busy || loading"
                required
                :maxlength="200"
                placeholder="说明本次商品、价格或库存调整"
            /></label>
            <div class="dialog-actions">
              <a-button
                html-type="button"
                :disabled="busy || uploadingImage"
                @click="editing = false"
              >
                取消</a-button
              ><a-button
                html-type="submit"
                type="primary"
                :disabled="busy || uploadingImage"
              >
                {{ busy ? "保存中…" : "保存商品" }}
              </a-button>
            </div>
          </footer>
        </AdminForm>
      </AdminDialog>

      <a-card size="small" class="catalog-panel">
        <header class="catalog-toolbar">
          <div>
            <h3>商品列表</h3>
            <p>共 {{ productTotal }} 件商品 · 本页 {{ products.length }} 件</p>
          </div>
          <div class="catalog-filters">
            <label class="search-field"
              ><span class="sr-only">搜索商品</span
              ><a-input
                v-model:value="query"
                type="search"
                placeholder="搜索商品、规格或分类"
            /></label>
            <label
              ><span class="sr-only">按分类筛选</span
              ><AdminSelect v-model.number="categoryFilter">
                <option :value="0">全部分类</option>
                <option v-for="c in categories" :key="c.id" :value="c.id">
                  {{ c.name }}
                </option>
              </AdminSelect></label
            >
            <label
              ><span class="sr-only">按状态筛选</span
              ><AdminSelect v-model="statusFilter">
                <option value="">全部状态</option>
                <option value="active">已上架</option>
                <option value="inactive">已下架</option>
              </AdminSelect></label
            >
            <a-button
              html-type="button"
              v-if="hasFilters"
              @click="resetFilters"
            >
              重置筛选
            </a-button>
          </div>
        </header>
        <div class="table-wrap">
          <a-table
            :data-source="filteredProducts"
            :columns="productColumns"
            row-key="id"
            size="middle"
            :loading="busy || loading || listLoading"
            :scroll="{ x: 680 }"
            :pagination="productPagination"
            ><template #bodyCell="{ column, record: p }"
              ><template v-if="column.key === 'c0'"
                ><div class="table-cell-content">
                  <div class="product-cell">
                    <div class="product-thumbnail image-placeholder">
                      <img
                        v-if="p.image"
                        :src="imageUrl(p.image, 'thumb')"
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
                </div></template
              >
              <template v-else-if="column.key === 'c1'"
                ><div class="table-cell-content">
                  <a-tag :color="p.active ? 'green' : 'default'">{{
                    p.active ? "已上架" : "已下架"
                  }}</a-tag>
                </div></template
              >
              <template v-else-if="column.key === 'c2'"
                ><div class="table-cell-content">
                  <div v-for="s in p.skus" :key="s.id" class="stock-line">
                    <span>{{ s.name }}</span
                    ><strong
                      >{{ s.stock - s.reserved
                      }}<small>{{ s.unit }}</small></strong
                    >
                  </div>
                </div></template
              >
              <template v-else-if="column.key === 'c3'"
                ><div class="table-cell-content">
                  <a-button
                    html-type="button"
                    class="edit-button"
                    :disabled="busy || loading"
                    :aria-label="'编辑商品：' + p.name"
                    @click="edit(p)"
                  >
                    {{ editing && form.id === p.id ? "正在编辑" : "编辑商品" }}
                  </a-button>
                </div></template
              ></template
            ></a-table
          >
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
          <a-button
            html-type="button"
            v-if="products.length"
            @click="resetFilters"
          >
            重置筛选
          </a-button>
          <a-button
            html-type="button"
            type="primary"
            v-else
            :disabled="busy"
            @click="edit()"
          >
            新增商品
          </a-button>
        </div>
      </a-card>
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
.business-settings .check-label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  white-space: nowrap;
}
.business-settings .message {
  margin: 0;
}
.feedback,
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
.content-layout {
  max-width: 1000px;
}
.content-sections,
.sku-list {
  display: grid;
  gap: 16px;
}
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
.editor-heading h3 {
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
.product-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.image-placeholder {
  display: grid;
  place-items: center;
  background: var(--admin-bg);
  color: var(--admin-muted);
  font-size: 11px;
}
.product-thumbnail {
  flex-shrink: 0;
  width: 64px;
  height: 72px;
  border-radius: 6px;
  overflow: hidden;
}
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
.business-settings .admin-dialog .product-editor {
  border: 0;
  overflow: visible;
}
.admin-dialog .save-bar {
  position: sticky;
  bottom: 0;
  z-index: 2;
  padding: 16px 20px;
  background: white;
  border-top: 1px solid var(--admin-border);
}
.admin-dialog .save-bar .dialog-actions {
  position: static;
  margin: 0;
  padding: 0;
  border: 0;
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
.stock-line strong {
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
  .business-settings .content-group,
  .editor-section {
    padding: 16px;
  }
}

.number-field {
  width: 100%;
  min-width: 0;
}
.table-cell-content {
  min-width: 0;
  overflow-wrap: anywhere;
}
.table-cell-content > .ant-btn + .ant-btn {
  margin-left: 8px;
}
.section-heading {
  margin: 0 0 16px;
}
.confirmation-field {
  min-width: 0;
  grid-column: 1 / -1;
}
.admin-form > label,
.fields > label,
.result-fields > label,
.field-grid > label,
.sku-form > label {
  align-content: start;
}
</style>
