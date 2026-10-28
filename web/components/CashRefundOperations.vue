<script setup lang="ts">
import AdminUserName from "./AdminUserName.vue";
import AdminUserSelect from "./AdminUserSelect.vue";
import { useAdminList } from "../useAdminList";
import FeedbackToast from "./FeedbackToast.vue";
import { onMounted, ref, watch } from "vue";
import { api } from "../client";
import AdminDialog from "./AdminDialog.vue";
import AdminForm from "./AdminForm.vue";
import AdminSelect from "./AdminSelect.vue";
import AdminDatePicker from "./AdminDatePicker.vue";
const creating = ref(false);
const userInput = ref<{ focus: () => void } | null>(null);
const receipts = ref<any[]>([]),
  wallet = ref<any>(null),
  busy = ref(false),
  error = ref(""),
  notice = ref("");
const userId = ref(""),
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
watch(
  userId,
  () => {
    wallet.value = null;
    receipts.value = [];
    receiptId.value = 0;
    creating.value = false;
  },
  { flush: "sync" },
);
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
const {
  rows,
  pagination: refundPagination,
  loading: listLoading,
  load: loadRefunds,
} = useAdminList<any>("/admin/cash-refunds", () => ({}), error);
async function load() {
  await loadRefunds();
}
async function run(work: () => Promise<any>, refresh = true) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await work();
    if (refresh) {
      await load();
      if (userId.value)
        wallet.value = await api("/admin/wallets/" + userId.value);
      notice.value = "操作已记录，请查看当前退现金办理状态";
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
    if (!Number.isSafeInteger(Number(userId.value)) || Number(userId.value) < 1)
      throw new Error("请输入有效的用户编号");
    [wallet.value, receipts.value] = await Promise.all([
      api("/admin/wallets/" + userId.value),
      api<any[]>("/admin/receipts?userId=" + userId.value),
    ]);
    receiptId.value = 0;
  }, false);
}
function openCreate() {
  receiptId.value = 0;
  amount.value = "";
  reason.value = "";
  error.value = "";
  creating.value = true;
}
async function create() {
  await run(async () => {
    await api("/admin/cash-refunds", {
      method: "POST",
      body: {
        userId: Number(userId.value),
        receiptId: receiptId.value,
        amountCents: cents(amount.value),
        reason: reason.value,
        requestKey: crypto.randomUUID(),
      },
    });
    creating.value = false;
  });
}
function open(r: any) {
  error.value = "";
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
    if (outcome.value === "success" && !confirmed.value)
      throw new Error("请确认已经实际退现金");
    if (
      selected.value.status === "unknown" &&
      ["failed", "rejected"].includes(outcome.value) &&
      !confirmedNoPayment.value
    )
      throw new Error("请核实款项未退后再释放积分占用");
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

const cashRefundColumns = [
  { title: "状态 / 金额", key: "c0", width: 220 },
  { title: "退款单 / 来源", key: "c1", width: 160 },
  { title: "原因 / 异常", key: "c2", width: 160 },
  { title: "操作", key: "c3", width: 200 },
];
</script>
<template>
  <section class="cash-refunds">
    <Teleport defer to="#admin-page-actions">
      <a-button
        html-type="button"
        type="primary"
        :disabled="busy || !wallet"
        :aria-describedby="!wallet ? 'cash-refund-create-help' : undefined"
        @click="openCreate"
      >
        人工受理未用积分退现金
      </a-button>
    </Teleport>
    <FeedbackToast v-model:notice="notice" v-model:error="error" />
    <a-steps
      size="small"
      :current="wallet ? 1 : 0"
      :items="[
        { title: '核实来源', description: '查询原收款和未用积分' },
        { title: '受理申请', description: '填写金额并占用积分' },
        { title: '登记实际结果', description: '核实线下退款并存证' },
      ]"
    />
    <div class="application-grid">
      <AdminForm class="surface query-form" @submit="query">
        <div class="section-heading">
          <h3>核实原收款与未用积分</h3>
          <p>搜索并选择申请人，核对可退款来源。</p>
        </div>
        <label
          >用户编号<AdminUserSelect
            v-model="userId"
            ref="userInput"
            required
            :disabled="busy"
        /></label>
        <div class="form-actions">
          <a-button html-type="submit" :disabled="busy"
            >查询原收款及积分</a-button
          >
        </div>
        <div v-if="wallet" class="wallet-summary">
          <div>
            <span>可用积分</span
            ><strong>{{ (wallet.availableCents / 100).toFixed(2) }}</strong>
          </div>
          <div>
            <span>占用积分</span
            ><strong>{{ (wallet.heldCents / 100).toFixed(2) }}</strong>
          </div>
          <p>1元 = 1积分</p>
        </div>
        <p v-else id="cash-refund-create-help" class="empty-hint">
          查询后可查看积分余额，并选择原公司订货收款办理退现金。
        </p>
      </AdminForm>
    </div>
    <a-card size="small" class="records">
      <div class="section-heading">
        <h3>积分余额退款记录</h3>
        <p>办理中或结果未明的记录，可继续登记实际处理结果。</p>
      </div>
      <div class="table-wrap">
        <a-table
          :data-source="rows"
          :columns="cashRefundColumns"
          row-key="id"
          size="middle"
          :loading="busy || listLoading"
          :scroll="{ x: 680 }"
          :pagination="refundPagination"
          ><template #bodyCell="{ column, record: r }"
            ><template v-if="column.key === 'c0'"
              ><div class="table-cell-content">
                <a-tag
                  :color="
                    r.status === 'success'
                      ? 'green'
                      : ['unknown', 'failed'].includes(r.status)
                        ? 'orange'
                        : 'default'
                  "
                  >{{ names[r.status] }}</a-tag
                ><strong class="record-amount">{{
                  money(r.amount_cents)
                }}</strong>
              </div></template
            >
            <template v-else-if="column.key === 'c1'"
              ><div class="table-cell-content">
                {{ r.number
                }}<small
                  ><AdminUserName :id="r.user_id" /> · 原收款 #{{
                    r.receipt_id
                  }}</small
                >
              </div></template
            >
            <template v-else-if="column.key === 'c2'"
              ><div class="table-cell-content">
                <p>{{ r.reason }}</p>
                <a-collapse v-if="r.last_error" ghost
                  ><a-collapse-panel key="error" header="查看历史异常">{{
                    r.last_error
                  }}</a-collapse-panel></a-collapse
                >
              </div></template
            >
            <template v-else-if="column.key === 'c3'"
              ><div class="table-cell-content">
                <a-button
                  html-type="button"
                  v-if="['processing', 'unknown'].includes(r.status)"
                  @click="open(r)"
                >
                  登记实际处理结果</a-button
                ><span v-else class="record-complete">已完成处理</span>
              </div></template
            ></template
          ><template #emptyText>
            <strong>暂无积分余额退款记录</strong>
            <p>搜索用户，查询原公司收款和未用积分，核实后办理退款。</p>
            <a-button html-type="button" @click="userInput?.focus()">
              搜索用户
            </a-button>
          </template></a-table
        >
      </div>
    </a-card>
    <AdminDialog
      v-if="creating"
      drawer
      title="人工受理未用积分退现金"
      :busy="busy"
      @close="!busy && (creating = false)"
    >
      <AdminForm class="application-form" @submit="create">
        <div class="section-heading">
          <p>
            产品包款、已消费积分和其他人的收款不能作为退款来源。办理时先占用，实际退款后才扣回积分。
          </p>
        </div>
        <label
          >原公司订货收款<AdminSelect v-model.number="receiptId" required>
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
          </AdminSelect></label
        >
        <p
          v-if="!receipts.some((r) => r.purpose === 'points')"
          class="empty-hint"
        >
          该用户暂无原公司订货收款可选。请核对用户编号和原收款记录后重新查询。
        </p>
        <label
          >本次退款（元）<a-input
            v-model:value="amount"
            pattern="\d+(\.\d{1,2})?"
            data-error-message="金额最多两位小数"
            inputmode="decimal"
            required
        /></label>
        <label
          >申请原因<a-input v-model:value="reason" required :maxlength="200"
        /></label>
        <div class="dialog-actions">
          <a-button
            html-type="button"
            :disabled="busy"
            @click="creating = false"
          >
            取消
          </a-button>
          <a-button
            html-type="submit"
            type="primary"
            :disabled="busy || !receiptId"
          >
            确认受理并占用积分
          </a-button>
        </div>
      </AdminForm>
    </AdminDialog>
    <AdminDialog
      v-if="selected"
      drawer
      :title="'登记实际处理结果 · ' + selected.number"
      :busy="busy"
      @close="!busy && (selected = null)"
    >
      <AdminForm v-if="selected" class="result-form" @submit="resolve">
        <div class="section-heading">
          <p>
            本次金额
            <strong class="refund-amount">{{
              money(selected.amount_cents)
            }}</strong>
          </p>
        </div>
        <div class="result-fields">
          <label
            >结果<AdminSelect v-model="outcome">
              <option value="success">实际已退款成功</option>
              <option value="unknown">结果待核查，保留占用</option>
              <option value="failed">核实退款失败，释放占用</option>
              <option value="rejected">驳回未付款申请，释放占用</option>
            </AdminSelect></label
          >
          <label
            >实际收款人<a-input
              v-model:value="recipient"
              :required="outcome === 'success'"
          /></label>
          <label
            >实际付款时间<AdminDatePicker
              v-model="paidAt"
              type="datetime-local"
              :required="outcome === 'success'"
          /></label>
          <label
            >实际转账或核查凭证<a-input
              v-model:value="proof"
              :required="
                outcome === 'success' ||
                (selected.status === 'unknown' &&
                  ['failed', 'rejected'].includes(outcome))
              "
          /></label>
          <div class="confirmations">
            <div class="confirmation-field check-label">
              <a-checkbox
                v-model:checked="confirmed"
                :required="outcome === 'success'"
                >确认已经实际退现金</a-checkbox
              >
            </div>
            <div class="confirmation-field check-label">
              <a-checkbox
                v-model:checked="confirmedNoPayment"
                :required="
                  selected.status === 'unknown' &&
                  ['failed', 'rejected'].includes(outcome)
                "
                >已核实款项未退，允许释放积分占用</a-checkbox
              >
            </div>
          </div>
          <label class="full-width"
            >处理原因<a-input v-model:value="reason" required :maxlength="200"
          /></label>
        </div>
        <div class="dialog-actions">
          <a-button
            html-type="button"
            :disabled="busy"
            @click="selected = null"
          >
            取消
          </a-button>
          <a-button html-type="submit" type="primary" :disabled="busy"
            >保存实际处理结果</a-button
          >
        </div>
      </AdminForm>
    </AdminDialog>
  </section>
</template>
<style scoped>
.cash-refunds {
  display: grid;
  min-width: 0;
  gap: 16px;
  color: var(--admin-ink);
  font-size: 13px;
}
h2,
h3,
p {
  margin: 0;
}
h2 {
  font-size: 16px;
}
h3 {
  font-size: 14px;
  overflow-wrap: anywhere;
}
p,
small {
  color: var(--admin-muted);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.form-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.surface {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: #fff;
}
.section-heading {
  display: grid;
  gap: 6px;
}
.process-guide {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding: 12px 16px;
  margin: 0;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: #fff;
  list-style: none;
}
.process-guide li {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.process-guide strong {
  font-size: 13px;
}
.process-guide p {
  font-size: 12px;
}
.step-number {
  display: grid;
  place-items: center;
  flex: 0 0 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--admin-accent-soft);
  color: var(--admin-accent);
  font-weight: 600;
}
.application-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
  gap: 16px;
  min-width: 0;
}
.application-grid.has-wallet {
  grid-template-columns: minmax(240px, 1fr) minmax(0, 2fr);
}
.query-form {
  grid-column: 1;
}
.query-form > label:not(.ant-checkbox-wrapper):not(.ant-radio-wrapper) {
  max-width: 360px;
}
form {
  display: grid;
  gap: 12px;
}
label:not(.ant-checkbox-wrapper):not(.ant-radio-wrapper) {
  display: grid;
  min-width: 0;
  gap: 6px;
  font-size: 13px;
}
.wallet-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 12px;
  border-radius: 8px;
  background: var(--admin-bg);
}
.wallet-summary div {
  display: grid;
  min-width: 0;
  gap: 4px;
}
.wallet-summary span {
  color: var(--admin-muted);
}
.wallet-summary strong {
  font-size: 16px;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.wallet-summary p {
  grid-column: 1 / -1;
  font-size: 12px;
}
.result-form {
  border-top: 3px solid var(--admin-accent);
}
.result-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.full-width,
.confirmations {
  grid-column: 1 / -1;
}
.confirmations {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: var(--admin-bg);
}
.check-label {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  line-height: 1.6;
}
.refund-amount {
  color: var(--admin-accent);
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}
small,
.record-amount {
  display: block;
  margin-top: 6px;
}
.record-amount {
  font-variant-numeric: tabular-nums;
}
.record-context {
  min-width: 200px;
  max-width: 360px;
  white-space: normal;
}
.status {
  display: inline-block;
  padding: 3px 8px;
  border-radius: 8px;
  background: var(--admin-bg);
}
.needs-attention {
  color: var(--admin-accent);
  background: var(--admin-accent-soft);
  font-weight: 600;
}
p.record-error {
  color: var(--admin-accent);
  background: var(--admin-accent-soft);
  border-left: 3px solid var(--admin-accent);
  margin-top: 8px;
  padding: 8px;
  border-radius: 0 8px 8px 0;
}
.record-complete {
  color: var(--admin-muted);
}
.message {
  padding: 12px 16px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
p.error {
  color: var(--admin-accent);
  background: var(--admin-accent-soft);
  border-color: var(--admin-accent);
  font-weight: 600;
}
p.success {
  color: var(--admin-ink);
  background: #fff;
}
.empty-hint {
  padding: 12px;
  border-radius: 8px;
  background: var(--admin-bg);
}
.empty-state {
  padding: 24px 12px;
  text-align: center;
  white-space: normal;
}
.empty-state p {
  margin: 6px 0 12px;
}
@media (max-width: 850px) {
  .application-grid.has-wallet {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 650px) {
  .surface {
    padding: 12px;
  }
  .process-guide {
    grid-template-columns: minmax(0, 1fr);
    padding: 12px;
  }
  .result-fields {
    grid-template-columns: minmax(0, 1fr);
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
.confirmation-field {
  min-width: 0;
  grid-column: 1 / -1;
}
.admin-form > label,
.fields > label,
.result-fields > label,
.field-grid > label,
.sku-form > label:not(.ant-checkbox-wrapper):not(.ant-radio-wrapper) {
  align-content: start;
}
</style>
