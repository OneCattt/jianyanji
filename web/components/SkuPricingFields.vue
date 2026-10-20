<script setup lang="ts">
import { computed } from "vue";
import AdminSelect from "./AdminSelect.vue";
const props = defineProps<{
  productType: "regular" | "welfare";
  retail: string;
  welfare: string;
}>();
const emit = defineEmits<{
  "update:productType": [value: "regular" | "welfare"];
  "update:retail": [value: string];
  "update:welfare": [value: string];
}>();
const preview = computed(() => {
  if (!/^\d+(\.\d{1,2})?$/.test(props.retail)) return "填写零售价后显示身份价";
  const [yuan, fraction = ""] = props.retail.split(".");
  const cents = Number(yuan) * 100 + Number(fraction.padEnd(2, "0"));
  const price = (percent: number) =>
    (Math.floor((cents * percent + 50) / 100) / 100).toFixed(2);
  return `店主 ¥${price(70)} · 主理人 ¥${price(35)}（保存后以服务端核价为准）`;
});
</script>
<template>
  <label
    >商品类型<AdminSelect
      :model-value="productType"
      @update:model-value="
        emit('update:productType', $event as 'regular' | 'welfare')
      "
    >
      <option value="regular">普通商品</option>
      <option value="welfare">福利品</option>
    </AdminSelect></label
  >
  <label v-if="productType === 'regular'"
    >零售价（元）<input
      :value="retail"
      @input="emit('update:retail', ($event.target as HTMLInputElement).value)"
      inputmode="decimal"
      pattern="[0-9]+([.][0-9]{1,2})?"
      placeholder="暂不可购买"
  /></label>
  <label v-else
    >统一福利价（元）<input
      :value="welfare"
      @input="emit('update:welfare', ($event.target as HTMLInputElement).value)"
      inputmode="decimal"
      pattern="[0-9]+([.][0-9]{1,2})?"
      placeholder="暂不可购买"
  /></label>
  <p class="field-help">
    {{
      productType === "welfare"
        ? "各身份同价；不产生零售佣金或主理人订货收益。"
        : preview
    }}
  </p>
</template>
