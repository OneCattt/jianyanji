<script setup lang="ts">
import { Drawer } from "ant-design-vue";
import { nextTick, onBeforeUnmount, ref, watch } from "vue";

const props = withDefaults(
  defineProps<{
    title: string;
    busy?: boolean;
    wide?: boolean;
    showFooter?: boolean;
  }>(),
  { busy: false, wide: false, showFooter: true },
);
const emit = defineEmits<{ close: [] }>();
const body = ref<HTMLElement>();
const trigger = document.activeElement;
function close() {
  if (!props.busy) emit("close");
}
watch(
  () => props.busy,
  async (busy) => {
    if (busy) return;
    await nextTick();
    if (document.activeElement === document.body) body.value?.focus();
  },
);
onBeforeUnmount(() => {
  void nextTick(() => {
    if (trigger instanceof HTMLElement && trigger.isConnected)
      trigger.focus({ preventScroll: true });
  });
});
</script>

<template>
  <Drawer
    :open="true"
    :title="title"
    placement="right"
    :width="wide ? 'min(1040px, 100vw)' : 'min(800px, 100vw)'"
    :mask-closable="false"
    :keyboard="!busy"
    :closable="!busy"
    :destroy-on-close="true"
    :push="false"
    root-class-name="admin-drawer-wrap"
    :aria-busy="busy"
    @close="close"
  >
    <div ref="body" class="admin-drawer-body" tabindex="-1"><slot /></div>
    <template v-if="showFooter" #footer>
      <slot name="actions" />
      <a-button html-type="button" :disabled="busy" @click="close"
        >关闭详情</a-button
      >
    </template>
  </Drawer>
</template>
