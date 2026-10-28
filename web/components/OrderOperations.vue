<script setup lang="ts">
import { useAdminList } from "../useAdminList";
import { computed, ref, watch } from "vue";
import { api } from "../client";
import AdminDialog from "./AdminDialog.vue";
import AdminForm from "./AdminForm.vue";
import AdminSelect from "./AdminSelect.vue";
import FeedbackToast from "./FeedbackToast.vue";

interface Shipping {
  provinces: string[];
  feeCents: number;
  instructions: string;
  pendingMinutes: number;
}
interface SnapshotLine {
  skuId: number;
  productId?: number;
  name: string;
  skuName: string;
  quantity: number;
  unitCents: number;
  principalCents?: number | null;
  unit?: string;
}
interface Order {
  id: number;
  number: string;
  user_id: number;
  kind: string;
  status: string;
  fulfillmentText?: string;
  payment_method: string | null;
  payment_state: string;
  total_cents: number;
  goods_cents: number;
  shipping_cents: number;
  create_time: string;
  expires_at: string;
  received_at: string | null;
  snapshot: {
    identity?: string;
    principalId?: number | null;
    address?: {
      recipient: string;
      phone: string;
      province: string;
      city: string;
      district: string;
      detail: string;
    };
    lines?: SnapshotLine[];
    shippingInstructions?: string;
    pendingMinutes?: number;
    [key: string]: unknown;
  };
  lines: {
    id: number;
    sku_id: number;
    quantity: number;
    shipped_qty: number;
    received_qty: number;
    refunded_qty: number;
    refund_held: number;
  }[];
  shipments: {
    id: number;
    carrier: string;
    tracking: string;
    shipped_at: string;
    received_at: string | null;
    lines: unknown;
  }[];
}
const props = defineProps<{ section?: string }>();
const active = ref<"orders" | "shipping">("orders");
const error = ref(""),
  notice = ref(""),
  loading = ref(false),
  saving = ref(false);
const selectedId = ref(0);
const search = ref(""),
  status = ref(""),
  paymentState = ref("");
const shippingLoaded = ref(false),
  shippingConfigured = ref(false);
const editingShipping = ref(false);
const savedShipping = ref<Shipping | null>(null);
const form = ref({
  provinces: "",
  fee: "",
  instructions: "",
  pendingMinutes: "",
  reason: "",
});
const selected = computed(() =>
  orders.value.find((order) => order.id === selectedId.value),
);
const orderStates: Record<string, string> = {
  pending: "待核款",
  paid: "待发货",
  shipping: "配送中",
  shipped: "已发货",
  received: "已收货",
  completed: "已完成",
  closed: "已关闭",
  cancelled: "已取消",
  refunded: "已退款",
  partially_refunded: "部分退款",
};
const paymentStates: Record<string, string> = {
  unpaid: "待核款",
  paying: "历史交易处理中",
  processing: "处理中",
  confirming: "确认中",
  pending: "待确认",
  paid: "已收款 / 积分已扣款",
  succeeded: "已收款 / 积分已扣款",
  failed: "未收款",
  refunded: "已退款",
  partially_refunded: "部分退款",
  review: "待核实",
  unknown: "结果待确认",
};
const identityNames: Record<string, string> = {
  B: "调肤主理人",
  C: "调肤店主",
  D: "游客",
};
const paymentMethods: Record<string, string> = {
  wechat: "历史微信支付",
  points: "积分订货",
  offline: "线下付款",
  company: "线下公司转款",
  transfer: "线下公司转款",
};
const states = computed(() => Object.keys(orderStates));

const paymentOptions = computed(() => Object.keys(paymentStates));

const {
  rows: orders,
  total: orderTotal,
  pagination: orderPagination,
  loading: listLoading,
  load: loadOrders,
} = useAdminList<Order>(
  "/admin/orders",
  () => ({
    q: search.value,
    status: status.value,
    paymentState: paymentState.value,
  }),
  error,
);
const filtered = computed(() => orders.value);

const money = (cents: number) => `¥${(cents / 100).toFixed(2)}`;
const time = (value: string | null | undefined) =>
  value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
const kindName = (value: string) =>
  value === "goods" ? "普通商品" : value === "package" ? "入驻产品包" : value;
let generation = 0;

function report(e: unknown) {
  error.value = e instanceof Error ? e.message : "请求失败，请重试";
}
async function load() {
  const current = ++generation;
  loading.value = true;
  error.value = "";
  try {
    if (active.value === "shipping") {
      shippingLoaded.value = false;
      const result = await api<Shipping | null>("/admin/shipping-settings");
      if (current !== generation) return;
      savedShipping.value = result;
      shippingConfigured.value = result !== null;
      form.value = {
        provinces: result?.provinces.join("\n") ?? "",
        fee: result ? (result.feeCents / 100).toFixed(2) : "",
        instructions: result?.instructions ?? "",
        pendingMinutes: result ? String(result.pendingMinutes) : "",
        reason: "",
      };
      shippingLoaded.value = true;
    } else {
      const result = await loadOrders();
      if (current !== generation) return;
      orders.value = result;
      if (!result.some((order) => order.id === selectedId.value))
        selectedId.value = 0;
    }
  } catch (e) {
    if (current === generation) report(e);
  } finally {
    if (current === generation) loading.value = false;
  }
}
function navigate(section: "orders" | "shipping") {
  if (loading.value || saving.value) return;
  active.value = section;
  notice.value = "";
  void load();
}
watch(
  () => props.section,
  (value) => {
    active.value =
      value === "shipping" || value === "shipping-settings"
        ? "shipping"
        : "orders";
    notice.value = "";
    void load();
  },
  { immediate: true },
);

function openShipping() {
  const result = savedShipping.value;
  form.value = {
    provinces: result?.provinces.join("\n") ?? "",
    fee: result ? (result.feeCents / 100).toFixed(2) : "",
    instructions: result?.instructions ?? "",
    pendingMinutes: result ? String(result.pendingMinutes) : "",
    reason: "",
  };
  error.value = "";
  editingShipping.value = true;
}
function toCents(value: string) {
  if (!/^\d+(\.\d{1,2})?$/.test(value))
    throw new Error("运费请输入非负金额，最多两位小数");
  const [yuan, fraction = ""] = value.split(".");
  const cents = Number(yuan) * 100 + Number(fraction.padEnd(2, "0"));
  if (!Number.isSafeInteger(cents) || cents > 10000000)
    throw new Error("运费不得超过 100,000.00 元");
  return cents;
}
async function saveShipping() {
  if (saving.value || loading.value || !shippingLoaded.value) return;
  saving.value = true;
  error.value = "";
  notice.value = "";
  try {
    const provinces = [
      ...new Set(
        form.value.provinces
          .split(/[\n,，、;；]+/)
          .map((value) => value.trim())
          .filter(Boolean),
      ),
    ];
    if (
      !provinces.length ||
      provinces.length > 40 ||
      provinces.some((value) => value.length > 30)
    )
      throw new Error("请填写 1～40 个配送省份，每个名称最多 30 字");
    const pending = form.value.pendingMinutes.trim();
    if (!/^\d+$/.test(pending) || Number(pending) < 5 || Number(pending) > 1440)
      throw new Error("待付款有效期须为 5～1440 的整数分钟");
    const instructions = form.value.instructions.trim(),
      reason = form.value.reason.trim();
    if (!instructions || instructions.length > 2000)
      throw new Error("请填写发货说明，最多 2000 字");
    if (!reason || reason.length > 200)
      throw new Error("请填写修改原因，最多 200 字");
    const result = await api<Shipping>("/admin/shipping-settings", {
      method: "PUT",
      body: {
        provinces,
        feeCents: toCents(form.value.fee.trim()),
        instructions,
        pendingMinutes: Number(pending),
        reason,
      },
    });
    form.value = {
      provinces: result.provinces.join("\n"),
      fee: (result.feeCents / 100).toFixed(2),
      instructions: result.instructions,
      pendingMinutes: String(result.pendingMinutes),
      reason: "",
    };
    savedShipping.value = result;
    shippingConfigured.value = true;
    editingShipping.value = false;
    notice.value = "配送配置已保存；历史订单的成交依据保留。";
  } catch (e) {
    report(e);
  } finally {
    saving.value = false;
  }
}
const snapshotColumns = [
  { title: "商品 / 规格", key: "product", width: 180 },
  { title: "数量", key: "quantity", width: 180 },
  { title: "成交单价", key: "unit", width: 180 },
  { title: "当时主理人单价", key: "principal", width: 180 },
  { title: "货款小计", key: "subtotal", width: 180 },
];
const fulfillmentColumns = [
  { title: "SKU / 行 ID", key: "sku", width: 180 },
  { title: "订购", key: "quantity", width: 180 },
  { title: "已发货", key: "shipped", width: 180 },
  { title: "已收货", key: "received", width: 180 },
  { title: "已退款", key: "refunded", width: 180 },
  { title: "退款占用", key: "held", width: 180 },
];
const orderColumns = [
  { title: "订单号 / 时间", key: "order", width: 230 },
  { title: "购买者 / 类型", key: "buyer", width: 180 },
  { title: "收货人", key: "recipient", width: 180 },
  { title: "订单状态", key: "status", width: 180 },
  { title: "收款 / 积分状态", key: "payment", width: 180 },
  { title: "订单金额", key: "amount", width: 120 },
  { title: "详情", key: "actions", width: 170, fixed: "right" as const },
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
  <section class="order-operations" :aria-busy="loading || saving">
    <a-space
      v-if="!props.section"
      class="operation-tabs"
      wrap
      aria-label="订单管理"
    >
      <a-button
        html-type="button"
        :type="active === 'orders' ? 'primary' : 'default'"
        :disabled="loading || saving"
        @click="navigate('orders')"
        >订单记录</a-button
      >
      <a-button
        html-type="button"
        :type="active === 'shipping' ? 'primary' : 'default'"
        :disabled="loading || saving"
        @click="navigate('shipping')"
        >配送配置</a-button
      >
    </a-space>
    <FeedbackToast v-model:notice="notice" v-model:error="error" />
    <a-spin v-if="loading" tip="正在读取…" />
    <template v-else-if="active === 'shipping'">
      <a-card size="small" v-if="shippingLoaded" class="order-detail">
        <div class="section-heading">
          <h3>{{ shippingConfigured ? "当前配送配置" : "配送尚未配置" }}</h3>
          <a-button
            v-if="shippingConfigured"
            html-type="button"
            type="primary"
            :disabled="saving"
            @click="openShipping"
          >
            编辑配送配置
          </a-button>
        </div>
        <dl v-if="savedShipping" class="facts">
          <div>
            <dt>配送省份</dt>
            <dd>{{ savedShipping.provinces.join("、") }}</dd>
          </div>
          <div>
            <dt>固定运费</dt>
            <dd>{{ money(savedShipping.feeCents) }}</dd>
          </div>
          <div>
            <dt>待付款有效期</dt>
            <dd>{{ savedShipping.pendingMinutes }} 分钟</dd>
          </div>
          <div>
            <dt>发货说明</dt>
            <dd>{{ savedShipping.instructions }}</dd>
          </div>
        </dl>
        <div v-else class="shipping-empty">
          <p>设置配送省份、运费及发货说明后，购买者即可下单。</p>
          <a-button
            html-type="button"
            type="primary"
            :disabled="saving"
            @click="openShipping"
          >
            新增配送配置
          </a-button>
        </div>
      </a-card>
      <p v-else class="empty">暂未读取到配送配置，请刷新重试。</p>
    </template>
    <template v-else-if="selected">
      <a-button
        html-type="button"
        type="link"
        class="back-orders"
        @click="selectedId = 0"
      >
        ‹ 返回订单列表
      </a-button>
      <a-card size="small" class="order-detail">
        <div class="detail-heading">
          <div>
            <small class="cell-note">订单编号</small>
            <h3>{{ selected.number }}</h3>
          </div>
          <a-tag :color="statusColor(selected.status)">{{
            selected.fulfillmentText ||
            orderStates[selected.status] ||
            selected.status
          }}</a-tag>
        </div>
        <dl class="facts">
          <div>
            <dt>订单 ID / 购买者 ID</dt>
            <dd>{{ selected.id }} / {{ selected.user_id }}</dd>
          </div>
          <div>
            <dt>业务类型</dt>
            <dd>{{ kindName(selected.kind) }}</dd>
          </div>
          <div>
            <dt>购买身份</dt>
            <dd>
              {{
                identityNames[selected.snapshot.identity ?? ""] ??
                selected.snapshot.identity ??
                "—"
              }}
            </dd>
          </div>
          <div>
            <dt>归属主理人 ID</dt>
            <dd>{{ selected.snapshot.principalId ?? "—" }}</dd>
          </div>
          <div>
            <dt>收款 / 积分状态</dt>
            <dd>
              {{
                paymentStates[selected.payment_state] ?? selected.payment_state
              }}
            </dd>
          </div>
          <div>
            <dt>付款方式</dt>
            <dd>
              {{
                selected.payment_method
                  ? (paymentMethods[selected.payment_method] ??
                    selected.payment_method)
                  : "尚未选择"
              }}
            </dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{{ time(selected.create_time) }}</dd>
          </div>
          <div>
            <dt>待付款截止</dt>
            <dd>{{ time(selected.expires_at) }}</dd>
          </div>
          <div>
            <dt>收货完成时间</dt>
            <dd>{{ time(selected.received_at) }}</dd>
          </div>
        </dl>
        <section class="snapshot-section">
          <h3>收货地址快照</h3>
          <template v-if="selected.snapshot.address"
            ><p>
              {{ selected.snapshot.address.recipient }}
              {{ selected.snapshot.address.phone }}
            </p>
            <p>
              {{ selected.snapshot.address.province
              }}{{ selected.snapshot.address.city
              }}{{ selected.snapshot.address.district
              }}{{ selected.snapshot.address.detail }}
            </p></template
          >
          <p v-else>该订单未保存收货地址。</p>
        </section>
        <section class="snapshot-section">
          <h3>商品成交快照</h3>
          <div class="table-wrap">
            <a-table
              :columns="snapshotColumns"
              :data-source="selected.snapshot.lines || []"
              row-key="skuId"
              :pagination="false"
              :scroll="{ x: 720 }"
              size="small"
              ><template #bodyCell="{ column, record: line }"
                ><template v-if="column.key === 'product'">
                  {{ line.name
                  }}<small class="cell-note"
                    >{{ line.skuName }} · SKU {{ line.skuId }}</small
                  > </template
                ><template v-else-if="column.key === 'quantity'"
                  >{{ line.quantity }}{{ line.unit }}</template
                ><template v-else-if="column.key === 'unit'">{{
                  money(line.unitCents)
                }}</template
                ><template v-else-if="column.key === 'principal'">
                  {{
                    line.principalCents != null
                      ? money(line.principalCents)
                      : "—"
                  }} </template
                ><template v-else-if="column.key === 'subtotal'">{{
                  money(line.unitCents * line.quantity)
                }}</template></template
              ></a-table
            >
          </div>
        </section>
        <dl class="totals">
          <div>
            <dt>商品金额</dt>
            <dd>{{ money(selected.goods_cents) }}</dd>
          </div>
          <div>
            <dt>运费</dt>
            <dd>{{ money(selected.shipping_cents) }}</dd>
          </div>
          <div>
            <dt>订单总额</dt>
            <dd>{{ money(selected.total_cents) }}</dd>
          </div>
        </dl>
        <section
          v-if="selected.snapshot.shippingInstructions"
          class="snapshot-section"
        >
          <h3>当时发货说明</h3>
          <p class="preserve-lines">
            {{ selected.snapshot.shippingInstructions }}
          </p>
        </section>
        <section class="snapshot-section">
          <h3>商品履约数量</h3>
          <div class="table-wrap">
            <a-table
              :columns="fulfillmentColumns"
              :data-source="selected.lines || []"
              row-key="id"
              :pagination="false"
              :scroll="{ x: 660 }"
              size="small"
              ><template #bodyCell="{ column, record: line }"
                ><template v-if="column.key === 'sku'"
                  >{{ line.sku_id }} / {{ line.id }}</template
                ><template v-else-if="column.key === 'quantity'">{{
                  line.quantity
                }}</template
                ><template v-else-if="column.key === 'shipped'">{{
                  line.shipped_qty
                }}</template
                ><template v-else-if="column.key === 'received'">{{
                  line.received_qty
                }}</template
                ><template v-else-if="column.key === 'refunded'">{{
                  line.refunded_qty
                }}</template
                ><template v-else-if="column.key === 'held'">{{
                  line.refund_held
                }}</template></template
              ></a-table
            >
          </div>
        </section>
        <section class="snapshot-section">
          <h3>发货记录</h3>
          <p v-if="!selected.shipments.length">暂无发货记录。</p>
          <article
            v-for="shipment in selected.shipments"
            :key="shipment.id"
            class="shipment"
          >
            <strong>{{ shipment.carrier }}</strong>
            <p>运单号：{{ shipment.tracking }}</p>
            <p>
              发货：{{ time(shipment.shipped_at) }} · 收货：{{
                time(shipment.received_at)
              }}
            </p>
          </article>
        </section>
        <details class="snapshot-section">
          <summary>查看完整成交快照</summary>
          <pre>{{ JSON.stringify(selected.snapshot, null, 2) }}</pre>
        </details>
      </a-card>
    </template>
    <template v-else>
      <a-card size="small" class="order-list">
        <AdminForm class="order-filters" @submit="() => {}">
          <label class="field"
            >搜索订单 / 购买者<a-input
              v-model:value="search"
              type="search"
              placeholder="商品名称、规格、订单号或购买者"
              :disabled="loading || saving" /></label
          ><label class="field"
            >订单状态<AdminSelect
              v-model="status"
              :disabled="loading || saving"
            >
              <option value="">全部状态</option>
              <option v-for="value in states" :key="value" :value="value">
                {{ orderStates[value] ?? value }}
              </option>
            </AdminSelect></label
          ><label class="field"
            >收款 / 积分状态<AdminSelect
              v-model="paymentState"
              :disabled="loading || saving"
            >
              <option value="">全部状态</option>
              <option
                v-for="value in paymentOptions"
                :key="value"
                :value="value"
              >
                {{ paymentStates[value] ?? value }}
              </option>
            </AdminSelect></label
          >
        </AdminForm>
        <p class="list-caption">
          共 {{ orderTotal }} 笔订单，本页 {{ orders.length }} 笔。
        </p>
        <div class="table-wrap order-table">
          <a-table
            :columns="orderColumns"
            :data-source="filtered || []"
            row-key="id"
            :pagination="orderPagination"
            :scroll="{ x: 1000 }"
            size="small"
            :loading="loading || listLoading"
            ><template #bodyCell="{ column, record: order }"
              ><template v-if="column.key === 'order'">
                {{ order.number
                }}<small class="cell-note">{{
                  time(order.create_time)
                }}</small> </template
              ><template v-else-if="column.key === 'buyer'">
                {{ order.user_id
                }}<small class="cell-note">{{
                  kindName(order.kind)
                }}</small> </template
              ><template v-else-if="column.key === 'recipient'">{{
                order.snapshot.address?.recipient ?? "—"
              }}</template
              ><template v-else-if="column.key === 'status'">
                <a-tag :color="statusColor(order.status)">{{
                  order.fulfillmentText ||
                  orderStates[order.status] ||
                  order.status
                }}</a-tag> </template
              ><template v-else-if="column.key === 'payment'">
                <a-tag :color="statusColor(order.payment_state)">{{
                  paymentStates[order.payment_state] ?? order.payment_state
                }}</a-tag> </template
              ><template v-else-if="column.key === 'amount'">{{
                money(order.total_cents)
              }}</template
              ><template v-else-if="column.key === 'actions'">
                <a-button
                  html-type="button"
                  type="link"
                  @click="selectedId = order.id"
                >
                  查看详情
                </a-button>
              </template></template
            ></a-table
          >
        </div>
      </a-card>
    </template>
    <AdminDialog
      v-if="editingShipping"
      drawer
      title="配送与待付款配置"
      :busy="saving"
      @close="!saving && (editingShipping = false)"
    >
      <AdminForm class="shipping-form config-form" @submit="saveShipping">
        <p v-if="!shippingConfigured">
          配送尚未配置，请完整填写后保存。未配置时购买端无法下单。
        </p>
        <fieldset :disabled="saving">
          <label class="field"
            >配送省份<a-textarea
              v-model:value="form.provinces"
              :rows="6"
              required
              placeholder="每行填写一个完整省份名称，如：浙江省"
              :disabled="loading || saving"
            /><small class="cell-note"
              >须与收货地址省份一致；支持按行或逗号分隔。不在范围内的地址不可下单。</small
            ></label
          >
          <div class="shipping-fields">
            <label class="field"
              >固定运费（元）<a-input
                v-model:value="form.fee"
                required
                inputmode="decimal"
                pattern="[0-9]+(\.[0-9]{1,2})?"
                placeholder="0.00"
                :disabled="loading || saving"
              /><small class="cell-note"
                >0 表示包邮；最多两位小数。</small
              ></label
            ><label class="field"
              >待付款有效期（分钟）<a-input
                v-model:value="form.pendingMinutes"
                required
                inputmode="numeric"
                pattern="[0-9]+"
                placeholder="5～1440"
                :disabled="loading || saving"
              /><small class="cell-note">范围 5～1440 分钟。</small></label
            >
          </div>
          <label class="field"
            >发货说明<a-textarea
              v-model:value="form.instructions"
              required
              :maxlength="2000"
              :rows="4"
              placeholder="填写真实发货安排及配送说明"
              :disabled="loading || saving"
            />
          </label>
          <label class="field"
            >修改原因<a-input
              v-model:value="form.reason"
              required
              :maxlength="200"
              placeholder="用于操作记录与后续核对"
              :disabled="loading || saving"
          /></label>
        </fieldset>
        <div class="dialog-actions">
          <a-button
            html-type="button"
            :disabled="saving"
            @click="editingShipping = false"
          >
            取消
          </a-button>
          <a-button
            html-type="submit"
            type="primary"
            class="save-shipping"
            :disabled="saving"
            :loading="saving"
          >
            {{ saving ? "保存中…" : "保存配送配置" }}
          </a-button>
        </div>
      </AdminForm>
    </AdminDialog>
  </section>
</template>

<style scoped>
.order-operations {
  min-width: 0;
}
.operation-tabs {
  margin-bottom: 16px;
}
.shipping-empty {
  display: grid;
  justify-items: start;
  gap: 16px;
}
.section-heading,
.detail-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin: 0 0 16px;
}
.section-heading > div,
.detail-heading > div,
.order-list,
.order-detail,
.table-wrap {
  min-width: 0;
}
h3 {
  margin: 0;
  font-size: 15px;
  line-height: 1.5;
}
.shipping-form,
.shipping-form fieldset {
  display: grid;
  gap: 16px;
}
.shipping-form fieldset {
  min-width: 0;
  padding: 0;
  margin: 0;
  border: 0;
}
.field {
  display: grid;
  align-content: start;
  gap: 6px;
  min-width: 0;
}
.field :deep(.ant-input-number) {
  width: 100%;
}
.shipping-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.order-filters {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr;
  align-items: end;
  gap: 12px;
}
.cell-note {
  display: block;
  margin-top: 4px;
  color: var(--admin-muted);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.5;
}
.list-caption {
  margin: 12px 0;
  color: var(--admin-muted);
  font-size: 12px;
}
.back-orders {
  margin-bottom: 12px;
}
.detail-heading h3 {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
}
.facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin: 16px 0;
}
.facts dt,
.totals dt {
  color: var(--admin-muted);
  font-size: 12px;
}
.facts dd {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
}
.snapshot-section {
  min-width: 0;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--admin-border);
}
.snapshot-section h3 {
  margin-bottom: 12px;
}
.snapshot-section p {
  margin: 8px 0;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.totals {
  display: grid;
  gap: 8px;
  max-width: 320px;
  margin: 20px 0 0 auto;
}
.totals > div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}
.totals dd {
  margin: 0;
}
.totals > :last-child {
  padding-top: 12px;
  border-top: 1px solid var(--admin-border);
  color: var(--admin-accent);
  font-weight: 600;
}
.preserve-lines {
  white-space: pre-line;
}
.shipment {
  padding: 12px 0;
  border-bottom: 1px solid var(--admin-border);
}
.snapshot-section summary {
  color: var(--admin-accent);
  cursor: pointer;
}
.snapshot-section pre {
  max-width: 100%;
  padding: 12px;
  background: var(--admin-bg);
  font-size: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
}
@media (max-width: 650px) {
  .shipping-fields,
  .order-filters {
    grid-template-columns: 1fr;
  }
  .facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
}
</style>
