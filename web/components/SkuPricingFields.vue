<script setup lang="ts">
import AdminSelect from "./AdminSelect.vue";
const props = defineProps<{
  productType: "regular" | "welfare";
  retail: string;
  welfare: string;
  store?: string;
  principal?: string;
}>();
const emit = defineEmits<{
  "update:productType": [value: "regular" | "welfare"];
  "update:retail": [value: string];
  "update:welfare": [value: string];
  "update:store": [value: string];
  "update:principal": [value: string];
}>();
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
    >零售价（元）<a-input
      :value="retail"
      @update:value="emit('update:retail', $event)"
      inputmode="decimal"
      pattern="[0-9]+([.][0-9]{1,2})?"
      placeholder="暂不可购买"
  /></label>
  <label v-else
    >统一福利价（元）<a-input
      :value="welfare"
      @update:value="emit('update:welfare', $event)"
      inputmode="decimal"
      pattern="[0-9]+([.][0-9]{1,2})?"
      placeholder="暂不可购买"
  /></label>
  <template v-if="productType === 'regular'">
    <label
      >2980店主价（元）<a-input
        :value="store"
        @update:value="emit('update:store', $event)"
        inputmode="decimal"
        placeholder="未配置则该档不可购买"
    /></label>
    <label
      >19800店主价（元）<a-input
        :value="principal"
        @update:value="emit('update:principal', $event)"
        inputmode="decimal"
        placeholder="未配置则该档不可购买"
    /></label>
  </template>
  <p class="field-help">
    {{
      productType === "welfare"
        ? "各身份同价；不产生零售佣金或主理人订货收益。"
        : "三档价格独立设置，按当前有效身份计价。"
    }}
  </p>
</template>
