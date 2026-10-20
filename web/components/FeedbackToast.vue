<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from "vue";
import { CircleCheck, CircleX, X } from "lucide-vue-next";

const props = withDefaults(defineProps<{ showError?: boolean }>(), {
  showError: true,
});
const notice = defineModel<string>("notice", { default: "" });
const error = defineModel<string>("error", { default: "" });
const isError = computed(() => props.showError && Boolean(error.value));
const message = computed(() => (isError.value ? error.value : notice.value));
let timer: ReturnType<typeof setTimeout> | undefined;

function clearTimer() {
  clearTimeout(timer);
}
function dismiss() {
  if (isError.value) error.value = "";
  else notice.value = "";
}
watch(
  [message, isError],
  () => {
    clearTimer();
    if (message.value && !isError.value) timer = setTimeout(dismiss, 3000);
  },
  { immediate: true },
);
onBeforeUnmount(clearTimer);
</script>

<template>
  <Teleport to="body">
    <Transition name="feedback-toast">
      <div
        v-if="message"
        class="feedback-toast"
        :class="{ 'feedback-toast-error': isError }"
        :role="isError ? 'alert' : 'status'"
        aria-atomic="true"
      >
        <component
          :is="isError ? CircleX : CircleCheck"
          class="feedback-toast-icon"
          :size="16"
          aria-hidden="true"
        />
        <span>{{ message }}</span>
        <slot v-if="isError" />
        <button
          v-if="isError"
          type="button"
          class="feedback-toast-close"
          aria-label="关闭提示"
          @click="dismiss"
        >
          <X :size="14" aria-hidden="true" />
        </button>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.feedback-toast {
  position: fixed;
  top: calc(20px + env(safe-area-inset-top));
  left: 50%;
  z-index: 2000;
  display: flex;
  align-items: center;
  gap: 8px;
  width: max-content;
  max-width: min(560px, calc(100vw - 32px));
  padding: 12px 20px;
  border: 1px solid #e1f3d8;
  border-radius: 4px;
  background: #f0f9eb;
  color: #529b2e;
  box-shadow: 0 4px 12px #00000008;
  font-size: 14px;
  line-height: 22px;
  overflow-wrap: anywhere;
  transform: translateX(-50%);
}
.feedback-toast-error {
  border-color: #fde2e2;
  background: #fef0f0;
  color: #c45656;
}
.feedback-toast-icon {
  flex-shrink: 0;
  fill: currentColor;
}
.feedback-toast-icon :deep(path) {
  stroke: #fff;
}
.feedback-toast-close {
  display: inline-flex;
  flex-shrink: 0;
  padding: 4px;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.feedback-toast-close:focus-visible {
  outline: 2px solid currentColor;
  outline-offset: 2px;
}
.feedback-toast-enter-active,
.feedback-toast-leave-active {
  transition: opacity 150ms ease;
}
.feedback-toast-enter-from,
.feedback-toast-leave-to {
  opacity: 0;
}
@media (prefers-reduced-motion: reduce) {
  .feedback-toast-enter-active,
  .feedback-toast-leave-active {
    transition: none;
  }
}
</style>
