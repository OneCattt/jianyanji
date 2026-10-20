<script setup lang="ts">
import FeedbackToast from "./FeedbackToast.vue";
import { computed, ref } from "vue";
import AdminSelect from "./AdminSelect.vue";
import AdminForm from "./AdminForm.vue";
const props = defineProps<{ permissions: readonly string[] }>();
const kinds = [
  ["receipts", "线下公司收款（货款 / 积分充值）", "finance:manage"],
  ["points", "指定用户积分流水", "finance:manage"],
  ["cash-refunds", "未用积分退现金", "finance:manage"],
  ["payments", "指定订单收款 / 积分扣款", "orders:manage"],
  ["refunds", "订单线下退款 / 积分退款", "orders:manage"],
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
const selectedKind = computed(() => kind.value || options.value[0]?.[0]);
async function download() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const selection = kind.value || options.value[0]?.[0];
    if (!selection || !options.value.some((option) => option[0] === selection))
      throw new Error("没有可导出的业务权限");
    if (selection === "points" && !userId.value)
      throw new Error("请填写需要导出积分流水的用户 ID");
    if (selection === "payments" && !orderId.value)
      throw new Error("请填写需要导出收款或积分扣款记录的订单 ID");
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
  <FeedbackToast v-model:error="error" />
  <a-collapse v-if="options.length"
    ><a-collapse-panel key="finance" header="财务业务导出"
      ><AdminForm class="export-form" @submit="download">
        <p>
          沿用当前账号的查询权限；金额单位为分，积分流水为百分之一积分。每次最多
          200 条，可填上次最后一行 ID 继续。凭证和完整账户资料不导出。
        </p>
        <label
          >业务<AdminSelect v-model="kind"
            ><option value="">请选择（默认首项）</option>
            <option
              v-for="option in options"
              :key="option[0]"
              :value="option[0]"
            >
              {{ option[1] }}
            </option></AdminSelect
          ></label
        >
        <label
          >用户 ID（积分流水必填）<a-input
            v-model:value="userId"
            :required="selectedKind === 'points'"
            pattern="[1-9][0-9]*"
            inputmode="numeric"
        /></label>
        <label
          >订单 ID（指定订单收款必填；公司收款汇总不支持）<a-input
            v-model:value="orderId"
            :required="selectedKind === 'payments'"
            pattern="[1-9][0-9]*"
            inputmode="numeric"
        /></label>
        <label
          >继续导出的最后一行 ID<a-input
            v-model:value="beforeId"
            pattern="[1-9][0-9]*"
            inputmode="numeric"
        /></label>
        <a-button html-type="submit" :loading="busy">
          {{ busy ? "正在导出…" : "下载 CSV" }}
        </a-button>
      </AdminForm></a-collapse-panel
    ></a-collapse
  >
</template>

<style scoped>
.export-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.export-form > p {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--admin-muted);
  line-height: 1.6;
}
.export-form > label {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.export-form > .ant-btn {
  justify-self: start;
}
@media (max-width: 650px) {
  .export-form {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
