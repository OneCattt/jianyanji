<script setup lang="ts">
import AdminDrawer from "./AdminDrawer.vue";
import { Modal } from "ant-design-vue";
import { nextTick, ref, watch } from "vue";
const props = withDefaults(
  defineProps<{
    title: string;
    busy?: boolean;
    wide?: boolean;
    drawer?: boolean;
  }>(),
  {
    busy: false,
    wide: false,
    drawer: false,
  },
);
const emit = defineEmits<{ close: [] }>();
const body = ref<HTMLElement>();
watch(
  () => props.busy,
  async (busy) => {
    if (busy) return;
    await nextTick();
    // 提交时禁用按钮可能使焦点回到页面，完成后恢复弹窗键盘操作。
    if (document.activeElement === document.body)
      body.value?.closest<HTMLElement>(".ant-modal-wrap")?.focus();
  },
);
</script>

<template>
  <AdminDrawer
    v-if="wide || drawer"
    :title="title"
    :busy="busy"
    :wide="wide"
    :show-footer="false"
    @close="emit('close')"
    ><slot
  /></AdminDrawer>
  <Modal
    v-else
    :open="true"
    :title="title"
    :width="wide ? 900 : 680"
    :footer="null"
    :mask-closable="false"
    :keyboard="!busy"
    :closable="!busy"
    :centered="true"
    :destroy-on-close="true"
    class="admin-dialog"
    :class="{ 'admin-dialog-wide': wide }"
    wrap-class-name="admin-modal-wrap"
    :aria-busy="busy"
    @cancel="!busy && emit('close')"
  >
    <div ref="body" class="admin-dialog-body"><slot /></div>
  </Modal>
</template>
