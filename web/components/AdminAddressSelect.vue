<script setup lang="ts">
import { computed, onScopeDispose, ref, useId, watch } from "vue";
import { Select } from "ant-design-vue";
import { api } from "../client";
defineOptions({ inheritAttrs: false });
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
const props = defineProps<{
  modelValue: string;
  userId: string;
  disabled?: boolean;
  required?: boolean;
}>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const id = useId(),
  query = ref(""),
  page = ref(1),
  total = ref(0);
const rows = ref<Address[]>([]),
  selected = ref<Address>(),
  loading = ref(false),
  error = ref("");
let controller: AbortController | undefined,
  generation = 0,
  timer: ReturnType<typeof setTimeout>;
function label(row: Address) {
  return `${row.recipient} · ${row.phone} · ${row.province}${row.city}${row.district}${row.detail}${row.is_default ? "（默认）" : ""}`;
}
const options = computed(() => {
  const items = [...rows.value];
  if (selected.value && !items.some((row) => row.id === selected.value!.id))
    items.unshift(selected.value);
  return items.map((row) => ({ value: String(row.id), label: label(row) }));
});
const valid = computed(
  () => selected.value && String(selected.value.id) === props.modelValue,
);
async function load() {
  controller?.abort();
  const current = ++generation;
  if (!props.userId) return;
  const active = new AbortController();
  controller = active;
  loading.value = true;
  error.value = "";
  try {
    const q = new URLSearchParams({
      q: query.value,
      page: String(page.value),
      pageSize: "20",
    });
    const result = await api<{ items: Address[]; total: number }>(
      `/admin/users/${props.userId}/address-options?${q}`,
      { signal: active.signal },
    );
    if (current !== generation) return;
    rows.value = result.items;
    total.value = result.total;
  } catch (e) {
    if (current === generation)
      error.value = e instanceof Error ? e.message : "地址读取失败";
  } finally {
    if (current === generation) loading.value = false;
  }
}
function choose(value: unknown) {
  selected.value =
    rows.value.find((row) => String(row.id) === value) ||
    (String(selected.value?.id) === value ? selected.value : undefined);
  emit("update:modelValue", selected.value ? String(selected.value.id) : "");
}
watch(
  () => props.userId,
  () => {
    clearTimeout(timer);
    controller?.abort();
    generation++;
    rows.value = [];
    selected.value = undefined;
    total.value = 0;
    page.value = 1;
    query.value = "";
    loading.value = false;
    error.value = "";
    emit("update:modelValue", "");
    void load();
  },
  { immediate: true, flush: "sync" },
);
watch(query, () => {
  clearTimeout(timer);
  controller?.abort();
  generation++;
  rows.value = [];
  page.value = 1;
  timer = setTimeout(() => void load(), 250);
});
function move(offset: number) {
  page.value += offset;
  void load();
}
onScopeDispose(() => {
  clearTimeout(timer);
  generation++;
  controller?.abort();
});
</script>
<template>
  <span class="admin-address-select" data-admin-control>
    <Select
      :id="String($attrs.id || id)"
      :value="modelValue || undefined"
      :options="options"
      :disabled="disabled || !userId"
      :loading="loading"
      show-search
      :filter-option="false"
      allow-clear
      placeholder="选择该用户的收货地址"
      :dropdown-style="{ maxWidth: 'calc(100vw - 32px)' }"
      @search="query = $event"
      @change="choose"
    >
      <template #notFoundContent>{{
        loading
          ? "正在读取地址…"
          : error
            ? "地址读取失败，请重试"
            : query
              ? "没有匹配的收货地址"
              : "该用户暂无收货地址，请先由用户添加"
      }}</template>
      <template #option="option"
        ><span class="address-option">{{ option.label }}</span></template
      >
      <template #dropdownRender="{ menuNode }"
        ><component :is="menuNode" />
        <div v-if="total > 20" class="address-pages">
          <span>共 {{ total }} 个地址</span
          ><a-button
            size="small"
            :disabled="loading || page <= 1"
            @click="move(-1)"
            >上一页</a-button
          ><a-button
            size="small"
            :disabled="loading || page * 20 >= total"
            @click="move(1)"
            >下一页</a-button
          >
        </div></template
      >
    </Select>
    <input
      v-if="required"
      class="admin-validation-proxy"
      tabindex="-1"
      aria-hidden="true"
      :value="valid ? 'selected' : ''"
      :disabled="disabled"
      required
    />
    <span v-if="selected" class="address-preview">{{ label(selected) }}</span>
    <span v-if="error" role="status"
      >地址读取失败<a-button type="link" size="small" @click="load"
        >重试</a-button
      ></span
    >
  </span>
</template>
<style scoped>
.admin-address-select {
  display: grid;
  min-width: 0;
  gap: 8px;
}
.admin-address-select :deep(.ant-select) {
  width: 100%;
  min-width: 0;
}
.address-option,
.address-preview {
  white-space: normal;
  overflow-wrap: anywhere;
  line-height: 1.7;
}
.address-preview {
  color: var(--admin-muted);
  font-size: 12px;
}
.address-pages {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid var(--admin-border);
}
.address-pages > span {
  margin-right: auto;
}
</style>
