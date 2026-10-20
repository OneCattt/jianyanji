<script setup lang="ts">
import { computed, ref } from "vue";
import AdminSelect from "./AdminSelect.vue";
const props = defineProps<{ permissions: readonly string[] }>();
const kinds = [
  ["receipts", "公司收款", "finance:manage"],
  ["points", "指定用户积分流水", "finance:manage"],
  ["cash-refunds", "未用积分退现金", "finance:manage"],
  ["payments", "指定订单支付", "orders:manage"],
  ["refunds", "订单退款", "orders:manage"],
  ["rewards", "各收益人奖励", "rewards:manage"],
  ["settlements", "月结实转", "rewards:manage"],
  ["reversals", "奖励冲回及待收回", "rewards:manage"],
  ["offsets", "实际抵扣", "rewards:manage"],
  ["recoveries", "实际追回", "rewards:manage"],
];
const options = computed(() =>
  kinds.filter((k) => props.permissions.includes(k[2]!)),
);
const kind = ref(""),
  userId = ref(""),
  orderId = ref(""),
  beforeId = ref(""),
  error = ref(""),
  busy = ref(false);
async function download() {
  busy.value = true;
  error.value = "";
  try {
    const selection = kind.value || options.value[0]?.[0];
    if (!selection) throw new Error("没有可导出的业务权限");
    const params = new URLSearchParams();
    for (const [key, value] of [
      ["userId", userId.value],
      ["orderId", orderId.value],
      ["beforeId", beforeId.value],
    ])
      if (value) params.set(key!, value);
    const response = await fetch(
      `/api/admin/exports/finance/${selection}?${params}`,
      { credentials: "same-origin" },
    );
    if (!response.ok)
      throw new Error((await response.json()).message || "导出失败");
    const url = URL.createObjectURL(await response.blob()),
      link = document.createElement("a");
    link.href = url;
    link.download = `简妍肌-${selection}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <details v-if="options.length" class="surface">
    <summary>财务业务导出</summary>
    <p>
      沿用当前账号的查询权限；金额单位为分，积分流水为百分之一积分。每次最多 200
      条，可填上次最后一行 ID 继续。凭证和完整账户资料不导出。
    </p>
    <label
      >业务<AdminSelect v-model="kind"
        ><option value="">请选择（默认首项）</option>
        <option v-for="option in options" :key="option[0]" :value="option[0]">
          {{ option[1] }}
        </option></AdminSelect
      ></label
    >
    <label
      >用户 ID（积分流水必填）<input v-model="userId" inputmode="numeric"
    /></label>
    <label
      >订单 ID（支付必填；仅支付、退款、积分、奖励支持）<input
        v-model="orderId"
        inputmode="numeric"
    /></label>
    <label
      >继续导出的最后一行 ID<input v-model="beforeId" inputmode="numeric"
    /></label>
    <button type="button" :disabled="busy" @click="download">
      {{ busy ? "正在导出…" : "下载 CSV" }}
    </button>
    <p v-if="error" role="alert" class="message error">{{ error }}</p>
  </details>
</template>
