import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";

/** 后台选择控件共用浮层定位，避免被弹窗滚动区域裁切。 */
export function useAdminPopover(
  options: { minWidth?: number; maxHeight?: number } = {},
) {
  const trigger = ref<HTMLElement>();
  const panel = ref<HTMLElement>();
  const isOpen = ref(false);
  function position() {
    if (!trigger.value || !panel.value) return;
    const rect = trigger.value.getBoundingClientRect();
    const gap = 6;
    const margin = 12;
    const width = Math.min(
      Math.max(rect.width, options.minWidth || 0),
      window.innerWidth - margin * 2,
    );
    const below = window.innerHeight - rect.bottom - gap - margin;
    const above = rect.top - gap - margin;
    const heightLimit = options.maxHeight || 360;
    const useBelow =
      below >= Math.min(panel.value.scrollHeight, heightLimit) ||
      below >= above;
    const maxHeight = Math.max(
      100,
      Math.min(heightLimit, useBelow ? below : above),
    );
    panel.value.style.width = `${width}px`;
    panel.value.style.maxHeight = `${maxHeight}px`;
    panel.value.style.left = `${Math.max(margin, Math.min(rect.left, window.innerWidth - width - margin))}px`;
    panel.value.style.top = `${useBelow ? rect.bottom + gap : Math.max(margin, rect.top - gap - Math.min(panel.value.scrollHeight, maxHeight))}px`;
  }
  async function open() {
    if (isOpen.value) return;
    isOpen.value = true;
    await nextTick();
    if (!isOpen.value || !panel.value?.isConnected) return;
    panel.value.showPopover();
    position();
  }
  function close(restoreFocus = true) {
    if (!isOpen.value) return;
    isOpen.value = false;
    panel.value?.hidePopover();
    if (restoreFocus && trigger.value?.isConnected) trigger.value.focus();
  }
  function outside(event: Event) {
    const target = event.target as Node;
    if (
      isOpen.value &&
      !trigger.value?.contains(target) &&
      !panel.value?.contains(target)
    )
      close(false);
  }
  function keydown(event: KeyboardEvent) {
    if (isOpen.value && event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      close();
    }
  }
  function scroll(event: Event) {
    if (isOpen.value && !panel.value?.contains(event.target as Node))
      close(false);
  }
  onMounted(() => {
    document.addEventListener("pointerdown", outside, true);
    document.addEventListener("focusin", outside);
    document.addEventListener("keydown", keydown, true);
    document.addEventListener("scroll", scroll, true);
    window.addEventListener("resize", position);
  });
  onBeforeUnmount(() => {
    close(false);
    document.removeEventListener("pointerdown", outside, true);
    document.removeEventListener("focusin", outside);
    document.removeEventListener("keydown", keydown, true);
    document.removeEventListener("scroll", scroll, true);
    window.removeEventListener("resize", position);
  });
  return {
    trigger,
    panel,
    isOpen,
    open,
    close,
    toggle: () => (isOpen.value ? close() : open()),
  };
}
