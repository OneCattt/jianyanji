<script setup lang="ts">
import FeedbackToast from "./FeedbackToast.vue";
import { computed, onMounted, ref } from "vue";
import { api } from "../client";
import AdminDialog from "./AdminDialog.vue";
import AdminForm from "./AdminForm.vue";
import AdminSelect from "./AdminSelect.vue";
interface OrderLine {
  id: number;
  sku_id: number;
  quantity: number;
  shipped_qty: number;
  refunded_unshipped: number;
  held_unshipped: number;
  remaining?: number;
  received_qty: number;
  refund_held: number;
  refunded_qty: number;
}
interface Shipment {
  id: number;
  carrier: string;
  tracking: string;
  shipped_at: string;
  received_at: string | null;
  lines: { lineId: number; quantity: number }[];
}
interface Order {
  id: number;
  number: string;
  kind: string;
  user_id: number;
  status: string;
  payment_state: string;
  payment_method: string | null;
  total_cents: number;
  received_at: string | null;
  snapshot: {
    address: {
      recipient: string;
      phone: string;
      province: string;
      city: string;
      district: string;
      detail: string;
    };
    lines: { skuId: number; name: string; skuName: string }[];
  };
  lines: OrderLine[];
  shipments: Shipment[];
}
interface Payment {
  attempt: {
    number: string;
    state: string;
    closeRequested: boolean;
    transactionId?: string;
    lastError?: string;
  } | null;
  receipt: { transaction_id: string; paid_at: string } | null;
  attempts: {
    id: number;
    number: string;
    state: string;
    transaction_id: string | null;
    last_error: string;
    last_request_id: string;
  }[];
  events: {
    id: number;
    action: string;
    result: string;
    detail: string;
    request_id: string;
    create_time: string;
  }[];
}
const orders = ref<Order[]>([]),
  selected = ref<Order | null>(null),
  payment = ref<Payment | null>(null),
  query = ref(""),
  status = ref(""),
  error = ref(""),
  notice = ref(""),
  busy = ref(false),
  reason = ref("");
const dialog = ref<"" | "ship" | "close">("");
const shipment = ref({
  carrier: "",
  tracking: "",
  requestKey: "",
  quantities: {} as Record<number, number>,
});
const correcting = ref<{
  id: number;
  carrier: string;
  tracking: string;
} | null>(null);
const labels: Record<string, string> = {
  pending: "待付款",
  paid: "待发货",
  awaiting_shipment: "待发货",
  shipped: "运输中",
  completed: "已完成",
  closed: "已关闭",
  refunded: "已退款",
  shipping: "配送中",
  received: "已收货",
  cancelled: "已取消",
  partially_refunded: "部分退款",
};
const paymentLabels: Record<string, string> = {
  unpaid: "未付款",
  paying: "支付处理中",
  unknown: "结果待核查",
  review: "需补偿核查",
  paid: "已付款",
  failed: "本次支付已结束",
  partially_refunded: "部分退款",
  refunded: "已退款",
};
const visible = computed(() =>
  orders.value.filter(
    (o) =>
      (!status.value || o.status === status.value) &&
      (!query.value ||
        o.number.includes(query.value) ||
        String(o.user_id) === query.value),
  ),
);
const canShip = computed(
  () =>
    selected.value &&
    ["paid", "partially_refunded"].includes(selected.value.payment_state) &&
    ["paid", "awaiting_shipment", "shipped"].includes(selected.value.status),
);
const money = (v: number) => (v / 100).toFixed(2);
const remaining = (l: OrderLine) =>
  l.quantity - l.shipped_qty - l.refunded_unshipped - l.held_unshipped;
const name = (l: OrderLine) =>
  selected.value?.snapshot.lines.find((s) => s.skuId === l.sku_id)?.name ||
  `商品规格 ${l.sku_id}`;
const freshKey = () => globalThis.crypto.randomUUID();
async function load() {
  orders.value = await api<Order[]>("/admin/orders");
}
async function select(id: number) {
  const [p, f] = await Promise.all([
    api<Payment>("/admin/orders/" + id + "/payment"),
    api<{ order: Order }>("/admin/orders/" + id + "/fulfillment"),
  ]);
  selected.value = f.order;
  payment.value = p;
  shipment.value = {
    carrier: "",
    tracking: "",
    requestKey: freshKey(),
    quantities: {},
  };
  correcting.value = null;
  dialog.value = "";
}
function openShipment() {
  shipment.value = {
    carrier: "",
    tracking: "",
    requestKey: freshKey(),
    quantities: {},
  };
  reason.value = "";
  error.value = "";
  dialog.value = "ship";
}
function openCorrection(value: Shipment) {
  correcting.value = {
    id: value.id,
    carrier: value.carrier,
    tracking: value.tracking,
  };
  reason.value = "";
  error.value = "";
}
function openClose() {
  reason.value = "";
  error.value = "";
  dialog.value = "close";
}
async function act(work: () => Promise<void>, message = "") {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await work();
    if (message) {
      await load();
      reason.value = "";
      notice.value = message;
    }
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function refreshPayment(close = false) {
  if (!selected.value) return;
  const id = selected.value.id;
  await api("/admin/orders/" + id + "/payment/" + (close ? "close" : "query"), {
    method: "POST",
    body: close ? { reason: reason.value } : {},
  });
  await select(id);
  if (!close) await load();
}
async function ship() {
  if (!selected.value) return;
  const id = selected.value.id;
  const lines = Object.entries(shipment.value.quantities)
    .filter(([, quantity]) => quantity > 0)
    .map(([lineId, quantity]) => ({
      lineId: Number(lineId),
      quantity: Number(quantity),
    }));
  if (!lines.length) throw new Error("请填写本次实际发货数量");
  if (
    lines.some((item) => {
      const line = selected.value!.lines.find(
        (value) => value.id === item.lineId,
      );
      return (
        !line ||
        !Number.isInteger(item.quantity) ||
        item.quantity > remaining(line)
      );
    })
  )
    throw new Error("发货数量须为整数，且不能超过各商品的待发货数量");
  await api("/admin/orders/" + id + "/shipments", {
    method: "POST",
    body: {
      carrier: shipment.value.carrier,
      tracking: shipment.value.tracking,
      requestKey: shipment.value.requestKey,
      reason: reason.value,
      lines,
    },
  });
  await select(id);
}
async function correct() {
  if (!correcting.value || !selected.value) return;
  const id = selected.value.id;
  await api("/admin/shipments/" + correcting.value.id, {
    method: "PUT",
    body: {
      carrier: correcting.value.carrier,
      tracking: correcting.value.tracking,
      reason: reason.value,
    },
  });
  await select(id);
}
onMounted(() => act(load));
const orderColumns = [
  { title: "订单 / 购买者", key: "order", width: 230 },
  { title: "履约", key: "fulfillment", width: 180 },
  { title: "支付", key: "payment", width: 180 },
  { title: "订单金额", key: "amount", width: 120 },
  { title: "操作", key: "actions", width: 170, fixed: "right" as const },
];
const attemptColumns = [
  { title: "原商户单号", key: "number", width: 180 },
  { title: "状态", key: "state", width: 180 },
  { title: "渠道流水", key: "transaction", width: 180 },
  { title: "最近异常", key: "error", width: 180 },
  { title: "追踪编号", key: "request", width: 180 },
];
const eventColumns = [
  { title: "时间", key: "time", width: 180 },
  { title: "动作 / 结果", key: "result", width: 180 },
  { title: "说明", key: "detail", width: 180 },
  { title: "追踪编号", key: "request", width: 180 },
];
const shipmentColumns = [
  { title: "商品", key: "product", width: 180 },
  { title: "订单数量", key: "quantity", width: 90 },
  { title: "已出库", key: "shipped", width: 90 },
  { title: "待发货", key: "remaining", width: 90 },
  { title: "本次发货", key: "ship", width: 140 },
];

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
</script>
<template>
  <section class="payment-operations" :aria-busy="busy">
    <FeedbackToast v-model:notice="notice" v-model:error="error" />
    <a-card size="small" class="order-list">
      <header class="section-heading">
        <div>
          <h3>支付与履约订单</h3>
          <p>从订单进入支付核查、品牌仓发货与物流纠正。</p>
        </div>
        <span class="list-count">{{ visible.length }} 笔订单</span>
      </header>
      <AdminForm class="filters" @submit="act(load)">
        <label class="field"
          >订单编号 / 用户编号<a-input
            v-model:value="query"
            placeholder="输入订单号或用户编号"
            :disabled="busy" /></label
        ><label class="field"
          >履约状态<AdminSelect v-model="status" :disabled="busy">
            <option value="">全部状态</option>
            <option v-for="(label, key) in labels" :key="key" :value="key">
              {{ label }}
            </option>
          </AdminSelect></label
        ><a-button html-type="submit" :disabled="busy" :loading="busy"
          >刷新订单</a-button
        >
      </AdminForm>
      <div class="table-wrap">
        <a-table
          :columns="orderColumns"
          :data-source="visible || []"
          row-key="id"
          :pagination="{
            pageSize: 20,
            showSizeChanger: false,
            hideOnSinglePage: true,
          }"
          :scroll="{ x: 880 }"
          size="small"
          :loading="busy"
          ><template #bodyCell="{ column, record: o }"
            ><template v-if="column.key === 'order'">
              {{ o.number
              }}<small class="cell-note"
                >用户 {{ o.user_id }} ·
                {{ o.kind === "package" ? "入驻产品包" : "商品订单" }}</small
              > </template
            ><template v-else-if="column.key === 'fulfillment'">
              <a-tag :color="statusColor(o.status)">{{
                labels[o.status] || o.status
              }}</a-tag
              ><small class="cell-note" v-if="o.received_at"
                >收货完成 {{ o.received_at }}</small
              > </template
            ><template v-else-if="column.key === 'payment'"
              ><a-tag :color="statusColor(o.payment_state)">{{
                paymentLabels[o.payment_state] || o.payment_state
              }}</a-tag></template
            ><template v-else-if="column.key === 'amount'"
              >¥{{ money(o.total_cents) }}</template
            ><template v-else-if="column.key === 'actions'">
              <a-button
                html-type="button"
                :disabled="busy"
                @click="act(() => select(o.id))"
              >
                查看与处理
              </a-button>
            </template></template
          ></a-table
        >
      </div>
    </a-card>
    <a-card size="small" v-if="selected" class="order-detail">
      <header class="section-heading">
        <div>
          <small class="cell-note">当前处理订单</small>
          <h3>{{ selected.number }}</h3>
        </div>
        <a-button html-type="button" @click="selected = null">收起</a-button>
      </header>
      <p class="delivery-address">
        {{ selected.snapshot.address.recipient }} ·
        {{ selected.snapshot.address.phone }} ·
        {{ selected.snapshot.address.province
        }}{{ selected.snapshot.address.city
        }}{{ selected.snapshot.address.district
        }}{{ selected.snapshot.address.detail }}
      </p>
      <section class="detail-section">
        <h4>支付核查</h4>
        <p>
          订单：{{
            paymentLabels[selected.payment_state] || selected.payment_state
          }}　原商户单：{{
            payment?.attempt?.number || "无微信尝试"
          }}　渠道流水：{{
            payment?.receipt?.transaction_id ||
            payment?.attempt?.transactionId ||
            "尚未确认"
          }}
        </p>
        <details v-if="payment?.attempt?.lastError" class="payment-history">
          <summary>查看最近支付异常</summary>
          <p>{{ payment.attempt.lastError }}</p>
        </details>
        <p>
          支付返回确认中时，请查询原交易；需要关闭时，系统核实渠道结果后才释放库存。
        </p>
        <a-space wrap class="actions">
          <a-button
            html-type="button"
            :disabled="busy"
            @click="act(() => refreshPayment())"
          >
            查询原支付</a-button
          ><a-button
            html-type="button"
            danger
            :disabled="busy || selected.payment_state === 'paid'"
            @click="openClose"
          >
            核查并关闭未付交易
          </a-button>
        </a-space>
      </section>
      <section class="detail-section">
        <a-button
          html-type="button"
          type="primary"
          v-if="canShip"
          :disabled="busy"
          @click="openShipment"
        >
          登记品牌仓发货
        </a-button>
        <p v-else>当前订单不允许新增发货。</p>
      </section>
      <section class="detail-section">
        <h4>包裹与收货记录</h4>
        <div v-for="s in selected.shipments" :key="s.id" class="parcel">
          <div>
            {{ s.carrier }} · {{ s.tracking
            }}<small class="cell-note"
              >出库 {{ s.shipped_at }} ·
              {{
                s.received_at ? "收货 " + s.received_at : "尚未确认收货"
              }}</small
            >
          </div>
          <a-button
            html-type="button"
            :disabled="busy"
            @click="openCorrection(s)"
          >
            纠正物流信息
          </a-button>
        </div>
        <p v-if="!selected.shipments.length">尚未登记发货</p>
      </section>
      <details class="detail-section payment-evidence">
        <summary>查看支付尝试、异常到账与补偿证据</summary>
        <h4>支付尝试与异常到账依据</h4>
        <div class="table-wrap">
          <a-table
            :columns="attemptColumns"
            :data-source="payment?.attempts || []"
            row-key="id"
            :pagination="false"
            :scroll="{ x: 900 }"
            size="small"
            ><template #bodyCell="{ column, record: a }"
              ><template v-if="column.key === 'number'">{{ a.number }}</template
              ><template v-else-if="column.key === 'state'"
                ><a-tag :color="statusColor(a.state)">{{
                  paymentLabels[a.state] || a.state
                }}</a-tag></template
              ><template v-else-if="column.key === 'transaction'">{{
                a.transaction_id || "尚未核验到账"
              }}</template
              ><template v-else-if="column.key === 'error'">{{
                a.last_error || "无"
              }}</template
              ><template v-else-if="column.key === 'request'">{{
                a.last_request_id
              }}</template></template
            ></a-table
          >
        </div>
        <h4>支付事件与补偿证据</h4>
        <div class="table-wrap">
          <a-table
            :columns="eventColumns"
            :data-source="payment?.events || []"
            row-key="id"
            :pagination="false"
            :scroll="{ x: 800 }"
            size="small"
            ><template #bodyCell="{ column, record: e }"
              ><template v-if="column.key === 'time'">{{
                e.create_time
              }}</template
              ><template v-else-if="column.key === 'result'"
                >{{ e.action }} / {{ e.result }}</template
              ><template v-else-if="column.key === 'detail'">{{
                e.detail
              }}</template
              ><template v-else-if="column.key === 'request'">{{
                e.request_id
              }}</template></template
            ></a-table
          >
        </div>
      </details>
    </a-card>
    <AdminDialog
      v-if="selected && dialog"
      :title="dialog === 'ship' ? '登记品牌仓发货' : '核查并关闭未付交易'"
      :busy="busy"
      :wide="dialog === 'ship'"
      @close="!busy && (dialog = '')"
    >
      <p>当前订单：{{ selected.number }}</p>
      <AdminForm
        class="dialog-fields"
        v-if="dialog === 'close'"
        @submit="
          act(
            () => refreshPayment(true),
            '已提交关闭核查，请以当前支付状态为准',
          )
        "
      >
        <p>系统核实渠道结果后才释放库存。</p>
        <label class="field"
          >操作原因<a-input
            v-model:value="reason"
            required
            :maxlength="200"
            :disabled="busy"
        /></label>
        <div class="dialog-actions">
          <a-button html-type="button" :disabled="busy" @click="dialog = ''">
            取消
          </a-button>
          <a-button
            html-type="submit"
            type="primary"
            danger
            :loading="busy"
            :disabled="busy"
            >核查并关闭未付交易</a-button
          >
        </div>
      </AdminForm>
      <template v-else>
        <AdminForm class="dialog-fields" @submit="act(ship, '发货记录已保存')">
          <h4>登记品牌仓发货</h4>
          <div class="table-wrap">
            <a-table
              :columns="shipmentColumns"
              :data-source="selected.lines || []"
              row-key="id"
              :pagination="false"
              :scroll="{ x: 660 }"
              size="small"
              ><template #bodyCell="{ column, record: l }"
                ><template v-if="column.key === 'product'">{{
                  name(l)
                }}</template
                ><template v-else-if="column.key === 'quantity'">{{
                  l.quantity
                }}</template
                ><template v-else-if="column.key === 'shipped'">{{
                  l.shipped_qty
                }}</template
                ><template v-else-if="column.key === 'remaining'">{{
                  remaining(l)
                }}</template
                ><template v-else-if="column.key === 'ship'">
                  <a-input-number
                    type="number"
                    :step="1"
                    class="shipment-quantity"
                    v-model:value="shipment.quantities[l.id]"
                    :min="0"
                    :max="remaining(l)"
                    :disabled="busy || remaining(l) <= 0"
                    aria-label="本次发货数量"
                  /> </template></template
            ></a-table>
          </div>
          <div class="filters">
            <label class="field"
              >物流公司<a-input
                v-model:value="shipment.carrier"
                required
                :maxlength="60"
                :disabled="busy" /></label
            ><label class="field"
              >运单号<a-input
                v-model:value="shipment.tracking"
                required
                pattern="[a-zA-Z0-9\-]{4,80}"
                :disabled="busy"
            /></label>
          </div>
          <label class="field"
            >操作原因<a-input
              v-model:value="reason"
              required
              :maxlength="200"
              :disabled="busy"
          /></label>
          <div class="dialog-actions">
            <a-button html-type="button" :disabled="busy" @click="dialog = ''">
              取消
            </a-button>
            <a-button
              html-type="submit"
              type="primary"
              :disabled="busy"
              :loading="busy"
              >确认发货</a-button
            >
          </div>
        </AdminForm>
      </template>
    </AdminDialog>
    <AdminDialog
      v-if="selected && correcting"
      title="纠正物流信息"
      :busy="busy"
      @close="!busy && (correcting = null)"
    >
      <AdminForm
        class="dialog-fields"
        v-if="correcting"
        @submit="act(correct, '物流纠正记录已保存')"
      >
        <label class="field"
          >物流公司<a-input
            v-model:value="correcting.carrier"
            required
            :disabled="busy" /></label
        ><label class="field"
          >运单号<a-input
            v-model:value="correcting.tracking"
            required
            pattern="[a-zA-Z0-9\-]{4,80}"
            :disabled="busy"
        /></label>
        <label class="field"
          >操作原因<a-input
            v-model:value="reason"
            required
            :maxlength="200"
            :disabled="busy"
        /></label>
        <div class="dialog-actions">
          <a-button
            html-type="button"
            :disabled="busy"
            @click="correcting = null"
          >
            取消
          </a-button>
          <a-button
            html-type="submit"
            type="primary"
            :disabled="busy"
            :loading="busy"
            >保留记录并纠正</a-button
          >
        </div>
      </AdminForm>
    </AdminDialog>
  </section>
</template>
<style scoped>
.payment-operations {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.order-list,
.order-detail,
.table-wrap {
  min-width: 0;
}
.section-heading,
.parcel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.section-heading {
  margin: 0 0 16px;
}
.section-heading > div,
.parcel > div {
  min-width: 0;
  overflow-wrap: anywhere;
}
h3,
h4 {
  margin: 0;
  font-size: 15px;
  line-height: 1.5;
}
h4 {
  margin-bottom: 12px;
  font-size: 14px;
}
.section-heading p {
  margin: 4px 0 0;
  color: var(--admin-muted);
}
.list-count {
  color: var(--admin-muted);
  font-size: 12px;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 12px;
  margin: 0 0 16px;
}
.filters > .field {
  flex: 1 1 200px;
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
.actions {
  margin-top: 12px;
}
.detail-section {
  min-width: 0;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--admin-border);
}
.order-detail p {
  margin: 8px 0;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.delivery-address {
  color: var(--admin-muted);
}
.cell-note {
  display: block;
  margin-top: 4px;
  color: var(--admin-muted);
  font-size: 12px;
  line-height: 1.5;
}
.parcel {
  padding: 12px;
  margin-top: 8px;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
}
.payment-evidence summary,
.payment-history summary {
  color: var(--admin-accent);
  cursor: pointer;
}
.payment-evidence[open] summary {
  margin-bottom: 16px;
}
.payment-evidence h4:not(:first-of-type) {
  margin-top: 20px;
}
.shipment-quantity {
  width: 100%;
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  grid-column: 1 / -1;
  margin-top: 20px;
}
.dialog-fields {
  display: grid;
  gap: 16px;
}
@media (max-width: 650px) {
  .filters > .field {
    flex-basis: 100%;
  }
  .parcel {
    align-items: flex-start;
  }
}
</style>
