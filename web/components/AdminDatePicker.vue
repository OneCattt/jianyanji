<script setup lang="ts">
import { computed, nextTick, ref, useAttrs, useId } from "vue";
import { DatePicker } from "ant-design-vue";
import dayjs, { type Dayjs } from "dayjs";
defineOptions({ inheritAttrs: false });
const props = withDefaults(
  defineProps<{
    type?: "datetime-local" | "date" | "month";
    disabled?: boolean;
    required?: boolean;
    min?: string;
    max?: string;
  }>(),
  { type: "datetime-local", disabled: false, required: false },
);
const model = defineModel<string>({ default: "" });
const attrs = useAttrs();
const id = useId();
const source = ref<HTMLInputElement>();
const valueFormat = computed(() =>
  props.type === "month"
    ? "YYYY-MM"
    : props.type === "date"
      ? "YYYY-MM-DD"
      : "YYYY-MM-DDTHH:mm",
);
const displayFormat = computed(() =>
  props.type === "datetime-local" ? "YYYY-MM-DD HH:mm" : valueFormat.value,
);
async function changed(value: unknown) {
  model.value = typeof value === "string" ? value : "";
  await nextTick();
  source.value?.dispatchEvent(new Event("change", { bubbles: true }));
}
function disabledDate(value: Dayjs) {
  return Boolean(
    (props.min && value.endOf("day").isBefore(dayjs(props.min))) ||
    (props.max && value.startOf("day").isAfter(dayjs(props.max))),
  );
}
</script>

<template>
  <span
    class="admin-date-control"
    data-admin-control
    :class="attrs.class"
    :style="attrs.style as any"
  >
    <DatePicker
      popup-class-name="admin-picker-popup"
      :id="String(attrs.id || id)"
      :value="model || undefined"
      :value-format="valueFormat"
      :format="displayFormat"
      :picker="type === 'month' ? 'month' : 'date'"
      :show-time="type === 'datetime-local' ? { format: 'HH:mm' } : false"
      :disabled="disabled"
      :disabled-date="disabledDate"
      :placeholder="
        type === 'month'
          ? '请选择月份'
          : type === 'date'
            ? '请选择日期'
            : '请选择日期和时间'
      "
      :aria-label="attrs['aria-label'] as string"
      @update:value="changed"
    />
    <input
      ref="source"
      class="admin-validation-proxy"
      tabindex="-1"
      aria-hidden="true"
      :value="model"
      :type="type"
      :required="required"
      :disabled="disabled"
      :min="min"
      :max="max"
    />
  </span>
</template>
