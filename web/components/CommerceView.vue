<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { api, ApiError } from "../client";

interface PurchaseItem {
  skuId: number;
  quantity: number;
}
interface CartItem extends PurchaseItem {
  id: number;
  selected: boolean;
  name: string;
  image: string;
  skuName: string;
  unit: string;
  stock: number;
  priceCents: number | null;
  available: boolean;
}
interface Address {
  id: number;
  recipient: string;
  phone: string;
  province: string;
  city: string;
  district: string;
  detail: string;
  is_default: number;
}
interface QuoteLine extends PurchaseItem {
  name: string;
  skuName: string;
  unit: string;
  image: string;
  unitCents: number;
}
interface Quote {
  id: number;
  identity: "B" | "C" | "D";
  address: Address;
  lines: QuoteLine[];
  goodsCents: number;
  shippingCents: number;
  totalCents: number;
  shippingInstructions: string;
  pendingMinutes: number;
}
const props = defineProps<{ token: string; initialItems?: PurchaseItem[] }>();
const emit = defineEmits<{ close: []; order: [id: number] }>();
// 原始图标来自 Figma 1:946 / 1:1069，嵌入资源避免临时链接过期。
const icons: Record<string, string> = {
  back: "data:image/svg+xml;base64,PHN2ZyBwcmVzZXJ2ZUFzcGVjdFJhdGlvPSJub25lIiBvdmVyZmxvdz0idmlzaWJsZSIgc3R5bGU9ImRpc3BsYXk6IGJsb2NrOyIgd2lkdGg9IjIwIiBoZWlnaHQ9IjIwIiB2aWV3Qm94PSIwIDAgMjAgMjAiIGZpbGw9Im5vbmUiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+CjxnIGlkPSJJY29uIj4KPHBhdGggaWQ9IlZlY3RvciIgZD0iTTEyLjUgNC4xNjY2N0w2LjY2NjY3IDEwTDEyLjUgMTUuODMzMyIgc3Ryb2tlLXdpZHRoPSIxLjM3NSIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIiBzdHJva2U9IiMyNTFEMUEiLz4KPC9nPgo8L3N2Zz4K",
  warehouse:
    "data:image/svg+xml;base64,PHN2ZyBwcmVzZXJ2ZUFzcGVjdFJhdGlvPSJub25lIiBvdmVyZmxvdz0idmlzaWJsZSIgc3R5bGU9ImRpc3BsYXk6IGJsb2NrOyIgd2lkdGg9IjE5IiBoZWlnaHQ9IjE5IiB2aWV3Qm94PSIwIDAgMTkgMTkiIGZpbGw9Im5vbmUiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+CjxnIGlkPSJJY29uIj4KPHBhdGggaWQ9IlZlY3RvciIgZD0iTTMuMTY2NjcgNS41NDE2N0w5LjUgMi4zNzVMMTUuODMzMyA1LjU0MTY3TDkuNSA4LjcwODMzTDMuMTY2NjcgNS41NDE2N1oiIHN0cm9rZS13aWR0aD0iMS4zMDYyNSIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIiBzdHJva2U9IiM3QzU5NDUiLz4KPHBhdGggaWQ9IlZlY3Rvcl8yIiBkPSJNMTUuODMzMyA1LjU0MTY3VjEzLjQ1ODNMOS41IDE2LjYyNUwzLjE2NjY3IDEzLjQ1ODNWNS41NDE2N005LjUgMTYuNjI1VjguNzA4MzMiIHN0cm9rZS13aWR0aD0iMS4zMDYyNSIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIiBzdHJva2U9IiM3QzU5NDUiLz4KPC9nPgo8L3N2Zz4K",
  check:
    "data:image/svg+xml;base64,PHN2ZyBwcmVzZXJ2ZUFzcGVjdFJhdGlvPSJub25lIiBvdmVyZmxvdz0idmlzaWJsZSIgc3R5bGU9ImRpc3BsYXk6IGJsb2NrOyIgd2lkdGg9IjEyIiBoZWlnaHQ9IjEyIiB2aWV3Qm94PSIwIDAgMTIgMTIiIGZpbGw9Im5vbmUiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+CjxnIGlkPSJJY29uIj4KPHBhdGggaWQ9IlZlY3RvciIgZD0iTTIuNSA2TDQuNSA4TDkuNSAzIiBzdHJva2Utd2lkdGg9IjAuODI1IiBzdHJva2UtbGluZWNhcD0icm91bmQiIHN0cm9rZS1saW5lam9pbj0icm91bmQiIHN0cm9rZT0id2hpdGUiLz4KPC9nPgo8L3N2Zz4K",
  shield:
    "data:image/svg+xml;base64,PHN2ZyBwcmVzZXJ2ZUFzcGVjdFJhdGlvPSJub25lIiBvdmVyZmxvdz0idmlzaWJsZSIgc3R5bGU9ImRpc3BsYXk6IGJsb2NrOyIgd2lkdGg9IjE3IiBoZWlnaHQ9IjE3IiB2aWV3Qm94PSIwIDAgMTcgMTciIGZpbGw9Im5vbmUiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+CjxnIGlkPSJJY29uIj4KPHBhdGggaWQ9IlZlY3RvciIgZD0iTTguNSAyLjEyNUwzLjU0MTY3IDQuMjVWNy43OTE2N0MzLjU0MTY3IDExLjMzMzMgNS41MjUgMTMuODEyNSA4LjUgMTQuODc1QzExLjQ3NSAxMy44MTI1IDEzLjQ1ODMgMTEuMzMzMyAxMy40NTgzIDcuNzkxNjdWNC4yNUw4LjUgMi4xMjVaIiBzdHJva2Utd2lkdGg9IjEuMTY4NzUiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCIgc3Ryb2tlPSIjOEQ3NjVGIi8+CjxwYXRoIGlkPSJWZWN0b3JfMiIgZD0iTTYuMzc1IDguNUw3Ljc5MTY3IDkuOTE2NjdMMTAuNjI1IDYuMzc1IiBzdHJva2Utd2lkdGg9IjEuMTY4NzUiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIgc3Ryb2tlLWxpbmVqb2luPSJyb3VuZCIgc3Ryb2tlPSIjOEQ3NjVGIi8+CjwvZz4KPC9zdmc+Cg==",
  pin: "data:image/svg+xml;base64,PHN2ZyBwcmVzZXJ2ZUFzcGVjdFJhdGlvPSJub25lIiBvdmVyZmxvdz0idmlzaWJsZSIgc3R5bGU9ImRpc3BsYXk6IGJsb2NrOyIgd2lkdGg9IjIyIiBoZWlnaHQ9IjIyIiB2aWV3Qm94PSIwIDAgMjIgMjIiIGZpbGw9Im5vbmUiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+CjxnIGlkPSJJY29uIj4KPHBhdGggaWQ9IlZlY3RvciIgZD0iTTE4LjMzMzMgOS4xNjY2N0MxOC4zMzMzIDEzLjc1IDExIDE5LjI1IDExIDE5LjI1QzExIDE5LjI1IDMuNjY2NjcgMTMuNzUgMy42NjY2NyA5LjE2NjY3QzMuNjY2NjcgNy4yMjE3NSA0LjQzOTI4IDUuMzU2NDggNS44MTQ1NSAzLjk4MTIyQzcuMTg5ODIgMi42MDU5NSA5LjA1NTA4IDEuODMzMzMgMTEgMS44MzMzM0MxMi45NDQ5IDEuODMzMzMgMTQuODEwMiAyLjYwNTk1IDE2LjE4NTUgMy45ODEyMkMxNy41NjA3IDUuMzU2NDggMTguMzMzMyA3LjIyMTc1IDE4LjMzMzMgOS4xNjY2N1oiIHN0cm9rZS13aWR0aD0iMS41MTI1IiBzdHJva2UtbGluZWNhcD0icm91bmQiIHN0cm9rZS1saW5lam9pbj0icm91bmQiIHN0cm9rZT0iIzc1NTM0MyIvPgo8cGF0aCBpZD0iVmVjdG9yXzIiIGQ9Ik0xMy4yOTE3IDkuMTY2NjdDMTMuMjkxNyAxMC40MzIzIDEyLjI2NTcgMTEuNDU4MyAxMSAxMS40NTgzQzkuNzM0MzUgMTEuNDU4MyA4LjcwODMzIDEwLjQzMjMgOC43MDgzMyA5LjE2NjY3QzguNzA4MzMgNy45MDEwMSA5LjczNDM1IDYuODc1IDExIDYuODc1QzEyLjI2NTcgNi44NzUgMTMuMjkxNyA3LjkwMTAxIDEzLjI5MTcgOS4xNjY2N1oiIHN0cm9rZS13aWR0aD0iMS41MTI1IiBzdHJva2UtbGluZWNhcD0icm91bmQiIHN0cm9rZS1saW5lam9pbj0icm91bmQiIHN0cm9rZT0iIzc1NTM0MyIvPgo8L2c+Cjwvc3ZnPgo=",
  arrow:
    "data:image/svg+xml;base64,PHN2ZyBwcmVzZXJ2ZUFzcGVjdFJhdGlvPSJub25lIiBvdmVyZmxvdz0idmlzaWJsZSIgc3R5bGU9ImRpc3BsYXk6IGJsb2NrOyIgd2lkdGg9IjE2IiBoZWlnaHQ9IjE2IiB2aWV3Qm94PSIwIDAgMTYgMTYiIGZpbGw9Im5vbmUiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+CjxnIGlkPSJJY29uIj4KPHBhdGggaWQ9IlZlY3RvciIgZD0iTTYgMy4zMzMzM0wxMC42NjY3IDhMNiAxMi42NjY3IiBzdHJva2Utd2lkdGg9IjEuMSIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIiBzdHJva2U9IiM3NTUzNDMiLz4KPC9nPgo8L3N2Zz4K",
};
const mode = ref<"cart" | "checkout" | "addresses">("cart");
const cart = ref<CartItem[]>([]),
  addresses = ref<Address[]>([]);
const loading = ref(false),
  busy = ref(false),
  loaded = ref(false);
const error = ref(""),
  notice = ref("");
const addressId = ref(0),
  quote = ref<Quote | null>(null);
const checkoutItems = ref<PurchaseItem[]>([]);
const directPurchase = ref(false),
  requestKey = ref("");
const createdOrderId = ref(0),
  uncertainOrder = ref(false);
const editorOpen = ref(false),
  editingId = ref(0),
  removingId = ref(0);
const blankAddress = () => ({
  recipient: "",
  phone: "",
  province: "",
  city: "",
  district: "",
  detail: "",
  isDefault: false,
});
const addressForm = ref(blankAddress());
const selected = computed(() => cart.value.filter((item) => item.selected));
const selectedCount = computed(() =>
  selected.value.reduce((sum, item) => sum + item.quantity, 0),
);
const totalCount = computed(() =>
  cart.value.reduce((sum, item) => sum + item.quantity, 0),
);
const goodsCents = computed(() =>
  selected.value.reduce(
    (sum, item) => sum + (item.priceCents ?? 0) * item.quantity,
    0,
  ),
);
const canCheckout = computed(
  () =>
    loaded.value &&
    selected.value.length > 0 &&
    selected.value.every((item) => item.available),
);
const selectedAddress = computed(() =>
  addresses.value.find((address) => address.id === addressId.value),
);
const identityNames = { B: "调肤主理人", C: "调肤店主", D: "游客" };
const money = (cents: number | null) =>
  cents === null ? "价格待配置" : `¥${(cents / 100).toFixed(2)}`;
const fullAddress = (address: Address) =>
  `${address.province}${address.city}${address.district}${address.detail}`;
const locked = computed(() => loading.value || busy.value);
let generation = 0;

function report(e: unknown) {
  error.value = e instanceof Error ? e.message : "请求失败，请重试";
}
async function request<T>(
  path: string,
  options: { method?: string; body?: unknown } = {},
) {
  if (!props.token) throw new Error("请先登录，再维护购物袋或确认订单");
  return api<T>(path, { ...options, token: props.token });
}
function invalidateQuote() {
  quote.value = null;
  requestKey.value = "";
}
async function reloadCart() {
  const result = await request<{ items: CartItem[] }>("/cart");
  cart.value = result.items;
}
async function reloadAddresses(preferredId = addressId.value) {
  addresses.value = await request<Address[]>("/addresses");
  addressId.value = addresses.value.some(
    (address) => address.id === preferredId,
  )
    ? preferredId
    : (addresses.value.find((address) => address.is_default)?.id ??
      addresses.value[0]?.id ??
      0);
}
async function load() {
  const current = ++generation;
  loading.value = true;
  error.value = "";
  loaded.value = false;
  cart.value = [];
  addresses.value = [];
  checkoutItems.value = [];
  addressId.value = 0;
  notice.value = "";
  invalidateQuote();
  createdOrderId.value = 0;
  uncertainOrder.value = false;
  editorOpen.value = false;
  removingId.value = 0;
  directPurchase.value = Boolean(props.initialItems?.length);
  mode.value = directPurchase.value ? "checkout" : "cart";
  try {
    const [bag, list] = await Promise.all([
      request<{ items: CartItem[] }>("/cart"),
      request<Address[]>("/addresses"),
    ]);
    if (current !== generation) return;
    cart.value = bag.items;
    addresses.value = list;
    addressId.value =
      list.find((address) => address.is_default)?.id ?? list[0]?.id ?? 0;
    loaded.value = true;
    if (directPurchase.value) {
      const items = props.initialItems!.map((item) => ({
        skuId: item.skuId,
        quantity: item.quantity,
      }));
      if (
        items.length > 50 ||
        new Set(items.map((item) => item.skuId)).size !== items.length ||
        items.some(
          (item) =>
            !Number.isSafeInteger(item.skuId) ||
            item.skuId < 1 ||
            !Number.isInteger(item.quantity) ||
            item.quantity < 1 ||
            item.quantity > 999,
        )
      )
        throw new Error("购买规格或数量无效，请返回商品页重新选择");
      checkoutItems.value = items;
      if (addressId.value) await fetchQuote(current);
    }
  } catch (e) {
    if (current === generation) report(e);
  } finally {
    if (current === generation) loading.value = false;
  }
}
watch(
  () => [props.token, JSON.stringify(props.initialItems)],
  () => {
    void load();
  },
  { immediate: true },
);

async function act(action: () => Promise<void>) {
  if (locked.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await action();
  } catch (e) {
    report(e);
  } finally {
    busy.value = false;
  }
}
function updateItem(
  item: CartItem,
  quantity = item.quantity,
  checked = item.selected,
) {
  void act(async () => {
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > 999)
      throw new Error("数量须为 1～999 的整数");
    await request(`/cart/${item.skuId}`, {
      method: "PUT",
      body: { quantity, selected: checked },
    });
    await reloadCart();
    invalidateQuote();
  });
}
function removeItem(item: CartItem) {
  void act(async () => {
    await request(`/cart/${item.skuId}/remove`, { method: "POST" });
    await reloadCart();
    invalidateQuote();
    notice.value = "商品已移出购物袋";
  });
}
async function fetchQuote(current = generation) {
  invalidateQuote();
  if (!addressId.value) return;
  if (!checkoutItems.value.length) throw new Error("请先选择需要结算的商品");
  const result = await request<Quote>("/checkout/quote", {
    method: "POST",
    body: { addressId: addressId.value, items: checkoutItems.value },
  });
  if (current !== generation) return;
  quote.value = result;
  requestKey.value = crypto.randomUUID();
}
function beginCheckout() {
  void act(async () => {
    if (!canCheckout.value)
      throw new Error("请勾选可购买的商品，检查库存和价格后再结算");
    if (selected.value.length > 50)
      throw new Error("每笔订单最多支持 50 种规格，请分批结算");
    directPurchase.value = false;
    checkoutItems.value = selected.value.map(({ skuId, quantity }) => ({
      skuId,
      quantity,
    }));
    mode.value = "checkout";
    await fetchQuote();
  });
}
function addToBag() {
  void act(async () => {
    await reloadCart();
    const additions = checkoutItems.value.map((item) => ({
      skuId: item.skuId,
      quantity:
        (cart.value.find((saved) => saved.skuId === item.skuId)?.quantity ??
          0) + item.quantity,
    }));
    if (additions.some((item) => item.quantity > 999))
      throw new Error("同一规格最多可添加 999 件，请先调整购物袋数量");
    try {
      for (const item of additions)
        await request(`/cart/${item.skuId}`, {
          method: "PUT",
          body: { quantity: item.quantity, selected: true },
        });
      notice.value = "已加入购物袋，可继续调整后结算";
    } finally {
      directPurchase.value = false;
      mode.value = "cart";
      invalidateQuote();
      await reloadCart();
    }
  });
}
function back() {
  if (locked.value) return;
  if (uncertainOrder.value) {
    error.value = "请先确认原订单结果，再返回，避免重复下单";
    return;
  }
  if (mode.value === "addresses") {
    editorOpen.value = false;
    removingId.value = 0;
    mode.value = "checkout";
  } else if (
    mode.value === "checkout" &&
    !directPurchase.value &&
    !uncertainOrder.value &&
    !createdOrderId.value
  ) {
    mode.value = "cart";
    invalidateQuote();
  } else emit("close");
}
function chooseAddress(address: Address) {
  void act(async () => {
    addressId.value = address.id;
    mode.value = "checkout";
    await fetchQuote();
  });
}
function editAddress(address?: Address) {
  editingId.value = address?.id ?? 0;
  addressForm.value = address
    ? {
        recipient: address.recipient,
        phone: address.phone,
        province: address.province,
        city: address.city,
        district: address.district,
        detail: address.detail,
        isDefault: Boolean(address.is_default),
      }
    : { ...blankAddress(), isDefault: addresses.value.length === 0 };
  editorOpen.value = true;
  removingId.value = 0;
}
function saveAddress() {
  void act(async () => {
    const form = addressForm.value;
    if (!/^1[3-9]\d{9}$/.test(form.phone))
      throw new Error("请输入正确的 11 位手机号码");
    const result = await request<{ id: number }>(
      `/addresses${editingId.value ? `/${editingId.value}` : ""}`,
      {
        method: editingId.value ? "PUT" : "POST",
        body: { ...form },
      },
    );
    invalidateQuote();
    await reloadAddresses(result.id);
    editorOpen.value = false;
    mode.value = "checkout";
    notice.value = "地址已保存，请核对最新订单金额";
    await fetchQuote();
  });
}
function setDefault(address: Address) {
  void act(async () => {
    const { recipient, phone, province, city, district, detail } = address;
    await request(`/addresses/${address.id}`, {
      method: "PUT",
      body: {
        recipient,
        phone,
        province,
        city,
        district,
        detail,
        isDefault: true,
      },
    });
    await reloadAddresses();
    notice.value = "默认地址已更新";
  });
}
function removeAddress(address: Address) {
  void act(async () => {
    await request(`/addresses/${address.id}/remove`, { method: "POST" });
    invalidateQuote();
    await reloadAddresses();
    removingId.value = 0;
    notice.value = "地址已移除，已有订单地址保留";
  });
}
function confirmOrder() {
  void act(async () => {
    if (!quote.value || !requestKey.value)
      throw new Error("请先重新核对配送与订单金额");
    try {
      const result = await request<{ id: number }>("/orders", {
        method: "POST",
        body: { quoteId: quote.value.id, requestKey: requestKey.value },
      });
      uncertainOrder.value = false;
      createdOrderId.value = result.id;
      notice.value = "待付款订单已生成，请进入订单详情继续付款";
      emit("order", result.id);
    } catch (e) {
      if (e instanceof ApiError && e.status < 500) {
        uncertainOrder.value = false;
        invalidateQuote();
      } else {
        // 结果未明时保留原报价和请求号，重试查询同一创建结果。
        uncertainOrder.value = true;
      }
      throw e;
    }
  });
}
</script>

<template>
  <section class="commerce qx" :aria-busy="locked">
    <header class="flow-header">
      <button
        class="flow-back"
        :disabled="locked"
        aria-label="返回"
        @click="back"
      >
        <img :src="icons.back" alt="" />
      </button>
      <h1>
        {{
          mode === "cart"
            ? "购物袋"
            : mode === "addresses"
              ? "收货地址"
              : "确认订单"
        }}
      </h1>
      <small v-if="mode === 'cart'">共 {{ cart.length }} 种</small>
    </header>
    <div class="commerce-body" :class="{ 'checkout-body': mode !== 'cart' }">
      <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
      <p v-if="notice" class="feedback success" role="status">{{ notice }}</p>
      <p v-if="loading" class="empty" role="status">
        正在读取{{ mode === "cart" ? "购物袋" : "订单信息" }}…
      </p>
      <template v-else-if="!loaded">
        <div class="empty">
          <p>暂未读取到购物信息</p>
          <button :disabled="locked || !token" @click="load">重新加载</button>
        </div>
      </template>
      <template v-else-if="mode === 'cart'">
        <div class="warehouse">
          <img :src="icons.warehouse" alt="" />
          <div>
            <strong>清欣简妍肌品牌仓</strong
            ><small>以下商品由品牌仓统一发货</small>
          </div>
        </div>
        <div v-if="cart.length" class="cart-list">
          <article v-for="item in cart" :key="item.skuId" class="cart-row">
            <label class="item-select"
              ><input
                type="checkbox"
                :checked="item.selected"
                :disabled="locked || !item.available"
                :aria-label="`选择${item.name} ${item.skuName}`"
                @change="
                  updateItem(item, item.quantity, !item.selected)
                " /><span
                ><img v-if="item.selected" :src="icons.check" alt="" /></span
            ></label>
            <img class="product-image" :src="item.image" :alt="item.name" />
            <div class="cart-copy">
              <h2>{{ item.name }}</h2>
              <small class="sku">{{ item.skuName }}</small>
              <p v-if="!item.available" class="unavailable">
                {{
                  item.priceCents === null
                    ? "当前身份价格未配置"
                    : item.stock < item.quantity
                      ? `库存不足，剩余 ${item.stock}${item.unit}`
                      : "该商品暂不可购买"
                }}
              </p>
              <div class="item-bottom">
                <strong class="price">{{ money(item.priceCents) }}</strong>
                <div class="quantity">
                  <button
                    :disabled="locked || item.quantity <= 1"
                    :aria-label="`减少${item.name}数量`"
                    @click="updateItem(item, item.quantity - 1)"
                  >
                    −</button
                  ><span>{{ item.quantity }}</span
                  ><button
                    :disabled="
                      locked || item.quantity >= Math.min(999, item.stock)
                    "
                    :aria-label="`增加${item.name}数量`"
                    @click="updateItem(item, item.quantity + 1)"
                  >
                    +
                  </button>
                </div>
              </div>
              <button
                class="remove-item"
                :disabled="locked"
                @click="removeItem(item)"
              >
                移除
              </button>
            </div>
          </article>
        </div>
        <div v-else class="empty">
          <h2>购物袋还是空的</h2>
          <p>去挑选适合你的清欣好物</p>
          <button @click="emit('close')">返回商城</button>
        </div>
        <div class="purchase-notes">
          <img :src="icons.shield" alt="" />
          <p>商品价格按当前身份与规格计算，结算时核对最新价格、库存及运费。</p>
        </div>
        <div v-if="cart.length" class="bag-summary">
          共 {{ cart.length }} 种 / {{ totalCount }} 件，已选
          {{ selected.length }} 种 / {{ selectedCount }} 件
        </div>
      </template>
      <template v-else-if="mode === 'addresses'">
        <button class="add-address" :disabled="locked" @click="editAddress()">
          ＋ 新增收货地址
        </button>
        <form
          v-if="editorOpen"
          class="address-form panel"
          @submit.prevent="saveAddress"
        >
          <h2>{{ editingId ? "编辑收货地址" : "新增收货地址" }}</h2>
          <fieldset :disabled="locked">
            <label
              >收货人<input
                v-model.trim="addressForm.recipient"
                required
                maxlength="40"
                autocomplete="shipping name"
            /></label>
            <label
              >手机号码<input
                v-model.trim="addressForm.phone"
                type="tel"
                inputmode="tel"
                required
                maxlength="11"
                pattern="1[3-9][0-9]{9}"
                autocomplete="shipping tel"
            /></label>
            <div class="region-fields">
              <label
                >省份<input
                  v-model.trim="addressForm.province"
                  required
                  maxlength="30"
                  placeholder="如：浙江省"
                  autocomplete="shipping address-level1" /></label
              ><label
                >城市<input
                  v-model.trim="addressForm.city"
                  required
                  maxlength="30"
                  placeholder="如：杭州市"
                  autocomplete="shipping address-level2" /></label
              ><label
                >区 / 县<input
                  v-model.trim="addressForm.district"
                  required
                  maxlength="30"
                  placeholder="如：西湖区"
                  autocomplete="shipping address-level3"
              /></label>
            </div>
            <label
              >详细地址<textarea
                v-model.trim="addressForm.detail"
                required
                maxlength="200"
                rows="3"
                autocomplete="shipping street-address"
              />
            </label>
            <label class="check-label"
              ><input
                v-model="addressForm.isDefault"
                type="checkbox"
              />设为默认收货地址</label
            >
          </fieldset>
          <div class="form-actions">
            <button class="commerce-primary" :disabled="locked">
              {{ busy ? "保存中…" : "保存并使用" }}</button
            ><button
              type="button"
              :disabled="locked"
              @click="editorOpen = false"
            >
              取消
            </button>
          </div>
        </form>
        <p v-if="!addresses.length && !editorOpen" class="empty">
          暂无收货地址，请新增后核对配送范围。
        </p>
        <article
          v-for="address in addresses"
          :key="address.id"
          class="address-card panel"
        >
          <button
            class="address-choice"
            :disabled="locked"
            :aria-pressed="address.id === addressId"
            @click="chooseAddress(address)"
          >
            <strong>{{ address.recipient }} {{ address.phone }}</strong
            ><small v-if="address.is_default" class="default-tag">默认</small>
            <p>{{ fullAddress(address) }}</p>
            <span>{{
              address.id === addressId ? "当前地址" : "使用此地址"
            }}</span>
          </button>
          <div class="address-actions">
            <button
              v-if="!address.is_default"
              :disabled="locked"
              @click="setDefault(address)"
            >
              设为默认</button
            ><button :disabled="locked" @click="editAddress(address)">
              编辑</button
            ><button :disabled="locked" @click="removingId = address.id">
              移除
            </button>
          </div>
          <div v-if="removingId === address.id" class="delete-confirm">
            <p>确认移除此地址？已有订单地址将保留。</p>
            <button :disabled="locked" @click="removeAddress(address)">
              确认移除</button
            ><button :disabled="locked" @click="removingId = 0">保留</button>
          </div>
        </article>
      </template>
      <template v-else-if="createdOrderId">
        <div class="empty panel">
          <h2>订单已生成</h2>
          <p>当前为待付款状态，请进入订单详情继续付款。</p>
          <button
            class="commerce-primary"
            @click="emit('order', createdOrderId)"
          >
            查看订单
          </button>
        </div>
      </template>
      <template v-else>
        <button
          v-if="directPurchase && !uncertainOrder"
          class="add-to-bag"
          :disabled="locked || !checkoutItems.length"
          @click="addToBag"
        >
          加入购物袋，继续挑选
        </button>
        <button
          class="checkout-address panel"
          :disabled="locked || uncertainOrder"
          @click="mode = 'addresses'"
        >
          <img :src="icons.pin" alt="" />
          <div v-if="selectedAddress">
            <strong
              >{{ selectedAddress.recipient }}
              {{ selectedAddress.phone }}</strong
            ><small>{{ fullAddress(selectedAddress) }}</small>
          </div>
          <div v-else>
            <strong>请选择收货地址</strong><small>新增或选择本人收货地址</small>
          </div>
          <img class="chevron" :src="icons.arrow" alt="" />
        </button>
        <template v-if="quote">
          <section class="checkout-products panel">
            <div class="checkout-warehouse">
              <img :src="icons.warehouse" alt="" /><strong
                >清欣简妍肌品牌仓</strong
              >
            </div>
            <article
              v-for="line in quote.lines"
              :key="line.skuId"
              class="checkout-line"
            >
              <img :src="line.image" :alt="line.name" />
              <div>
                <h2>{{ line.name }}</h2>
                <small>{{ line.skuName }}</small>
              </div>
              <div class="line-amount">
                <strong>{{ money(line.unitCents) }}</strong
                ><small>×{{ line.quantity }}</small>
              </div>
            </article>
            <div class="bill-row">
              <span>配送方式</span
              ><span
                >品牌仓快递 ·
                {{
                  quote.shippingCents === 0
                    ? "包邮"
                    : money(quote.shippingCents)
                }}</span
              >
            </div>
            <div class="shipping-instructions">
              <span>发货说明</span>
              <p>{{ quote.shippingInstructions }}</p>
            </div>
          </section>
          <section class="panel bill">
            <div class="bill-row">
              <span>商品合计</span
              ><strong>{{ money(quote.goodsCents) }}</strong>
            </div>
            <div class="bill-row">
              <span>运费</span><strong>{{ money(quote.shippingCents) }}</strong>
            </div>
            <div class="bill-total">
              <span>应付总额</span
              ><strong class="price">{{ money(quote.totalCents) }}</strong>
            </div>
          </section>
          <section class="order-method">
            <img :src="icons.shield" alt="" />
            <div>
              <strong>{{ identityNames[quote.identity] }}适用价</strong
              ><small>生成订单后，在订单详情选择支付方式</small>
            </div>
          </section>
          <p class="checkout-explanation">
            提交后生成待付款订单并占用库存，请在
            {{ quote.pendingMinutes }} 分钟内完成支付。此步骤不会扣款或扣积分。
          </p>
        </template>
        <div v-else class="empty">
          <p>
            {{
              addressId
                ? "配送、价格或库存暂未确认，请核对提示后重新获取金额。"
                : "选择收货地址后，将为你核验配送范围与订单金额。"
            }}
          </p>
          <button
            v-if="addressId"
            :disabled="locked"
            @click="act(() => fetchQuote())"
          >
            重新核对订单
          </button>
        </div>
        <p v-if="uncertainOrder" class="feedback" role="status">
          订单创建结果正在确认。请点击下方按钮重试原请求，避免重复下单。
        </p>
      </template>
    </div>
    <footer
      v-if="mode !== 'addresses' && loaded && !createdOrderId"
      class="commerce-footer"
    >
      <div>
        <small>{{ mode === "cart" ? "合计（不含运费）" : "需支付" }}</small
        ><strong class="price">{{
          mode === "cart"
            ? money(goodsCents)
            : quote
              ? money(quote.totalCents)
              : "待确认"
        }}</strong>
      </div>
      <button
        class="commerce-primary"
        :disabled="locked || (mode === 'cart' ? !canCheckout : !quote)"
        @click="mode === 'cart' ? beginCheckout() : confirmOrder()"
      >
        {{
          busy
            ? "处理中…"
            : mode === "cart"
              ? "去结算"
              : uncertainOrder
                ? "确认原订单结果"
                : "提交订单"
        }}
      </button>
    </footer>
  </section>
</template>

<style scoped>
.commerce {
  --wine: #8d1724;
  --ink: #251d1a;
  --muted: #8e8178;
  --border: #4530261f;
  display: flex;
  flex-direction: column;
  min-height: 830px;
  min-height: min(830px, 100dvh);
  padding: 0;
  background: #f2ece4;
  color: var(--ink);
  width: 100%;
  min-width: 0;
  font:
    12px/1.5 "PingFang SC",
    sans-serif;
}
.commerce *,
.commerce *::before,
.commerce *::after {
  box-sizing: border-box;
}
.commerce button {
  font: inherit;
  border: 0;
  margin: 0;
  border-radius: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.commerce button:disabled {
  cursor: default;
  opacity: 0.5;
}
.commerce button:focus-visible,
.commerce input:focus-visible,
.commerce textarea:focus-visible {
  outline: 2px solid var(--wine);
  outline-offset: 3px;
}
.commerce h1,
.commerce h2,
.commerce p {
  margin: 0;
}
.commerce p {
  line-height: 1.7;
}
.commerce h2 {
  font:
    12px/1.5 STSong,
    "Songti SC",
    serif;
}
.commerce small {
  font-size: 9px;
  color: var(--muted);
}
.flow-header {
  height: 64px;
  flex: 0 0 64px;
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) 48px;
  gap: 4px;
  align-items: center;
  padding: 12px 10px 0;
  background: #f8f5eef5;
  border-bottom: 1px solid var(--border);
}
.flow-header h1 {
  font:
    16px/24px STSong,
    "Songti SC",
    serif;
  letter-spacing: 1px;
  text-align: center;
}
.flow-header > small {
  text-align: right;
  font-size: 8px;
}
.flow-back {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
}
.flow-back img {
  width: 20px;
  height: 20px;
}
.commerce-body {
  flex: 1;
  min-width: 0;
  padding: 14px 16px 26px;
}
.commerce-body.checkout-body {
  padding: 12px 14px 28px;
}
.warehouse {
  display: flex;
  align-items: center;
  gap: 11px;
  background: #e9ddcf;
  padding: 13px 15px;
}
.warehouse img {
  width: 19px;
  height: 19px;
}
.warehouse strong {
  display: block;
  color: #7c5945;
  font-size: 10px;
}
.warehouse small {
  display: block;
  font-size: 8px;
  margin-top: 4px;
}
.cart-list {
  margin-top: 10px;
  padding: 0 14px;
  background: #fffdfa;
}
.cart-row {
  display: grid;
  grid-template-columns: 20px 86px minmax(0, 1fr);
  gap: 11px;
  padding: 17px 0;
  align-items: center;
}
.cart-row + .cart-row {
  border-top: 1px solid var(--border);
}
.product-image {
  width: 86px;
  height: 93px;
  object-fit: cover;
  border-radius: 3px;
}
.item-select {
  position: relative;
  display: block;
  width: 20px;
  height: 32px;
  cursor: pointer;
}
.item-select input {
  opacity: 0;
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  cursor: pointer;
}
.item-select span {
  display: grid;
  place-items: center;
  width: 17px;
  height: 17px;
  border: 1px solid #b3a69b;
  border-radius: 50%;
  position: absolute;
  top: 8px;
  pointer-events: none;
}
.item-select input:checked + span {
  background: var(--wine);
  border-color: var(--wine);
}
.item-select input:focus-visible + span {
  outline: 2px solid var(--wine);
  outline-offset: 3px;
}
.item-select img {
  width: 12px;
  height: 12px;
}
.cart-copy {
  min-width: 0;
  align-self: stretch;
  display: flex;
  flex-direction: column;
  position: relative;
  padding-top: 2px;
}
.cart-copy h2 {
  overflow-wrap: anywhere;
  padding-right: 22px;
}
.cart-copy .sku {
  align-self: flex-start;
  background: #f1ece6;
  padding: 3px 6px;
  font-size: 8px;
  margin: 6px 0;
}
.item-bottom {
  display: flex;
  justify-content: space-between;
  gap: 5px;
  flex-wrap: wrap;
  align-items: center;
  margin-top: auto;
}
.price {
  font:
    bold 17px/1.5 Georgia,
    serif;
  color: var(--wine);
  overflow-wrap: anywhere;
}
.cart-copy .price {
  font-size: 13px;
}
.quantity {
  display: flex;
  height: 25px;
  flex-shrink: 0;
}
.quantity button,
.quantity span {
  display: grid;
  place-items: center;
  border: 1px solid var(--border);
  min-width: 25px;
  height: 25px;
  padding: 0;
  background: #fff;
  font-size: 10px;
}
.quantity span {
  min-width: 26px;
  border-inline: 0;
}
.commerce .remove-item {
  align-self: flex-end;
  font-size: 9px;
  color: var(--muted);
  padding: 3px 0 0 10px;
}
.unavailable {
  font-size: 9px;
  color: var(--wine);
  margin-bottom: 4px !important;
}
.purchase-notes {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  background: #eee4d7;
  color: #8d765f;
  padding: 13px;
  margin-top: 10px;
}
.purchase-notes img {
  width: 17px;
  height: 17px;
  flex-shrink: 0;
}
.purchase-notes p {
  font-size: 9px;
}
.bag-summary {
  color: var(--muted);
  font-size: 9px;
  padding: 12px 0;
}
.commerce-footer {
  position: sticky;
  bottom: 0;
  z-index: 2;
  background: #fffdfaf7;
  border-top: 1px solid var(--border);
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding: 11px 18px max(14px, env(safe-area-inset-bottom));
  min-height: 72px;
  box-shadow: 0 -8px 24px #3f2a210d;
}
.commerce-footer > div {
  min-width: 0;
  flex: 1;
}
.commerce-footer small,
.commerce-footer strong {
  display: block;
}
.commerce .commerce-primary {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  background: var(--wine);
  color: #fff;
  min-height: 43px;
  min-width: 0;
  padding: 10px 18px;
  font-size: 11px;
}
.commerce-footer .commerce-primary {
  flex: 0 0 46%;
  max-width: 155px;
}
.panel {
  background: #fffdfa;
  padding: 0 16px;
  margin-bottom: 10px;
}
.commerce .checkout-address {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr) 16px;
  align-items: center;
  gap: 11px;
  text-align: left;
  width: 100%;
  padding: 18px 16px;
  background: #fffdfa;
}
.checkout-address > img {
  width: 22px;
  height: 22px;
}
.checkout-address > img.chevron {
  width: 16px;
  height: 16px;
}
.checkout-address strong {
  font-size: 11px;
}
.checkout-address small {
  display: block;
  margin-top: 7px;
  overflow-wrap: anywhere;
}
.checkout-warehouse {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 15px 0;
  border-bottom: 1px solid var(--border);
  color: #6d4d3e;
  font-size: 10px;
}
.checkout-warehouse img {
  width: 18px;
  height: 18px;
}
.checkout-line {
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr) auto;
  gap: 11px;
  padding: 14px 0;
}
.checkout-line > img {
  width: 70px;
  height: 76px;
  object-fit: cover;
  border-radius: 3px;
}
.checkout-line h2 {
  font:
    600 10px/1.5 "PingFang SC",
    sans-serif;
  overflow-wrap: anywhere;
}
.checkout-line small {
  display: block;
  margin-top: 6px;
  font-size: 8px;
}
.line-amount {
  text-align: right;
  font-size: 10px;
}
.bill-row {
  min-height: 44px;
  border-top: 1px solid var(--border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  font-size: 10px;
}
.bill-row strong {
  font-weight: 500;
}
.bill-row > :last-child {
  text-align: right;
}
.shipping-instructions {
  border-top: 1px solid var(--border);
  padding: 14px 0;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 16px;
  font-size: 10px;
}
.shipping-instructions p {
  color: var(--muted);
  text-align: right;
  white-space: pre-line;
  overflow-wrap: anywhere;
}
.bill > :first-child {
  border-top: 0;
}
.bill-total {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  min-height: 59px;
  border-top: 1px solid var(--border);
  font-size: 10px;
}
.bill-total .price {
  font-size: 20px;
}
.order-method {
  display: flex;
  gap: 10px;
  align-items: center;
  background: #59131b;
  color: white;
  padding: 16px;
}
.order-method img {
  width: 19px;
  height: 19px;
}
.order-method strong {
  font-size: 10px;
}
.order-method small {
  display: block;
  color: #ffffff94;
  font-size: 9px;
  margin-top: 4px;
}
.checkout-explanation {
  padding: 13px 4px 0;
  color: var(--muted);
  font-size: 9px;
}
.empty {
  text-align: center;
  padding: 48px 12px;
  color: var(--muted);
}
.empty h2 {
  font-size: 16px;
}
.empty p {
  margin: 12px 0;
}
.empty button {
  border-bottom: 1px solid currentColor;
  padding: 9px 14px;
  color: var(--wine);
}
.feedback {
  padding: 12px;
  margin-bottom: 10px !important;
  color: #735c42;
  background: #e9ddcf;
  overflow-wrap: anywhere;
}
.feedback.error {
  color: #8d1724;
  background: #f8e4e2;
}
.feedback.success {
  background: #e7eddf;
  color: #435c36;
}
.commerce .add-address {
  border: 1px dashed #b69a81;
  padding: 14px;
  width: 100%;
  color: var(--wine);
  margin-bottom: 12px;
}
.commerce .add-to-bag {
  color: var(--wine);
  display: block;
  margin: 0 0 12px auto;
  padding: 4px 0;
  font-size: 11px;
}
.address-form {
  padding: 18px 16px;
  display: grid;
  gap: 18px;
}
.address-form h2 {
  font-size: 16px;
}
.address-form fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 14px;
  min-width: 0;
}
.address-form label {
  display: grid;
  gap: 6px;
  font-size: 11px;
}
.address-form input,
.address-form textarea {
  width: 100%;
  min-width: 0;
  border: 1px solid var(--border);
  padding: 10px;
  background: white;
  border-radius: 2px;
  font:
    13px/1.5 "PingFang SC",
    sans-serif;
  color: var(--ink);
}
.address-form textarea {
  resize: vertical;
}
.region-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.address-form .check-label {
  display: flex;
  align-items: center;
}
.address-form .check-label input {
  width: 17px;
  height: 17px;
  accent-color: var(--wine);
}
.form-actions,
.address-actions {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  align-items: center;
}
.form-actions button {
  flex: 1;
}
.address-card {
  padding-block: 16px;
}
.commerce .address-choice {
  width: 100%;
  text-align: left;
  padding: 0;
  overflow-wrap: anywhere;
}
.address-choice p {
  color: var(--muted);
  font-size: 11px;
  margin: 8px 0;
}
.address-choice span {
  font-size: 10px;
  color: var(--wine);
}
.default-tag {
  margin-left: 8px;
  background: #f8e4e2;
  padding: 2px 5px;
  color: var(--wine) !important;
}
.address-actions {
  justify-content: flex-end;
  border-top: 1px solid var(--border);
  margin-top: 12px;
  padding-top: 8px;
}
.address-actions button,
.delete-confirm button {
  padding: 6px 0;
  font-size: 11px;
  color: var(--muted);
}
.delete-confirm {
  border-top: 1px solid var(--border);
  margin-top: 10px;
  padding-top: 10px;
}
.delete-confirm button {
  margin-right: 20px;
  color: var(--wine);
}
@media (min-width: 600px) {
  .commerce {
    min-height: 830px;
  }
}
@media (max-width: 350px) {
  .cart-row {
    grid-template-columns: 20px 66px minmax(0, 1fr);
    gap: 8px;
  }
  .product-image {
    width: 66px;
  }
  .region-fields {
    grid-template-columns: 1fr;
  }
  .checkout-line {
    grid-template-columns: 56px minmax(0, 1fr) auto;
    gap: 8px;
  }
  .checkout-line > img {
    width: 56px;
  }
}
</style>
