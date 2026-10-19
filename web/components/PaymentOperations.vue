<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../client";
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
}
async function act(work: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await work();
    notice.value = "操作已完成，请以当前业务状态为准";
    reason.value = "";
    await load();
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
  <section class="payment-operations">
    <p v-if="error" role="alert" class="message error">{{ error }}</p>
    <p v-if="notice" role="status" class="message success">{{ notice }}</p>
    <form class="filters" @submit.prevent="act(load)">
      <label
        >订单编号 / 用户编号<input
          v-model="query"
          placeholder="输入订单号或用户编号" /></label
      ><label
        >履约状态<select v-model="status">
          <option value="">全部状态</option>
          <option v-for="(label, key) in labels" :key="key" :value="key">
            {{ label }}
          </option>
        </select></label
      ><button :disabled="busy">刷新订单</button>
    </form>
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
              }}<small v-if="o.received_at">收货完成 {{ o.received_at }}</small>
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
      <p v-if="!visible.length">暂无符合条件的订单</p>
    </div>
    <section v-if="selected" class="surface">
      <header>
        <h3>{{ selected.number }}</h3>
        <button @click="selected = null">收起</button>
      </header>
      <p>
        {{ selected.snapshot.address.recipient }} ·
        {{ selected.snapshot.address.phone }} ·
        {{ selected.snapshot.address.province
        }}{{ selected.snapshot.address.city
        }}{{ selected.snapshot.address.district
        }}{{ selected.snapshot.address.detail }}
      </p>
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
      <label class="reason"
        >操作原因<input
          v-model="reason"
          maxlength="200"
          placeholder="发货、关闭或纠正必填"
      /></label>
      <div class="actions">
        <button :disabled="busy" @click="act(() => refreshPayment())">
          查询原支付</button
        ><button
          :disabled="
            busy || !reason.trim() || selected.payment_state === 'paid'
          "
          @click="act(() => refreshPayment(true))"
        >
          核查并关闭未付交易
        </button>
      </div>
      <form v-if="canShip" @submit.prevent="act(ship)">
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
              pattern="[a-zA-Z0-9-]{4,80}" /></label
          ><button :disabled="busy || !reason.trim()">确认发货</button>
        </div>
      </form>
      <p v-else>当前订单不允许新增发货。</p>
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
        <button
          @click="
            correcting = { id: s.id, carrier: s.carrier, tracking: s.tracking }
          "
        >
          纠正物流信息
        </button>
      </div>
      <p v-if="!selected.shipments.length">尚未登记发货</p>
      <form v-if="correcting" class="filters" @submit.prevent="act(correct)">
        <label>物流公司<input v-model="correcting.carrier" required /></label
        ><label
          >运单号<input
            v-model="correcting.tracking"
            required
            pattern="[a-zA-Z0-9-]{4,80}" /></label
        ><button :disabled="busy || !reason.trim()">保留记录并纠正</button
        ><button type="button" @click="correcting = null">取消</button>
      </form>
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
      </div>
    </section>
  </section>
</template>
<style scoped>
.filters,
.actions,
.parcel {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: end;
  margin: 18px 0;
}
.filters label,
.reason {
  display: grid;
  gap: 8px;
  font-size: 13px;
}
.reason {
  max-width: 640px;
}
select {
  padding: 12px;
  border: 1px solid #d7ddd7;
  background: #fff;
  border-radius: 6px;
  font: inherit;
}
.surface {
  margin-top: 24px;
  padding: 24px;
  background: #fff;
  border: 1px solid #e5e0da;
}
.surface header,
.parcel {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.surface p {
  font-size: 13px;
  line-height: 1.8;
  color: #74675f;
}
.surface h4 {
  margin: 28px 0 12px;
}
small {
  display: block;
  font-size: 12px;
  color: #8e8178;
  margin-top: 6px;
  line-height: 1.6;
}
td {
  overflow-wrap: anywhere;
}
td input {
  width: 90px;
}
.parcel {
  padding: 16px;
  border: 1px solid #e5e0da;
  font-size: 13px;
}
.error {
  color: #8d1724 !important;
}
button {
  cursor: pointer;
}
button[disabled] {
  cursor: default;
  opacity: 0.5;
}
@media (max-width: 650px) {
  .surface {
    padding: 16px;
  }
  .filters label {
    width: 100%;
  }
  .parcel {
    align-items: flex-start;
  }
}
</style>
