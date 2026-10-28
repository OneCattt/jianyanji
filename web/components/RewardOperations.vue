<script setup lang="ts">
import { useAdminList } from "../useAdminList";
import FeedbackToast from "./FeedbackToast.vue";
import { computed, onMounted, ref, useId } from "vue";
import { api } from "../client";
import AdminDialog from "./AdminDialog.vue";
import AdminDrawer from "./AdminDrawer.vue";
import AdminForm from "./AdminForm.vue";
import AdminSelect from "./AdminSelect.vue";
import AdminDatePicker from "./AdminDatePicker.vue";
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
const sourceOrderInputId = useId();
type Dialog =
  "settings" | "source" | "plan" | "result" | "recovery" | "reconcile";
const dialog = ref<Dialog | "">("");
const reconcileId = ref(0);
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
let savedSettings = { ...settings.value };
const debts = ref<Debt[]>([]),
  selected = ref<number[]>([]),
  sourceId = ref<number>(),
  basis = ref<any>(null),
  registrationAmount = ref(""),
  sourceCandidates = ref<any[]>([]),
  recipientId = ref(0),
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
const selectionRows = ref(new Map<number, Reward>());
function toggleReward(id: number, checked: boolean) {
  const row = rewards.value.find((r) => r.id === id);
  if (checked && row) selectionRows.value.set(id, row);
  else selectionRows.value.delete(id);
  selected.value = checked
    ? [...selected.value.filter((value) => value !== id), id]
    : selected.value.filter((value) => value !== id);
}
const selectedRewards = computed(() =>
  [...selectionRows.value.values()].filter((r) =>
    selected.value.includes(r.id),
  ),
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
const {
  rows: rewards,
  total: rewardTotal,
  pagination: rewardPagination,
  loading: rewardLoading,
  load: loadRewards,
} = useAdminList<Reward>("/admin/rewards", () => ({}), error);
const {
  rows: settlements,
  total: settlementTotal,
  pagination: settlementPagination,
  loading: settlementLoading,
  load: loadSettlements,
} = useAdminList<Settlement>("/admin/reward-settlements", () => ({}), error);
async function load() {
  const [s, r, d, p] = await Promise.all([
    api<any>("/admin/reward-settings"),
    loadRewards(),
    api<Debt[]>("/admin/reward-debts"),
    loadSettlements(),
  ]);
  configured.value = Boolean(s);
  if (s)
    settings.value = {
      ...s,
      storeRewardCents: amount(s.storeRewardCents),
      principalRewardCents: amount(s.principalRewardCents),
    };
  savedSettings = { ...settings.value };
  rewards.value = r;
  debts.value = d;
  settlements.value = p;
  for (const row of r) {
    if (selected.value.includes(row.id)) selectionRows.value.set(row.id, row);
  }
  selected.value = selected.value.filter((id) => {
    const row = selectionRows.value.get(id);
    return row?.eligible && row.remainingCents > 0;
  });
}
async function act(work: () => Promise<void>, message = "") {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await work();
    if (message) {
      dialog.value = "";
      await load();
      notice.value = message;
    }
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(() => act(load));
function saveSettings() {
  return act(async () => {
    if (
      !Number.isInteger(settings.value.settlementDay) ||
      Number(settings.value.settlementDay) < 1 ||
      Number(settings.value.settlementDay) > 28
    )
      throw new Error("集中结算日须为 1 至 28 的整数");
    await api("/admin/reward-settings", {
      method: "PUT",
      body: {
        storeRewardCents: 100000,
        principalRewardCents: 0,
        settlementDay: settings.value.settlementDay,
        reason: reason.value,
      },
    });
  }, "奖励参数已保存");
}
function readSource() {
  return act(async () => {
    if (!Number.isSafeInteger(sourceId.value) || Number(sourceId.value) < 1)
      throw new Error("请输入有效的来源订单 ID");
    basis.value = null;
    basis.value = await api("/admin/rewards/source/" + sourceId.value);
    sourceCandidates.value = basis.value.candidates || [basis.value];
    if (!sourceCandidates.value.length) {
      basis.value = null;
      throw new Error("此来源没有可登记的奖励");
    }
    recipientId.value = basis.value.userId;
    registrationAmount.value = amount(basis.value.amountCents);
  });
}
function register() {
  return act(async () => {
    if (!basis.value) throw new Error("请先核对来源");
    if (!reason.value.trim()) throw new Error("请填写本次操作原因");
    await api("/admin/rewards", {
      method: "POST",
      body: {
        orderId: basis.value.orderId,
        userId: basis.value.userId,
        amountCents: cents(registrationAmount.value),
        reason: reason.value,
      },
    });
  }, "来源奖励已登记，尚未付款");
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
  }, "待付款月结单已创建，尚未付款");
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
  }, "结算核实结果已保存");
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
  }, "实际追回记录已保存");
}
function showDetail(id: number) {
  return act(async () => {
    detail.value = await api("/admin/rewards/" + id);
  });
}
function reconcile() {
  return act(async () => {
    detail.value = await api(
      "/admin/rewards/" + reconcileId.value + "/reconcile",
      {
        method: "POST",
        body: { reason: reason.value },
      },
    );
  }, "退款冲回核对记录已保存");
}
function openDialog(next: Dialog) {
  if (busy.value) return;
  error.value = "";
  notice.value = "";
  reason.value = "";
  if (next === "settings") settings.value = { ...savedSettings };
  if (next === "plan") payee.value = "";
  if (next === "source") {
    sourceId.value = undefined;
    basis.value = null;
    registrationAmount.value = "";
  }
  dialog.value = next;
}
function closeDialog() {
  if (busy.value) return;
  if (dialog.value === "settings") settings.value = { ...savedSettings };
  dialog.value = "";
  error.value = "";
}
function openResult(settlement: Settlement) {
  if (busy.value) return;
  result.value = { id: settlement.id, status: "paid", paidAt: "", proof: "" };
  openDialog("result");
}
function openRecovery(debt: Debt) {
  if (busy.value) return;
  recovery.value = {
    reversalId: debt.id,
    amount: amount(debt.remainingCents),
    receivedAt: "",
    proof: "",
  };
  openDialog("recovery");
}
function openReconcile(id: number) {
  if (busy.value) return;
  reconcileId.value = id;
  openDialog("reconcile");
}

const sourceLineColumns = [
  { title: "原订单行", dataIndex: "lineId", key: "lineId" },
  { title: "商品类型", key: "type" },
  { title: "数量", dataIndex: "quantity", key: "quantity" },
  { title: "原成交单价", key: "unit" },
  { title: "原主理人价", key: "principal" },
];
const rewardColumns = [
  { title: "月结选择", key: "c0", width: 76 },
  { title: "来源 / 对象", key: "c1", width: 180 },
  { title: "原奖励", key: "c2", width: 110 },
  { title: "已冲回", key: "c3", width: 110 },
  { title: "已结算", key: "c4", width: 110 },
  { title: "剩余应付", key: "c5", width: 110 },
  { title: "资格", key: "c6", width: 140 },
  { title: "操作", key: "c7", width: 170 },
];
const settlementColumns = [
  { title: "月份 / 收款人", key: "c0", width: 220 },
  { title: "应付", key: "c1", width: 160 },
  { title: "抵扣", key: "c2", width: 160 },
  { title: "净付", key: "c3", width: 160 },
  { title: "状态 / 依据", key: "c4", width: 160 },
  { title: "操作", key: "c5", width: 200 },
];
const debtColumns = [
  { title: "原代理 / 冲回", key: "c0", width: 220 },
  { title: "形成欠款", key: "c1", width: 160 },
  { title: "已抵扣", key: "c2", width: 160 },
  { title: "人工实收", key: "c3", width: 160 },
  { title: "尚待收回", key: "c4", width: 160 },
  { title: "操作", key: "c5", width: 200 },
];
</script>
<template>
  <section class="reward-operations">
    <Teleport defer to="#admin-page-actions">
      <a-button
        html-type="button"
        :disabled="busy"
        @click="openDialog('settings')"
      >
        奖励参数
      </a-button>
      <a-button
        html-type="button"
        :disabled="busy"
        @click="openDialog('source')"
      >
        登记来源奖励
      </a-button>
      <a-button
        html-type="button"
        type="primary"
        :disabled="
          busy || !configured || !selected.length || selectedUser.size !== 1
        "
        @click="openDialog('plan')"
      >
        创建月结单
      </a-button>
    </Teleport>
    <p class="admin-business-note">
      奖励由公司线下转账结算，与订货积分分别记账。
    </p>
    <FeedbackToast v-model:notice="notice" v-model:error="error" />

    <a-collapse
      ><a-collapse-panel key="details"
        ><template #header>
          真实奖励档位与结算参数 ·
          {{ configured ? "已配置" : "尚未配置，不能月结" }}
        </template>
        <p>
          店主邀请店主：邀请店主及其归属主理人各 500 元；主理人邀请店主：1,000
          元；主理人包奖励关闭。· 每月结算日：{{
            settings.settlementDay || "未配置"
          }}
        </p>
        <p>参数仅作用于后续业务；历史来源沿用原快照。</p></a-collapse-panel
      ></a-collapse
    >
    <a-tabs v-model:active-key="activeArea" class="area-tabs">
      <a-tab-pane key="rewards" :tab="`奖励登记与月结 (${rewardTotal})`" />
      <a-tab-pane key="settlements" :tab="`付款核实 (${settlementTotal})`" />
      <a-tab-pane key="recovery" :tab="`奖励追回 (${debts.length})`" />
    </a-tabs>
    <div v-show="activeArea === 'rewards'" class="area-content">
      <a-card size="small" class="">
        <div class="section-heading">
          <h3>奖励来源与到期检查</h3>
          <p>勾选同一对象的可结算奖励，点击顶部按钮创建月结单。</p>
        </div>
        <div class="table-wrap">
          <a-table
            :data-source="rewards"
            :columns="rewardColumns"
            row-key="id"
            size="middle"
            :loading="busy || rewardLoading || settlementLoading"
            :scroll="{ x: 1040 }"
            :pagination="rewardPagination"
            ><template #bodyCell="{ column, record: r }"
              ><template v-if="column.key === 'c0'"
                ><div class="table-cell-content">
                  <a-checkbox
                    :checked="selected.includes(r.id)"
                    @change="toggleReward(r.id, $event.target.checked)"
                    :value="r.id"
                    :disabled="!r.eligible || !r.remainingCents || busy"
                    :aria-label="'选择奖励' + r.id"
                  /></div
              ></template>
              <template v-else-if="column.key === 'c1'"
                ><div class="table-cell-content">
                  {{ r.orderNumber
                  }}<small>奖励 #{{ r.id }} · 代理 #{{ r.userId }}</small>
                </div></template
              >
              <template v-else-if="column.key === 'c2'"
                ><div class="table-cell-content">
                  ¥{{ amount(r.amountCents) }}
                </div></template
              >
              <template v-else-if="column.key === 'c3'"
                ><div class="table-cell-content">
                  ¥{{ amount(r.reversedCents) }}
                </div></template
              >
              <template v-else-if="column.key === 'c4'"
                ><div class="table-cell-content">
                  ¥{{ amount(r.settledCents) }}
                </div></template
              >
              <template v-else-if="column.key === 'c5'"
                ><div class="table-cell-content">
                  ¥{{ amount(r.remainingCents) }}
                </div></template
              >
              <template v-else-if="column.key === 'c6'"
                ><div class="table-cell-content">
                  <a-tag :color="r.eligible ? 'green' : 'default'">{{
                    statusName(r.status)
                  }}</a-tag
                  ><small>{{ r.reason }}</small>
                </div></template
              >
              <template v-else-if="column.key === 'c7'"
                ><div class="table-cell-content">
                  <a-button
                    html-type="button"
                    :disabled="busy"
                    @click="showDetail(r.id)"
                  >
                    明细</a-button
                  ><a-button
                    html-type="button"
                    :disabled="busy"
                    @click="openReconcile(r.id)"
                  >
                    核对退款冲回
                  </a-button>
                </div></template
              ></template
            ><template #emptyText>
              <strong>尚无人工登记奖励</strong>
              <p>先核对来源订单并登记奖励，再进行月结。</p>
              <a-button
                html-type="button"
                :disabled="busy"
                @click="openDialog('source')"
              >
                填写来源订单
              </a-button>
            </template></a-table
          >
        </div>
        <div class="settlement-plan">
          <div class="form-heading">
            <h4>月结选择汇总</h4>
            <p>
              已选择 {{ selected.length }} 项奖励<span
                v-if="selectedUser.size > 1"
                class="selection-warning"
              >
                · 请选择同一对象的奖励</span
              >
            </p>
          </div>
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
        </div>
      </a-card>
    </div>
    <a-card size="small" v-show="activeArea === 'settlements'" class="">
      <div class="section-heading">
        <h3>月结与真实付款</h3>
        <p>公司线下转账后，在此登记付款或全额抵扣的实际结果。</p>
      </div>
      <div class="table-wrap">
        <a-table
          :data-source="settlements"
          :columns="settlementColumns"
          row-key="id"
          size="middle"
          :loading="busy || rewardLoading || settlementLoading"
          :scroll="{ x: 1020 }"
          :pagination="settlementPagination"
          ><template #bodyCell="{ column, record: s }"
            ><template v-if="column.key === 'c0'"
              ><div class="table-cell-content">
                {{ s.month }} · {{ s.payee
                }}<small>代理 #{{ s.user_id }} / 结算 #{{ s.id }}</small>
              </div></template
            >
            <template v-else-if="column.key === 'c1'"
              ><div class="table-cell-content">
                ¥{{ amount(s.gross_cents) }}
              </div></template
            >
            <template v-else-if="column.key === 'c2'"
              ><div class="table-cell-content">
                ¥{{ amount(s.offset_cents) }}
              </div></template
            >
            <template v-else-if="column.key === 'c3'"
              ><div class="table-cell-content">
                ¥{{ amount(s.net_cents) }}
              </div></template
            >
            <template v-else-if="column.key === 'c4'"
              ><div class="table-cell-content">
                <a-tag
                  :color="
                    s.status === 'paid'
                      ? 'green'
                      : ['failed', 'unknown'].includes(s.status)
                        ? 'orange'
                        : 'default'
                  "
                  >{{ statusName(s.status) }}</a-tag
                ><small>{{ s.paid_at }} {{ s.proof }}</small>
              </div></template
            >
            <template v-else-if="column.key === 'c5'"
              ><div class="table-cell-content">
                <a-button
                  html-type="button"
                  v-if="!['paid', 'cancelled'].includes(s.status)"
                  :disabled="busy"
                  @click="openResult(s)"
                >
                  登记实际结果
                </a-button>
              </div></template
            ></template
          ><template #emptyText>
            <strong>暂无月结单</strong>
            <p>先选择同一对象的奖励，创建待付款月结单。</p>
            <a-button html-type="button" @click="activeArea = 'rewards'">
              前往奖励登记与月结
            </a-button>
          </template></a-table
        >
      </div>
    </a-card>
    <a-card size="small" v-show="activeArea === 'recovery'" class="">
      <div class="section-heading">
        <h3>持续待收回款</h3>
        <p>优先抵扣同一人后续可结算奖励；实际到账后才能登记人工追回。</p>
      </div>
      <div class="table-wrap">
        <a-table
          :data-source="debts"
          :columns="debtColumns"
          row-key="id"
          size="middle"
          :loading="busy || rewardLoading || settlementLoading"
          :scroll="{ x: 1020 }"
          :pagination="{
            defaultPageSize: 10,
            showSizeChanger: true,
            pageSizeOptions: ['10', '20', '50'],
            hideOnSinglePage: false,
          }"
          ><template #bodyCell="{ column, record: d }"
            ><template v-if="column.key === 'c0'"
              ><div class="table-cell-content">
                代理 #{{ d.user_id }} · 冲回 #{{ d.id
                }}<small>原奖励 #{{ d.reward_id }}</small>
              </div></template
            >
            <template v-else-if="column.key === 'c1'"
              ><div class="table-cell-content">
                ¥{{ amount(d.debt_cents) }}
              </div></template
            >
            <template v-else-if="column.key === 'c2'"
              ><div class="table-cell-content">
                ¥{{ amount(d.offset_cents) }}
              </div></template
            >
            <template v-else-if="column.key === 'c3'"
              ><div class="table-cell-content">
                ¥{{ amount(d.recovered_cents) }}
              </div></template
            >
            <template v-else-if="column.key === 'c4'"
              ><div class="table-cell-content">
                ¥{{ amount(d.remainingCents) }}
              </div></template
            >
            <template v-else-if="column.key === 'c5'"
              ><div class="table-cell-content">
                <a-button
                  html-type="button"
                  :disabled="busy"
                  @click="openRecovery(d)"
                >
                  登记实际收回
                </a-button>
              </div></template
            ></template
          ><template #emptyText>
            <strong>当前无未收回奖励款</strong>
            <p>如已完成退款冲回核对，可刷新查看最新待收回记录。</p>
            <a-button html-type="button" :disabled="busy" @click="act(load)">
              刷新记录
            </a-button>
          </template></a-table
        >
      </div>
    </a-card>
    <AdminDrawer
      v-if="detail"
      title="奖励追溯明细"
      :busy="busy || Boolean(dialog)"
      @close="detail = null"
    >
      <header>
        <h3>奖励 #{{ detail.id }} 追溯明细</h3>
      </header>
      <a-descriptions
        bordered
        size="small"
        :column="{ xs: 1, sm: 2 }"
        class="detail-summary"
      >
        <a-descriptions-item label="来源订单">{{
          detail.orderNumber
        }}</a-descriptions-item>
        <a-descriptions-item label="收益人"
          >#{{ detail.userId }}</a-descriptions-item
        >
        <a-descriptions-item label="原奖励"
          >¥{{ amount(detail.amountCents) }}</a-descriptions-item
        >
        <a-descriptions-item label="剩余应付"
          >¥{{ amount(detail.remainingCents) }}</a-descriptions-item
        >
        <a-descriptions-item label="已冲回"
          >¥{{ amount(detail.reversedCents) }}</a-descriptions-item
        >
        <a-descriptions-item label="已结算"
          >¥{{ amount(detail.settledCents) }}</a-descriptions-item
        >
        <a-descriptions-item label="结算资格" :span="2"
          >{{ statusName(detail.status) }} ·
          {{ detail.reason }}</a-descriptions-item
        >
      </a-descriptions>
      <a-collapse ghost>
        <a-collapse-panel key="history" header="核算、冲回与结算原始记录">
          <pre>{{ JSON.stringify(detail, null, 2) }}</pre>
        </a-collapse-panel>
      </a-collapse>
    </AdminDrawer>
    <AdminDialog
      v-if="dialog === 'settings'"
      drawer
      title="奖励参数"
      :busy="busy"
      @close="closeDialog"
    >
      <AdminForm @submit="saveSettings" class="form-grid config-form">
        <p>
          店主邀请店主：双方各 500 元；主理人直邀店主：1,000
          元。主理人包及未确认政策关闭。
        </p>
        <p>
          统一售后期：{{
            settings.afterSalesDays === null
              ? "尚未配置"
              : settings.afterSalesDays + " 天"
          }}。请在订单售后设置维护，历史奖励按原订单期限核对。
        </p>
        <label
          >每月集中结算日<a-input-number
            class="number-field"
            :precision="0"
            pattern="[0-9]+"
            v-model:value="settings.settlementDay"
            :min="1"
            :max="28"
            required
        /></label>
        <p>
          仅作用于后续业务；历史来源沿用原快照。具体金额和日期须由品牌方确认。
        </p>
        <label class="reason"
          >本次操作原因<a-input
            v-model:value="reason"
            required
            :maxlength="1000"
            placeholder="填写核对依据，写入操作记录"
        /></label>
        <div class="dialog-actions">
          <a-button html-type="button" :disabled="busy" @click="closeDialog">
            取消
          </a-button>
          <a-button html-type="submit" type="primary" :disabled="busy"
            >保存参数</a-button
          >
        </div>
      </AdminForm>
    </AdminDialog>
    <AdminDialog
      v-if="dialog === 'source'"
      drawer
      title="来源核算与奖励登记"
      :busy="busy"
      @close="closeDialog"
    >
      <p class="source-intro">先核对来源订单，再登记对应收益人的奖励。</p>
      <AdminForm @submit="readSource" class="source-lookup">
        <label :for="sourceOrderInputId" class="source-order-label">
          <span
            ><span aria-hidden="true" class="required-mark">*</span>来源订单
            ID</span
          >
        </label>
        <div class="source-query-row">
          <div class="source-query-input">
            <a-input-number
              class="number-field"
              :precision="0"
              pattern="[0-9]+"
              :id="sourceOrderInputId"
              v-model:value="sourceId"
              @change="basis = null"
              :disabled="busy"
              :min="1"
              placeholder="请输入来源订单 ID"
              required
            />
          </div>
          <a-button html-type="submit" type="primary" :disabled="busy">
            {{ busy ? "正在读取…" : "读取原始核算依据" }}
          </a-button>
        </div>
      </AdminForm>
      <AdminForm v-if="basis" @submit="register" class="source-basis">
        <section class="source-summary" aria-label="来源订单核算摘要">
          <div class="source-order-copy">
            <span class="source-caption">来源订单</span>
            <strong>{{ basis.orderNumber }}</strong>
            <p>
              {{
                basis.kind === "onboarding"
                  ? "入驻奖励"
                  : basis.kind === "retail"
                    ? "零售佣金"
                    : "店主订货收益"
              }}
              · 收益人 #{{ basis.userId }}
            </p>
          </div>
          <div class="source-calculated-amount">
            <span class="source-caption">原业务核算金额</span>
            <strong>¥{{ amount(basis.amountCents) }}</strong>
          </div>
        </section>
        <label v-if="sourceCandidates.length > 1"
          >本次收益人<AdminSelect
            v-model.number="recipientId"
            @change="
              basis = sourceCandidates.find((c) => c.userId === recipientId);
              registrationAmount = amount(basis.amountCents);
            "
          >
            <option
              v-for="candidate in sourceCandidates"
              :key="candidate.userId"
              :value="candidate.userId"
            >
              #{{ candidate.userId }} ·
              {{
                candidate.snapshot.beneficiaryRole === "principal"
                  ? "归属主理人"
                  : "直接邀请人"
              }}
              · {{ amount(candidate.amountCents) }} 元
            </option>
          </AdminSelect></label
        >
        <a-descriptions bordered size="small" :column="{ xs: 1, sm: 2 }">
          <a-descriptions-item label="原商品货款"
            >¥{{ amount(basis.snapshot.goodsCents) }}</a-descriptions-item
          >
          <a-descriptions-item label="原售后期"
            >{{ basis.snapshot.afterSalesDays }} 天</a-descriptions-item
          >
          <a-descriptions-item
            v-if="basis.snapshot.percent"
            label="零售佣金比例"
            >{{ basis.snapshot.percent }}%</a-descriptions-item
          >
          <a-descriptions-item
            v-if="basis.snapshot.commissionGoodsCents != null"
            label="普通商品佣金基数"
            >¥{{
              amount(basis.snapshot.commissionGoodsCents)
            }}</a-descriptions-item
          >
          <a-descriptions-item
            v-if="basis.snapshot.beneficiaryRole"
            label="奖励归属"
            >{{
              basis.snapshot.beneficiaryRole === "principal"
                ? "归属主理人"
                : "直接邀请人"
            }}</a-descriptions-item
          >
          <a-descriptions-item
            v-if="basis.snapshot.paymentMethod"
            label="原付款方式"
            >{{
              basis.snapshot.paymentMethod === "points"
                ? "积分"
                : basis.snapshot.paymentMethod === "wechat"
                  ? "历史微信支付"
                  : "线下付款"
            }}</a-descriptions-item
          >
        </a-descriptions>
        <a-collapse ghost class="source-evidence">
          <a-collapse-panel key="evidence" header="商品核算明细与原始记录">
            <a-table
              v-if="basis.snapshot.lines?.length"
              :data-source="basis.snapshot.lines"
              :columns="sourceLineColumns"
              row-key="lineId"
              size="small"
              :pagination="false"
              :scroll="{ x: 600 }"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'type'">{{
                  record.productType === "welfare"
                    ? "福利品（不计奖励）"
                    : "普通商品"
                }}</template>
                <template v-else-if="column.key === 'unit'"
                  >¥{{ amount(record.unitCents) }}</template
                >
                <template v-else-if="column.key === 'principal'">{{
                  record.principalCents == null
                    ? "—"
                    : "¥" + amount(record.principalCents)
                }}</template>
              </template>
            </a-table>
            <pre>{{ JSON.stringify(basis.snapshot, null, 2) }}</pre>
          </a-collapse-panel>
        </a-collapse>
        <label
          >人工核对金额（元）<a-input
            v-model:value="registrationAmount"
            required
            inputmode="decimal"
            pattern="\d+(\.\d{1,2})?"
            data-error-message="请输入金额，最多两位小数"
        /></label>
        <p>
          普通商品收益不含运费和福利品；入驻奖励按原来源分别登记每位收益人。登记不代表付款。
        </p>
        <label class="reason"
          >本次操作原因<a-textarea
            v-model:value="reason"
            required
            :maxlength="1000"
            :rows="3"
            placeholder="填写核对依据，写入操作记录"
          />
        </label>
        <div class="dialog-actions">
          <a-button html-type="button" :disabled="busy" @click="closeDialog">
            取消
          </a-button>
          <a-button html-type="submit" type="primary" :disabled="busy"
            >确认登记</a-button
          >
        </div>
      </AdminForm>
      <div v-else class="dialog-actions">
        <a-button html-type="button" :disabled="busy" @click="closeDialog">
          取消
        </a-button>
      </div>
    </AdminDialog>
    <AdminDialog
      v-if="dialog === 'plan'"
      drawer
      title="创建待付款月结单"
      :busy="busy"
      @close="closeDialog"
    >
      <AdminForm @submit="plan" class="form-grid">
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
        <label
          >结算月份<AdminDatePicker
            v-model="month"
            type="month"
            required /></label
        ><label
          >实际收款人<a-input v-model:value="payee" required :maxlength="100"
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
        <label class="reason"
          >本次操作原因<a-input
            v-model:value="reason"
            required
            :maxlength="1000"
            placeholder="填写核对依据，写入操作记录"
        /></label>
        <div class="dialog-actions">
          <a-button html-type="button" :disabled="busy" @click="closeDialog">
            取消
          </a-button>
          <a-button
            html-type="submit"
            type="primary"
            :disabled="
              busy || !configured || !selected.length || selectedUser.size !== 1
            "
          >
            创建待付款月结单
          </a-button>
        </div>
      </AdminForm>
    </AdminDialog>
    <AdminDialog
      v-if="dialog === 'result'"
      drawer
      title="登记结算实际结果"
      :busy="busy"
      @close="closeDialog"
    >
      <AdminForm @submit="saveResult" class="form-grid">
        <p>核实结算单 #{{ result.id }}；系统不会发起银行转账。</p>
        <label
          >实际结果<AdminSelect v-model="result.status">
            <option value="paid">已核实付款 / 全额抵扣</option>
            <option value="failed">明确付款失败</option>
            <option value="unknown">结果待核实</option>
            <option value="cancelled">取消未付计划</option>
          </AdminSelect></label
        ><template v-if="result.status === 'paid'"
          ><label
            >实际付款 / 抵扣确认时间<AdminDatePicker
              v-model="result.paidAt"
              type="datetime-local"
              required /></label
          ><label
            >付款或全额抵扣凭证<a-textarea
              v-model:value="result.proof"
              required
              placeholder="银行流水号、凭证存储地址或抵扣核对依据" /></label
        ></template>
        <label class="reason"
          >本次操作原因<a-input
            v-model:value="reason"
            required
            :maxlength="1000"
            placeholder="填写核对依据，写入操作记录"
        /></label>
        <div class="dialog-actions">
          <a-button html-type="button" :disabled="busy" @click="closeDialog">
            取消
          </a-button>
          <a-button html-type="submit" type="primary" :disabled="busy"
            >保存核实结果</a-button
          >
        </div>
      </AdminForm>
    </AdminDialog>
    <AdminDialog
      v-if="dialog === 'recovery'"
      drawer
      title="登记人工实际追回"
      :busy="busy"
      @close="closeDialog"
    >
      <AdminForm @submit="recover" class="form-grid">
        <p>冲回 #{{ recovery.reversalId }} 的实际到账记录</p>
        <label
          >实际收回（元）<a-input
            v-model:value="recovery.amount"
            inputmode="decimal"
            pattern="\d+(\.\d{1,2})?"
            data-error-message="请输入金额，最多两位小数"
            required /></label
        ><label
          >到账时间<AdminDatePicker
            v-model="recovery.receivedAt"
            type="datetime-local"
            required /></label
        ><label
          >到账凭证<a-textarea v-model:value="recovery.proof" required
        /></label>
        <label class="reason"
          >本次操作原因<a-input
            v-model:value="reason"
            required
            :maxlength="1000"
            placeholder="填写核对依据，写入操作记录"
        /></label>
        <div class="dialog-actions">
          <a-button html-type="button" :disabled="busy" @click="closeDialog">
            取消
          </a-button>
          <a-button html-type="submit" type="primary" :disabled="busy"
            >核实到账并登记</a-button
          >
        </div>
      </AdminForm>
    </AdminDialog>
    <AdminDialog
      v-if="dialog === 'reconcile'"
      title="核对退款冲回"
      :busy="busy"
      @close="closeDialog"
    >
      <AdminForm class="form-grid" @submit="reconcile">
        <p>
          核对奖励 #{{ reconcileId }}
          来源订单的退款并记录冲回；已付奖励优先抵扣同一人后续应结算奖励，不足部分持续记录待追回。
        </p>
        <label class="reason"
          >本次操作原因<a-input
            v-model:value="reason"
            required
            :maxlength="1000"
            placeholder="填写核对依据，写入操作记录"
        /></label>
        <div class="dialog-actions">
          <a-button html-type="button" :disabled="busy" @click="closeDialog">
            取消
          </a-button>
          <a-button html-type="submit" type="primary" :disabled="busy"
            >确认核对退款冲回</a-button
          >
        </div>
      </AdminForm>
    </AdminDialog>
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
.form-grid .reason,
.form-heading,
.form-actions,
.settlement-totals {
  grid-column: 1 / -1;
}
.form-heading {
  display: grid;
  gap: 4px;
}
.reward-operations label:not(.ant-checkbox-wrapper):not(.ant-radio-wrapper) {
  display: grid;
  min-width: 0;
  gap: 6px;
}
.inline {
  justify-content: flex-start;
  align-items: end;
}
.inline label:not(.ant-checkbox-wrapper):not(.ant-radio-wrapper) {
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
.area-nav span {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
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
.source-basis label:not(.ant-checkbox-wrapper):not(.ant-radio-wrapper) {
  max-width: none;
}
.source-intro {
  margin-bottom: 20px;
}
.admin-form.source-lookup {
  gap: 8px;
}
.source-lookup .source-order-label {
  display: block;
  line-height: 20px;
}
.required-mark {
  margin-right: 5px;
  color: #bd3047;
}
.source-query-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.source-lookup + .source-basis,
.source-lookup + .dialog-actions {
  margin-top: 24px;
}
.source-summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 16px;
  padding: 16px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: var(--admin-bg);
}
.source-order-copy,
.source-calculated-amount {
  display: grid;
  gap: 6px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.source-caption {
  color: var(--admin-muted);
  font-size: 12px;
}
.source-calculated-amount {
  text-align: right;
}
.source-calculated-amount strong {
  color: var(--admin-accent);
  font-size: 22px;
  line-height: 1.3;
  font-weight: 600;
}
.source-evidence {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}
.source-evidence summary {
  color: var(--admin-muted);
  font-size: 12px;
}
.source-evidence pre {
  margin-top: 10px;
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
  .inline label:not(.ant-checkbox-wrapper):not(.ant-radio-wrapper) {
    max-width: none;
  }
  .source-query-row {
    flex-direction: column;
  }
  .source-summary {
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
  }
  .source-calculated-amount {
    text-align: left;
  }
  .settlement-totals {
    gap: 8px;
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
.area-tabs {
  min-width: 0;
}
.detail-summary {
  margin-top: 16px;
}
.source-query-input {
  display: grid;
  gap: 6px;
  flex: 1;
  min-width: 0;
}
.source-query-row > .ant-btn {
  flex-shrink: 0;
}
.form-grid > label,
.source-basis > label:not(.ant-checkbox-wrapper):not(.ant-radio-wrapper) {
  align-content: start;
}
@media (max-width: 650px) {
  .source-query-input,
  .source-query-row > .ant-btn {
    width: 100%;
  }
}
</style>
