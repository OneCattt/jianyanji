<script setup lang="ts">
import { useAdminList } from "../useAdminList";
import FeedbackToast from "./FeedbackToast.vue";
import { onMounted, ref } from "vue";
import { api } from "../client";
import AdminDialog from "./AdminDialog.vue";
import AdminForm from "./AdminForm.vue";
import AdminSelect from "./AdminSelect.vue";
import AdminDatePicker from "./AdminDatePicker.vue";
const purposeNames: Record<string, string> = {
  points: "积分充值",
  package: "入驻产品包",
  order: "普通商品货款",
};
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
const dialog = ref<"receipt" | "correction" | null>(null);
const wallet = ref<Wallet | null>(null),
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
const {
  rows: receipts,
  pagination: receiptPagination,
  loading: listLoading,
  load: loadReceipts,
} = useAdminList<Receipt>(
  "/admin/receipts",
  () => ({
    userId: userId.value,
    receiptId: receiptId.value,
    externalTransactionId: externalTransactionId.value,
  }),
  error,
);
const {
  rows: ledger,
  pagination: ledgerPagination,
  loading: ledgerLoading,
  load: loadLedger,
} = useAdminList<Entry>(
  () => "/admin/wallets/" + userId.value + "/ledger",
  () => ({ userId: userId.value }),
  error,
  () => Boolean(userId.value),
);
async function refresh() {
  await loadReceipts();
  if (userId.value)
    [wallet.value] = await Promise.all([
      api<Wallet>("/admin/wallets/" + userId.value + "?includeLedger=false"),
      loadLedger(),
    ]);
  else wallet.value = null;
}
function closeDialog() {
  if (!busy.value) dialog.value = null;
}
function openReceipt() {
  form.value = {
    userId: userId.value,
    amount: "",
    sourceChannel: "bank",
    accountRef: "",
    externalTransactionId: "",
    receivedAt: "",
    evidence: "",
    reason: "",
    verified: false,
  };
  error.value = "";
  dialog.value = "receipt";
}
function openCorrection(lotId: number) {
  correction.value = {
    lotId,
    amount: "",
    reason: "",
    requestKey: crypto.randomUUID(),
  };
  error.value = "";
  dialog.value = "correction";
}
async function confirmReceipt() {
  await run(async () => {
    if (!form.value.verified)
      throw new Error("请先确认公司真实到账、收款人和原流水");
    if (!/^[1-9]\d*$/.test(form.value.userId))
      throw new Error("请输入有效的代理用户编号");
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
    dialog.value = null;
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
    await refresh();
    correction.value = {
      lotId: 0,
      amount: "",
      reason: "",
      requestKey: crypto.randomUUID(),
    };
    dialog.value = null;
    notice.value = "纠正记录已保存，原收款和历史流水仍可追溯";
  });
}
onMounted(() => run(refresh));

const pointLotColumns = [
  { title: "批次 / 原收款", key: "c0", width: 220 },
  { title: "原始积分", key: "c1", width: 160 },
  { title: "剩余 / 占用", key: "c2", width: 160 },
  { title: "操作", key: "c3", width: 200 },
];
const receiptColumns = [
  { title: "原收款 / 代理", key: "c0", width: 220 },
  { title: "用途", key: "c1", width: 160 },
  { title: "到账 / 已退现金", key: "c2", width: 160 },
  { title: "原流水 / 账户", key: "c3", width: 160 },
  { title: "到账时间", key: "c4", width: 200 },
];
</script>
<template>
  <section class="wallet-operations" :aria-busy="busy">
    <Teleport defer to="#admin-page-actions">
      <a-button
        html-type="button"
        type="primary"
        :disabled="busy"
        @click="openReceipt"
      >
        充值核款并发积分
      </a-button>
    </Teleport>
    <p class="admin-business-note">
      仅登记已核实的真实到账。1 元对应 1
      积分，不赠送、不过期。普通货款请到“普通订单收款”，产品包款请到入驻申请核款；两类货款均不发积分。
    </p>
    <FeedbackToast v-model:notice="notice" v-model:error="error" />
    <div class="wallet-workspace">
      <div class="account-workspace">
        <a-card size="small" class="account-query" aria-label="钱包与收款查询">
          <h3>查账与收款追溯</h3>
          <p class="muted">
            填写代理编号可查看积分钱包，也可按原收款编号或流水追查。
          </p>
          <AdminForm class="query-fields" @submit="run(refresh)">
            <label
              >按代理编号追查<a-input-number
                class="number-field"
                string-mode
                :precision="0"
                pattern="[0-9]+"
                v-model:value="userId"
                :min="1" /></label
            ><label
              >原收款编号<a-input-number
                class="number-field"
                string-mode
                :precision="0"
                pattern="[0-9]+"
                v-model:value="receiptId"
                :min="1" /></label
            ><label
              >原银行 / 公司微信流水<a-input
                v-model:value="externalTransactionId"
                :maxlength="200" /></label
            ><a-button html-type="submit" :disabled="busy"
              >查询钱包和收款</a-button
            >
          </AdminForm>
        </a-card>
        <a-card size="small" v-if="wallet" class="wallet-detail">
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
          <div
            class="table-wrap"
            tabindex="0"
            role="region"
            aria-label="原收款积分批次，可横向滚动"
          >
            <a-table
              :data-source="wallet.lots"
              :columns="pointLotColumns"
              row-key="id"
              size="middle"
              :loading="busy || listLoading"
              :scroll="{ x: 680 }"
              :pagination="{
                defaultPageSize: 10,
                showSizeChanger: true,
                pageSizeOptions: ['10', '20', '50'],
                hideOnSinglePage: false,
              }"
              ><template #bodyCell="{ column, record: lot }"
                ><template v-if="column.key === 'c0'"
                  ><div class="table-cell-content">
                    #{{ lot.id }} / #{{ lot.receipt_id
                    }}<small>{{ lot.external_transaction_id }}</small>
                  </div></template
                >
                <template v-else-if="column.key === 'c1'"
                  ><div class="table-cell-content">
                    {{ money(lot.issued_cents) }}
                  </div></template
                >
                <template v-else-if="column.key === 'c2'"
                  ><div class="table-cell-content">
                    {{ money(lot.remaining_cents) }} /
                    {{ money(lot.held_cents) }}
                  </div></template
                >
                <template v-else-if="column.key === 'c3'"
                  ><div class="table-cell-content">
                    <a-button
                      html-type="button"
                      :disabled="busy"
                      @click="openCorrection(lot.id)"
                    >
                      来源差额纠正
                    </a-button>
                  </div></template
                ></template
              ></a-table
            >
          </div>
          <p v-if="!wallet.lots.length" class="empty-state">
            暂无原收款积分批次。
          </p>
          <p class="muted">
            仅能纠正该原批次未消费且未占用的积分，已消费差额须人工核实处理。
          </p>
          <h3>积分流水</h3>
          <div v-for="entry in ledger" :key="entry.id" class="entry">
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
          <p v-if="!ledger.length && !ledgerLoading" class="empty-state">
            暂无积分流水。
          </p>
          <a-pagination v-bind="ledgerPagination" :disabled="ledgerLoading" />
        </a-card>
        <a-card size="small" class="receipts-panel" aria-label="真实收款记录">
          <h3>真实收款记录</h3>
          <div
            class="table-wrap"
            tabindex="0"
            role="region"
            aria-label="真实收款记录，可横向滚动"
          >
            <a-table
              :data-source="receipts"
              :columns="receiptColumns"
              row-key="id"
              size="middle"
              :loading="busy || listLoading"
              :scroll="{ x: 850 }"
              :pagination="receiptPagination"
              ><template #bodyCell="{ column, record: r }"
                ><template v-if="column.key === 'c0'"
                  ><div class="table-cell-content">
                    #{{ r.id }} / #{{ r.user_id }}
                  </div></template
                >
                <template v-else-if="column.key === 'c1'"
                  ><div class="table-cell-content">
                    {{ purposeNames[r.purpose] || r.purpose }}
                  </div></template
                >
                <template v-else-if="column.key === 'c2'"
                  ><div class="table-cell-content">
                    ¥{{ money(r.amount_cents) }} / ¥{{
                      money(r.cash_refunded_cents)
                    }}
                  </div></template
                >
                <template v-else-if="column.key === 'c3'"
                  ><div class="table-cell-content">
                    {{ r.external_transaction_id
                    }}<small>{{ r.account_ref }}</small>
                  </div></template
                >
                <template v-else-if="column.key === 'c4'"
                  ><div class="table-cell-content">
                    {{ r.received_at }}
                  </div></template
                ></template
              ></a-table
            >
          </div>
          <p v-if="busy && !receipts.length" class="empty-state" role="status">
            正在加载收款记录…
          </p>
          <p v-else-if="!error && !receipts.length" class="empty-state">
            暂无匹配的真实收款，请核对查询条件。
          </p>
        </a-card>
      </div>
    </div>
    <AdminDialog
      v-if="dialog === 'receipt'"
      title="充值核款并发积分"
      :busy="busy"
      @close="closeDialog"
    >
      <AdminForm class="fields receipt-form" @submit="confirmReceipt">
        <h3>核实订货到账并发积分</h3>
        <label
          >代理用户编号<a-input-number
            class="number-field"
            string-mode
            :precision="0"
            pattern="[0-9]+"
            v-model:value="form.userId"
            :min="1"
            required
        /></label>
        <label
          >实际到账（元）<a-input
            v-model:value="form.amount"
            pattern="[0-9]+([.][0-9]{1,2})?"
            data-error-message="请填写最多两位小数的金额"
            inputmode="decimal"
            required
            placeholder="1000.00"
        /></label>
        <label
          >实际收款渠道<AdminSelect v-model="form.sourceChannel">
            <option value="bank">公司银行账户</option>
            <option value="wechat_company">公司微信收款</option>
          </AdminSelect></label
        >
        <label
          >收款账户标识<a-input
            v-model:value="form.accountRef"
            :maxlength="200"
            required
        /></label>
        <label
          >原银行 / 公司微信流水<a-input
            v-model:value="form.externalTransactionId"
            :maxlength="200"
            required
        /></label>
        <label
          >实际到账时间<AdminDatePicker
            v-model="form.receivedAt"
            type="datetime-local"
            required
        /></label>
        <label
          >原始凭证地址或归档编号<a-input
            v-model:value="form.evidence"
            :maxlength="1000"
            required
        /></label>
        <label
          >核实说明<a-input
            v-model:value="form.reason"
            :maxlength="200"
            required
        /></label>
        <div class="confirmation-field check">
          <a-checkbox v-model:checked="form.verified" required
            >我已核实公司真实到账、收款人和原流水，非仅核对转款截图</a-checkbox
          >
        </div>
        <div class="dialog-actions">
          <a-button html-type="button" :disabled="busy" @click="closeDialog">
            取消
          </a-button>
          <a-button html-type="submit" type="primary" :disabled="busy"
            >确认到账并发积分</a-button
          >
        </div>
      </AdminForm>
    </AdminDialog>
    <AdminDialog
      v-if="dialog === 'correction'"
      title="积分来源差额纠正"
      :busy="busy"
      @close="closeDialog"
    >
      <AdminForm class="correction-form" @submit="correct">
        <h4>纠正原批次 #{{ correction.lotId }}</h4>
        <label
          >冲减积分<a-input
            v-model:value="correction.amount"
            pattern="[0-9]+([.][0-9]{1,2})?"
            data-error-message="请填写最多两位小数的金额"
            required
            inputmode="decimal" /></label
        ><label
          >原来源及纠错原因<a-input
            v-model:value="correction.reason"
            :maxlength="200"
            required
        /></label>
        <p class="wide muted">
          仅能纠正该原批次未消费且未占用的积分，已消费差额须人工核实处理。
        </p>
        <div class="dialog-actions">
          <a-button html-type="button" :disabled="busy" @click="closeDialog">
            取消
          </a-button>
          <a-button html-type="submit" type="primary" :disabled="busy"
            >保存纠正流水</a-button
          >
        </div>
      </AdminForm>
    </AdminDialog>
  </section>
</template>
<style scoped>
.wallet-operations {
  display: grid;
  gap: 16px;
  min-width: 0;
  color: var(--admin-ink);
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
h2,
h3,
h4,
p {
  margin: 0;
}
h2 {
  font-size: 18px;
}
h3 {
  font-size: 15px;
}
h4 {
  font-size: 13px;
}
.wallet-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
  gap: 16px;
  min-width: 0;
}
.account-workspace {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.surface {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: #fff;
}
.fields,
.query-fields,
.correction-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  align-content: start;
}
.fields h3,
.fields .check,
.fields > .dialog-actions,
.query-fields > label:last-of-type,
.correction-form h4,
.correction-form > label:last-of-type,
.correction-form > .dialog-actions,
.wide {
  grid-column: 1 / -1;
}
.account-query {
  display: grid;
  gap: 8px;
}
.query-fields {
  margin-top: 4px;
}
label {
  display: grid;
  min-width: 0;
  gap: 5px;
  font-size: 13px;
}
.check {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.wallet-detail {
  display: grid;
  gap: 12px;
}
.balances {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--admin-border);
}
.balances p {
  color: var(--admin-muted);
}
.balances strong {
  display: block;
  margin-top: 4px;
  color: var(--admin-ink);
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1.3;
}
.balances p:nth-child(2) strong {
  color: var(--admin-accent);
}
.entry {
  display: flex;
  justify-content: space-between;
  align-items: start;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--admin-border);
}
.entry > div {
  min-width: 0;
}
.entry > strong {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
small {
  display: block;
  color: var(--admin-muted);
  margin-top: 4px;
  overflow-wrap: anywhere;
}
.muted {
  color: var(--admin-muted);
}
.feedback {
  padding: 10px 12px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
.error,
.notice {
  color: var(--admin-accent);
  background: var(--admin-accent-soft);
}
.error {
  border-color: var(--admin-accent);
}
.receipts-panel {
  display: grid;
  gap: 12px;
}
.empty-state {
  padding: 20px 12px;
  text-align: center;
  color: var(--admin-muted);
}
@media (max-width: 1100px) {
  .wallet-workspace {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 650px) {
  .fields,
  .query-fields,
  .correction-form {
    grid-template-columns: minmax(0, 1fr);
  }
  .surface {
    padding: 12px;
  }
  .balances {
    gap: 8px;
  }
  .balances strong {
    font-size: 18px;
  }
}

.number-field {
  width: 100%;
  min-width: 0;
}
.table-cell-content {
  min-width: 0;
  overflow-wrap: anywhere;
}
.table-cell-content > .ant-btn + .ant-btn {
  margin-left: 8px;
}
.section-heading {
  margin: 0 0 16px;
}
.wallet-detail :deep(.ant-card-body),
.account-query :deep(.ant-card-body),
.receipts-panel :deep(.ant-card-body) {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.confirmation-field {
  min-width: 0;
  grid-column: 1 / -1;
}
.admin-form > label,
.fields > label,
.result-fields > label,
.field-grid > label,
.sku-form > label {
  align-content: start;
}
</style>
