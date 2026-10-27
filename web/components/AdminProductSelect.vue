<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { Select } from "ant-design-vue";
import { api } from "../client";
defineOptions({ inheritAttrs: false });
interface Option {
  id: number;
  label: string;
  active: boolean;
  quantity?: number;
}
interface Page {
  items: Option[];
  total: number;
  page: number;
  pageSize: number;
}
const props = withDefaults(
  defineProps<{
    modelValue?: number | number[];
    kind?: "sku" | "product" | "orderLine" | "lot";
    multiple?: boolean;
    includeInactive?: boolean;
    disabled?: boolean;
    required?: boolean;
    disabledIds?: number[];
    userId?: string;
    max?: number;
    placeholder?: string;
  }>(),
  { kind: "sku", max: 10, disabledIds: () => [] },
);
const validationInput = ref<HTMLInputElement>();
const emit = defineEmits<{
  "update:modelValue": [value: number | number[]];
  select: [option: Option];
}>();
const rows = ref<Option[]>([]),
  cache = ref<Record<number, Option>>({}),
  total = ref(0),
  page = ref(0);
const loading = ref(false),
  error = ref(""),
  resolvingError = ref("");
const query = ref("");
let generation = 0,
  resolveGeneration = 0,
  controller: AbortController | undefined,
  resolveController: AbortController | undefined;
let timer: ReturnType<typeof setTimeout> | undefined;
const ids = computed(() =>
  (Array.isArray(props.modelValue)
    ? props.modelValue
    : [props.modelValue]
  ).filter((id): id is number => !!id),
);
const value = computed(() => (props.multiple ? ids.value : ids.value[0]));
const options = computed(() => {
  const visible = new Map(rows.value.map((row) => [row.id, row]));
  for (const id of ids.value)
    if (!visible.has(id))
      visible.set(
        id,
        cache.value[id] || {
          id,
          label: `${props.kind === "product" ? "原商品" : "原商品明细"} #${id}（${resolvingError.value ? "名称读取失败" : "已不可用"}）`,
          active: false,
        },
      );
  return Array.from(visible.values()).map((row) => ({
    value: row.id,
    label: row.label,
    disabled:
      (!props.includeInactive && !row.active) ||
      props.disabledIds.includes(row.id) ||
      (props.multiple &&
        ids.value.length >= props.max &&
        !ids.value.includes(row.id)),
  }));
});
function parameters() {
  const params = new URLSearchParams({ kind: props.kind, pageSize: "20" });
  if (props.includeInactive) params.set("includeInactive", "true");
  if (props.userId) params.set("userId", props.userId);
  return params;
}
function remember(items: Option[]) {
  for (const row of items) cache.value[row.id] = row;
}
async function load(more = false) {
  if (more && (loading.value || rows.value.length >= total.value)) return;
  controller?.abort();
  const current = ++generation;
  controller = new AbortController();
  loading.value = true;
  error.value = "";
  const next = more ? page.value + 1 : 1;
  const params = parameters();
  params.set("q", query.value);
  params.set("page", String(next));
  try {
    const result = await api<Page>(`/admin/product-options?${params}`, {
      signal: controller.signal,
    });
    if (current !== generation) return;
    remember(result.items);
    rows.value = more ? [...rows.value, ...result.items] : result.items;
    total.value = result.total;
    page.value = result.page;
  } catch (e) {
    if (current === generation)
      error.value = e instanceof Error ? e.message : "商品读取失败";
  } finally {
    if (current === generation) loading.value = false;
  }
}
function search(text: string) {
  query.value = text;
  clearTimeout(timer);
  controller?.abort();
  ++generation;
  rows.value = [];
  page.value = 0;
  total.value = 0;
  loading.value = true;
  timer = setTimeout(() => {
    void load();
  }, 250);
}
function open(visible: boolean) {
  if (visible) {
    clearTimeout(timer);
    query.value = "";
    void load();
  }
}
async function resolveSelected() {
  resolveController?.abort();
  const current = ++resolveGeneration;
  const missing = ids.value.filter((id) => !cache.value[id]);
  if (!missing.length) {
    resolvingError.value = "";
    return;
  }
  resolveController = new AbortController();
  const params = parameters();
  params.set("ids", missing.join(","));
  params.set("pageSize", "100");
  try {
    const result = await api<Page>(`/admin/product-options?${params}`, {
      signal: resolveController.signal,
    });
    if (current !== resolveGeneration) return;
    remember(result.items);
    resolvingError.value = "";
  } catch (e) {
    if (current === resolveGeneration)
      resolvingError.value =
        e instanceof Error ? e.message : "商品名称读取失败";
  }
}
function choose(next: unknown) {
  const selected = Array.isArray(next)
    ? next.map(Number)
    : next
      ? [Number(next)]
      : [];
  if (props.multiple && selected.length > props.max) return;
  emit("update:modelValue", props.multiple ? selected : selected[0] || 0);
  const last = selected[selected.length - 1];
  if (last && cache.value[last]) emit("select", cache.value[last]);
  query.value = "";
}
watch(
  () => [props.kind, props.userId, props.includeInactive],
  () => {
    controller?.abort();
    ++generation;
    clearTimeout(timer);
    rows.value = [];
    cache.value = {};
    total.value = 0;
    page.value = 0;
    loading.value = false;
    error.value = "";
    void resolveSelected();
  },
);
watch(
  ids,
  () => {
    void resolveSelected();
    void nextTick(() =>
      validationInput.value?.dispatchEvent(
        new Event("change", { bubbles: true }),
      ),
    );
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  clearTimeout(timer);
  ++generation;
  ++resolveGeneration;
  controller?.abort();
  resolveController?.abort();
});
</script>
<template>
  <span class="admin-product-select" data-admin-control>
    <Select
      v-bind="$attrs"
      :value="value"
      :mode="multiple ? 'multiple' : undefined"
      :options="options"
      :disabled="disabled"
      :loading="loading"
      :show-search="true"
      :filter-option="false"
      :allow-clear="true"
      :placeholder="
        placeholder ||
        (kind === 'product' ? '搜索商品名称、规格或分类' : '搜索商品名称或规格')
      "
      :not-found-content="
        loading
          ? '正在搜索…'
          : error
            ? '商品读取失败，请重试'
            : '没有匹配商品，请调整关键词'
      "
      :dropdown-style="{ minWidth: 'min(360px, 90vw)' }"
      @search="search"
      @change="choose"
      @dropdown-visible-change="open"
    >
      <template #dropdownRender="{ menuNode }">
        <component :is="menuNode" />
        <div
          v-if="error || rows.length < total"
          class="product-search-footer"
          @mousedown.prevent
        >
          <span>{{ error || `已显示 ${rows.length} / ${total} 项` }}</span>
          <a-button
            type="link"
            size="small"
            :loading="loading"
            @click="load(!error && page > 0)"
            >{{ error ? "重试" : "加载更多" }}</a-button
          >
        </div>
      </template>
    </Select>
    <input
      v-if="required"
      ref="validationInput"
      class="admin-validation-proxy"
      tabindex="-1"
      aria-hidden="true"
      :value="ids.length ? 'selected' : ''"
      :disabled="disabled"
      required
    />
    <span v-if="resolvingError" class="product-search-error" role="status"
      >商品名称读取失败
      <a-button type="link" size="small" @click="resolveSelected"
        >重试</a-button
      ></span
    >
    <span v-if="multiple" class="product-search-hint"
      >已选 {{ ids.length }} / {{ max }} 个商品</span
    >
  </span>
</template>
<style scoped>
.admin-product-select {
  display: grid;
  gap: 5px;
  min-width: 0;
  width: 100%;
}
.admin-product-select :deep(.ant-select) {
  width: 100%;
  min-width: 0;
}
.product-search-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 12px;
  border-top: 1px solid #e5e7eb;
  color: #687080;
  font-size: 12px;
}
.product-search-error {
  color: #ad3544;
  font-size: 12px;
}
.product-search-hint {
  color: var(--admin-muted, #687080);
  font-size: 12px;
}
</style>
