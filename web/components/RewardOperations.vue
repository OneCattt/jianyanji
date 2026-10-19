<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../client";
interface Reward {
  id: number;
  orderId: number;
  orderNumber: string;
  userId: number;
  kind: string;
  amountCents: number;
  reversedCents: number;
  settledCents: number;
  remainingCents: number;
  eligible: boolean;
  reason: string;
  status: string;
}
interface Debt {
  id: number;
  user_id: number;
  reward_id: number;
  debt_cents: number;
  offset_cents: number;
  recovered_cents: number;
  remainingCents: number;
}
interface Settlement {
  id: number;
  user_id: number;
  month: string;
  payee: string;
  status: string;
  gross_cents: number;
  offset_cents: number;
  net_cents: number;
  paid_at: string | null;
  proof: string | null;
}
const activeArea = ref("rewards");
const sourceInput = ref<HTMLInputElement | null>(null);
const error = ref(""),
  notice = ref(""),
  busy = ref(false),
  reason = ref(""),
  settings = ref({
    storeRewardCents: "",
    principalRewardCents: "",
    afterSalesDays: null as number | null,
    settlementDay: null as number | null,
  }),
  configured = ref(false);
const rewards = ref<Reward[]>([]),
  debts = ref<Debt[]>([]),
  settlements = ref<Settlement[]>([]),
  selected = ref<number[]>([]),
  sourceId = ref<number>(),
  basis = ref<any>(null),
  registrationAmount = ref(""),
  detail = ref<any>(null);
const month = ref(
    new Intl.DateTimeFormat("sv-SE", {
      timeZone: "Asia/Shanghai",
      year: "numeric",
      month: "2-digit",
    }).format(new Date()),
  ),
  payee = ref("");
const result = ref({ id: 0, status: "paid", paidAt: "", proof: "" }),
  recovery = ref({ reversalId: 0, amount: "", receivedAt: "", proof: "" });
let planKey = crypto.randomUUID(),
  recoveryKey = crypto.randomUUID();
const amount = (n: number) => (n / 100).toFixed(2);
function cents(value: string) {
  if (!/^\d+(\.\d{1,2})?$/.test(value))
    throw new Error("请输入金额，最多两位小数");
  const [a, b = ""] = value.split(".");
  return Number(a) * 100 + Number(b.padEnd(2, "0"));
}
const selectedRewards = computed(() =>
  rewards.value.filter((r) => selected.value.includes(r.id)),
);
const selectedUser = computed(
  () => new Set(selectedRewards.value.map((r) => r.userId)),
);
const totals = computed(() => {
  const gross = selectedRewards.value.reduce((a, r) => a + r.remainingCents, 0),
    debt = debts.value
      .filter((d) => selectedUser.value.has(d.user_id))
      .reduce((a, d) => a + d.remainingCents, 0);
  return {
    gross,
    offset: Math.min(gross, debt),
    net: Math.max(0, gross - debt),
  };
});
const statusName = (s: string) =>
  ({
    pending: "待付款",
    failed: "付款失败",
    unknown: "结果待核实",
    paid: "已核实结算",
    cancelled: "已取消",
    not_due: "未到结算期",
    eligible: "可结算",
    settled: "已结算",
    reversed: "已冲回",
  })[s] || s;
async function load() {
  const [s, r, d, p] = await Promise.all([
    api<any>("/admin/reward-settings"),
    api<Reward[]>("/admin/rewards"),
    api<Debt[]>("/admin/reward-debts"),
    api<Settlement[]>("/admin/reward-settlements"),
  ]);
  configured.value = Boolean(s);
  if (s)
    settings.value = {
      ...s,
      storeRewardCents: amount(s.storeRewardCents),
      principalRewardCents: amount(s.principalRewardCents),
    };
  rewards.value = r;
  debts.value = d;
  settlements.value = p;
  selected.value = selected.value.filter((id) =>
    r.some((x) => x.id === id && x.eligible && x.remainingCents > 0),
  );
}
async function act(work: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await work();
    await load();
    notice.value = "操作记录已保存";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(() => act(load));
function saveSettings() {
  return act(async () => {
    await api("/admin/reward-settings", {
      method: "PUT",
      body: {
        storeRewardCents: cents(settings.value.storeRewardCents),
        principalRewardCents: cents(settings.value.principalRewardCents),
        settlementDay: settings.value.settlementDay,
        reason: reason.value,
      },
    });
  });
}
function readSource() {
  return act(async () => {
    basis.value = null;
    basis.value = await api("/admin/rewards/source/" + sourceId.value);
    registrationAmount.value = amount(basis.value.amountCents);
  });
}
function register() {
  return act(async () => {
    if (!basis.value) throw new Error("请先核对来源");
    await api("/admin/rewards", {
      method: "POST",
      body: {
        orderId: basis.value.orderId,
        amountCents: cents(registrationAmount.value),
        reason: reason.value,
      },
    });
    basis.value = null;
  });
}
function plan() {
  return act(async () => {
    if (selectedUser.value.size !== 1)
      throw new Error("每次月结只能选择同一对象");
    await api("/admin/reward-settlements", {
      method: "POST",
      body: {
        userId: selectedRewards.value[0].userId,
        month: month.value,
        rewardIds: selected.value,
        payee: payee.value,
        requestKey: planKey,
        reason: reason.value,
      },
    });
    planKey = crypto.randomUUID();
    selected.value = [];
  });
}
function saveResult() {
  return act(async () => {
    await api("/admin/reward-settlements/" + result.value.id + "/result", {
      method: "POST",
      body: {
        status: result.value.status,
        ...(result.value.status === "paid"
          ? {
              paidAt: new Date(result.value.paidAt).toISOString(),
              proof: result.value.proof,
            }
          : {}),
        reason: reason.value,
      },
    });
    result.value.id = 0;
  });
}
function recover() {
  return act(async () => {
    await api("/admin/reward-recoveries", {
      method: "POST",
      body: {
        reversalId: recovery.value.reversalId,
        amountCents: cents(recovery.value.amount),
        receivedAt: new Date(recovery.value.receivedAt).toISOString(),
        proof: recovery.value.proof,
        requestKey: recoveryKey,
        reason: reason.value,
      },
    });
    recoveryKey = crypto.randomUUID();
    recovery.value = { reversalId: 0, amount: "", receivedAt: "", proof: "" };
  });
}
function showDetail(id: number) {
  return act(async () => {
    detail.value = await api("/admin/rewards/" + id);
  });
}
function reconcile(id: number) {
  return act(async () => {
    detail.value = await api("/admin/rewards/" + id + "/reconcile", {
      method: "POST",
      body: { reason: reason.value },
    });
  });
}
</script>
<template>
  <section class="reward-operations">
    <header class="page-header">
      <div>
        <h2>奖励与线下月结</h2>
        <p>人工核算登记 · 公司线下转账 · 与订货积分分别记账</p>
      </div>
      <button class="secondary" :disabled="busy" @click="act(load)">
        刷新
      </button>
    </header>
    <p v-if="error" role="alert" class="message error">{{ error }}</p>
    <p v-if="notice" role="status" class="message success">{{ notice }}</p>
    <div class="surface operation-context">
      <label class="reason"
        >本次操作原因<input
          v-model="reason"
          maxlength="1000"
          placeholder="填写核对依据，写入操作记录"
      /></label>
      <p class="hint">
        保存参数、登记奖励、核实付款或追回前，请填写本次操作原因。
      </p>
    </div>
    <details>
      <summary>
        真实奖励档位与结算参数 ·
        {{ configured ? "已配置" : "尚未配置，不能月结" }}
      </summary>
      <form @submit.prevent="saveSettings" class="form-grid">
        <label
          >邀请店主固定奖励（元）<input
            v-model="settings.storeRewardCents"
            inputmode="decimal"
            required /></label
        ><label
          >邀请主理人固定奖励（元）<input
            v-model="settings.principalRewardCents"
            inputmode="decimal"
            required
        /></label>
        <p>
          统一售后期：{{
            settings.afterSalesDays === null
              ? "尚未配置"
              : settings.afterSalesDays + " 天"
          }}。请在订单售后设置维护，历史奖励按原订单期限核对。
        </p>
        <label
          >每月集中结算日<input
            v-model.number="settings.settlementDay"
            type="number"
            min="1"
            max="28"
            required
        /></label>
        <p>
          仅作用于后续业务；历史来源沿用原快照。具体金额和日期须由品牌方确认。
        </p>
        <div class="form-actions">
          <button class="primary" :disabled="busy || !reason">保存参数</button>
        </div>
      </form>
    </details>
    <nav class="area-nav" aria-label="奖励业务区域">
      <button
        type="button"
        :aria-pressed="activeArea === 'rewards'"
        @click="activeArea = 'rewards'"
      >
        奖励登记与月结 <span>{{ rewards.length }}</span>
      </button>
      <button
        type="button"
        :aria-pressed="activeArea === 'settlements'"
        @click="activeArea = 'settlements'"
      >
        付款核实 <span>{{ settlements.length }}</span>
      </button>
      <button
        type="button"
        :aria-pressed="activeArea === 'recovery'"
        @click="activeArea = 'recovery'"
      >
        奖励追回 <span>{{ debts.length }}</span>
      </button>
    </nav>
    <div v-show="activeArea === 'rewards'" class="area-content">
      <section class="surface">
        <div class="section-heading">
          <h3>人工登记来源奖励</h3>
          <p>先读取订单依据，再核对金额并登记奖励。</p>
        </div>
        <form @submit.prevent="readSource" class="inline">
          <label
            >来源订单 ID<input
              ref="sourceInput"
              v-model.number="sourceId"
              type="number"
              min="1"
              required /></label
          ><button :disabled="busy">读取原始核算依据</button>
        </form>
        <p v-if="!basis" class="empty-hint">
          输入来源订单 ID，读取原始核算依据后可填写人工核对金额。
        </p>
        <form v-if="basis" @submit.prevent="register" class="source-basis">
          <p>
            {{ basis.orderNumber }} ·
            {{ basis.kind === "onboarding" ? "入驻奖励" : "店主订货收益" }} ·
            收款代理 #{{ basis.userId }}
          </p>
          <pre>{{ JSON.stringify(basis.snapshot, null, 2) }}</pre>
          <label
            >人工核对金额（元）<input
              v-model="registrationAmount"
              required
              inputmode="decimal"
          /></label>
          <p>价差不含运费；入驻奖励归直接邀请人。登记不代表付款。</p>
          <div class="form-actions">
            <button class="primary" :disabled="busy || !reason">
              确认登记
            </button>
          </div>
        </form>
      </section>
      <section class="surface">
        <div class="section-heading">
          <h3>奖励来源与到期检查</h3>
          <p>勾选同一对象的可结算奖励，在下方创建月结单。</p>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>月结选择</th>
                <th>来源 / 对象</th>
                <th>原奖励</th>
                <th>已冲回</th>
                <th>已结算</th>
                <th>剩余应付</th>
                <th>资格</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in rewards" :key="r.id">
                <td>
                  <input
                    v-model="selected"
                    type="checkbox"
                    :value="r.id"
                    :disabled="!r.eligible || !r.remainingCents || busy"
                    :aria-label="'选择奖励' + r.id"
                  />
                </td>
                <td>
                  {{ r.orderNumber
                  }}<small>奖励 #{{ r.id }} · 代理 #{{ r.userId }}</small>
                </td>
                <td>¥{{ amount(r.amountCents) }}</td>
                <td>¥{{ amount(r.reversedCents) }}</td>
                <td>¥{{ amount(r.settledCents) }}</td>
                <td>¥{{ amount(r.remainingCents) }}</td>
                <td>
                  {{ statusName(r.status) }}<small>{{ r.reason }}</small>
                </td>
                <td>
                  <button :disabled="busy" @click="showDetail(r.id)">
                    明细</button
                  ><button :disabled="busy || !reason" @click="reconcile(r.id)">
                    核对退款冲回
                  </button>
                </td>
              </tr>
              <tr v-if="!rewards.length">
                <td colspan="8" class="empty-state">
                  <strong>尚无人工登记奖励</strong>
                  <p>先核对来源订单并登记奖励，再进行月结。</p>
                  <button
                    type="button"
                    class="secondary"
                    @click="sourceInput?.focus()"
                  >
                    填写来源订单
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <form @submit.prevent="plan" class="form-grid settlement-plan">
          <div class="form-heading">
            <h4>创建月结单</h4>
            <p>
              已选择 {{ selected.length }} 项奖励<span
                v-if="selectedUser.size > 1"
                class="selection-warning"
              >
                · 请选择同一对象的奖励</span
              >
            </p>
          </div>
          <label>结算月份<input v-model="month" type="month" required /></label
          ><label
            >实际收款人<input v-model="payee" required maxlength="100"
          /></label>
          <div class="settlement-totals">
            <div>
              <span>应付</span><strong>¥{{ amount(totals.gross) }}</strong>
            </div>
            <div>
              <span>同人抵扣</span><strong>¥{{ amount(totals.offset) }}</strong>
            </div>
            <div>
              <span>净付</span><strong>¥{{ amount(totals.net) }}</strong>
            </div>
          </div>
          <p>
            集中结算日：{{
              configured ? `每月 ${settings.settlementDay} 日` : "未配置"
            }}。抵扣在核实结算后入账。
          </p>
          <div class="form-actions">
            <button
              class="primary"
              :disabled="
                busy ||
                !configured ||
                !selected.length ||
                selectedUser.size !== 1 ||
                !reason
              "
            >
              创建待付款月结单
            </button>
          </div>
        </form>
      </section>
    </div>
    <section v-show="activeArea === 'settlements'" class="surface">
      <div class="section-heading">
        <h3>月结与真实付款</h3>
        <p>公司线下转账后，在此登记付款或全额抵扣的实际结果。</p>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>月份 / 收款人</th>
              <th>应付</th>
              <th>抵扣</th>
              <th>净付</th>
              <th>状态 / 依据</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in settlements" :key="s.id">
              <td>
                {{ s.month }} · {{ s.payee
                }}<small>代理 #{{ s.user_id }} / 结算 #{{ s.id }}</small>
              </td>
              <td>¥{{ amount(s.gross_cents) }}</td>
              <td>¥{{ amount(s.offset_cents) }}</td>
              <td>¥{{ amount(s.net_cents) }}</td>
              <td>
                <span
                  class="status"
                  :class="{
                    'needs-attention': ['failed', 'unknown'].includes(s.status),
                  }"
                  >{{ statusName(s.status) }}</span
                ><small>{{ s.paid_at }} {{ s.proof }}</small>
              </td>
              <td>
                <button
                  v-if="!['paid', 'cancelled'].includes(s.status)"
                  :disabled="busy"
                  @click="
                    result = { id: s.id, status: 'paid', paidAt: '', proof: '' }
                  "
                >
                  登记实际结果
                </button>
              </td>
            </tr>
            <tr v-if="!settlements.length">
              <td colspan="6" class="empty-state">
                <strong>暂无月结单</strong>
                <p>先选择同一对象的奖励，创建待付款月结单。</p>
                <button
                  type="button"
                  class="secondary"
                  @click="activeArea = 'rewards'"
                >
                  前往奖励登记与月结
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <form
        v-if="result.id"
        @submit.prevent="saveResult"
        class="form-grid inset-form"
      >
        <p>核实结算单 #{{ result.id }}；系统不会发起银行转账。</p>
        <label
          >实际结果<select v-model="result.status">
            <option value="paid">已核实付款 / 全额抵扣</option>
            <option value="failed">明确付款失败</option>
            <option value="unknown">结果待核实</option>
            <option value="cancelled">取消未付计划</option>
          </select></label
        ><template v-if="result.status === 'paid'"
          ><label
            >实际付款 / 抵扣确认时间<input
              v-model="result.paidAt"
              type="datetime-local"
              required /></label
          ><label
            >付款或全额抵扣凭证<textarea
              v-model="result.proof"
              required
              placeholder="银行流水号、凭证存储地址或抵扣核对依据"
            /></label
        ></template>
        <div class="form-actions">
          <button class="primary" :disabled="busy || !reason">
            保存核实结果</button
          ><button type="button" class="secondary" @click="result.id = 0">
            关闭
          </button>
        </div>
      </form>
    </section>
    <section v-show="activeArea === 'recovery'" class="surface">
      <div class="section-heading">
        <h3>持续待收回款</h3>
        <p>优先抵扣同一人后续可结算奖励；实际到账后才能登记人工追回。</p>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>原代理 / 冲回</th>
              <th>形成欠款</th>
              <th>已抵扣</th>
              <th>人工实收</th>
              <th>尚待收回</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in debts" :key="d.id">
              <td>
                代理 #{{ d.user_id }} · 冲回 #{{ d.id
                }}<small>原奖励 #{{ d.reward_id }}</small>
              </td>
              <td>¥{{ amount(d.debt_cents) }}</td>
              <td>¥{{ amount(d.offset_cents) }}</td>
              <td>¥{{ amount(d.recovered_cents) }}</td>
              <td>¥{{ amount(d.remainingCents) }}</td>
              <td>
                <button
                  :disabled="busy"
                  @click="
                    recovery = {
                      reversalId: d.id,
                      amount: amount(d.remainingCents),
                      receivedAt: '',
                      proof: '',
                    }
                  "
                >
                  登记实际收回
                </button>
              </td>
            </tr>
            <tr v-if="!debts.length">
              <td colspan="6" class="empty-state">
                <strong>当前无未收回奖励款</strong>
                <p>如已完成退款冲回核对，可刷新查看最新待收回记录。</p>
                <button
                  type="button"
                  class="secondary"
                  :disabled="busy"
                  @click="act(load)"
                >
                  刷新记录
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <form
        v-if="recovery.reversalId"
        @submit.prevent="recover"
        class="form-grid inset-form"
      >
        <p>冲回 #{{ recovery.reversalId }} 的实际到账记录</p>
        <label
          >实际收回（元）<input
            v-model="recovery.amount"
            inputmode="decimal"
            required /></label
        ><label
          >到账时间<input
            v-model="recovery.receivedAt"
            type="datetime-local"
            required /></label
        ><label>到账凭证<textarea v-model="recovery.proof" required /></label>
        <div class="form-actions">
          <button class="primary" :disabled="busy || !reason">
            核实到账并登记</button
          ><button
            type="button"
            class="secondary"
            @click="recovery.reversalId = 0"
          >
            关闭
          </button>
        </div>
      </form>
    </section>
    <section v-if="detail" class="surface">
      <header>
        <h3>奖励 #{{ detail.id }} 追溯明细</h3>
        <button @click="detail = null">收起</button>
      </header>
      <pre>{{ JSON.stringify(detail, null, 2) }}</pre>
    </section>
  </section>
</template>
<style scoped>
.reward-operations,
.area-content {
  display: grid;
  min-width: 0;
  gap: 16px;
  color: var(--admin-ink);
  font-size: 13px;
}
.page-header,
.reward-operations header,
.inline,
.form-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.reward-operations header {
  justify-content: space-between;
}
h2,
h3,
h4,
p {
  margin: 0;
}
h2 {
  font-size: 16px;
}
h3 {
  font-size: 14px;
}
h4 {
  font-size: 13px;
}
p,
small {
  color: var(--admin-muted);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.surface,
details {
  min-width: 0;
  padding: 16px;
  background: #fff;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
.operation-context,
.section-heading,
.source-basis {
  display: grid;
  gap: 8px;
}
.section-heading {
  margin-bottom: 16px;
}
summary {
  cursor: pointer;
  line-height: 1.6;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: end;
  gap: 12px;
  margin-top: 16px;
}
.form-grid p,
.form-heading,
.form-actions,
.settlement-totals {
  grid-column: 1 / -1;
}
.form-heading {
  display: grid;
  gap: 4px;
}
.reward-operations label {
  display: grid;
  min-width: 0;
  gap: 6px;
}
.reward-operations input:not([type="checkbox"]),
select,
textarea {
  box-sizing: border-box;
  min-width: 0;
  width: 100%;
  max-width: 100%;
}
textarea {
  resize: vertical;
}
.inline {
  justify-content: flex-start;
  align-items: end;
}
.inline label {
  flex: 1 1 220px;
  max-width: 360px;
}
.area-nav {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  padding: 8px;
  background: #fff;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
.area-nav button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 8px 12px;
  color: var(--admin-muted);
  border: 1px solid transparent;
  border-radius: 8px;
}
.area-nav button[aria-pressed="true"] {
  background: var(--admin-accent-soft);
  color: var(--admin-accent);
  border-color: var(--admin-accent);
  font-weight: 600;
}
.area-nav span {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.area-nav button:focus-visible {
  outline: 2px solid var(--admin-accent);
  outline-offset: 2px;
}
.table-wrap {
  min-width: 0;
  max-width: 100%;
  overflow: auto;
}
th,
td {
  white-space: nowrap;
}
td:last-child button + button {
  margin-left: 8px;
}
small {
  display: block;
  margin-top: 4px;
  max-width: 340px;
  white-space: normal;
}
pre {
  min-width: 0;
  max-width: 100%;
  max-height: 340px;
  margin: 0;
  overflow: auto;
  padding: 12px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: var(--admin-bg);
  font-size: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.source-basis {
  margin-top: 16px;
}
.source-basis label {
  max-width: 360px;
}
.settlement-plan,
.inset-form {
  border-top: 1px solid var(--admin-border);
  padding-top: 16px;
}
.settlement-totals {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding: 12px;
  background: var(--admin-bg);
  border-radius: 8px;
}
.settlement-totals div {
  display: grid;
  gap: 4px;
  min-width: 0;
}
.settlement-totals span {
  color: var(--admin-muted);
}
.settlement-totals strong {
  font-size: 16px;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.settlement-totals div:last-child strong {
  color: var(--admin-accent);
}
.status {
  display: inline-block;
  padding: 3px 8px;
  border-radius: 8px;
  background: var(--admin-bg);
}
.needs-attention,
.selection-warning {
  color: var(--admin-accent);
  font-weight: 600;
}
.needs-attention {
  background: var(--admin-accent-soft);
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
.hint {
  font-size: 12px;
}
.empty-hint {
  margin-top: 12px;
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
.reason {
  max-width: 720px;
}
@media (max-width: 650px) {
  .surface,
  details {
    padding: 12px;
  }
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .area-nav button {
    flex: 1 1 auto;
  }
  .inline label {
    max-width: none;
  }
  .inline button {
    width: 100%;
  }
  .settlement-totals {
    gap: 8px;
  }
}
</style>
