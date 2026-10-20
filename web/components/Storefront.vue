<script setup lang="ts">
import { ref, watch } from "vue";
import { api } from "../client";
const props = defineProps<{ tab: string; token: string }>();
const emit = defineEmits<{
  navigate: [tab: string];
  buy: [items: { skuId: number; quantity: number }[]];
  cart: [];
}>();
const content = ref<any>(null),
  products = ref<any[]>([]),
  categories = ref<any[]>([]),
  product = ref<any>(null),
  sku = ref<any>(null),
  quantity = ref(1),
  category = ref(0),
  identity = ref("D"),
  error = ref(""),
  info = ref(false);
const names: Record<string, string> = {
  D: "游客",
  C: "调肤店主",
  B: "调肤主理人",
};
const money = (v: number | null) =>
  v === null ? "价格待配置" : "¥" + (v / 100).toFixed(2);
const first = (p: any) => p.skus.find((s: any) => s.available) || p.skus[0];
async function load() {
  try {
    const [c, cats, r] = await Promise.all([
      api<any>("/public/content"),
      api<any[]>("/public/categories"),
      api<any>(
        "/products" + (category.value ? "?categoryId=" + category.value : ""),
        { token: props.token },
      ),
    ]);
    content.value = c.content;
    categories.value = cats;
    products.value = r.items;
    identity.value = r.identity;
    error.value = "";
  } catch (e) {
    error.value = (e as Error).message;
  }
}
watch(
  () => [props.tab, props.token],
  () => {
    product.value = null;
    info.value = false;
    load();
  },
  { immediate: true },
);
async function open(p: any) {
  try {
    product.value = await api<any>("/products/" + p.id, { token: props.token });
    sku.value = first(product.value);
    quantity.value = 1;
  } catch (e) {
    error.value = (e as Error).message;
  }
}
</script>
<template>
  <div class="qx">
    <template v-if="product"
      ><button class="back" @click="product = null">‹ 返回商城</button
      ><img class="qx-detail-image" :src="product.detailImage" alt="商品" />
      <div class="qx-detail-section">
        <span class="qx-badge">{{
          sku.productType === "welfare"
            ? "统一福利价"
            : names[identity] + "适用价"
        }}</span>
        <h1 class="qx-h1">{{ product.name }}</h1>
        <p class="qx-muted">{{ product.subtitle }}</p>
        <p v-if="sku.productType !== 'welfare'" class="qx-muted">
          零售价 {{ money(sku.retailCents) }}
        </p>
        <div class="qx-price qx-large">
          {{ money(sku.priceCents) }} / {{ sku.unit }}
        </div>
      </div>
      <div class="qx-detail-section">
        <div class="qx-label">规格</div>
        <div class="qx-specs">
          <button
            v-for="s in product.skus"
            :key="s.id"
            :class="{ chosen: s.id === sku.id }"
            @click="
              sku = s;
              quantity = 1;
            "
          >
            {{ s.name }} · {{ s.unit }}
          </button>
        </div>
        <div class="qx-section-row">
          <span>购买数量</span>
          <div class="qx-quantity">
            <button :disabled="quantity <= 1" @click="quantity--">−</button
            ><span>{{ quantity }}</span
            ><button :disabled="quantity >= sku.stock" @click="quantity++">
              +
            </button>
          </div>
        </div>
        <p class="qx-muted">
          {{
            sku.available
              ? "可用库存：" + sku.stock + sku.unit
              : "该规格暂不可购买，请检查价格及库存配置"
          }}
        </p>
      </div>
      <div class="qx-detail-section qx-section-row">
        <button class="qx-text" @click="emit('cart')">购物袋</button
        ><button
          class="qx-primary"
          :disabled="!sku.available"
          @click="emit('buy', [{ skuId: sku.id, quantity }])"
        >
          立即订货
        </button>
      </div>
      <div class="qx-notes">
        <div class="qx-caption">PRODUCT NOTES</div>
        <h2 class="qx-h2">产品说明</h2>
        <div class="qx-about">{{ product.description }}</div>
      </div></template
    >
    <template v-else-if="info"
      ><button class="back" @click="info = false">‹ 返回首页</button>
      <div class="qx-section">
        <h1 class="qx-h1">品牌与购买说明</h1>
        <template v-if="content"
          ><h2 class="qx-h2">{{ content.brandName }}</h2>
          <div class="qx-about">{{ content.introduction }}</div>
          <h2 class="qx-h2">客服</h2>
          <div class="qx-about">
            {{ content.customerService || "客服资料尚未配置" }}
          </div>
          <h2 class="qx-h2">购买说明</h2>
          <div class="qx-about">
            {{ content.purchaseNotes || "购买说明尚未配置" }}
          </div>
          <h2 class="qx-h2">公司收款资料</h2>
          <div class="qx-about">
            {{
              content.companyName
                ? `户名：${content.companyName}\n开户行：${content.bankName}\n账号：${content.bankAccount}`
                : "公司收款资料尚未配置"
            }}
          </div>
          <div class="qx-about">
            {{ content.transferNotes || "转款说明尚未配置" }}
          </div>
          <p class="qx-muted">
            转款到账须经品牌方核实，请按实际业务用途转款。
          </p></template
        >
        <p v-else class="qx-empty">品牌资料尚未配置</p>
      </div></template
    >
    <template v-else-if="tab === 'home'"
      ><div class="qx-brand">
        <span class="qx-seal">清</span>
        <div>
          <div class="qx-brand-name">
            {{ content?.brandName || "清欣简妍肌" }}
          </div>
          <div class="qx-en">SKIN WELLNESS</div>
        </div>
      </div>
      <div class="qx-hero">
        <img
          class="qx-hero-image"
          :src="content?.heroImage || '/assets/figma/Home-imgImage.png'"
          alt="东方美学护肤形象"
        />
        <div class="qx-shade" />
        <div class="qx-hero-copy">
          <div class="qx-overline">东方肌肤管理美学</div>
          <div class="qx-hero-title">
            {{ content?.heroTitle || "让肌肤，回到\n它本来的光" }}
          </div>
          <div class="qx-hero-sub">
            {{ content?.heroSubtitle || "专业调肤方案 · 精准护理 · 长效陪伴" }}
          </div>
          <button class="qx-hero-button" @click="emit('navigate', 'mall')">
            开启肌肤之旅 <span>→</span>
          </button>
        </div>
      </div>
      <div class="qx-shortcuts">
        <button @click="emit('navigate', 'mall')">
          <img :src="'/assets/figma/shop-imgIcon.svg'" alt="" />
          <div>臻选商城</div>
          <small>查看在售商品</small></button
        ><button @click="info = true">
          <img :src="'/assets/figma/Home-imgIcon1.svg'" alt="" />
          <div>品牌与购买说明</div>
          <small>了解清欣简妍肌</small>
        </button>
      </div>
      <section v-if="products.some((p) => p.featured)" class="qx-section">
        <div class="qx-section-row">
          <div>
            <div class="qx-caption">SELECTED FOR YOU</div>
            <h2 class="qx-h2">今日臻选</h2>
          </div>
          <button class="qx-text" @click="emit('navigate', 'mall')">
            查看全部 ›
          </button>
        </div>
        <div
          v-for="p in products.filter((p) => p.featured).slice(0, 1)"
          :key="p.id"
          class="qx-feature"
          @click="open(p)"
        >
          <img :src="p.image" alt="商品" />
          <div class="qx-feature-copy">
            <span class="qx-badge">明星单品</span>
            <h2 class="qx-h2">{{ p.name }}</h2>
            <span class="qx-muted">{{ p.subtitle }}</span>
            <div class="qx-price">{{ money(first(p).priceCents) }}</div>
            <button class="qx-primary">立即选购</button>
          </div>
        </div>
      </section>
      <section class="qx-section">
        <div class="qx-caption">ABOUT QINGXIN</div>
        <h2 class="qx-h2">与品牌，共成长</h2>
        <div class="qx-about">
          {{ content?.introduction || "品牌资料尚未配置" }}
        </div>
        <button class="qx-info" @click="info = true">
          品牌介绍与购买说明 <span>↗</span>
        </button>
      </section></template
    >
    <template v-else
      ><div class="qx-shop-head">
        <button class="qx-text" @click="emit('cart')">购物袋 ›</button>
        <div class="qx-caption">BRAND STORE</div>
        <h1 class="qx-h1">臻选商城</h1>
        <div class="qx-muted">专业配方，为每一种肌肤状态而生</div>
      </div>
      <div class="qx-role">
        <small>{{ names[identity] }}专享</small>
        <div>当前使用{{ names[identity] }}价格</div>
      </div>
      <div class="qx-categories">
        <button
          :class="{ chosen: !category }"
          @click="
            category = 0;
            load();
          "
        >
          全部</button
        ><button
          v-for="c in categories"
          :key="c.id"
          :class="{ chosen: category === c.id }"
          @click="
            category = c.id;
            load();
          "
        >
          {{ c.name }}
        </button>
      </div>
      <div class="qx-grid">
        <article
          v-for="p in products"
          :key="p.id"
          class="qx-product"
          @click="open(p)"
        >
          <img :src="p.image" :alt="p.name" />
          <div class="qx-product-copy">
            <div class="qx-muted">{{ p.subtitle }}</div>
            <h2 class="qx-product-name">{{ p.name }}</h2>
            <div class="qx-section-row">
              <div class="qx-price">{{ money(first(p).priceCents) }}</div>
              <img
                class="qx-add"
                :src="'/assets/figma/shop-imgIcon2.svg'"
                alt="查看详情"
              />
            </div>
            <small v-if="!p.skus.some((s: any) => s.available)" class="qx-muted"
              >暂不可购买</small
            >
          </div>
        </article>
      </div>
      <p v-if="!products.length" class="qx-empty">商品尚未上架</p></template
    >
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
  </div>
</template>
<style scoped src="../storefront.css"></style>
