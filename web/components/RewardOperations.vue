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
    <header>
      <div>
        <h2>奖励与线下月结</h2>
        <p>人工核算登记 · 公司线下转账 · 与订货积分分别记账</p>
      </div>
      <button :disabled="busy" @click="act(load)">刷新</button>
    </header>
    <p v-if="error" role="alert" class="message error">{{ error }}</p>
    <p v-if="notice" role="status" class="message success">{{ notice }}</p>
    <label class="reason"
      >本次操作原因<input
        v-model="reason"
        maxlength="1000"
        placeholder="填写核对依据，写入操作记录"
    /></label>
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
        <button :disabled="busy || !reason">保存参数</button>
      </form>
    </details>
    <section class="surface">
      <h3>人工登记来源奖励</h3>
      <form @submit.prevent="readSource" class="inline">
        <label
          >来源订单 ID<input
            v-model.number="sourceId"
            type="number"
            min="1"
            required /></label
        ><button :disabled="busy">读取原始核算依据</button>
      </form>
      <form v-if="basis" @submit.prevent="register">
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
        <button :disabled="busy || !reason">确认登记</button>
      </form>
    </section>
    <section class="surface">
      <h3>奖励来源与到期检查</h3>
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
                <button :disabled="busy" @click="showDetail(r.id)">明细</button
                ><button :disabled="busy || !reason" @click="reconcile(r.id)">
                  核对退款冲回
                </button>
              </td>
            </tr>
            <tr v-if="!rewards.length">
              <td colspan="8">尚无人工登记奖励</td>
            </tr>
          </tbody>
        </table>
      </div>
      <form @submit.prevent="plan" class="form-grid">
        <label>结算月份<input v-model="month" type="month" required /></label
        ><label
          >实际收款人<input v-model="payee" required maxlength="100"
        /></label>
        <p>
          应付 ¥{{ amount(totals.gross) }} · 同人抵扣 ¥{{
            amount(totals.offset)
          }}
          · 净付 ¥{{ amount(totals.net) }}<br />集中结算日：{{
            configured ? `每月 ${settings.settlementDay} 日` : "未配置"
          }}。抵扣在核实结算后入账。
        </p>
        <button
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
      </form>
    </section>
    <section class="surface">
      <h3>月结与真实付款</h3>
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
                {{ statusName(s.status)
                }}<small>{{ s.paid_at }} {{ s.proof }}</small>
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
          </tbody>
        </table>
      </div>
      <form v-if="result.id" @submit.prevent="saveResult" class="form-grid">
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
            /></label></template
        ><button :disabled="busy || !reason">保存核实结果</button
        ><button type="button" @click="result.id = 0">关闭</button>
      </form>
    </section>
    <section class="surface">
      <h3>持续待收回款</h3>
      <p>优先抵扣同一人后续可结算奖励；实际到账后才能登记人工追回。</p>
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
              <td colspan="6">当前无未收回奖励款</td>
            </tr>
          </tbody>
        </table>
      </div>
      <form
        v-if="recovery.reversalId"
        @submit.prevent="recover"
        class="form-grid"
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
        ><label>到账凭证<textarea v-model="recovery.proof" required /></label
        ><button :disabled="busy || !reason">核实到账并登记</button
        ><button type="button" @click="recovery.reversalId = 0">关闭</button>
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
.reward-operations {
  display: grid;
  gap: 20px;
  color: #251d1a;
}
.reward-operations header,
.inline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.reward-operations h2,
.reward-operations h3 {
  margin: 0 0 12px;
}
.reward-operations p {
  line-height: 1.7;
  color: #756b63;
}
.surface,
details {
  background: #fffdfa;
  border: 1px solid #e8e0d7;
  padding: 20px;
  border-radius: 12px;
}
summary {
  cursor: pointer;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  align-items: end;
  gap: 16px;
  margin-top: 18px;
}
.form-grid p {
  grid-column: 1/-1;
}
.reward-operations label {
  display: grid;
  gap: 8px;
}
.reward-operations input:not([type="checkbox"]),
select,
textarea {
  box-sizing: border-box;
  max-width: 100%;
  padding: 10px;
  border: 1px solid #d6c9bd;
  background: white;
  border-radius: 6px;
  font: inherit;
}
.reward-operations button {
  padding: 9px 14px;
  border: 1px solid #dacdc2;
  border-radius: 6px;
  background: #fffdfa;
  color: #7b1824;
  cursor: pointer;
}
.reward-operations button:disabled {
  opacity: 0.45;
  cursor: default;
}
.table-wrap {
  overflow: auto;
}
table {
  border-collapse: collapse;
  width: 100%;
  font-size: 13px;
}
th,
td {
  text-align: left;
  padding: 12px;
  border-bottom: 1px solid #e9e1d8;
  white-space: nowrap;
}
small {
  display: block;
  color: #8e8178;
  margin-top: 6px;
}
pre {
  max-height: 340px;
  overflow: auto;
  background: #f8f5ee;
  padding: 14px;
  font-size: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.error {
  color: #a12030 !important;
}
.success {
  color: #35724b !important;
}
.message {
  padding: 12px;
  background: #f8f5ee;
}
.reason {
  max-width: 650px;
}
@media (max-width: 650px) {
  .surface {
    padding: 14px;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
