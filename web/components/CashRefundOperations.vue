<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "../client";
const rows = ref<any[]>([]),
  receipts = ref<any[]>([]),
  wallet = ref<any>(null),
  busy = ref(false),
  error = ref(""),
  notice = ref("");
const userId = ref<number | null>(null),
  receiptId = ref(0),
  amount = ref(""),
  reason = ref(""),
  selected = ref<any>(null),
  outcome = ref("success"),
  recipient = ref(""),
  paidAt = ref(""),
  proof = ref(""),
  confirmed = ref(false),
  confirmedNoPayment = ref(false);
const names: Record<string, string> = {
  processing: "办理中，积分已占用",
  unknown: "结果未明，保留占用",
  failed: "失败并释放占用",
  rejected: "已驳回释放占用",
  success: "已实际退现金并扣积分",
};
const money = (v: number) => "¥" + (v / 100).toFixed(2);
function cents(v: string) {
  if (!/^\d+(\.\d{1,2})?$/.test(v)) throw new Error("金额最多两位小数");
  const [a, b = ""] = v.split(".");
  return Number(a) * 100 + Number(b.padEnd(2, "0"));
}
async function load() {
  rows.value = await api<any[]>("/admin/cash-refunds");
}
async function run(work: () => Promise<any>, refresh = true) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await work();
    if (refresh) {
      notice.value = "操作已记录";
      await load();
      if (userId.value)
        wallet.value = await api("/admin/wallets/" + userId.value);
    }
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(() => run(load, false));
async function query() {
  await run(async () => {
    if (!userId.value) throw new Error("请输入用户编号");
    [wallet.value, receipts.value] = await Promise.all([
      api("/admin/wallets/" + userId.value),
      api<any[]>("/admin/receipts?userId=" + userId.value),
    ]);
    receiptId.value = 0;
  }, false);
}
async function create() {
  await run(async () => {
    await api("/admin/cash-refunds", {
      method: "POST",
      body: {
        userId: userId.value,
        receiptId: receiptId.value,
        amountCents: cents(amount.value),
        reason: reason.value,
        requestKey: crypto.randomUUID(),
      },
    });
  });
}
function open(r: any) {
  selected.value = r;
  outcome.value = "success";
  confirmed.value = false;
  confirmedNoPayment.value = false;
  proof.value = "";
  recipient.value = "";
  paidAt.value = "";
  reason.value = "";
}
async function resolve() {
  await run(async () => {
    await api("/admin/cash-refunds/" + selected.value.id + "/resolve", {
      method: "POST",
      body: {
        outcome: outcome.value,
        recipient: recipient.value,
        paidAt: paidAt.value ? new Date(paidAt.value).toISOString() : "",
        proof: proof.value,
        confirmed: confirmed.value,
        confirmedNoPayment: confirmedNoPayment.value,
        reason: reason.value,
      },
    });
    selected.value = null;
  });
}
</script>
<template>
  <section class="cash-refunds">
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-if="notice" class="message success" role="status">{{ notice }}</p>
    <form class="surface" @submit.prevent="query">
      <h3>核实原收款与未用积分</h3>
      <label
        >用户编号<input
          v-model.number="userId"
          type="number"
          min="1"
          required /></label
      ><button :disabled="busy">查询原收款及积分</button>
      <p v-if="wallet">
        可用 {{ money(wallet.availableCents) }}，占用
        {{ money(wallet.heldCents) }}（1元=1积分）
      </p>
    </form>
    <form v-if="wallet" class="surface" @submit.prevent="create">
      <h3>人工受理未用积分退现金</h3>
      <p>
        产品包款、已消费积分和其他人的收款不能作为退款来源。办理时先占用，实际退款后才扣回积分。
      </p>
      <label
        >原公司订货收款<select v-model.number="receiptId" required>
          <option :value="0" disabled>请选择原收款</option>
          <option
            v-for="r in receipts.filter((r) => r.purpose === 'points')"
            :key="r.id"
            :value="r.id"
          >
            #{{ r.id }} · {{ r.external_transaction_id }} · 原收{{
              money(r.amount_cents)
            }}
            已退{{ money(r.cash_refunded_cents) }}
          </option>
        </select></label
      ><label
        >本次退款（元）<input
          v-model="amount"
          inputmode="decimal"
          required /></label
      ><label>申请原因<input v-model="reason" required maxlength="200" /></label
      ><button class="primary" :disabled="busy || !receiptId">
        确认受理并占用积分
      </button>
    </form>
    <form v-if="selected" class="surface" @submit.prevent="resolve">
      <h3>登记实际处理结果 · {{ selected.number }}</h3>
      <p>本次金额 {{ money(selected.amount_cents) }}</p>
      <label
        >结果<select v-model="outcome">
          <option value="success">实际已退款成功</option>
          <option value="unknown">结果待核查，保留占用</option>
          <option value="failed">核实退款失败，释放占用</option>
          <option value="rejected">驳回未付款申请，释放占用</option>
        </select></label
      ><label
        >实际收款人<input
          v-model="recipient"
          :required="outcome === 'success'" /></label
      ><label
        >实际付款时间<input
          v-model="paidAt"
          type="datetime-local"
          :required="outcome === 'success'" /></label
      ><label
        >实际转账或核查凭证<input
          v-model="proof"
          :required="
            outcome === 'success' ||
            (selected.status === 'unknown' &&
              ['failed', 'rejected'].includes(outcome))
          " /></label
      ><label
        ><input v-model="confirmed" type="checkbox" />确认已经实际退现金</label
      ><label
        ><input
          v-model="confirmedNoPayment"
          type="checkbox"
        />已核实款项未退，允许释放积分占用</label
      ><label>处理原因<input v-model="reason" required maxlength="200" /></label
      ><button class="primary" :disabled="busy">保存实际处理结果</button
      ><button type="button" @click="selected = null">取消</button>
    </form>
    <section class="surface">
      <h3>积分余额退款记录</h3>
      <article v-for="r in rows" :key="r.id">
        <strong>{{ names[r.status] }} · {{ money(r.amount_cents) }}</strong>
        <p>
          {{ r.number }} · 用户 #{{ r.user_id }} · 原收款 #{{ r.receipt_id }}
        </p>
        <p>{{ r.reason }}</p>
        <p v-if="r.last_error">{{ r.last_error }}</p>
        <button
          v-if="['processing', 'unknown'].includes(r.status)"
          @click="open(r)"
        >
          登记实际处理结果
        </button>
      </article>
      <p v-if="!rows.length">暂无记录</p>
    </section>
  </section>
</template>
<style scoped>
.surface {
  background: #fff;
  padding: 24px;
  border: 1px solid #e0e5df;
  margin-bottom: 20px;
}
form {
  display: grid;
  gap: 16px;
  max-width: 850px;
}
label {
  display: grid;
  gap: 7px;
  font-size: 13px;
}
input[type="checkbox"] {
  width: 20px;
  height: 20px;
}
select {
  padding: 12px;
  border: 1px solid #d7ddd7;
  border-radius: 6px;
  font: inherit;
  width: 100%;
}
p {
  font-size: 13px;
  color: #738076;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
article {
  padding: 18px 0;
  border-top: 1px solid #e4e8e2;
}
button {
  cursor: pointer;
}
</style>
