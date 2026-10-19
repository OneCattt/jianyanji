<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, useId } from "vue";
import { X } from "lucide-vue-next";

withDefaults(defineProps<{ title: string; busy?: boolean; wide?: boolean }>(), {
  busy: false,
  wide: false,
});
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
const titleId = useId();
let previous: HTMLElement | null = null;
let previousOverflow = "";
onMounted(() => {
  previous = document.activeElement as HTMLElement;
  previousOverflow = document.body.style.overflow;
  document.body.style.overflow = "hidden";
  dialog.value?.showModal();
});
onBeforeUnmount(() => {
  dialog.value?.close();
  document.body.style.overflow = previousOverflow;
  if (previous?.isConnected) previous.focus();
});
</script>

<template>
  <dialog
    ref="dialog"
    class="admin-dialog"
    :class="{ 'admin-dialog-wide': wide }"
    :aria-labelledby="titleId"
    :aria-busy="busy"
    @cancel.prevent="!busy && emit('close')"
  >
    <header class="admin-dialog-header">
      <h2 :id="titleId">{{ title }}</h2>
      <button
        type="button"
        class="admin-dialog-close"
        aria-label="关闭弹窗"
        :disabled="busy"
        @click="emit('close')"
      >
        <X :size="20" aria-hidden="true" />
      </button>
    </header>
    <div class="admin-dialog-body"><slot /></div>
  </dialog>
</template>
