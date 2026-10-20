<script setup lang="ts">
import { onBeforeUnmount, onMounted, onUpdated, ref, useId } from "vue";

import { Form } from "ant-design-vue";

const emit = defineEmits<{ submit: [event: SubmitEvent] }>();
const form = ref<HTMLFormElement>();
const antForm = ref<{ $el: HTMLFormElement }>();
onMounted(() => {
  form.value = antForm.value?.$el;
});
const prefix = useId();
type Control = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
const errors = new Map<
  Control,
  {
    node: HTMLElement;
    describedBy: string | null;
    target: HTMLElement;
    targetDescribedBy: string | null;
  }
>();
let submitted = false;
let sequence = 0;

function fieldName(control: Control) {
  const label = (control.closest("label") || control.labels?.[0])?.cloneNode(
    true,
  ) as HTMLElement | undefined;
  label
    ?.querySelectorAll(
      "[data-admin-control], .ant-select, .ant-checkbox, .ant-picker, input, select, textarea, button, small, .admin-field-error",
    )
    .forEach((node) => node.remove());
  return (
    label?.textContent?.replace(/\s+/g, " ").trim() ||
    control.getAttribute("aria-label") ||
    "此项"
  );
}
function message(control: Control) {
  if (!control.willValidate) return "";
  const name = fieldName(control);
  const input = control instanceof HTMLInputElement ? control : null;
  const missing =
    input?.type === "checkbox"
      ? !input.checked
      : !control.value.trim() ||
        (control instanceof HTMLSelectElement &&
          Boolean(control.selectedOptions[0]?.disabled));
  if (control.required && missing) {
    if (input?.type === "checkbox") return `请确认${name}`;
    return `${control instanceof HTMLSelectElement ? "请选择" : "请填写"}${name}`;
  }
  if (control.validity.badInput) return `请填写有效的${name}`;
  if (!control.value) return "";
  // AntD 数值框使用文本 input，范围需从无障碍属性读取后参与提交校验。
  if (input?.getAttribute("role") === "spinbutton") {
    const value = Number(input.value);
    if (!Number.isFinite(value)) return `请填写有效的${name}`;
    const min = input.getAttribute("aria-valuemin");
    const max = input.getAttribute("aria-valuemax");
    if (min !== null && value < Number(min)) return `${name}不能小于 ${min}`;
    if (max !== null && value > Number(max)) return `${name}不能大于 ${max}`;
  }
  if (!(control instanceof HTMLSelectElement)) {
    if (control.minLength > 0 && control.value.length < control.minLength)
      return `${name}至少填写 ${control.minLength} 个字符`;
    if (control.maxLength > 0 && control.value.length > control.maxLength)
      return `${name}最多填写 ${control.maxLength} 个字符`;
  }
  if (control.validity.rangeUnderflow) return `${name}不能小于 ${input?.min}`;
  if (control.validity.rangeOverflow) return `${name}不能大于 ${input?.max}`;
  if (control.validity.stepMismatch)
    return `${name}请按 ${input?.step || "1"} 的间隔填写`;
  if (control.validity.patternMismatch)
    return (
      control.getAttribute("data-error-message") ||
      `${name}格式不正确${control.getAttribute("placeholder") ? `，${control.getAttribute("placeholder")}` : ""}`
    );
  if (control.validity.typeMismatch) return `请填写有效的${name}`;
  return control.validity.valid ? "" : `请检查${name}`;
}
function clear(control: Control) {
  const error = errors.get(control);
  if (!error) return;
  error.node.remove();
  control.removeAttribute("aria-invalid");
  if (error.describedBy)
    control.setAttribute("aria-describedby", error.describedBy);
  else control.removeAttribute("aria-describedby");
  if (error.target !== control) {
    error.target.removeAttribute("aria-invalid");
    if (error.targetDescribedBy)
      error.target.setAttribute("aria-describedby", error.targetDescribedBy);
    else error.target.removeAttribute("aria-describedby");
  }
  errors.delete(control);
}
function focusTarget(control: Control) {
  return (
    control
      .closest("[data-admin-control]")
      ?.querySelector<HTMLElement>(
        "[role=combobox], .ant-picker-input input, [data-admin-trigger]",
      ) || control
  );
}
function validate(control: Control) {
  const text = message(control);
  if (!text) {
    clear(control);
    return true;
  }
  let error = errors.get(control);
  if (!error) {
    const node = document.createElement("span");
    node.className = "admin-field-error";
    node.id = `${prefix}-error-${++sequence}`;
    node.setAttribute("aria-live", "polite");
    const target = focusTarget(control);
    error = {
      node,
      describedBy: control.getAttribute("aria-describedby"),
      target,
      targetDescribedBy: target.getAttribute("aria-describedby"),
    };
    errors.set(control, error);
    (
      control.closest(
        "[data-admin-control], .ant-input-number, .ant-checkbox-wrapper, .ant-input-affix-wrapper",
      ) || control
    ).insertAdjacentElement("afterend", node);
    control.setAttribute(
      "aria-describedby",
      [error.describedBy, node.id].filter(Boolean).join(" "),
    );
    target.setAttribute(
      "aria-describedby",
      [error.targetDescribedBy, node.id].filter(Boolean).join(" "),
    );
  }
  error.node.textContent = text;
  control.setAttribute("aria-invalid", "true");
  error.target.setAttribute("aria-invalid", "true");
  return false;
}
function changed(event: Event) {
  if (!submitted) return;
  const control = event.target;
  if (
    control instanceof HTMLInputElement ||
    control instanceof HTMLSelectElement ||
    control instanceof HTMLTextAreaElement
  )
    validate(control);
}
function submit(event: Event) {
  submitted = true;
  let first: Control | undefined;
  for (const control of Array.from(form.value?.elements || [])) {
    if (
      control instanceof HTMLInputElement ||
      control instanceof HTMLSelectElement ||
      control instanceof HTMLTextAreaElement
    ) {
      if (!validate(control)) first ||= control;
    }
  }
  if (first) {
    const target = focusTarget(first);
    target.focus();
    target.scrollIntoView({ block: "nearest", behavior: "instant" });
  } else emit("submit", event as SubmitEvent);
}
onUpdated(() => {
  for (const control of errors.keys()) {
    if (!form.value?.contains(control) || !control.willValidate) clear(control);
  }
});
onBeforeUnmount(() => {
  for (const control of errors.keys()) clear(control);
});
</script>

<template>
  <Form
    ref="antForm"
    layout="vertical"
    class="admin-form"
    novalidate
    @submit.prevent="submit"
    @input="changed"
    @change="changed"
  >
    <slot />
  </Form>
</template>
