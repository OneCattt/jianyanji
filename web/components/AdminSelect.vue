<script setup lang="ts">
import { computed, onMounted, onUpdated, ref, useAttrs, useId } from "vue";
import { Select } from "ant-design-vue";
defineOptions({ inheritAttrs: false });
const props = defineProps<{
  modelValue?: unknown;
  modelModifiers?: { number?: boolean };
}>();
const emit = defineEmits<{
  "update:modelValue": [value: unknown];
  change: [event: Event];
}>();
const attrs = useAttrs();
const id = useId();
const source = ref<HTMLSelectElement>();
const options = ref<{ value: number; label: string; disabled: boolean }[]>([]);
const selected = ref<number>();
const disabled = ref(false);
const model = computed({
  get: () => props.modelValue,
  set: (value: unknown) => {
    if (
      props.modelModifiers?.number &&
      typeof value === "string" &&
      value !== ""
    ) {
      const number = Number(value);
      if (!Number.isNaN(number)) value = number;
    }
    emit("update:modelValue", value);
  },
});
const sourceAttrs = computed(() =>
  Object.fromEntries(
    Object.entries(attrs).filter(
      ([key]) => !["id", "class", "style"].includes(key),
    ),
  ),
);
function sync() {
  if (!source.value) return;
  const next = Array.from(source.value.options).map((option, index) => ({
    value: index,
    label: option.label,
    disabled: option.disabled || Boolean(option.closest("optgroup")?.disabled),
  }));
  if (JSON.stringify(next) !== JSON.stringify(options.value))
    options.value = next;
  selected.value =
    source.value.selectedIndex < 0 ? undefined : source.value.selectedIndex;
  disabled.value = source.value.matches(":disabled");
}
function choose(index: unknown) {
  if (!source.value || typeof index !== "number") return;
  source.value.selectedIndex = index;
  source.value.dispatchEvent(new Event("change", { bubbles: true }));
}
onMounted(sync);
onUpdated(sync);
</script>

<template>
  <span
    class="admin-select-control"
    data-admin-control
    :class="attrs.class"
    :style="attrs.style as any"
  >
    <Select
      :id="String(attrs.id || id)"
      :value="selected"
      :options="options"
      :disabled="disabled"
      :show-search="true"
      option-filter-prop="label"
      :aria-label="attrs['aria-label'] as string"
      @change="choose"
    />
    <select
      ref="source"
      v-model="model"
      v-bind="sourceAttrs"
      class="admin-validation-proxy"
      tabindex="-1"
      aria-hidden="true"
      @change="emit('change', $event)"
    >
      <slot />
    </select>
  </span>
</template>
