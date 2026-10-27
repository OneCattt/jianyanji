<script setup lang="ts">
import { useAdminList } from "../useAdminList";
import FeedbackToast from "./FeedbackToast.vue";
import { ref, onMounted, computed, watch } from "vue";
import { api } from "../client";
import AdminDialog from "./AdminDialog.vue";
import AdminForm from "./AdminForm.vue";
import AdminSelect from "./AdminSelect.vue";
import AdminDatePicker from "./AdminDatePicker.vue";
const props = withDefaults(
  defineProps<{ canFinance: boolean; canManage?: boolean }>(),
  { canManage: true },
);
const busy = ref(false),
  error = ref(""),
  notice = ref(""),
  reason = ref(""),
  policy = ref({ days: 7, policy: "" }),
  selectedOrderId = ref(0),
  quantities = ref<Record<number, number>>({}),
  shipping = ref("0.00");
const setupDialog = ref<"" | "policy" | "create">("");
const policyDraft = ref({ days: 7, policy: "" });
function openSetup(key: "policy" | "create") {
  reason.value = "";
  error.value = "";
  if (key === "policy") policyDraft.value = { ...policy.value };
  else {
    selectedOrderId.value = 0;
    quantities.value = {};
    shipping.value = "0.00";
    exceptionApproved.value = false;
    exceptionReason.value = "";
  }
  setupDialog.value = key;
  if (key === "create") {
    chosenOrder.value = null;
    orderQuery.value = "";
    void loadOrderOptions().catch((e) => {
      error.value = (e as Error).message;
    });
  }
}
async function savePolicy() {
  await run(async () => {
    if (
      !Number.isInteger(policyDraft.value.days) ||
      policyDraft.value.days < 0 ||
      policyDraft.value.days > 365
    )
      throw new Error("售后天数须为 0～365 的整数");
    await api("/admin/aftersales-settings", {
      method: "PUT",
      body: { ...policyDraft.value, reason: reason.value },
    });
    policy.value = { ...policyDraft.value };
    setupDialog.value = "";
  }, "售后政策已保存");
}
const orderQuery = ref("");
const chosenOrder = ref<any>(null);
const {
  rows: orders,
  pagination: orderPagination,
  loading: orderLoading,
  load: loadOrderOptions,
} = useAdminList<any>(
  "/admin/orders",
  () => ({ q: orderQuery.value, paidOnly: "true" }),
  error,
  () => setupDialog.value === "create",
);
const orderOptions = computed(() =>
  chosenOrder.value && !orders.value.some((o) => o.id === chosenOrder.value.id)
    ? [chosenOrder.value, ...orders.value]
    : orders.value,
);
watch(selectedOrderId, (id) => {
  chosenOrder.value = orders.value.find((o) => o.id === id) ?? null;
});
const current = computed(() => chosenOrder.value);
const exceptionApproved = ref(false),
  exceptionReason = ref("");
const action = ref(""),
  selected = ref<any>(null),
  decision = ref<"retain" | "revoke">("retain"),
  restock = ref(false),
  inspection = ref(""),
  recipient = ref(""),
  paidAt = ref(""),
  proof = ref(""),
  confirmed = ref(false);
const names: Record<string, string> = {
  requested: "待审核",
  approved: "已通过待退款",
  processing: "退款处理中",
  unknown: "结果待核查",
  failed: "退款失败待处理",
  rejected: "已驳回",
  success: "实际退款完成",
};
const money = (v: number) => "¥" + (v / 100).toFixed(2);
function cents(v: string) {
  if (!/^\d+(\.\d{1,2})?$/.test(v)) throw new Error("金额最多两位小数");
  const [a, b = ""] = v.split(".");
  return Number(a) * 100 + Number(b.padEnd(2, "0"));
}
async function load() {
  await loadRefunds();
  if (props.canManage) {
    const p = await api<any>("/admin/aftersales-settings");
    if (p) policy.value = p;
  }
}
onMounted(() => run(load));
async function run(work: () => Promise<any>, message = "") {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await work();
    if (message) {
      await load();
      notice.value = message;
    }
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function create() {
  await run(async () => {
    const items = Object.entries(quantities.value)
      .filter(([, q]) => q > 0)
      .map(([lineId, quantity]) => ({
        lineId: Number(lineId),
        quantity: Number(quantity),
        ...(returnShipped.value ? { unshippedQuantity: 0 } : {}),
      }));
    if (
      items.some((item) => {
        const line = current.value?.lines.find(
          (value: any) => value.id === item.lineId,
        );
        return (
          !line ||
          !Number.isInteger(item.quantity) ||
          item.quantity > line.quantity - line.refunded_qty - line.refund_held
        );
      })
    )
      throw new Error("退货数量须为整数，且不能超过各商品的可退数量");
    if (!items.length && cents(shipping.value) === 0)
      throw new Error("请选择实际退款商品数量或需退运费");
    await api("/admin/refunds", {
      method: "POST",
      body: {
        orderId: selectedOrderId.value,
        items,
        shippingCents: cents(shipping.value),
        reason: reason.value,
        requestKey: crypto.randomUUID(),
        ...(exceptionApproved.value
          ? { exceptionReason: exceptionReason.value }
          : {}),
      },
    });
    setupDialog.value = "";
    quantities.value = {};
    exceptionApproved.value = false;
    exceptionReason.value = "";
  }, "售后申请已登记，尚未退款");
}
const returnShipped = ref(false);
const giftReturns = ref<
  { lineId: number; quantity: number; available: number; skuId: number }[]
>([]);
async function open(r: any, key: string) {
  error.value = "";
  selected.value = r;
  action.value = key;
  reason.value = "";
  confirmed.value = false;
  proof.value = "";
  recipient.value = "";
  paidAt.value = "";
  inspection.value = "";
  restock.value = false;
  decision.value = "retain";
  giftReturns.value = [];
  if (key === "approve") {
    try {
      const response = await api<{ order: any }>(
        `/admin/orders/${r.order_id}/payment`,
      );
      giftReturns.value = response.order.lines
        .filter(
          (line: any) =>
            line.is_gift &&
            !r.lines.some((existing: any) => existing.lineId === line.id),
        )
        .map((line: any) => ({
          lineId: line.id,
          skuId: line.sku_id,
          quantity: 0,
          available: line.quantity - line.refunded_qty - line.refund_held,
        }));
    } catch (e) {
      error.value = (e as Error).message;
      action.value = "";
    }
  }
}
function refundMethod(refund: any): string | undefined {
  return orders.value.find((order) => order.id === refund.order_id)
    ?.payment_method;
}
async function loadRefundOrder(refund: any) {
  await run(async () => {
    const { order } = await api<{ order: any }>(
      `/admin/orders/${refund.order_id}/payment`,
    );
    orders.value = [
      ...orders.value.filter((value) => value.id !== order.id),
      order,
    ];
  });
}
function awaitingInspection(refund: any): boolean {
  return (
    !refund.returned_at &&
    refund.lines.some((line: any) => line.returnQuantity > 0)
  );
}
function canRefund(refund: any, method: "offline" | "points") {
  return (
    props.canFinance &&
    ["approved", "failed"].includes(refund.status) &&
    !awaitingInspection(refund) &&
    refundMethod(refund) === method
  );
}
async function submit() {
  await run(async () => {
    const id = selected.value.id;
    if (
      ["offline", "points"].includes(action.value) &&
      !canRefund(selected.value, action.value as "offline" | "points")
    )
      throw new Error("请核对原订单付款方式和退款状态后重新办理");
    if (
      ["approve", "reject", "inspect"].includes(action.value) &&
      !props.canManage
    )
      throw new Error("当前账号没有售后审核或验收权限");
    if (action.value === "offline") {
      const time = new Date(paidAt.value).getTime();
      if (!Number.isFinite(time) || time >= Date.now())
        throw new Error("实际退款时间须为过去的有效时间");
      if (!confirmed.value) throw new Error("请确认公司已经实际退款");
      if (
        ![recipient.value, proof.value, reason.value].every((value) =>
          value.trim(),
        )
      )
        throw new Error("请完整填写实际收款人、转账凭证和处理原因");
    }
    let body: any = { reason: reason.value };
    let endpoint = action.value;
    if (action.value === "approve" || action.value === "reject") {
      endpoint = "review";
      body = {
        ...body,
        approve: action.value === "approve",
        identityDecision: null,
        giftDecision: reason.value,
        giftItems: giftReturns.value
          .filter((line) => line.quantity > 0)
          .map((line) => ({ lineId: line.lineId, quantity: line.quantity })),
      };
    }
    if (action.value === "inspect")
      body = {
        ...body,
        returnedAt: new Date(paidAt.value).toISOString(),
        inspection: inspection.value,
        restock: restock.value,
      };
    if (action.value === "offline")
      body = {
        ...body,
        recipient: recipient.value,
        paidAt: new Date(paidAt.value).toISOString(),
        proof: proof.value,
        confirmed: confirmed.value,
      };
    await api(`/admin/refunds/${id}/${endpoint}`, { method: "POST", body });
    action.value = "";
  }, "售后处理记录已保存，请查看当前退款状态");
}

function statusColor(value: string) {
  if (
    [
      "paid",
      "completed",
      "received",
      "approved",
      "success",
      "succeeded",
    ].includes(value)
  )
    return "success";
  if (["failed", "rejected", "revoked", "review"].includes(value))
    return "error";
  if (
    [
      "pending",
      "requested",
      "unpaid",
      "unknown",
      "partially_refunded",
      "submitted",
    ].includes(value)
  )
    return "warning";
  if (
    [
      "paying",
      "processing",
      "shipping",
      "shipped",
      "awaiting_shipment",
    ].includes(value)
  )
    return "processing";
  return "default";
}

const refundQuery = ref("");
const refundStatus = ref("");
const {
  rows: refunds,
  total: refundTotal,
  pagination: refundPagination,
  loading: listLoading,
  load: loadRefunds,
} = useAdminList<any>(
  "/admin/refunds",
  () => ({ q: refundQuery.value, status: refundStatus.value }),
  error,
);
const filteredRefunds = computed(() => refunds.value);

const refundColumns = [
  { title: "售后编号", key: "number", width: 140 },
  { title: "原订单", key: "order", width: 110 },
  { title: "申请退款金额", key: "amount", width: 120 },
  { title: "处理状态", key: "status", width: 150 },
  { title: "受理原因", key: "reason", width: 170 },
  { title: "操作", key: "actions", width: 300 },
];
const actionNames: Record<string, string> = {
  approve: "审核同意售后",
  reject: "驳回售后申请",
  inspect: "登记实际退货验收",
  offline: "登记实际线下退款",
  points: "退回原积分",
};
</script>
<template>
  <section class="aftersales" :aria-busy="busy">
    <FeedbackToast v-model:notice="notice" v-model:error="error" />
    <div v-if="props.canManage" class="setup-panels">
      <a-card size="small" class="setup-panel">
        <div class="section-heading">
          <h3>售后政策</h3>
          <a-button
            html-type="button"
            :disabled="busy"
            @click="openSetup('policy')"
          >
            编辑售后政策
          </a-button>
        </div>
        <p>收货后 {{ policy.days }} 天可申请售后。</p>
        <p>{{ policy.policy || "暂未配置售后政策" }}</p>
        <p>新政策只用于新交易；历史订单保留购买时的政策。</p>
      </a-card>
      <a-card size="small" class="setup-panel">
        <div class="section-heading">
          <h3>客服受理商品售后</h3>
          <a-button
            html-type="button"
            type="primary"
            :disabled="busy"
            @click="openSetup('create')"
          >
            新增售后受理
          </a-button>
        </div>
        <p>选择原订单，登记退款商品与受理原因。</p>
      </a-card>
    </div>
    <a-card size="small" class="refund-list">
      <header class="section-heading">
        <div>
          <h3>{{ props.canManage ? "售后处理记录" : "订单退款登记" }}</h3>
          <p>
            现金由财务在线下退还后登记；积分订单退回原积分。审核通过不代表实际退款完成。
          </p>
        </div>
        <span class="list-count">共 {{ refundTotal }} 笔记录</span>
      </header>

      <AdminForm class="filters" @submit="() => {}">
        <label class="field"
          >搜索售后 / 原订单<a-input
            v-model:value="refundQuery"
            placeholder="商品名称、规格、售后编号或受理原因"
            :disabled="busy"
        /></label>
        <label class="field"
          >处理状态<AdminSelect v-model="refundStatus" :disabled="busy"
            ><option value="">全部状态</option>
            <option v-for="(label, key) in names" :key="key" :value="key">
              {{ label }}
            </option></AdminSelect
          ></label
        >
      </AdminForm>
      <a-table
        :columns="refundColumns"
        :data-source="filteredRefunds"
        row-key="id"
        :loading="busy || listLoading"
        :pagination="refundPagination"
        :scroll="{ x: 1040 }"
        size="small"
        :locale="{ emptyText: '暂无符合条件的售后记录' }"
      >
        <template #bodyCell="{ column, record: r }">
          <template v-if="column.key === 'number'">{{ r.number }}</template>
          <template v-else-if="column.key === 'order'"
            >#{{ r.order_id
            }}<small class="cell-note">{{
              r.kind === "package" ? "入驻产品包" : "商品订单"
            }}</small></template
          >
          <template v-else-if="column.key === 'amount'">{{
            money(r.total_cents)
          }}</template>
          <template v-else-if="column.key === 'status'"
            ><a-tag :color="statusColor(r.status)">{{
              names[r.status] || r.status
            }}</a-tag
            ><small v-if="r.last_error" class="cell-note"
              >展开查看退款异常</small
            ></template
          >
          <template v-else-if="column.key === 'reason'">{{
            r.reason
          }}</template>
          <template v-else-if="column.key === 'actions'">
            <a-space wrap class="actions">
              <a-button
                html-type="button"
                size="small"
                :disabled="busy"
                v-if="props.canManage && r.status === 'requested'"
                type="primary"
                @click="open(r, 'approve')"
              >
                审核同意</a-button
              ><a-button
                html-type="button"
                size="small"
                :disabled="busy"
                v-if="props.canManage && r.status === 'requested'"
                danger
                @click="open(r, 'reject')"
              >
                驳回</a-button
              ><a-button
                html-type="button"
                size="small"
                :disabled="busy"
                v-if="
                  props.canManage &&
                  r.status !== 'requested' &&
                  r.status !== 'rejected' &&
                  !r.returned_at &&
                  r.lines.some((l: any) => l.returnQuantity > 0)
                "
                @click="open(r, 'inspect')"
              >
                登记实际退货验收</a-button
              ><a-button
                html-type="button"
                size="small"
                :disabled="busy"
                v-if="canRefund(r, 'points')"
                @click="open(r, 'points')"
              >
                退回原积分 </a-button
              ><a-button
                html-type="button"
                size="small"
                :disabled="busy"
                v-if="canRefund(r, 'offline')"
                @click="open(r, 'offline')"
              >
                登记实际线下退款
              </a-button>
              <a-button
                v-if="
                  props.canFinance &&
                  ['approved', 'failed'].includes(r.status) &&
                  refundMethod(r) === undefined
                "
                html-type="button"
                size="small"
                :disabled="busy"
                @click="loadRefundOrder(r)"
                >核对原订单付款方式</a-button
              >
              <small
                v-if="
                  props.canFinance &&
                  ['approved', 'failed', 'processing', 'unknown'].includes(
                    r.status,
                  ) &&
                  !canRefund(r, 'offline') &&
                  !canRefund(r, 'points')
                "
                class="cell-note"
              >
                {{
                  awaitingInspection(r)
                    ? "请先由客服登记实际退货验收，再办理退款。"
                    : ["processing", "unknown"].includes(r.status)
                      ? "历史退款结果待核实，请先核对实际退款记录。"
                      : "请核对原订单付款记录；历史交易须先核实处理依据。"
                }}
              </small>
            </a-space></template
          >
        </template>
        <template #expandedRowRender="{ record: r }">
          <div class="refund-details">
            <p v-if="r.policy?.exceptionReason">
              品牌方特批：{{ r.policy.exceptionReason }}
            </p>
            <p v-for="l in r.lines" :key="l.lineId">
              商品行 #{{ l.lineId }}：退 {{ l.quantity }} 件，需收回
              {{ l.returnQuantity }} 件
            </p>
            <p>退货验收：{{ r.returned_at || "尚未登记实际退货验收" }}</p>
            <details v-if="r.last_error">
              <summary>查看退款异常</summary>
              <p>{{ r.last_error }}</p>
            </details>
          </div>
        </template>
      </a-table>
    </a-card>
    <AdminDialog
      v-if="setupDialog"
      :title="setupDialog === 'policy' ? '编辑售后政策' : '客服受理商品售后'"
      :busy="busy"
      :wide="setupDialog === 'create'"
      @close="!busy && (setupDialog = '')"
    >
      <template v-if="setupDialog === 'policy'">
        <AdminForm class="form-grid setup-form" @submit="savePolicy">
          <label class="field"
            >收货后售后天数<a-input-number
              type="number"
              :step="1"
              v-model:value="policyDraft.days"
              :min="0"
              :max="365"
              required
              :disabled="busy" /></label
          ><label class="field wide-field"
            >实际售后政策<a-textarea
              v-model:value="policyDraft.policy"
              :rows="4"
              required
              :disabled="busy" /></label
          ><label class="field wide-field"
            >修改原因<a-input v-model:value="reason" required :disabled="busy"
          /></label>
          <div class="dialog-actions wide-field">
            <a-button
              html-type="button"
              :disabled="busy"
              @click="setupDialog = ''"
            >
              取消
            </a-button>
            <a-button
              html-type="submit"
              type="primary"
              :disabled="busy"
              :loading="busy"
              >保存政策</a-button
            >
          </div>
        </AdminForm>
      </template>
      <template v-else>
        <AdminForm class="form-grid setup-form" @submit="create">
          <a-checkbox v-model:checked="returnShipped"
            >本次退回已发出的货品（不取消未提寄存数量）</a-checkbox
          >
          <label class="field wide-field"
            >搜索已付款订单<a-input
              v-model:value="orderQuery"
              placeholder="商品名称、规格、订单号或购买者"
              :disabled="busy"
          /></label>
          <label class="field wide-field"
            >原订单<AdminSelect
              v-model.number="selectedOrderId"
              required
              @change="quantities = {}"
              :disabled="busy"
            >
              <option :value="0" disabled>选择已付款订单</option>
              <option v-for="o in orderOptions" :key="o.id" :value="o.id">
                {{ o.number }} · 用户{{ o.user_id }} ·
                {{ money(o.total_cents) }}
              </option>
            </AdminSelect></label
          >
          <div class="wide-field">
            <a-pagination
              v-bind="orderPagination"
              :disabled="busy || orderLoading"
            />
          </div>
          <template v-if="current"
            ><div
              v-for="l in current.lines"
              :key="l.id"
              class="line return-line wide-field"
            >
              <span
                >{{
                  current.snapshot.lines.find((i: any) => i.skuId === l.sku_id)
                    ?.name
                }}
                · 已发{{ l.shipped_qty }} 已退{{ l.refunded_qty }} 办理中{{
                  l.refund_held
                }}</span
              ><label class="field"
                >本次退货数量<a-input-number
                  type="number"
                  :step="1"
                  v-model:value="quantities[l.id]"
                  :min="0"
                  :max="l.quantity - l.refunded_qty - l.refund_held"
                  placeholder="0"
                  :disabled="busy"
              /></label></div></template
          ><label class="field"
            >退运费（元）<a-input
              v-model:value="shipping"
              pattern="[0-9]+([.][0-9]{1,2})?"
              inputmode="decimal"
              required
              :disabled="busy" /></label
          ><label class="field"
            >受理原因<a-input
              v-model:value="reason"
              required
              :maxlength="200"
              :disabled="busy" /></label
          ><template v-if="props.canFinance"
            ><a-checkbox
              class="check-field wide-field"
              v-model:checked="exceptionApproved"
              :disabled="busy"
              >品牌方确认按特殊原因受理超期售后</a-checkbox
            ><label v-if="exceptionApproved" class="field wide-field"
              >特批依据及原因<a-input
                v-model:value="exceptionReason"
                required
                :maxlength="200"
                :disabled="busy" /></label
          ></template>
          <div class="dialog-actions wide-field">
            <a-button
              html-type="button"
              :disabled="busy"
              @click="setupDialog = ''"
            >
              取消
            </a-button>
            <a-button
              html-type="submit"
              type="primary"
              :disabled="busy || !current"
              :loading="busy"
            >
              登记售后并占用可退数量
            </a-button>
          </div>
        </AdminForm>
      </template>
    </AdminDialog>
    <AdminDialog
      v-if="selected && action"
      :title="actionNames[action] + ' · ' + selected.number"
      :busy="busy"
      @close="!busy && (action = '')"
    >
      <AdminForm class="form-grid" @submit="submit">
        <a-descriptions class="wide-field" size="small" :column="2" bordered>
          <a-descriptions-item label="原订单"
            >#{{ selected.order_id }}</a-descriptions-item
          >
          <a-descriptions-item label="申请退款金额">{{
            money(selected.total_cents)
          }}</a-descriptions-item>
        </a-descriptions>
        <p v-if="['approve', 'reject'].includes(action)">
          退款不自动关闭身份。涉及买赠套餐时，请在处理原因中明确应取消或收回的赠品及数量，已发赠品须核验退回。
        </p>
        <label
          v-for="gift in action === 'approve' ? giftReturns : []"
          :key="gift.lineId"
          class="field"
          >赠品规格 #{{ gift.skuId }} 应退数量（剩余{{
            gift.available
          }}）<a-input-number
            v-model:value="gift.quantity"
            :min="0"
            :max="gift.available"
            :precision="0"
        /></label>
        <template v-if="action === 'inspect'"
          ><label class="field"
            >实际退货收到时间<AdminDatePicker
              v-model="paidAt"
              type="datetime-local"
              required
              :disabled="busy" /></label
          ><label class="field"
            >验货依据<a-textarea
              v-model:value="inspection"
              required
              :disabled="busy" /></label
          ><a-checkbox
            class="check-field wide-field"
            v-model:checked="restock"
            :disabled="busy"
            >实际验货确认可重新销售并回库存</a-checkbox
          ></template
        ><template v-if="action === 'offline'"
          ><p class="wide-field">
            请先在线下完成本次退款，再登记真实收款人、付款时间和凭证；保存成功后才确认退款并联动资格及奖励。
          </p>
          <label class="field"
            >实际收款人<a-input
              v-model:value="recipient"
              required
              :disabled="busy" /></label
          ><label class="field"
            >实际付款时间<AdminDatePicker
              v-model="paidAt"
              type="datetime-local"
              required
              :disabled="busy" /></label
          ><label class="field"
            >实际转账凭证<a-input
              v-model:value="proof"
              required
              :disabled="busy" /></label
          ><a-checkbox
            class="check-field wide-field"
            v-model:checked="confirmed"
            required
            :disabled="busy"
            >公司已实际退款，资料核实无误</a-checkbox
          ></template
        ><label class="field wide-field"
          >处理原因<a-input
            v-model:value="reason"
            required
            :maxlength="200"
            :disabled="busy"
        /></label>
        <div class="dialog-actions wide-field">
          <a-button html-type="button" :disabled="busy" @click="action = ''">
            取消
          </a-button>
          <a-button
            html-type="submit"
            type="primary"
            :disabled="busy || (action === 'offline' && !confirmed)"
            :loading="busy"
            :danger="action === 'reject'"
            >{{ actionNames[action] }}</a-button
          >
        </div>
      </AdminForm>
    </AdminDialog>
  </section>
</template>
<style scoped>
.aftersales {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.setup-panels {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: stretch;
  gap: 16px;
  min-width: 0;
}
.setup-panel,
.refund-list {
  min-width: 0;
}
.section-heading,
.line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  min-width: 0;
}
.section-heading {
  margin: 0 0 16px;
}
h3 {
  margin: 0;
  font-size: 15px;
  line-height: 1.5;
}
.setup-panel p,
.refund-details p {
  margin: 8px 0;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.list-count,
.cell-note {
  color: var(--admin-muted);
  font-size: 12px;
}
.cell-note {
  display: block;
  margin-top: 4px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: 16px;
}
.field {
  display: grid;
  align-content: start;
  gap: 6px;
  min-width: 0;
  line-height: 1.5;
}
.field :deep(.ant-input-number) {
  width: 100%;
}
.wide-field {
  grid-column: 1 / -1;
}
.return-line {
  padding: 12px 0;
  border-bottom: 1px solid var(--admin-border);
}
.return-line > span {
  flex: 1 1 240px;
}
.return-line .field {
  width: 140px;
}
.filters {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
}
.refund-details {
  display: grid;
  gap: 8px;
  padding: 0 8px;
}
.refund-details summary {
  color: var(--admin-accent);
  cursor: pointer;
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  grid-column: 1 / -1;
  margin-top: 4px;
}
@media (max-width: 900px) {
  .setup-panels {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 650px) {
  .filters,
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
