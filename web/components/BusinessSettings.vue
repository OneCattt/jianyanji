<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "../client";
const props = defineProps<{ section: string }>();
const error = ref(""),
  notice = ref(""),
  busy = ref(false),
  reason = ref("");
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
const categories = ref<
    { id: number; name: string; sort: number; active: number }[]
  >([]),
  products = ref<any[]>([]),
  editing = ref(false);
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
  try {
    if (props.section === "content") {
      const v = await api<typeof content.value | null>("/admin/content");
      if (v) content.value = v;
    } else {
      [categories.value, products.value] = await Promise.all([
        api<typeof categories.value>("/admin/categories"),
        api<any[]>("/admin/products"),
      ]);
    }
  } catch (e) {
    error.value = (e as Error).message;
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
function edit(p?: any) {
  form.value = p
    ? {
        ...p,
        skus: p.skus.map((s: any) => ({
          ...s,
          retail: amount(s.retailCents),
          store: amount(s.storeCents),
          principal: amount(s.principalCents),
        })),
      }
    : blank();
  editing.value = true;
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
  <section class="business-settings">
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-if="notice" class="message success" role="status">{{ notice }}</p>
    <form
      v-if="section === 'content'"
      @submit.prevent="
        act(async () => {
          await api('/admin/content', {
            method: 'PUT',
            body: { ...content, reason },
          });
        })
      "
      class="settings-form"
    >
      <p>
        收款资料请使用公司真实信息；未填写时购买端会显示“尚未配置”。展示说明不代表到账确认。
      </p>
      <label v-for="(label, key) in contentLabels" :key="key"
        >{{ label
        }}<textarea
          v-if="
            ['introduction', 'purchaseNotes', 'transferNotes'].includes(key)
          "
          v-model="content[key as keyof typeof content]"
          rows="4"
          :required="key === 'introduction'" /><input
          v-else
          v-model="content[key as keyof typeof content]"
          :required="
            ['brandName', 'heroTitle', 'heroSubtitle', 'heroImage'].includes(
              key,
            )
          "
      /></label>
      <label>修改原因<input v-model="reason" required maxlength="200" /></label
      ><button class="primary" :disabled="busy">保存品牌资料</button>
    </form>
    <template v-else>
      <section class="surface">
        <h3>商品分类</h3>
        <div class="category-list">
          <button
            v-for="c in categories"
            :key="c.id"
            @click="category = { ...c, active: Boolean(c.active) }"
          >
            {{ c.name }} · {{ c.active ? "启用" : "停用" }}
          </button>
        </div>
        <form
          class="inline-form"
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
          <label
            >分类名称<input
              v-model="category.name"
              required
              maxlength="40" /></label
          ><label
            >排序<input
              v-model.number="category.sort"
              type="number"
              min="0"
              max="9999"
              required /></label
          ><label><input v-model="category.active" type="checkbox" />启用</label
          ><label
            >修改原因<input v-model="reason" required maxlength="200" /></label
          ><button :disabled="busy">
            {{ category.id ? "保存分类" : "新增分类" }}</button
          ><button
            v-if="category.id"
            type="button"
            @click="category = { id: 0, name: '', sort: 0, active: true }"
          >
            取消编辑
          </button>
        </form>
      </section>
      <div class="section-heading">
        <h3>商品与规格</h3>
        <button class="primary" @click="edit()">新增商品</button>
      </div>
      <form
        v-if="editing"
        class="settings-form surface"
        @submit.prevent="saveProduct"
      >
        <label
          >分类<select v-model.number="form.categoryId" required>
            <option :value="0" disabled>请选择分类</option>
            <option v-for="c in categories" :key="c.id" :value="c.id">
              {{ c.name }}
            </option>
          </select></label
        >
        <label
          >商品名<input v-model="form.name" required maxlength="80" /></label
        ><label>副标题<input v-model="form.subtitle" maxlength="160" /></label
        ><label
          >商品说明<textarea
            v-model="form.description"
            required
            rows="4"
          /></label
        ><label
          >列表图片地址<input
            v-model="form.image"
            required
            placeholder="https://… 或 /assets/…" /></label
        ><label
          >详情图片地址<input v-model="form.detailImage" required
        /></label>
        <div class="inline-form">
          <label><input v-model="form.active" type="checkbox" />上架</label
          ><label
            ><input v-model="form.featured" type="checkbox" />首页臻选</label
          >
        </div>
        <fieldset v-for="(s, i) in form.skus" :key="i">
          <legend>规格 {{ i + 1 }}</legend>
          <div class="sku-form">
            <label>名称<input v-model="s.name" required /></label
            ><label>单位<input v-model="s.unit" required /></label
            ><label
              >零售价（元）<input
                v-model="s.retail"
                inputmode="decimal" /></label
            ><label
              >店主价（元）<input
                v-model="s.store"
                inputmode="decimal" /></label
            ><label
              >主理人价（元）<input
                v-model="s.principal"
                inputmode="decimal" /></label
            ><label
              >总库存<input
                v-model.number="s.stock"
                type="number"
                :min="s.reserved || 0"
                required /></label
            ><label><input v-model="s.active" type="checkbox" />可售</label
            ><button
              v-if="form.skus.length > 1"
              type="button"
              @click="form.skus.splice(i, 1)"
            >
              移除规格
            </button>
          </div>
        </fieldset>
        <button
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
        <p>价格留空表示该身份暂不可购买。主理人价不得高于店主价。</p>
        <label
          >修改原因<input v-model="reason" required maxlength="200"
        /></label>
        <div class="inline-form">
          <button class="primary" :disabled="busy">保存商品</button
          ><button type="button" @click="editing = false">取消</button>
        </div>
      </form>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>商品</th>
              <th>状态</th>
              <th>规格 / 可用库存</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in products" :key="p.id">
              <td>{{ p.name }}</td>
              <td>{{ p.active ? "已上架" : "已下架" }}</td>
              <td>
                <div v-for="s in p.skus" :key="s.id">
                  {{ s.name }} · {{ s.stock - s.reserved }}{{ s.unit }}
                </div>
              </td>
              <td><button @click="edit(p)">编辑商品</button></td>
            </tr>
          </tbody>
        </table>
        <p v-if="!products.length">尚未配置商品</p>
      </div>
    </template>
  </section>
</template>
<style scoped>
.settings-form {
  max-width: 850px;
  display: grid;
  gap: 18px;
}
.settings-form label,
.inline-form label,
.sku-form label {
  display: grid;
  gap: 8px;
  font-size: 13px;
}
.settings-form p {
  line-height: 1.8;
  color: #74675f;
}
.surface {
  padding: 24px;
  background: #fff;
  border: 1px solid #e5e0da;
  margin: 16px 0;
}
textarea,
select {
  width: 100%;
  padding: 12px;
  background: #fff;
  border: 1px solid #d7ddd7;
  border-radius: 6px;
  font: inherit;
}
textarea {
  resize: vertical;
}
.inline-form,
.category-list {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: end;
}
.sku-form {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}
.inline-form input[type="checkbox"],
.sku-form input[type="checkbox"] {
  width: 20px;
  height: 20px;
}
fieldset {
  border: 1px solid #d7ddd7;
  padding: 18px;
}
.section-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
button {
  cursor: pointer;
}
input {
  min-width: 0;
}
@media (max-width: 650px) {
  .sku-form {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
