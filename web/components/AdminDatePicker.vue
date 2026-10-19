<script setup lang="ts">
import {
  computed,
  nextTick,
  onMounted,
  onUpdated,
  ref,
  useAttrs,
  useId,
  watch,
  type StyleValue,
} from "vue";
import { Calendar, ChevronLeft, ChevronRight, X } from "lucide-vue-next";
import { useAdminPopover } from "../useAdminPopover";

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
const fieldLabel = ref("");
const { trigger, panel, isOpen, open, close } = useAdminPopover({
  minWidth: 320,
  maxHeight: 480,
});
const year = ref(new Date().getFullYear());
const month = ref(new Date().getMonth() + 1);
const selected = ref("");
const focused = ref("");
const hour = ref("00");
const minute = ref("00");
const weekdays = ["一", "二", "三", "四", "五", "六", "日"];
const months = Array.from({ length: 12 }, (_, index) => index + 1);
const pad = (value: number) => String(value).padStart(2, "0");
const yearText = (value: number) => String(value).padStart(4, "0");

// Date 仅用于公历运算；模型始终按原始本地日期字符串读写，不转换时区。
function calendarDate(y: number, m: number, d: number) {
  const value = new Date(0);
  value.setUTCFullYear(y, m - 1, d);
  value.setUTCHours(0, 0, 0, 0);
  return value;
}
function dateText(value: Date) {
  return `${yearText(value.getUTCFullYear())}-${pad(value.getUTCMonth() + 1)}-${pad(value.getUTCDate())}`;
}
function todayValue() {
  const now = new Date();
  const day = `${yearText(now.getFullYear())}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  if (props.type === "month") return day.slice(0, 7);
  if (props.type === "date") return day;
  return `${day}T${pad(now.getHours())}:${pad(now.getMinutes())}`;
}
function validFormat(value: string) {
  const pattern =
    props.type === "month"
      ? /^\d{4}-\d{2}$/
      : props.type === "date"
        ? /^\d{4}-\d{2}-\d{2}$/
        : /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/;
  if (!pattern.test(value)) return false;
  const y = Number(value.slice(0, 4));
  const m = Number(value.slice(5, 7));
  const d = props.type === "month" ? 1 : Number(value.slice(8, 10));
  if (y < 1 || m < 1 || m > 12 || d < 1) return false;
  const date = calendarDate(y, m, d);
  if (
    date.getUTCFullYear() !== y ||
    date.getUTCMonth() + 1 !== m ||
    date.getUTCDate() !== d
  )
    return false;
  return (
    props.type !== "datetime-local" ||
    (Number(value.slice(11, 13)) <= 23 && Number(value.slice(14, 16)) <= 59)
  );
}
const minimum = computed(() =>
  props.min && validFormat(props.min) ? props.min : "",
);
const maximum = computed(() =>
  props.max && validFormat(props.max) ? props.max : "",
);
function inRange(value: string) {
  return (
    (!minimum.value || value >= minimum.value) &&
    (!maximum.value || value <= maximum.value)
  );
}
function periodAvailable(start: string, end: string) {
  return (
    (!maximum.value || start <= maximum.value) &&
    (!minimum.value || end >= minimum.value)
  );
}
function dayAvailable(day: string) {
  if (!validFormat(props.type === "datetime-local" ? `${day}T00:00` : day))
    return false;
  return props.type === "datetime-local"
    ? periodAvailable(`${day}T00:00`, `${day}T23:59`)
    : inRange(day);
}
function monthAvailable(y: number, m: number) {
  if (y < 1 || y > 9999) return false;
  const prefix = `${yearText(y)}-${pad(m)}`;
  if (props.type === "month") return inRange(prefix);
  const last = calendarDate(y, m + 1, 0).getUTCDate();
  return props.type === "date"
    ? periodAvailable(`${prefix}-01`, `${prefix}-${last}`)
    : periodAvailable(`${prefix}-01T00:00`, `${prefix}-${last}T23:59`);
}
function yearAvailable(y: number) {
  return months.some((m) => monthAvailable(y, m));
}
const draft = computed(() => {
  if (!selected.value) return "";
  if (props.type !== "datetime-local") return selected.value;
  if (!/^\d{1,2}$/.test(hour.value) || !/^\d{1,2}$/.test(minute.value))
    return "invalid";
  return `${selected.value}T${pad(Number(hour.value))}:${pad(Number(minute.value))}`;
});
const error = computed(() => {
  if (!draft.value) return props.required ? "请选择日期" : "";
  if (!validFormat(draft.value))
    return "请填写有效时间，小时为 0–23，分钟为 0–59";
  if (!inRange(draft.value)) return "所选日期或时间超出允许范围";
  return "";
});
const display = computed(() => {
  if (!model.value) return "";
  if (!validFormat(model.value)) return model.value;
  const value = model.value;
  const text = `${Number(value.slice(0, 4))}年${Number(value.slice(5, 7))}月`;
  if (props.type === "month") return text;
  const day = `${text}${Number(value.slice(8, 10))}日`;
  return props.type === "date" ? day : `${day} ${value.slice(11, 16)}`;
});
const placeholder = computed(() =>
  String(
    attrs.placeholder ||
      (props.type === "month"
        ? "请选择月份"
        : props.type === "date"
          ? "请选择日期"
          : "请选择日期和时间"),
  ),
);
const title = computed(() =>
  props.type === "month"
    ? `${year.value}年`
    : `${year.value}年${month.value}月`,
);
const days = computed(() => {
  const first = calendarDate(year.value, month.value, 1);
  const offset = (first.getUTCDay() + 6) % 7;
  return Array.from({ length: 6 }, (_, row) =>
    Array.from({ length: 7 }, (_, column) => {
      const value = calendarDate(
        year.value,
        month.value,
        row * 7 + column - offset + 1,
      );
      const key = dateText(value);
      return {
        key,
        day: value.getUTCDate(),
        outside: value.getUTCMonth() + 1 !== month.value,
        enabled: dayAvailable(key),
      };
    }),
  );
});
const monthRows = computed(() => [
  months.slice(0, 3),
  months.slice(3, 6),
  months.slice(6, 9),
  months.slice(9, 12),
]);
const currentDay = computed(() => todayValue().slice(0, 10));
function monthKey(m: number) {
  return `${yearText(year.value)}-${pad(m)}`;
}
function sourceAttrs() {
  const {
    id: _id,
    class: _class,
    style: _style,
    tabindex: _tabindex,
    onFocus: _focus,
    onBlur: _blur,
    ...rest
  } = attrs;
  return rest;
}
function syncFieldLabel() {
  const labels = Array.from(
    (trigger.value as HTMLButtonElement | undefined)?.labels || [],
  );
  const enclosing = source.value?.closest("label");
  if (enclosing && !labels.includes(enclosing)) labels.push(enclosing);
  fieldLabel.value = labels
    .map((label) => {
      const clone = label.cloneNode(true) as HTMLElement;
      clone
        .querySelectorAll(
          "button, input, select, textarea, .admin-picker-panel, .admin-field-error, small",
        )
        .forEach((node) => node.remove());
      return clone.textContent?.replace(/\s+/g, " ").trim() || "";
    })
    .filter(Boolean)
    .join("，");
}
function loadDraft(value: string) {
  selected.value = value.slice(0, props.type === "month" ? 7 : 10);
  hour.value = value.slice(11, 13) || "00";
  minute.value = value.slice(14, 16) || "00";
  year.value = Number(value.slice(0, 4));
  month.value = Number(value.slice(5, 7));
  focused.value = selected.value;
}
async function focusCell() {
  await nextTick();
  panel.value
    ?.querySelector<HTMLButtonElement>(`[data-date="${focused.value}"]`)
    ?.focus();
}
async function show() {
  if (props.disabled) return;
  if (isOpen.value) {
    await focusCell();
    return;
  }
  let initial = validFormat(model.value) ? model.value : todayValue();
  if (minimum.value && initial < minimum.value) initial = minimum.value;
  if (maximum.value && initial > maximum.value) initial = maximum.value;
  loadDraft(initial);
  await open();
  if (isOpen.value) await focusCell();
}
function activate() {
  if (props.disabled) return;
  if (isOpen.value) close();
  else void show();
}
function previousPeriod(delta: number) {
  if (props.type === "month") return { y: year.value + delta, m: month.value };
  const date = calendarDate(year.value, month.value + delta, 1);
  return { y: date.getUTCFullYear(), m: date.getUTCMonth() + 1 };
}
function canNavigate(delta: number) {
  const { y, m } = previousPeriod(delta);
  return props.type === "month" ? yearAvailable(y) : monthAvailable(y, m);
}
function navigate(delta: number) {
  if (props.disabled || !canNavigate(delta)) return;
  const { y, m } = previousPeriod(delta);
  year.value = y;
  month.value = m;
  focused.value =
    props.type === "month"
      ? `${yearText(y)}-${pad(months.find((item) => monthAvailable(y, item)) || 1)}`
      : days.value.flat().find((day) => day.enabled && !day.outside)?.key || "";
}
function selectDay(day: string) {
  if (props.disabled || !dayAvailable(day)) return;
  selected.value = day;
  focused.value = day;
  year.value = Number(day.slice(0, 4));
  month.value = Number(day.slice(5, 7));
  // 边界日自动将时间收敛到允许区间，避免选中日期后仍沿用越界时刻。
  if (props.type === "datetime-local") {
    if (minimum.value?.startsWith(day) && draft.value < minimum.value) {
      hour.value = minimum.value.slice(11, 13);
      minute.value = minimum.value.slice(14, 16);
    }
    if (maximum.value?.startsWith(day) && draft.value > maximum.value) {
      hour.value = maximum.value.slice(11, 13);
      minute.value = maximum.value.slice(14, 16);
    }
  }
  void focusCell();
}
function selectMonth(m: number) {
  if (props.disabled || !monthAvailable(year.value, m)) return;
  selected.value = monthKey(m);
  focused.value = selected.value;
}
function chooseToday() {
  const value = todayValue();
  if (props.disabled || !inRange(value)) return;
  loadDraft(value);
  void focusCell();
}
function clearDraft() {
  if (!props.disabled && !props.required) selected.value = "";
}
async function confirm() {
  if (props.disabled || error.value) return;
  const value = draft.value;
  const changed = model.value !== value;
  model.value = value;
  await nextTick();
  if (source.value && changed) {
    source.value.value = value;
    source.value.dispatchEvent(new Event("input", { bubbles: true }));
    source.value.dispatchEvent(new Event("change", { bubbles: true }));
  }
  close();
}
function timeInput(event: Event, part: "hour" | "minute") {
  if (props.disabled) return;
  const value = (event.target as HTMLInputElement).value;
  if (part === "hour") hour.value = value;
  else minute.value = value;
}
function gridKeydown(event: KeyboardEvent, value: string) {
  if (props.disabled) return;
  const monthly = props.type === "month";
  const shifts: Record<string, number> = {
    ArrowLeft: -1,
    ArrowRight: 1,
    ArrowUp: monthly ? -3 : -7,
    ArrowDown: monthly ? 3 : 7,
  };
  let shift = shifts[event.key];
  const date = calendarDate(
    Number(value.slice(0, 4)),
    Number(value.slice(5, 7)),
    monthly ? 1 : Number(value.slice(8, 10)),
  );
  if (event.key === "Home")
    shift = monthly ? -(date.getUTCMonth() % 3) : -((date.getUTCDay() + 6) % 7);
  if (event.key === "End")
    shift = monthly
      ? 2 - (date.getUTCMonth() % 3)
      : 6 - ((date.getUTCDay() + 6) % 7);
  if (shift === undefined) return;
  event.preventDefault();
  if (monthly) date.setUTCMonth(date.getUTCMonth() + shift);
  else date.setUTCDate(date.getUTCDate() + shift);
  const y = date.getUTCFullYear();
  const m = date.getUTCMonth() + 1;
  const key = monthly ? `${yearText(y)}-${pad(m)}` : dateText(date);
  if (
    y < 1 ||
    y > 9999 ||
    !(monthly ? monthAvailable(y, m) : dayAvailable(key))
  )
    return;
  year.value = y;
  month.value = m;
  focused.value = key;
  void focusCell();
}
watch(
  () => [props.disabled, props.type],
  () => close(false),
);
onMounted(syncFieldLabel);
onUpdated(syncFieldLabel);
</script>

<template>
  <div
    class="admin-control admin-date-picker"
    :class="attrs.class"
    :style="attrs.style as StyleValue"
    data-admin-control
  >
    <button
      :id="attrs.id as string | undefined"
      ref="trigger"
      type="button"
      class="admin-calendar-trigger"
      :class="{ 'is-placeholder': !display }"
      data-admin-trigger
      :disabled="disabled"
      aria-haspopup="dialog"
      :aria-expanded="isOpen"
      :aria-controls="`${id}-panel`"
      :aria-label="`${attrs['aria-label'] || fieldLabel || placeholder}：${display || '未选择'}`"
      :aria-labelledby="attrs['aria-labelledby'] as string | undefined"
      :aria-describedby="attrs['aria-describedby'] as string | undefined"
      :aria-required="required"
      :tabindex="attrs.tabindex as number | undefined"
      @focus="
        (attrs.onFocus as ((event: FocusEvent) => void) | undefined)?.($event)
      "
      @blur="
        (attrs.onBlur as ((event: FocusEvent) => void) | undefined)?.($event)
      "
      @click="activate"
      @keydown.down.prevent="show()"
    >
      <span class="admin-calendar-value">{{ display || placeholder }}</span>
      <Calendar :size="18" aria-hidden="true" />
    </button>
    <input
      v-bind="sourceAttrs()"
      ref="source"
      class="admin-control-source"
      :type="type"
      :value="model"
      :required="required"
      :disabled="disabled"
      :min="min"
      :max="max"
      tabindex="-1"
      aria-hidden="true"
      @click.prevent
      @focus="trigger?.focus()"
      @invalid.prevent="show()"
    />
    <div
      v-show="isOpen"
      :id="`${id}-panel`"
      ref="panel"
      popover="manual"
      class="admin-picker-panel admin-calendar-panel"
      role="dialog"
      :aria-label="placeholder"
      @keydown.esc.stop.prevent="close()"
    >
      <header class="admin-calendar-header">
        <button
          type="button"
          class="admin-calendar-nav"
          :disabled="disabled || !canNavigate(-1)"
          :aria-label="type === 'month' ? '上一年' : '上个月'"
          @click="navigate(-1)"
        >
          <ChevronLeft :size="18" aria-hidden="true" />
        </button>
        <strong
          :id="`${id}-heading`"
          class="admin-calendar-title"
          aria-live="polite"
          >{{ title }}</strong
        >
        <button
          type="button"
          class="admin-calendar-nav"
          :disabled="disabled || !canNavigate(1)"
          :aria-label="type === 'month' ? '下一年' : '下个月'"
          @click="navigate(1)"
        >
          <ChevronRight :size="18" aria-hidden="true" />
        </button>
      </header>
      <div
        v-if="type === 'month'"
        class="admin-calendar-months"
        role="grid"
        :aria-labelledby="`${id}-heading`"
      >
        <div
          v-for="(row, index) in monthRows"
          :key="index"
          class="admin-calendar-month-row"
          role="row"
        >
          <div
            v-for="item in row"
            :key="item"
            class="admin-calendar-cell"
            role="gridcell"
            :aria-selected="selected === monthKey(item)"
          >
            <button
              type="button"
              class="admin-calendar-month"
              :class="{ 'is-selected': selected === monthKey(item) }"
              :data-date="monthKey(item)"
              :tabindex="focused === monthKey(item) ? 0 : -1"
              :disabled="disabled || !monthAvailable(year, item)"
              :aria-label="`${year}年${item}月`"
              @click="selectMonth(item)"
              @focus="focused = monthKey(item)"
              @keydown="gridKeydown($event, monthKey(item))"
            >
              {{ item }}月
            </button>
          </div>
        </div>
      </div>
      <div
        v-else
        class="admin-calendar-grid"
        role="grid"
        :aria-labelledby="`${id}-heading`"
      >
        <div class="admin-calendar-weekdays" role="row">
          <span
            v-for="day in weekdays"
            :key="day"
            class="admin-calendar-weekday"
            role="columnheader"
            :aria-label="`星期${day}`"
            >{{ day }}</span
          >
        </div>
        <div
          v-for="(week, index) in days"
          :key="index"
          class="admin-calendar-week"
          role="row"
        >
          <div
            v-for="day in week"
            :key="day.key"
            class="admin-calendar-cell"
            role="gridcell"
            :aria-selected="selected === day.key"
          >
            <button
              type="button"
              class="admin-calendar-day"
              :class="{
                'is-selected': selected === day.key,
                'is-today': currentDay === day.key,
                'is-outside': day.outside,
              }"
              :data-date="day.key"
              :disabled="disabled || !day.enabled"
              :tabindex="focused === day.key ? 0 : -1"
              :aria-label="`${Number(day.key.slice(0, 4))}年${Number(day.key.slice(5, 7))}月${day.day}日`"
              :aria-current="currentDay === day.key ? 'date' : undefined"
              @click="selectDay(day.key)"
              @focus="focused = day.key"
              @keydown="gridKeydown($event, day.key)"
            >
              {{ day.day }}
            </button>
          </div>
        </div>
      </div>
      <div
        v-if="type === 'datetime-local'"
        class="admin-calendar-time"
        role="group"
        aria-label="选择时间"
      >
        <div class="admin-calendar-time-field">
          <span>小时</span
          ><input
            class="admin-calendar-time-input"
            aria-label="小时"
            form=""
            type="number"
            inputmode="numeric"
            min="0"
            max="23"
            step="1"
            :value="hour"
            :disabled="disabled || !selected"
            :aria-describedby="error ? `${id}-error` : undefined"
            @input.stop="timeInput($event, 'hour')"
            @change.stop
            @keydown.enter.prevent="confirm()"
          />
        </div>
        <span class="admin-calendar-time-separator" aria-hidden="true">:</span>
        <div class="admin-calendar-time-field">
          <span>分钟</span
          ><input
            class="admin-calendar-time-input"
            aria-label="分钟"
            form=""
            type="number"
            inputmode="numeric"
            min="0"
            max="59"
            step="1"
            :value="minute"
            :disabled="disabled || !selected"
            :aria-describedby="error ? `${id}-error` : undefined"
            @input.stop="timeInput($event, 'minute')"
            @change.stop
            @keydown.enter.prevent="confirm()"
          />
        </div>
      </div>
      <p
        v-if="error"
        :id="`${id}-error`"
        class="admin-calendar-error"
        role="status"
      >
        {{ error }}
      </p>
      <footer class="admin-calendar-footer">
        <button
          type="button"
          class="admin-calendar-today"
          :disabled="disabled || !inRange(todayValue())"
          @click="chooseToday"
        >
          {{ type === "month" ? "本月" : "今天" }}
        </button>
        <button
          v-if="!required"
          type="button"
          class="admin-calendar-clear"
          :disabled="disabled || !selected"
          aria-label="清空选择，确认后生效"
          @click="clearDraft"
        >
          <X :size="14" aria-hidden="true" />清空
        </button>
        <button
          type="button"
          class="admin-calendar-cancel"
          :disabled="disabled"
          @click="close()"
        >
          取消
        </button>
        <button
          type="button"
          class="admin-calendar-confirm"
          :disabled="disabled || !!error"
          @click="confirm"
        >
          确认
        </button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.admin-calendar-panel {
  font-variant-numeric: tabular-nums;
}
.admin-calendar-panel .admin-calendar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.admin-calendar-title {
  color: var(--admin-ink);
  font-size: 14px;
  font-weight: 600;
}
.admin-calendar-panel button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  min-height: 32px;
  margin: 0;
  padding: 6px;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
  color: var(--admin-ink);
  font: inherit;
  line-height: 1.4;
  cursor: pointer;
}
.admin-calendar-panel button:enabled:hover {
  background: var(--admin-accent-soft);
  color: var(--admin-accent);
}
.admin-calendar-panel button:focus-visible,
.admin-calendar-panel input:focus-visible {
  outline: 2px solid var(--admin-accent);
  outline-offset: -2px;
  box-shadow: none;
}
.admin-calendar-panel button:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.admin-calendar-panel .admin-calendar-nav {
  width: 32px;
  flex-shrink: 0;
}
.admin-calendar-weekdays,
.admin-calendar-week {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 2px;
}
.admin-calendar-month-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  margin-bottom: 6px;
}
.admin-calendar-weekday {
  padding: 7px 0;
  color: var(--admin-muted);
  text-align: center;
  font-size: 12px;
}
.admin-calendar-cell {
  min-width: 0;
}
.admin-calendar-panel .admin-calendar-day,
.admin-calendar-panel .admin-calendar-month {
  width: 100%;
  min-height: 34px;
}
.admin-calendar-panel .admin-calendar-month {
  min-height: 44px;
}
.admin-calendar-panel .is-outside {
  color: var(--admin-muted);
}
.admin-calendar-panel .is-today {
  border-color: var(--admin-accent);
  color: var(--admin-accent);
}
.admin-calendar-panel .is-selected,
.admin-calendar-panel .is-selected:enabled:hover {
  background: var(--admin-accent);
  color: white;
  font-weight: 600;
}
.admin-calendar-panel .admin-calendar-time {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 10px;
  margin-top: 8px;
  padding-top: 10px;
  border-top: 1px solid var(--admin-border);
}
.admin-calendar-panel .admin-calendar-time-field {
  display: grid;
  gap: 5px;
  width: 74px;
  margin: 0;
  color: var(--admin-muted);
  font-size: 12px;
  font-weight: 400;
}
.admin-calendar-panel .admin-calendar-time-input {
  width: 100%;
  min-height: 32px;
  padding: 5px 8px;
  border: 1px solid var(--admin-border);
  border-radius: 5px;
  background: white;
  color: var(--admin-ink);
  text-align: center;
  font-size: 14px;
}
.admin-calendar-time-separator {
  padding-bottom: 7px;
  color: var(--admin-muted);
}
.admin-calendar-error {
  margin: 8px 4px 0;
  color: var(--admin-accent);
  font-size: 12px;
  line-height: 1.5;
}
.admin-calendar-panel .admin-calendar-footer {
  position: sticky;
  bottom: 0;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--admin-border);
  background: white;
}
.admin-calendar-panel .admin-calendar-today {
  margin-right: auto;
  color: var(--admin-accent);
}
.admin-calendar-panel .admin-calendar-clear {
  gap: 3px;
  color: var(--admin-muted);
}
.admin-calendar-panel .admin-calendar-confirm,
.admin-calendar-panel .admin-calendar-confirm:enabled:hover {
  padding-inline: 12px;
  background: var(--admin-accent);
  color: white;
}
</style>
