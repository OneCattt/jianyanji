<script setup lang="ts">
import { h, onBeforeUnmount, useId, useSlots, watch } from "vue";
import { message } from "ant-design-vue";
const notice = defineModel<string>("notice", { default: "" });
const error = defineModel<string>("error", { default: "" });
const slots = useSlots();
const key = useId();
const [feedback, contextHolder] = message.useMessage();
watch(
  [notice, error],
  ([success, failure]) => {
    const text = failure || success;
    if (!text) {
      feedback.destroy(key);
      return;
    }
    feedback.open({
      key,
      type: failure ? "error" : "success",
      content:
        failure && slots.default
          ? h("span", { class: "feedback-content" }, [
              h("span", text),
              slots.default(),
            ])
          : text,
      duration: failure ? 5 : 3,
      onClose: () => {
        if (error.value === text) error.value = "";
        if (notice.value === text) notice.value = "";
      },
    });
  },
  { flush: "post" },
);
onBeforeUnmount(() => feedback.destroy(key));
</script>

<template><context-holder /></template>
