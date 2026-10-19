<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  onUpdated,
  ref,
  useAttrs,
  useId,
  type StyleValue,
} from "vue";
import { Check, ChevronDown } from "lucide-vue-next";
import { useAdminPopover } from "../useAdminPopover";

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
const listId = `${id}-options`;
const source = ref<HTMLSelectElement>();
const { trigger, panel, isOpen, open, close, toggle } = useAdminPopover();

// 让原生 v-model 指令读取 option._value，保留 :value 的数字、对象等原始类型。
const model = computed({
  get: () => props.modelValue,
  set: (value: unknown) => {
    if (props.modelModifiers?.number && typeof value === "string") {
      const number = Number.parseFloat(value);
      value = Number.isNaN(number) ? value : number;
    }
    emit("update:modelValue", value);
  },
});
const sourceAttrs = () =>
  Object.fromEntries(
    Object.entries(attrs).filter(
      ([key]) =>
        ![
          "id",
          "class",
          "style",
          "tabindex",
          "title",
          "onFocus",
          "onBlur",
        ].includes(key),
    ),
  );

type Option = {
  index: number;
  label: string;
  disabled: boolean;
  group: string;
  groupId: string;
};
const options = ref<Option[]>([]);
const selectedIndex = ref(-1);
const selectedLabel = ref("");
const activeIndex = ref(-1);
const disabled = ref(false);
const required = ref(false);
const label = ref("");
let observer: MutationObserver | undefined;
let search = "";
let searchedAt = 0;

function optionId(index: number) {
  return `${id}-option-${index}`;
}

function syncSource() {
  const select = source.value;
  if (!select) return;
  const next: Option[] = [];
  const groups = Array.from(select.querySelectorAll("optgroup"));
  for (const option of Array.from(select.options)) {
    const group =
      option.parentElement instanceof HTMLOptGroupElement
        ? option.parentElement
        : null;
    if (option.hidden || group?.hidden) continue;
    next.push({
      index: option.index,
      label: option.label,
      disabled: option.disabled || Boolean(group?.disabled),
      group: group?.label || "",
      groupId: group ? `${id}-group-${groups.indexOf(group)}` : "",
    });
  }
  // onUpdated 也会由本组件状态触发；只有快照变化才替换数组，避免更新循环。
  if (
    next.length !== options.value.length ||
    next.some((option, index) => {
      const previous = options.value[index];
      return (
        !previous ||
        Object.keys(option).some(
          (key) =>
            option[key as keyof Option] !== previous[key as keyof Option],
        )
      );
    })
  )
    options.value = next;
  selectedIndex.value = select.selectedIndex;
  selectedLabel.value = select.selectedOptions[0]?.label || "";
  disabled.value = select.matches(":disabled");
  required.value = select.required;
  if (disabled.value) close(false);
  if (
    !next.some(
      (option) => option.index === activeIndex.value && !option.disabled,
    )
  ) {
    activeIndex.value =
      next.find(
        (option) => option.index === select.selectedIndex && !option.disabled,
      )?.index ??
      next.find((option) => !option.disabled)?.index ??
      -1;
  }
  // 按钮排在隐藏 select 前面，使包裹式 label 和 for=id 都能聚焦可见控件。
  label.value = Array.from(
    (trigger.value as HTMLButtonElement | undefined)?.labels || [],
  )
    .map((node) => {
      const clone = node.cloneNode(true) as HTMLElement;
      clone
        .querySelectorAll(
          "[data-admin-control], button, select, .admin-picker-panel, .admin-field-error, small",
        )
        .forEach((child) => child.remove());
      return clone.textContent?.replace(/\s+/g, " ").trim() || "";
    })
    .filter(Boolean)
    .join(" ");
}

function scrollActive() {
  void nextTick(() => {
    if (isOpen.value)
      document
        .getElementById(optionId(activeIndex.value))
        ?.scrollIntoView({ block: "nearest" });
  });
}

function show(index?: number) {
  syncSource();
  if (disabled.value) return;
  search = "";
  activeIndex.value =
    index ??
    options.value.find(
      (option) => option.index === selectedIndex.value && !option.disabled,
    )?.index ??
    options.value.find((option) => !option.disabled)?.index ??
    -1;
  open();
  scrollActive();
}

function togglePicker() {
  if (source.value?.matches(":disabled")) return;
  if (isOpen.value) toggle();
  else show();
}

function changed(event: Event) {
  syncSource();
  emit("change", event);
}

function choose(index: number) {
  const select = source.value;
  const option = options.value.find((item) => item.index === index);
  if (!select || select.matches(":disabled") || !option || option.disabled)
    return;
  select.selectedIndex = index;
  // 同一次 change 先交给 v-model 同步原始值，再冒泡给表单校验，并转发给业务监听器。
  select.dispatchEvent(new Event("change", { bubbles: true }));
  close();
}

function keydown(event: KeyboardEvent) {
  if (source.value?.matches(":disabled") || event.isComposing) return;
  if (event.key === "Tab") {
    close(false);
    return;
  }
  if (event.key === "Escape") {
    if (isOpen.value) {
      event.preventDefault();
      event.stopPropagation();
      close();
    }
    return;
  }
  if (event.ctrlKey || event.metaKey || event.altKey) return;
  const enabled = options.value.filter((option) => !option.disabled);
  if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
    event.preventDefault();
    if (!isOpen.value) {
      const selected = enabled.find(
        (option) => option.index === selectedIndex.value,
      );
      show(
        event.key === "Home"
          ? enabled[0]?.index
          : event.key === "End"
            ? enabled.at(-1)?.index
            : (selected?.index ??
              (event.key === "ArrowUp"
                ? enabled.at(-1)?.index
                : enabled[0]?.index)),
      );
      return;
    }
    if (!enabled.length) return;
    const current = enabled.findIndex(
      (option) => option.index === activeIndex.value,
    );
    const position =
      event.key === "Home"
        ? 0
        : event.key === "End"
          ? enabled.length - 1
          : (current + (event.key === "ArrowDown" ? 1 : -1) + enabled.length) %
            enabled.length;
    activeIndex.value = enabled[position].index;
    scrollActive();
    return;
  }
  if (
    event.key === "Enter" ||
    (event.key === " " && (!search || Date.now() - searchedAt >= 700))
  ) {
    event.preventDefault();
    if (isOpen.value) choose(activeIndex.value);
    else show();
    return;
  }
  if (event.key.length !== 1) return;
  event.preventDefault();
  if (!isOpen.value) show();
  const now = Date.now();
  search =
    (now - searchedAt < 700 ? search : "") + event.key.toLocaleLowerCase();
  searchedAt = now;
  // 连续输入同一字符时依次跳转；连续输入不同字符时按完整前缀检索。
  const repeated = [...search].every((character) => character === search[0]);
  const prefix = repeated ? search[0] : search;
  const current = enabled.findIndex(
    (option) => option.index === activeIndex.value,
  );
  const start = Math.max(0, current + (repeated ? 1 : 0));
  const match = [...enabled.slice(start), ...enabled.slice(0, start)].find(
    (option) => option.label.trim().toLocaleLowerCase().startsWith(prefix),
  );
  if (match) {
    activeIndex.value = match.index;
    scrollActive();
  }
}

onMounted(() => {
  syncSource();
  observer = new MutationObserver(syncSource);
  if (source.value)
    observer.observe(source.value, {
      subtree: true,
      childList: true,
      characterData: true,
      attributes: true,
    });
});
onUpdated(syncSource);
onBeforeUnmount(() => observer?.disconnect());
</script>

<template>
  <div
    class="admin-control admin-select"
    :class="attrs.class"
    :style="attrs.style as StyleValue"
    data-admin-control
  >
    <button
      :id="String(attrs.id || `${id}-trigger`)"
      ref="trigger"
      type="button"
      data-admin-trigger
      role="combobox"
      aria-haspopup="listbox"
      :aria-expanded="isOpen"
      :aria-controls="listId"
      :aria-activedescendant="
        isOpen && activeIndex >= 0 ? optionId(activeIndex) : undefined
      "
      :aria-labelledby="attrs['aria-labelledby'] as string | undefined"
      :aria-label="
        (attrs['aria-label'] as string) ||
        (attrs['aria-labelledby']
          ? undefined
          : label || String(attrs.name || '请选择'))
      "
      :aria-describedby="attrs['aria-describedby'] as string | undefined"
      :aria-invalid="attrs['aria-invalid'] as 'true' | 'false' | undefined"
      :aria-errormessage="attrs['aria-errormessage'] as string | undefined"
      :aria-required="required"
      :aria-disabled="disabled"
      :disabled="disabled"
      :tabindex="attrs.tabindex as number | undefined"
      @focus="
        (attrs.onFocus as ((event: FocusEvent) => void) | undefined)?.($event)
      "
      @blur="
        (attrs.onBlur as ((event: FocusEvent) => void) | undefined)?.($event)
      "
      @click="togglePicker"
      @keydown="keydown"
    >
      <span class="admin-select-value">{{ selectedLabel || "\u00a0" }}</span>
      <ChevronDown :size="16" aria-hidden="true" />
    </button>
    <select
      :id="`${id}-source`"
      ref="source"
      v-model="model"
      v-bind="sourceAttrs()"
      :aria-label="(attrs['aria-label'] as string) || label || undefined"
      class="admin-control-source"
      tabindex="-1"
      hidden
      aria-hidden="true"
      @change="changed"
    >
      <slot />
    </select>
    <div
      :id="listId"
      ref="panel"
      v-show="isOpen"
      popover="manual"
      class="admin-picker-panel admin-select-options"
      role="listbox"
      :aria-labelledby="attrs['aria-labelledby'] as string | undefined"
      :aria-label="
        (attrs['aria-label'] as string) ||
        (attrs['aria-labelledby']
          ? undefined
          : label || String(attrs.name || '请选择'))
      "
      @keydown="keydown"
      @mousedown.prevent
    >
      <template v-for="(option, index) in options" :key="option.index">
        <div
          v-if="
            option.groupId && option.groupId !== options[index - 1]?.groupId
          "
          :id="option.groupId"
          class="admin-select-group"
          role="presentation"
        >
          {{ option.group }}
        </div>
        <button
          :id="optionId(option.index)"
          type="button"
          role="option"
          :aria-selected="option.index === selectedIndex"
          :aria-describedby="option.groupId || undefined"
          :disabled="option.disabled"
          class="admin-select-option"
          :class="{ 'is-active': option.index === activeIndex }"
          :data-active="option.index === activeIndex || undefined"
          tabindex="-1"
          @pointermove="!option.disabled && (activeIndex = option.index)"
          @click.stop="choose(option.index)"
        >
          <span>{{ option.label }}</span>
          <Check
            v-if="option.index === selectedIndex"
            :size="16"
            aria-hidden="true"
          />
        </button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.admin-select-option.is-active:not(:disabled) {
  background: var(--admin-accent-soft, #f7ecef);
  box-shadow: inset 0 0 0 1px var(--admin-accent, #8d2638);
}

.admin-select-group {
  padding: 8px 10px 4px;
  color: var(--admin-muted, #767c88);
  font-size: 12px;
  font-weight: 600;
}
</style>
