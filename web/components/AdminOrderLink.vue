<script setup lang="ts">
import { ref, onScopeDispose } from "vue";
import { api } from "../client";
import AdminDrawer from "./AdminDrawer.vue";
import AdminUserName from "./AdminUserName.vue";
const props = defineProps<{ id: number; label?: string }>();
const emit = defineEmits<{ "open-change": [open: boolean] }>();
interface Address {
  recipient: string;
  phone: string;
  province: string;
  city: string;
  district: string;
  detail: string;
}
interface SnapshotLine {
  skuId: number;
  name: string;
  skuName: string;
  unit?: string;
  isGift?: boolean;
}
interface OrderLine {
  id: number;
  sku_id: number;
  quantity: number;
  unit_cents: number;
  is_gift: boolean;
  shipped_qty: number;
  received_qty: number;
  refunded_qty: number;
}
interface Order {
  id: number;
  number: string;
  user_id: number;
  kind: string;
  status: string;
  payment_state: string;
  payment_method: string;
  total_cents: number;
  goods_cents: number;
  shipping_cents: number;
  create_time?: string;
  fulfillmentText?: string;
  lines: OrderLine[];
  snapshot: { address?: Address; lines: SnapshotLine[] };
  shipments: {
    id: number;
    carrier: string;
    tracking: string;
    shipped_at: string;
    received_at?: string;
  }[];
}
const open = ref(false),
  loading = ref(false),
  error = ref(""),
  order = ref<Order>();
let controller: AbortController | undefined;
const statusNames: Record<string, string> = {
  pending: "待支付",
  paid: "已支付",
  shipped: "已发货",
  received: "已收货",
  completed: "已完成",
  cancelled: "已取消",
  refunded: "已退款",
  partially_refunded: "部分退款",
  unpaid: "未支付",
  closed: "已关闭",
};
const money = (value: number) => (value / 100).toFixed(2);
const columns = [
  { title: "商品 / 规格", key: "product" },
  { title: "数量", dataIndex: "quantity", width: 70 },
  { title: "成交单价", key: "price", width: 100 },
  { title: "履约数量", key: "fulfillment", width: 180 },
];
function snapshot(line: OrderLine) {
  return order.value?.snapshot.lines.find(
    (item) =>
      item.skuId === line.sku_id &&
      Boolean(item.isGift) === Boolean(line.is_gift),
  );
}
async function load() {
  controller?.abort();
  const current = new AbortController();
  controller = current;
  loading.value = true;
  error.value = "";
  order.value = undefined;
  try {
    const result = await api<{ order: Order }>(
      `/admin/orders/${props.id}/fulfillment`,
      { signal: current.signal },
    );
    if (!current.signal.aborted) order.value = result.order;
  } catch (e) {
    if (!current.signal.aborted)
      error.value = e instanceof Error ? e.message : "订单详情读取失败";
  } finally {
    if (!current.signal.aborted) loading.value = false;
  }
}
function show() {
  open.value = true;
  emit("open-change", true);
  void load();
}
function close() {
  controller?.abort();
  open.value = false;
  emit("open-change", false);
}
onScopeDispose(() => {
  controller?.abort();
  if (open.value) emit("open-change", false);
});
</script>
<template>
  <a-button type="link" size="small" class="admin-order-link" @click="show">{{
    label || `原订单 #${id} · 查看详情`
  }}</a-button>
  <AdminDrawer v-if="open" title="原订单详情" wide @close="close">
    <p v-if="loading" role="status">正在读取订单详情…</p>
    <div v-else-if="error" role="alert">
      <p>{{ error }}</p>
      <a-button @click="load">重试读取订单</a-button>
    </div>
    <div v-else-if="order" class="linked-order-detail">
      <header>
        <div>
          <span class="linked-order-caption">订单编号</span>
          <h3>{{ order.number }}</h3>
        </div>
        <a-tag>{{
          order.fulfillmentText || statusNames[order.status] || order.status
        }}</a-tag>
      </header>
      <a-descriptions title="订单信息" :column="{ xs: 1, sm: 2 }" size="small">
        <a-descriptions-item label="购买用户"
          ><AdminUserName :id="order.user_id"
        /></a-descriptions-item>
        <a-descriptions-item label="订单类型">{{
          order.kind === "package" ? "店主权益包" : "商品订单"
        }}</a-descriptions-item>
        <a-descriptions-item label="订单金额"
          >¥{{ money(order.total_cents) }}</a-descriptions-item
        >
        <a-descriptions-item label="支付状态">{{
          statusNames[order.payment_state] || order.payment_state
        }}</a-descriptions-item>
        <a-descriptions-item label="支付方式">{{
          order.payment_method === "points"
            ? "积分支付"
            : order.payment_method === "offline"
              ? "线下付款"
              : order.payment_method === "wechat"
                ? "微信支付"
                : "待确认"
        }}</a-descriptions-item>
        <a-descriptions-item v-if="order.create_time" label="下单时间">{{
          new Date(order.create_time).toLocaleString("zh-CN")
        }}</a-descriptions-item>
      </a-descriptions>
      <a-descriptions
        title="订单收货信息"
        :column="{ xs: 1, sm: 2 }"
        size="small"
      >
        <template v-if="order.snapshot.address"
          ><a-descriptions-item label="收货人">{{
            order.snapshot.address.recipient
          }}</a-descriptions-item
          ><a-descriptions-item label="联系电话">{{
            order.snapshot.address.phone
          }}</a-descriptions-item
          ><a-descriptions-item label="收货地址" :span="2"
            >{{ order.snapshot.address.province
            }}{{ order.snapshot.address.city
            }}{{ order.snapshot.address.district
            }}{{ order.snapshot.address.detail }}</a-descriptions-item
          ></template
        >
        <a-descriptions-item v-else label="收货地址"
          >该订单未登记直接配送地址，请按提货单核对</a-descriptions-item
        >
      </a-descriptions>
      <section>
        <h4>成交商品</h4>
        <a-table
          :columns="columns"
          :data-source="order.lines || []"
          row-key="id"
          size="small"
          :pagination="false"
          :scroll="{ x: 640 }"
          ><template #bodyCell="{ column, record: line }"
            ><template v-if="column.key === 'product'"
              >{{ snapshot(line)?.name || `商品规格 #${line.sku_id}`
              }}<a-tag v-if="line.is_gift">赠品</a-tag
              ><small class="linked-order-caption">{{
                snapshot(line)?.skuName
              }}</small></template
            ><template v-else-if="column.key === 'price'"
              >¥{{ money(line.unit_cents) }}</template
            ><template v-else-if="column.key === 'fulfillment'"
              >已发 {{ line.shipped_qty || 0 }} / 已收
              {{ line.received_qty || 0 }} / 已退
              {{ line.refunded_qty || 0 }}</template
            ></template
          ></a-table
        >
      </section>
      <section>
        <h4>发货记录</h4>
        <a-descriptions
          v-for="shipment in order.shipments"
          :key="shipment.id"
          bordered
          size="small"
          :column="{ xs: 1, sm: 2 }"
          class="linked-shipment"
          ><a-descriptions-item label="快递公司">{{
            shipment.carrier
          }}</a-descriptions-item
          ><a-descriptions-item label="快递单号">{{
            shipment.tracking
          }}</a-descriptions-item
          ><a-descriptions-item label="发货时间">{{
            shipment.shipped_at
          }}</a-descriptions-item
          ><a-descriptions-item label="收货时间">{{
            shipment.received_at || "尚未收货"
          }}</a-descriptions-item></a-descriptions
        >
        <p v-if="!order.shipments?.length" class="linked-order-caption">
          暂无发货记录
        </p>
      </section>
    </div>
  </AdminDrawer>
</template>
<style scoped>
.admin-order-link {
  height: auto;
  padding: 0;
  white-space: normal;
  text-align: left;
  overflow-wrap: anywhere;
}
.linked-order-detail {
  display: grid;
  gap: 28px;
  min-width: 0;
}
.linked-order-detail header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.linked-order-detail h3 {
  margin: 6px 0 0;
  font-size: 17px;
  overflow-wrap: anywhere;
}
.linked-order-detail h4 {
  margin: 0 0 16px;
  font-size: 14px;
}
.linked-order-caption {
  display: block;
  color: var(--admin-muted);
  font-size: 12px;
  line-height: 1.7;
}
.linked-shipment + .linked-shipment {
  margin-top: 12px;
}
</style>
