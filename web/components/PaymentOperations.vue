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
  await api("/admin/orders/" + id + "/shipments", {
    method: "POST",
    body: {
      carrier: shipment.value.carrier,
      tracking: shipment.value.tracking,
      requestKey: shipment.value.requestKey,
      reason: reason.value,
      lines: Object.entries(shipment.value.quantities)
        .filter(([, q]) => q > 0)
        .map(([lineId, quantity]) => ({
          lineId: Number(lineId),
          quantity: Number(quantity),
        })),
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
</script>
<template>
  <section class="payment-operations" :aria-busy="busy">
    <FeedbackToast
      v-model:notice="notice"
      v-model:error="error"
      :show-error="!dialog && !correcting"
    />
    <section class="surface order-list">
      <header class="section-heading">
        <div>
          <h3>支付与履约订单</h3>
          <p>从订单进入支付核查、品牌仓发货与物流纠正。</p>
        </div>
        <span class="list-count">{{ visible.length }} 笔订单</span>
      </header>
      <AdminForm class="filters" @submit="act(load)">
        <label
          >订单编号 / 用户编号<input
            v-model="query"
            placeholder="输入订单号或用户编号" /></label
        ><label
          >履约状态<AdminSelect v-model="status">
            <option value="">全部状态</option>
            <option v-for="(label, key) in labels" :key="key" :value="key">
              {{ label }}
            </option>
          </AdminSelect></label
        ><button :disabled="busy">刷新订单</button>
      </AdminForm>
      <p v-if="busy" class="loading" role="status">正在读取或处理订单…</p>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>订单 / 购买者</th>
              <th>履约</th>
              <th>支付</th>
              <th>订单金额</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="o in visible" :key="o.id">
              <td>
                {{ o.number
                }}<small
                  >用户 {{ o.user_id }} ·
                  {{ o.kind === "package" ? "入驻产品包" : "商品订单" }}</small
                >
              </td>
              <td>
                {{ labels[o.status] || o.status
                }}<small v-if="o.received_at"
                  >收货完成 {{ o.received_at }}</small
                >
              </td>
              <td>{{ paymentLabels[o.payment_state] || o.payment_state }}</td>
              <td>¥{{ money(o.total_cents) }}</td>
              <td>
                <button :disabled="busy" @click="act(() => select(o.id))">
                  查看与处理
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="!busy && !visible.length" class="empty">暂无符合条件的订单</p>
      </div>
    </section>
    <section v-if="selected" class="surface order-detail">
      <header class="section-heading">
        <div>
          <small>当前处理订单</small>
          <h3>{{ selected.number }}</h3>
        </div>
        <button @click="selected = null">收起</button>
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
        <p v-if="payment?.attempt?.lastError" class="error">
          {{ payment.attempt.lastError }}
        </p>
        <p>
          支付返回确认中时，请查询原交易；需要关闭时，系统核实渠道结果后才释放库存。
        </p>
        <div class="actions">
          <button :disabled="busy" @click="act(() => refreshPayment())">
            查询原支付</button
          ><button
            :disabled="busy || selected.payment_state === 'paid'"
            @click="openClose"
          >
            核查并关闭未付交易
          </button>
        </div>
      </section>
      <section class="detail-section">
        <button
          v-if="canShip"
          class="primary"
          :disabled="busy"
          @click="openShipment"
        >
          登记品牌仓发货
        </button>
        <p v-else>当前订单不允许新增发货。</p>
      </section>
      <section class="detail-section">
        <h4>包裹与收货记录</h4>
        <div v-for="s in selected.shipments" :key="s.id" class="parcel">
          <div>
            {{ s.carrier }} · {{ s.tracking
            }}<small
              >出库 {{ s.shipped_at }} ·
              {{
                s.received_at ? "收货 " + s.received_at : "尚未确认收货"
              }}</small
            >
          </div>
          <button :disabled="busy" @click="openCorrection(s)">
            纠正物流信息
          </button>
        </div>
        <p v-if="!selected.shipments.length">尚未登记发货</p>
      </section>
      <details class="detail-section payment-evidence">
        <summary>查看支付尝试、异常到账与补偿证据</summary>
        <h4>支付尝试与异常到账依据</h4>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>原商户单号</th>
                <th>状态</th>
                <th>渠道流水</th>
                <th>最近异常</th>
                <th>追踪编号</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="a in payment?.attempts || []" :key="a.id">
                <td>{{ a.number }}</td>
                <td>{{ a.state }}</td>
                <td>{{ a.transaction_id || "尚未核验到账" }}</td>
                <td>{{ a.last_error || "无" }}</td>
                <td>{{ a.last_request_id }}</td>
              </tr>
            </tbody>
          </table>
          <p v-if="!payment?.attempts.length" class="empty">暂无支付尝试记录</p>
        </div>
        <h4>支付事件与补偿证据</h4>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>动作 / 结果</th>
                <th>说明</th>
                <th>追踪编号</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="e in payment?.events || []" :key="e.id">
                <td>{{ e.create_time }}</td>
                <td>{{ e.action }} / {{ e.result }}</td>
                <td>{{ e.detail }}</td>
                <td>{{ e.request_id }}</td>
              </tr>
            </tbody>
          </table>
          <p v-if="!payment?.events.length" class="empty">暂无支付事件记录</p>
        </div>
      </details>
    </section>
    <AdminDialog
      v-if="selected && dialog"
      :title="dialog === 'ship' ? '登记品牌仓发货' : '核查并关闭未付交易'"
      :busy="busy"
      :wide="dialog === 'ship'"
      @close="!busy && (dialog = '')"
    >
      <p v-if="error" class="message error" role="alert">{{ error }}</p>
      <p>当前订单：{{ selected.number }}</p>
      <AdminForm
        v-if="dialog === 'close'"
        @submit="
          act(
            () => refreshPayment(true),
            '已提交关闭核查，请以当前支付状态为准',
          )
        "
      >
        <p>系统核实渠道结果后才释放库存。</p>
        <label
          >操作原因<input v-model="reason" required maxlength="200"
        /></label>
        <div class="dialog-actions">
          <button type="button" :disabled="busy" @click="dialog = ''">
            取消
          </button>
          <button :disabled="busy">核查并关闭未付交易</button>
        </div>
      </AdminForm>
      <template v-else>
        <AdminForm @submit="act(ship, '发货记录已保存')">
          <h4>登记品牌仓发货</h4>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>商品</th>
                  <th>订单数量</th>
                  <th>已出库</th>
                  <th>待发货</th>
                  <th>本次发货</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="l in selected.lines" :key="l.id">
                  <td>{{ name(l) }}</td>
                  <td>{{ l.quantity }}</td>
                  <td>{{ l.shipped_qty }}</td>
                  <td>{{ remaining(l) }}</td>
                  <td>
                    <input
                      v-model.number="shipment.quantities[l.id]"
                      type="number"
                      min="0"
                      :max="remaining(l)"
                      :disabled="remaining(l) <= 0"
                      aria-label="本次发货数量"
                    />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="filters">
            <label
              >物流公司<input
                v-model="shipment.carrier"
                required
                maxlength="60" /></label
            ><label
              >运单号<input
                v-model="shipment.tracking"
                required
                pattern="[a-zA-Z0-9-]{4,80}"
            /></label>
          </div>
          <label
            >操作原因<input v-model="reason" required maxlength="200"
          /></label>
          <div class="dialog-actions">
            <button type="button" :disabled="busy" @click="dialog = ''">
              取消
            </button>
            <button :disabled="busy">确认发货</button>
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
      <p v-if="error" class="message error" role="alert">{{ error }}</p>
      <AdminForm v-if="correcting" @submit="act(correct, '物流纠正记录已保存')">
        <label>物流公司<input v-model="correcting.carrier" required /></label
        ><label
          >运单号<input
            v-model="correcting.tracking"
            required
            pattern="[a-zA-Z0-9-]{4,80}"
        /></label>
        <label
          >操作原因<input v-model="reason" required maxlength="200"
        /></label>
        <div class="dialog-actions">
          <button type="button" :disabled="busy" @click="correcting = null">
            取消
          </button>
          <button :disabled="busy">保留记录并纠正</button>
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
  max-width: 100%;
  color: var(--admin-ink);
  font-size: 13px;
}
.payment-operations *,
.payment-operations *::before,
.payment-operations *::after {
  box-sizing: border-box;
}
.surface {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: #fff;
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
  margin-bottom: 16px;
}
.section-heading > div,
.parcel > div {
  min-width: 0;
  overflow-wrap: anywhere;
}
h3,
h4 {
  margin: 0;
  color: var(--admin-ink);
  line-height: 1.5;
}
h3 {
  font-size: 15px;
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
.filters,
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 12px;
}
.filters {
  margin: 12px 0;
}
.filters label {
  flex: 1 1 180px;
}
.filters label,
.reason {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.reason {
  max-width: 640px;
  margin: 12px 0;
}
.filters input,
.filters select,
.reason input {
  width: 100%;
  min-width: 0;
  max-width: 100%;
}
.actions {
  margin-top: 12px;
}
.actions button,
.filters button,
.parcel button {
  max-width: 100%;
  white-space: normal;
  overflow-wrap: anywhere;
}
.detail-section {
  min-width: 0;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--admin-border);
}
.order-detail p {
  margin: 8px 0;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.order-detail p:not(.error):not(.message) {
  color: var(--admin-muted);
}
.order-detail .delivery-address {
  padding: 12px;
  border-radius: 8px;
  background: var(--admin-bg);
}
small {
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
  border-radius: 8px;
}
.table-wrap {
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}
.table-wrap table {
  min-width: 640px;
}
td {
  overflow-wrap: anywhere;
}
td input {
  width: 88px;
}
.payment-evidence summary {
  color: var(--admin-accent);
  cursor: pointer;
  overflow-wrap: anywhere;
}
.payment-evidence[open] summary {
  margin-bottom: 16px;
}
.payment-evidence .table-wrap + h4 {
  margin-top: 16px;
}
.order-detail .error {
  color: var(--admin-accent);
}
.payment-operations .message {
  margin: 0;
  overflow-wrap: anywhere;
}
.loading,
.empty {
  padding: 16px 12px;
  color: var(--admin-muted);
  text-align: center;
}
@media (max-width: 650px) {
  .surface {
    padding: 12px;
  }
  .filters label {
    flex-basis: 100%;
  }
  .parcel {
    align-items: flex-start;
  }
}
</style>
