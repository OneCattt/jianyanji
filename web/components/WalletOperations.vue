<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "../client";
interface Receipt {
  id: number;
  user_id: number;
  purpose: string;
  amount_cents: number;
  account_ref: string;
  external_transaction_id: string;
  received_at: string;
  cash_refunded_cents: number;
}
interface Lot {
  id: number;
  receipt_id: number;
  issued_cents: number;
  remaining_cents: number;
  held_cents: number;
  external_transaction_id: string;
}
interface Entry {
  id: number;
  kind: string;
  delta_cents: number;
  receipt_id: number;
  lot_id: number;
  order_number: string | null;
  reason: string;
  create_time: string;
}
interface Wallet {
  balanceCents: number;
  availableCents: number;
  heldCents: number;
  lots: Lot[];
  ledger: Entry[];
}
const receipts = ref<Receipt[]>([]),
  wallet = ref<Wallet | null>(null),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  userId = ref(""),
  receiptId = ref(""),
  externalTransactionId = ref("");
const form = ref({
  userId: "",
  amount: "",
  sourceChannel: "bank",
  accountRef: "",
  externalTransactionId: "",
  receivedAt: "",
  evidence: "",
  reason: "",
  verified: false,
});
const correction = ref({
  lotId: 0,
  amount: "",
  reason: "",
  requestKey: crypto.randomUUID(),
});
const money = (v: number) => (v / 100).toFixed(2);
const kinds: Record<string, string> = {
  credit: "公司订货收款发积分",
  spend: "订单积分支付",
  order_refund: "商品退款原批次返还",
  cash_refund: "原公司收款退现金",
  correction: "来源积分纠错",
};
function cents(value: string) {
  if (!/^\d+(\.\d{1,2})?$/.test(value))
    throw new Error("金额须为最多两位小数的正数");
  const [whole, part = ""] = value.split(".");
  const result = Number(whole) * 100 + Number(part.padEnd(2, "0"));
  if (!Number.isSafeInteger(result) || result < 1 || result > 100000000)
    throw new Error("金额超出允许范围");
  return result;
}
async function run(work: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await work();
  } catch (e) {
    error.value =
      e instanceof Error ? e.message : "办理失败，请核实原流水后重试";
  } finally {
    busy.value = false;
  }
}
async function refresh() {
  const query = new URLSearchParams();
  if (userId.value) query.set("userId", userId.value);
  if (receiptId.value) query.set("receiptId", receiptId.value);
  if (externalTransactionId.value)
    query.set("externalTransactionId", externalTransactionId.value);
  receipts.value = await api<Receipt[]>("/admin/receipts?" + query.toString());
  if (userId.value)
    wallet.value = await api<Wallet>("/admin/wallets/" + userId.value);
  else wallet.value = null;
}
async function confirmReceipt() {
  await run(async () => {
    await api("/admin/receipts", {
      method: "POST",
      body: {
        userId: Number(form.value.userId),
        purpose: "points",
        sourceChannel: form.value.sourceChannel,
        accountRef: form.value.accountRef,
        externalTransactionId: form.value.externalTransactionId,
        amountCents: cents(form.value.amount),
        receivedAt: new Date(form.value.receivedAt).toISOString(),
        evidence: form.value.evidence,
        reason: form.value.reason,
        verified: form.value.verified,
      },
    });
    userId.value = form.value.userId;
    await refresh();
    form.value.externalTransactionId = "";
    form.value.verified = false;
    notice.value = "已确认公司收款并一次性发放对应积分";
  });
}
async function correct() {
  await run(async () => {
    await api("/admin/wallet-lots/" + correction.value.lotId + "/correct", {
      method: "POST",
      body: {
        amountCents: cents(correction.value.amount),
        reason: correction.value.reason,
        requestKey: correction.value.requestKey,
      },
    });
    correction.value = {
      lotId: 0,
      amount: "",
      reason: "",
      requestKey: crypto.randomUUID(),
    };
    await refresh();
    notice.value = "纠正记录已保存，原收款和历史流水仍可追溯";
  });
}
onMounted(() => run(refresh));
</script>
<template>
  <section class="wallet-operations">
    <h2>公司订货收款与积分钱包</h2>
    <p class="muted">
      仅登记品牌方已经核实的真实到账。1 元对应 1
      积分，不赠送、不过期；产品包款请到入驻申请核款。
    </p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <form class="surface fields" @submit.prevent="confirmReceipt">
      <h3>核实订货到账并发积分</h3>
      <label
        >代理用户编号<input
          v-model="form.userId"
          type="number"
          min="1"
          required
      /></label>
      <label
        >实际到账（元）<input
          v-model="form.amount"
          inputmode="decimal"
          required
          placeholder="1000.00"
      /></label>
      <label
        >实际收款渠道<select v-model="form.sourceChannel">
          <option value="bank">公司银行账户</option>
          <option value="wechat_company">公司微信收款</option>
        </select></label
      >
      <label
        >收款账户标识<input v-model="form.accountRef" maxlength="200" required
      /></label>
      <label
        >原银行 / 公司微信流水<input
          v-model="form.externalTransactionId"
          maxlength="200"
          required
      /></label>
      <label
        >实际到账时间<input
          v-model="form.receivedAt"
          type="datetime-local"
          required
      /></label>
      <label
        >原始凭证地址或归档编号<input
          v-model="form.evidence"
          maxlength="1000"
          required
      /></label>
      <label
        >核实说明<input v-model="form.reason" maxlength="200" required
      /></label>
      <label class="check"
        ><input
          v-model="form.verified"
          type="checkbox"
          required
        />我已核实公司真实到账、收款人和原流水，非仅核对转款截图</label
      >
      <button class="primary" :disabled="busy">确认到账并发积分</button>
    </form>
    <form class="inline" @submit.prevent="run(refresh)">
      <label
        >按代理编号追查<input v-model="userId" type="number" min="1" /></label
      ><label
        >原收款编号<input v-model="receiptId" type="number" min="1" /></label
      ><label
        >原银行 / 公司微信流水<input
          v-model="externalTransactionId"
          maxlength="200" /></label
      ><button :disabled="busy">查询钱包和收款</button>
    </form>
    <div v-if="wallet" class="surface">
      <div class="balances">
        <p>
          积分余额<strong>{{ money(wallet.balanceCents) }}</strong>
        </p>
        <p>
          可用积分<strong>{{ money(wallet.availableCents) }}</strong>
        </p>
        <p>
          办理中占用<strong>{{ money(wallet.heldCents) }}</strong>
        </p>
      </div>
      <h3>原收款积分批次</h3>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>批次 / 原收款</th>
              <th>原始积分</th>
              <th>剩余 / 占用</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="lot in wallet.lots" :key="lot.id">
              <td>
                #{{ lot.id }} / #{{ lot.receipt_id
                }}<small>{{ lot.external_transaction_id }}</small>
              </td>
              <td>{{ money(lot.issued_cents) }}</td>
              <td>
                {{ money(lot.remaining_cents) }} / {{ money(lot.held_cents) }}
              </td>
              <td>
                <button @click="correction.lotId = lot.id">来源差额纠正</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <form v-if="correction.lotId" class="inline" @submit.prevent="correct">
        <h4>纠正原批次 #{{ correction.lotId }}</h4>
        <label
          >冲减积分<input
            v-model="correction.amount"
            required
            inputmode="decimal" /></label
        ><label
          >原来源及纠错原因<input
            v-model="correction.reason"
            maxlength="200"
            required /></label
        ><button :disabled="busy">保存纠正流水</button
        ><button type="button" @click="correction.lotId = 0">取消</button>
      </form>
      <p class="muted">
        仅能纠正该原批次未消费且未占用的积分，已消费差额须人工核实处理。
      </p>
      <h3>积分流水</h3>
      <div v-for="entry in wallet.ledger" :key="entry.id" class="entry">
        <div>
          {{ kinds[entry.kind] || entry.kind }} ·
          {{ entry.order_number || "原收款 #" + entry.receipt_id
          }}<small>{{ entry.create_time }} · {{ entry.reason }}</small>
        </div>
        <strong
          >{{ entry.delta_cents > 0 ? "+" : ""
          }}{{ money(entry.delta_cents) }}</strong
        >
      </div>
      <p v-if="!wallet.ledger.length">暂无流水</p>
    </div>
    <h3>真实收款记录</h3>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>原收款 / 代理</th>
            <th>用途</th>
            <th>到账 / 已退现金</th>
            <th>原流水 / 账户</th>
            <th>到账时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in receipts" :key="r.id">
            <td>#{{ r.id }} / #{{ r.user_id }}</td>
            <td>{{ r.purpose === "points" ? "订货积分" : "入驻产品包" }}</td>
            <td>
              ¥{{ money(r.amount_cents) }} / ¥{{ money(r.cash_refunded_cents) }}
            </td>
            <td>
              {{ r.external_transaction_id }}<small>{{ r.account_ref }}</small>
            </td>
            <td>{{ r.received_at }}</td>
          </tr>
        </tbody>
      </table>
      <p v-if="!receipts.length">暂无匹配的真实收款</p>
    </div>
  </section>
</template>
<style scoped>
.wallet-operations {
  max-width: 1120px;
}
.surface {
  background: #fff;
  padding: 24px;
  border: 1px solid #e5e0da;
  margin: 20px 0;
}
.fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
.fields h3,
.fields .check {
  grid-column: 1/-1;
}
label {
  display: grid;
  gap: 8px;
  font-size: 13px;
}
input,
select {
  min-width: 0;
  width: 100%;
  box-sizing: border-box;
  padding: 11px;
  border: 1px solid #d7ddd7;
  background: white;
  border-radius: 5px;
  font: inherit;
}
.check {
  display: flex;
  align-items: center;
}
.check input {
  width: 18px;
  height: 18px;
}
.inline,
.balances,
.entry {
  display: flex;
  align-items: end;
  gap: 20px;
  flex-wrap: wrap;
}
.inline {
  margin: 20px 0;
}
.balances {
  gap: 40px;
}
.balances strong {
  display: block;
  font:
    26px Georgia,
    serif;
  margin-top: 8px;
  color: #8d1724;
}
.entry {
  justify-content: space-between;
  align-items: center;
  border-top: 1px solid #e9e1da;
  padding: 16px 0;
}
small {
  display: block;
  color: #8e8178;
  margin-top: 6px;
  overflow-wrap: anywhere;
}
.muted {
  color: #74675f;
  line-height: 1.8;
}
.error {
  color: #a32332;
}
button {
  cursor: pointer;
}
.table-wrap {
  overflow-x: auto;
}
table {
  width: 100%;
}
td,
th {
  padding: 14px;
  text-align: left;
  border-bottom: 1px solid #e9e1da;
}
@media (max-width: 650px) {
  .fields {
    grid-template-columns: 1fr;
  }
  .surface {
    padding: 16px;
  }
}
</style>
