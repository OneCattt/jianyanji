<script setup lang="ts">
import { ref } from "vue";
import { api } from "../client";
const props = defineProps<{ code: string; confirmed: boolean }>();
const emit = defineEmits<{
  "update:code": [value: string];
  "update:confirmed": [value: boolean];
  change: [];
}>();
const candidate = ref<{ name: string; identity: string } | null>(null),
  error = ref("");
async function resolve() {
  emit("update:confirmed", false);
  candidate.value = null;
  error.value = "";
  emit("change");
  if (!props.code) return;
  try {
    candidate.value = await api(
      "/sales/candidate?shareCode=" + encodeURIComponent(props.code),
    );
  } catch (e) {
    error.value = (e as Error).message;
  }
}
</script>
<template>
  <section class="surface">
    <p>本单销售代理（可选）。无代理可正常购买；代理本人订货请留空。</p>
    <label
      >销售分享码<input
        :value="code"
        @input="
          emit('update:code', ($event.target as HTMLInputElement).value);
          candidate = null;
          emit('update:confirmed', false);
          emit('change');
        "
        maxlength="64"
    /></label>
    <button type="button" @click="resolve">核验销售代理</button>
    <label v-if="candidate"
      ><input
        type="checkbox"
        :checked="confirmed"
        @change="
          emit('update:confirmed', ($event.target as HTMLInputElement).checked);
          emit('change');
        "
      />确认本单销售代理：{{ candidate.name }}（{{
        candidate.identity === "B" ? "主理人" : "店主"
      }}）</label
    >
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>
