<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { Select } from "ant-design-vue";
import { api } from "../client";
defineOptions({ inheritAttrs: false });
interface UserOption {
  id: number;
  label: string;
  identity: string;
}
interface UserPage {
  items: UserOption[];
  total: number;
  page: number;
}
const props = withDefaults(
  defineProps<{
    modelValue?: string;
    identity?: "" | "B" | "agents";
    disabledIds?: string[];
    disabled?: boolean;
    required?: boolean;
    placeholder?: string;
  }>(),
  { identity: "", disabledIds: () => [] },
);
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const select = ref<{ focus: () => void }>();
const validationInput = ref<HTMLInputElement>();
const rows = ref<UserOption[]>([]),
  cache = ref<Record<string, UserOption>>({});
const total = ref(0),
  page = ref(0),
  query = ref("");
const loading = ref(false),
  error = ref(""),
  resolvingError = ref("");
let generation = 0,
  resolveGeneration = 0;
let controller: AbortController | undefined,
  resolveController: AbortController | undefined;
let timer: ReturnType<typeof setTimeout> | undefined;
const options = computed(() => {
  const visible = new Map(rows.value.map((row) => [String(row.id), row]));
  const id = props.modelValue;
  if (id && !visible.has(id))
    visible.set(
      id,
      cache.value[id] || {
        id: Number(id),
        label: `用户 #${id}（${resolvingError.value ? "读取失败" : "尚未读取或不符合条件"}）`,
        identity: "",
      },
    );
  return Array.from(visible.entries()).map(([value, row]) => ({
    value,
    label: row.label,
    disabled: !row.identity || props.disabledIds.includes(value),
  }));
});
const validSelection = computed(() =>
  options.value.some((row) => row.value === props.modelValue && !row.disabled),
);
function parameters() {
  const params = new URLSearchParams({ pageSize: "20" });
  if (props.identity) params.set("identity", props.identity);
  return params;
}
function remember(items: UserOption[]) {
  for (const row of items) cache.value[String(row.id)] = row;
}
async function load(more = false) {
  if (more && (loading.value || rows.value.length >= total.value)) return;
  controller?.abort();
  const current = ++generation;
  controller = new AbortController();
  loading.value = true;
  error.value = "";
  const params = parameters();
  params.set("q", query.value);
  params.set("page", String(more ? page.value + 1 : 1));
  try {
    const result = await api<UserPage>(`/admin/user-options?${params}`, {
      signal: controller.signal,
    });
    if (current !== generation) return;
    remember(result.items);
    rows.value = more ? [...rows.value, ...result.items] : result.items;
    total.value = result.total;
    page.value = result.page;
  } catch (e) {
    if (current === generation)
      error.value = e instanceof Error ? e.message : "用户读取失败";
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
  total.value = 0;
  page.value = 0;
  loading.value = true;
  error.value = "";
  timer = setTimeout(() => void load(), 250);
}
function open(visible: boolean) {
  if (!visible) return;
  clearTimeout(timer);
  query.value = "";
  void load();
}
async function resolveSelected() {
  resolveController?.abort();
  const current = ++resolveGeneration;
  const id = props.modelValue;
  resolvingError.value = "";
  if (!id || cache.value[id]) return;
  resolveController = new AbortController();
  const params = parameters();
  params.set("id", id);
  try {
    const result = await api<UserPage>(`/admin/user-options?${params}`, {
      signal: resolveController.signal,
    });
    if (current === resolveGeneration) remember(result.items);
  } catch (e) {
    if (current === resolveGeneration)
      resolvingError.value = e instanceof Error ? e.message : "用户读取失败";
  }
}
function choose(value: unknown) {
  const id = value == null ? "" : String(value);
  if (id && !options.value.some((row) => row.value === id && !row.disabled))
    return;
  emit("update:modelValue", id);
}
watch(
  () => props.identity,
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
  () => props.modelValue,
  () => void resolveSelected(),
  { immediate: true },
);
watch(
  validSelection,
  () =>
    void nextTick(() =>
      validationInput.value?.dispatchEvent(
        new Event("change", { bubbles: true }),
      ),
    ),
);
onBeforeUnmount(() => {
  clearTimeout(timer);
  ++generation;
  ++resolveGeneration;
  controller?.abort();
  resolveController?.abort();
});
defineExpose({ focus: () => select.value?.focus() });
</script>
<template>
  <span class="admin-user-select" data-admin-control>
    <Select
      ref="select"
      v-bind="$attrs"
      :value="modelValue || undefined"
      :options="options"
      :disabled="disabled"
      :loading="loading"
      show-search
      :filter-option="false"
      allow-clear
      :placeholder="placeholder || '搜索昵称、手机号或用户ID'"
      :not-found-content="
        loading
          ? '正在搜索…'
          : error
            ? '用户读取失败，请重试'
            : '没有匹配用户，请调整关键词'
      "
      :dropdown-style="{ minWidth: 'min(420px, 90vw)' }"
      @search="search"
      @change="choose"
      @dropdown-visible-change="open"
    >
      <template #dropdownRender="{ menuNode }">
        <component :is="menuNode" />
        <div
          v-if="error || rows.length < total"
          class="user-search-footer"
          @mousedown.prevent
        >
          <span>{{ error || `已显示 ${rows.length} / ${total} 位用户` }}</span>
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
      :value="validSelection ? 'selected' : ''"
      :disabled="disabled"
      required
    />
    <span v-if="resolvingError" class="user-search-error" role="status"
      >用户读取失败<a-button type="link" size="small" @click="resolveSelected"
        >重试</a-button
      ></span
    >
  </span>
</template>
<style scoped>
.admin-user-select {
  display: grid;
  gap: 5px;
  width: 100%;
  min-width: 0;
}
.admin-user-select :deep(.ant-select) {
  width: 100%;
  min-width: 0;
}
.user-search-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 12px;
  border-top: 1px solid var(--admin-border, #e5e7eb);
  color: var(--admin-muted, #687080);
  font-size: 12px;
}
.user-search-error {
  color: #ad3544;
  font-size: 12px;
}
</style>
