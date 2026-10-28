<script setup lang="ts">
import AdminUserName from "./AdminUserName.vue";
import { useAdminList } from "../useAdminList";
import FeedbackToast from "./FeedbackToast.vue";
import { computed, onMounted, ref } from "vue";
import { api } from "../client";
import AdminDialog from "./AdminDialog.vue";
import AdminDrawer from "./AdminDrawer.vue";
import AdminForm from "./AdminForm.vue";
import AdminFilterBar from "./AdminFilterBar.vue";
import AdminSelect from "./AdminSelect.vue";
import AdminDatePicker from "./AdminDatePicker.vue";
import { ShoppingBag } from "lucide-vue-next";
import { imageUrl } from "../image-url";
const props = withDefaults(
  defineProps<{ financeOnly?: boolean; canFinance?: boolean }>(),
  { financeOnly: false, canFinance: false },
);
const emit = defineEmits<{
  "open-pickup": [pickup: { id: number; number: string }];
}>();
interface OrderLine {
  id: number;
  sku_id: number;
  quantity: number;
  shipped_qty: number;
  refunded_unshipped: number;
  held_unshipped: number;
  storage_remaining?: number;
  pickup_held?: number;
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
  fulfillmentText?: string;
  fulfillmentState?: string;
  storageRemainingQty?: number;
  directShipQuantity?: number;
  pickups?: { id: number; number: string; status: string }[];
  id: number;
  number: string;
  kind: string;
  user_id: number;
  status: string;
  payment_state: string;
  payment_method: string | null;
  total_cents: number;
  create_time?: string;
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
    lines: {
      skuId: number;
      name: string;
      skuName: string;
      quantity?: number;
      image?: string;
      isGift?: boolean;
    }[];
  };
  lines: OrderLine[];
  shipments: Shipment[];
}
interface PaymentReceipt {
  method: string;
  transaction_id: string;
  cents: number;
  paid_at: string;
}
interface Payment {
  order: Order;
  attempt: {
    number: string;
    state: string;
    closeRequested: boolean;
    transactionId?: string;
    lastError?: string;
  } | null;
  receipt: PaymentReceipt | null;
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
const selected = ref<Order | null>(null),
  payment = ref<Payment | null>(null),
  query = ref(""),
  status = ref(""),
  error = ref(""),
  notice = ref(""),
  busy = ref(false),
  reason = ref("");
const dialog = ref<"" | "ship" | "receipt">("");
const receipt = ref({
  sourceChannel: "bank",
  accountRef: "",
  externalTransactionId: "",
  amount: "",
  receivedAt: "",
  evidence: "",
  reason: "",
  verified: false,
});
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
  pending: "待核款",
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
  unpaid: "待核款",
  paying: "历史交易处理中",
  unknown: "结果待核查",
  review: "需补偿核查",
  paid: "已收款 / 积分已扣款",
  failed: "未收款",
  partially_refunded: "部分退款",
  refunded: "已退款",
};
const {
  rows: orders,
  total: orderTotal,
  pagination: orderPagination,
  loading: listLoading,
  load: loadOrders,
} = useAdminList<Order>(
  "/admin/orders",
  () => ({
    q: query.value,
    status: status.value,
    kind: props.financeOnly ? "goods" : "",
  }),
  error,
);
const visible = computed(() => orders.value);

const canShip = computed(
  () =>
    selected.value &&
    ["paid", "partially_refunded"].includes(selected.value.payment_state) &&
    ["paid", "awaiting_shipment", "shipped"].includes(selected.value.status) &&
    selected.value.lines.some((line) => remaining(line) > 0),
);
const activePickups = computed(
  () =>
    selected.value?.pickups?.filter((pickup) =>
      ["review", "approved"].includes(pickup.status),
    ) || [],
);
const canCollect = computed(() => false);

const methodNames: Record<string, string> = {
  offline: "线下付款",
  points: "积分订货",
  wechat: "历史微信支付",
};
const money = (v: number) => (v / 100).toFixed(2);
const statusTabs = [
  { key: "", tab: "全部订单" },
  { key: "pending", tab: "待支付" },
  { key: "paid", tab: "已支付" },
  { key: "shipped", tab: "已发货" },
  { key: "completed", tab: "已完成" },
  { key: "refunded", tab: "已退款" },
  { key: "closed", tab: "已关闭" },
];
// 筛选沿用服务端原订单状态，寄存和待审核配送由每单的实际履约信息展示。
const filterLabels = {
  ...labels,
  pending: "待支付",
  paid: "已支付",
  shipped: "已发货",
};
function orderTime(value?: string) {
  if (!value) return "";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString("zh-CN", {
        hour12: false,
        timeZone: "Asia/Shanghai",
      });
}
const remaining = (l: OrderLine) =>
  l.quantity -
  l.shipped_qty -
  l.refunded_unshipped -
  l.held_unshipped -
  (l.storage_remaining || 0) -
  (l.pickup_held || 0);
const name = (l: OrderLine) =>
  selected.value?.snapshot.lines.find((s) => s.skuId === l.sku_id)?.name ||
  `商品规格 ${l.sku_id}`;
const freshKey = () => globalThis.crypto.randomUUID();
async function load() {
  await loadOrders();
}
async function select(id: number) {
  const [details, fulfillment] = await Promise.all([
    api<Payment>("/admin/orders/" + id + "/payment"),
    props.financeOnly
      ? Promise.resolve(null)
      : api<{ order: Order }>("/admin/orders/" + id + "/fulfillment"),
  ]);
  selected.value = fulfillment?.order ?? details.order;
  payment.value = details;
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
function openReceipt() {
  if (!canCollect.value || !selected.value) return;
  receipt.value = {
    sourceChannel: "bank",
    accountRef: "",
    externalTransactionId: "",
    amount: money(selected.value.total_cents),
    receivedAt: "",
    evidence: "",
    reason: "",
    verified: false,
  };
  error.value = "";
  dialog.value = "receipt";
}
async function collect() {
  await act(async () => {
    if (!canCollect.value || !selected.value)
      throw new Error("当前订单不能登记收款，请刷新后核实订单状态");
    if (!/^\d+(\.\d{1,2})?$/.test(receipt.value.amount))
      throw new Error("到账金额须为最多两位小数的正数");
    const [whole, fraction = ""] = receipt.value.amount.split(".");
    const amountCents = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
    if (
      !Number.isSafeInteger(amountCents) ||
      amountCents <= 0 ||
      amountCents !== selected.value.total_cents
    )
      throw new Error("到账金额必须等于原订单应收金额，不能部分核款");
    const receivedAt = new Date(receipt.value.receivedAt);
    if (
      !Number.isFinite(receivedAt.getTime()) ||
      receivedAt.getTime() >= Date.now()
    )
      throw new Error("实际到账时间须为过去的有效时间");
    if (!receipt.value.verified) throw new Error("请先确认公司实际到账");
    if (!["bank", "wechat_company"].includes(receipt.value.sourceChannel))
      throw new Error("请选择线下公司收款渠道");
    if (
      [
        receipt.value.accountRef,
        receipt.value.externalTransactionId,
        receipt.value.evidence,
        receipt.value.reason,
      ].some((value) => !value.trim())
    )
      throw new Error("请完整填写收款账户、原始流水、凭证和核实说明");
    const result = await api<{
      order: Order;
      receipt: unknown;
      alreadyPaid: boolean;
    }>(`/admin/orders/${selected.value.id}/receipt`, {
      method: "POST",
      body: {
        sourceChannel: receipt.value.sourceChannel,
        accountRef: receipt.value.accountRef.trim(),
        externalTransactionId: receipt.value.externalTransactionId.trim(),
        amountCents,
        receivedAt: receivedAt.toISOString(),
        evidence: receipt.value.evidence.trim(),
        reason: receipt.value.reason.trim(),
        verified: true,
      },
    });
    selected.value = result.order;
    dialog.value = "";
    notice.value = result.alreadyPaid
      ? "该订单已核款，本次未重复登记收款"
      : "普通商品线下收款已核实，可安排发货；货款不发积分";
    // 收款成功后即更新本地状态，刷新失败也不重新开放核款。
    orders.value = orders.value.map((order) =>
      order.id === result.order.id ? result.order : order,
    );
    await select(result.order.id);
    await load();
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
  { title: "收款 / 积分", key: "payment", width: 180 },
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
      <header class="order-list-toolbar">
        <a-tabs
          v-model:active-key="status"
          :animated="false"
          class="order-status-tabs"
          aria-label="订单状态"
        >
          <a-tab-pane
            v-for="item in statusTabs"
            :key="item.key"
            :tab="item.tab"
          />
        </a-tabs>
        <span class="list-count">共 {{ orderTotal }} 笔订单</span>
      </header>
      <AdminFilterBar @submit="act(load)">
        <label class="field order-search"
          >搜索订单 / 商品<a-input
            v-model:value="query"
            placeholder="商品名称、规格、订单号或用户编号"
            :disabled="busy" /></label
        ><label class="field"
          >订单状态<AdminSelect v-model="status" :disabled="busy">
            <option value="">全部状态</option>
            <option
              v-for="(label, key) in filterLabels"
              :key="key"
              :value="key"
            >
              {{ label }}
            </option>
          </AdminSelect></label
        ><template #actions
          ><a-button
            html-type="submit"
            type="primary"
            :disabled="busy"
            :loading="busy || listLoading"
            >查询订单</a-button
          >
          <a-button
            v-if="query || status"
            :disabled="busy"
            @click="
              query = '';
              status = '';
            "
            >重置</a-button
          >
        </template>
      </AdminFilterBar>
      <template v-if="!props.financeOnly">
        <div class="order-grid-heading" aria-hidden="true">
          <span>商品 / 购买者</span><span>订单金额</span><span>履约与支付</span
          ><span>操作</span>
        </div>
        <a-spin :spinning="busy || listLoading">
          <div class="mall-orders" :aria-busy="busy || listLoading">
            <article
              v-for="o in visible"
              :key="o.id"
              class="mall-order"
              :aria-label="`订单 ${o.number}`"
            >
              <header class="mall-order-meta">
                <time v-if="o.create_time">{{ orderTime(o.create_time) }}</time>
                <span class="mall-order-number"
                  >订单号 <strong>{{ o.number }}</strong></span
                >
                <span class="mall-order-kind">{{
                  o.kind === "package" ? "入驻产品包" : "商品订单"
                }}</span>
              </header>
              <div class="mall-order-body">
                <div class="mall-order-products">
                  <div class="mall-order-buyer">
                    <span>购买者</span><AdminUserName :id="o.user_id" />
                  </div>
                  <div
                    v-for="(line, index) in o.snapshot.lines"
                    :key="index"
                    class="mall-order-product"
                  >
                    <div class="mall-product-image">
                      <img
                        v-if="line.image"
                        :src="imageUrl(line.image, 'thumb')"
                        :alt="line.name"
                        width="64"
                        height="64"
                        loading="lazy"
                      />
                      <ShoppingBag v-else :size="24" aria-hidden="true" />
                    </div>
                    <div class="mall-product-copy">
                      <strong>{{ line.name }}</strong>
                      <span class="cell-note"
                        >{{ line.skuName
                        }}<template v-if="line.quantity !== undefined">
                          · 原购 {{ line.quantity }} 件</template
                        ><a-tag v-if="line.isGift" class="mall-gift"
                          >赠品</a-tag
                        ></span
                      >
                    </div>
                  </div>
                  <p v-if="!o.snapshot.lines?.length" class="cell-note">
                    该订单暂无商品快照，可查看订单详情。
                  </p>
                </div>
                <div class="mall-order-amount">
                  <span class="mall-mobile-label">订单金额</span>
                  <strong>¥{{ money(o.total_cents) }}</strong>
                  <span class="cell-note">{{
                    o.payment_method
                      ? methodNames[o.payment_method] || o.payment_method
                      : "尚未支付"
                  }}</span>
                </div>
                <div class="mall-order-state">
                  <a-tag
                    :color="
                      ['paid', 'awaiting_shipment'].includes(
                        o.fulfillmentState || o.status,
                      )
                        ? 'warning'
                        : statusColor(o.fulfillmentState || o.status)
                    "
                    >{{
                      o.fulfillmentText || labels[o.status] || o.status
                    }}</a-tag
                  >
                  <span class="cell-note">{{
                    paymentLabels[o.payment_state] || o.payment_state
                  }}</span>
                  <span v-if="o.storageRemainingQty" class="cell-note"
                    >寄存剩余 {{ o.storageRemainingQty }} 件</span
                  >
                  <span v-if="o.received_at" class="cell-note"
                    >收货完成 {{ orderTime(o.received_at) }}</span
                  >
                </div>
                <div class="mall-order-actions">
                  <a-button :disabled="busy" @click="act(() => select(o.id))"
                    >查看与处理</a-button
                  >
                </div>
              </div>
            </article>
            <div
              v-if="!visible.length && !listLoading && !busy"
              class="mall-orders-empty"
            >
              <ShoppingBag :size="32" aria-hidden="true" />
              <strong>{{
                query || status ? "暂无符合条件的订单" : "暂无订单"
              }}</strong>
              <span>{{
                query || status
                  ? "请调整搜索条件或切换订单状态。"
                  : "客户提交订单后，将在这里显示。"
              }}</span>
            </div>
          </div>
        </a-spin>
        <a-pagination v-bind="orderPagination" class="mall-order-pagination" />
      </template>
      <div v-else class="table-wrap">
        <a-table
          :columns="orderColumns"
          :data-source="visible || []"
          row-key="id"
          :pagination="orderPagination"
          :scroll="{ x: 880 }"
          size="small"
          :loading="busy || listLoading"
          ><template #bodyCell="{ column, record: o }"
            ><template v-if="column.key === 'order'">
              {{ o.number
              }}<small class="cell-note"
                ><AdminUserName :id="o.user_id" /> ·
                {{ o.kind === "package" ? "入驻产品包" : "商品订单" }}</small
              > </template
            ><template v-else-if="column.key === 'fulfillment'">
              <a-tag :color="statusColor(o.status)">{{
                o.fulfillmentText || labels[o.status] || o.status
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
    <AdminDrawer
      v-if="selected"
      title="订单详情与处理"
      :busy="busy || Boolean(dialog || correcting)"
      wide
      @close="selected = null"
    >
      <div class="order-detail">
        <header class="section-heading">
          <div>
            <small class="cell-note">当前处理订单</small>
            <h3>{{ selected.number }}</h3>
          </div>
        </header>
        <p v-if="selected.snapshot.address" class="delivery-address">
          {{ selected.snapshot.address.recipient }} ·
          {{ selected.snapshot.address.phone }} ·
          {{ selected.snapshot.address.province
          }}{{ selected.snapshot.address.city
          }}{{ selected.snapshot.address.district
          }}{{ selected.snapshot.address.detail }}
        </p>
        <section class="detail-section">
          <h4>收款与扣款记录</h4>
          <p>
            应收：¥{{ money(selected.total_cents) }} ·
            {{
              paymentLabels[selected.payment_state] || selected.payment_state
            }}
            ·
            {{
              selected.payment_method
                ? methodNames[selected.payment_method] ||
                  selected.payment_method
                : "待核实线下收款"
            }}
          </p>
          <p v-if="payment?.receipt">
            收款 / 扣款流水标识：{{
              payment.receipt.transaction_id || "无外部流水"
            }}
            · 金额 ¥{{ money(payment.receipt.cents) }} · 实际到账 / 扣款时间：{{
              payment.receipt.paid_at
            }}
          </p>
          <p v-else>尚无积分扣款记录；客户足额积分支付后才可履约。</p>
          <p v-if="selected.kind === 'package'">
            产品包由客户扣积分购买后开通身份。
          </p>
          <a-button
            v-if="canCollect"
            type="primary"
            :disabled="busy"
            @click="openReceipt"
          >
            登记订单积分支付记录
          </a-button>
        </section>
        <template v-if="!props.financeOnly">
          <section class="detail-section">
            <p v-if="selected.storageRemainingQty">
              剩余寄存
              {{ selected.storageRemainingQty }}
              件；留仓货品须通过提货申请安排配送。
            </p>
            <p v-for="pickup in activePickups" :key="pickup.id">
              {{ pickup.status === "review" ? "待审核配送" : "待发货配送" }}
              {{ pickup.number }}
              <a-button @click="emit('open-pickup', pickup)"
                >办理本次配送</a-button
              >
            </p>
            <a-button
              html-type="button"
              type="primary"
              v-if="canShip"
              :disabled="busy"
              @click="openShipment"
              >登记品牌仓发货</a-button
            >
            <p
              v-else-if="!activePickups.length && !selected.storageRemainingQty"
            >
              当前没有可直接发货数量；未付款订单须先由客户扣积分支付。
            </p>
            <p v-if="canShip">
              本入口处理原订单未分配到有效配送单的货品，包含取消配送后保留的新购未发货商品。
            </p>
          </section>
          <section class="detail-section">
            <h4>包裹与收货记录</h4>
            <div v-for="s in selected.shipments" :key="s.id" class="parcel">
              <div>
                {{ s.carrier }} · {{ s.tracking }}
                <small class="cell-note"
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
        </template>
        <details class="detail-section payment-evidence">
          <summary>查看历史交易与核查记录（只读）</summary>
          <h4>历史支付尝试与异常到账依据</h4>
          <div class="table-wrap">
            <a-table
              :columns="attemptColumns"
              :data-source="payment?.attempts || []"
              row-key="id"
              :pagination="false"
              :scroll="{ x: 900 }"
              size="small"
              ><template #bodyCell="{ column, record: a }"
                ><template v-if="column.key === 'number'">{{
                  a.number
                }}</template
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
          <h4>历史交易事件与核查证据</h4>
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
      </div>
    </AdminDrawer>
    <AdminDialog
      v-if="selected && dialog"
      drawer
      :title="dialog === 'ship' ? '登记品牌仓发货' : '登记订单积分支付记录'"
      :busy="busy"
      :wide="dialog === 'ship'"
      @close="!busy && (dialog = '')"
    >
      <p>当前订单：{{ selected.number }}</p>
      <AdminForm
        v-if="dialog === 'receipt'"
        class="dialog-fields"
        @submit="collect"
      >
        <p>
          原订单应收 ¥{{
            money(selected.total_cents)
          }}；仅在公司实际到账后核款，普通货款不发积分。
        </p>
        <label class="field"
          >到账货款（元）<a-input
            v-model:value="receipt.amount"
            pattern="[0-9]+([.][0-9]{1,2})?"
            inputmode="decimal"
            required
            :disabled="busy"
        /></label>
        <label class="field"
          >线下公司收款渠道<AdminSelect
            v-model="receipt.sourceChannel"
            :disabled="busy"
          >
            <option value="bank">公司银行账户</option>
            <option value="wechat_company">公司微信收款（线下）</option>
          </AdminSelect></label
        >
        <label class="field"
          >收款账户标识<a-input
            v-model:value="receipt.accountRef"
            required
            :maxlength="200"
            :disabled="busy"
        /></label>
        <label class="field"
          >原始流水号<a-input
            v-model:value="receipt.externalTransactionId"
            required
            :maxlength="200"
            :disabled="busy"
        /></label>
        <label class="field"
          >实际到账时间<AdminDatePicker
            v-model="receipt.receivedAt"
            type="datetime-local"
            required
            :disabled="busy"
        /></label>
        <label class="field"
          >凭证地址 / 归档编号<a-input
            v-model:value="receipt.evidence"
            required
            :maxlength="1000"
            :disabled="busy"
        /></label>
        <label class="field"
          >核实说明<a-input
            v-model:value="receipt.reason"
            required
            :maxlength="200"
            :disabled="busy"
        /></label>
        <a-checkbox v-model:checked="receipt.verified" required :disabled="busy"
          >我已核实公司实际到账</a-checkbox
        >
        <div class="dialog-actions">
          <a-button html-type="button" :disabled="busy" @click="dialog = ''"
            >取消</a-button
          >
          <a-button
            html-type="submit"
            type="primary"
            :disabled="busy || !receipt.verified"
            :loading="busy"
            >确认普通订单收款</a-button
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
.order-list-toolbar {
  display: flex;
  align-items: center;
  gap: 20px;
  min-width: 0;
}
.order-list-toolbar .list-count {
  flex-shrink: 0;
  padding-bottom: 16px;
}
.order-status-tabs {
  flex: 1;
  min-width: 0;
}
.order-status-tabs :deep(.ant-tabs-nav) {
  margin-bottom: 0;
}
.order-status-tabs :deep(.ant-tabs-tab) {
  padding: 4px 0 16px;
  font-weight: 500;
}
.order-status-tabs :deep(.ant-tabs-tab + .ant-tabs-tab) {
  margin-left: 28px;
}
.order-status-tabs :deep(.ant-tabs-content-holder) {
  display: none;
}
.order-search {
  --admin-filter-width: 380px;
}
.order-grid-heading,
.mall-order-body {
  display: grid;
  grid-template-columns:
    minmax(0, 1fr) minmax(120px, 0.32fr) minmax(160px, 0.45fr)
    128px;
  gap: 20px;
}
.order-grid-heading {
  padding: 0 20px 12px;
  color: var(--admin-muted);
  font-size: 13px;
}
.mall-orders {
  display: grid;
  gap: 16px;
}
.mall-order {
  min-width: 0;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  overflow: hidden;
}
.mall-order-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 24px;
  padding: 12px 20px;
  background: #f5f6f8;
  color: var(--admin-muted);
  font-size: 12px;
}
.mall-order-number {
  min-width: 0;
  overflow-wrap: anywhere;
}
.mall-order-number strong {
  margin-left: 6px;
  font-weight: 500;
  color: var(--admin-ink);
}
.mall-order-kind {
  margin-left: auto;
  flex-shrink: 0;
}
.mall-order-body {
  padding: 20px;
}
.mall-order-products,
.mall-product-copy,
.mall-order-state {
  min-width: 0;
  overflow-wrap: anywhere;
}
.mall-order-buyer {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 10px;
  margin-bottom: 14px;
  font-size: 12px;
}
.mall-order-buyer > span:first-child {
  color: var(--admin-muted);
}
.mall-order-product {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.mall-order-product + .mall-order-product {
  margin-top: 16px;
}
.mall-product-image {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  flex: 0 0 64px;
  overflow: hidden;
  border-radius: 4px;
  background: #f7f8fa;
  color: #969daa;
}
.mall-product-image img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.mall-product-copy strong {
  font-size: 14px;
  font-weight: 500;
  line-height: 1.6;
}
.mall-gift {
  margin-left: 8px;
  color: var(--admin-accent);
  background: var(--admin-accent-soft);
  border: 0;
}
.mall-order-amount > strong {
  font-size: 18px;
  font-weight: 600;
}
.mall-mobile-label {
  display: none;
}
.mall-order-state :deep(.ant-tag) {
  margin: 0;
}
.mall-order-actions {
  text-align: right;
}
.mall-order-actions :deep(.ant-btn) {
  color: var(--admin-accent);
  border-color: #e5cbd1;
  background: #fff;
  box-shadow: none;
}
.mall-order-pagination {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px 0;
  margin-top: 24px;
}
.mall-orders-empty {
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 60px 20px;
  color: var(--admin-muted);
  text-align: center;
}
.mall-orders-empty strong {
  color: var(--admin-ink);
  font-weight: 500;
}
.mall-orders-empty > span {
  font-size: 13px;
}
@media (min-width: 901px) and (max-width: 1200px) {
  .order-grid-heading,
  .mall-order-body {
    grid-template-columns: minmax(0, 1fr) 110px 140px 110px;
    gap: 16px;
  }
}
@media (max-width: 700px) {
  .order-list-toolbar {
    display: block;
  }
  .order-list-toolbar .list-count {
    display: block;
    padding: 12px 0 0;
  }
  .order-grid-heading {
    display: none;
  }
  .mall-order-meta {
    padding: 12px;
    gap: 6px 12px;
  }
  .mall-order-number {
    flex-basis: 100%;
    order: 1;
  }
  .mall-order-body {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    padding: 16px 12px;
    gap: 16px;
  }
  .mall-order-products,
  .mall-order-actions {
    grid-column: 1 / -1;
  }
  .mall-order-buyer {
    margin-bottom: 12px;
  }
  .mall-order-amount,
  .mall-order-state {
    padding-top: 12px;
    border-top: 1px solid var(--admin-border);
  }
  .mall-mobile-label {
    display: block;
    margin-bottom: 4px;
    color: var(--admin-muted);
    font-size: 12px;
  }
  .mall-order-actions :deep(.ant-btn) {
    min-height: 44px;
  }
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
