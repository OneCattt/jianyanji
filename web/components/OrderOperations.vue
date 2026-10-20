<script setup lang="ts">
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
const orders = ref<Order[]>([]),
  selectedId = ref(0);
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
  pending: "待付款",
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
  unpaid: "未支付",
  paying: "支付中",
  processing: "处理中",
  confirming: "确认中",
  pending: "待确认",
  paid: "支付成功",
  succeeded: "支付成功",
  failed: "支付失败",
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
  wechat: "微信支付",
  points: "积分支付",
  offline: "公司转款",
  company: "公司转款",
  transfer: "公司转款",
};
const states = computed(() => [
  ...new Set(orders.value.map((order) => order.status)),
]);
const paymentOptions = computed(() => [
  ...new Set(orders.value.map((order) => order.payment_state)),
]);
const filtered = computed(() => {
  const keyword = search.value.trim().toLowerCase();
  return orders.value.filter(
    (order) =>
      (!status.value || order.status === status.value) &&
      (!paymentState.value || order.payment_state === paymentState.value) &&
      (!keyword ||
        [
          order.number,
          String(order.id),
          String(order.user_id),
          order.snapshot.address?.recipient ?? "",
          order.snapshot.address?.phone ?? "",
        ].some((value) => value.toLowerCase().includes(keyword))),
  );
});
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
      const result = await api<Order[]>("/admin/orders");
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
</script>

<template>
  <section class="order-operations" :aria-busy="loading || saving">
    <nav class="operation-tabs" aria-label="订单管理">
      <button
        :aria-current="active === 'orders' ? 'page' : undefined"
        :disabled="loading || saving"
        @click="navigate('orders')"
      >
        订单记录</button
      ><button
        :aria-current="active === 'shipping' ? 'page' : undefined"
        :disabled="loading || saving"
        @click="navigate('shipping')"
      >
        配送配置
      </button>
    </nav>
    <FeedbackToast
      v-model:notice="notice"
      v-model:error="error"
      :show-error="!editingShipping"
    />
    <p v-if="loading" class="loading" role="status">
      正在读取{{ active === "shipping" ? "配送配置" : "订单记录" }}…
    </p>
    <template v-else-if="active === 'shipping'">
      <article v-if="shippingLoaded" class="surface order-detail">
        <div class="section-heading">
          <h3>{{ shippingConfigured ? "当前配送配置" : "配送尚未配置" }}</h3>
          <button class="primary" :disabled="saving" @click="openShipping">
            {{ shippingConfigured ? "编辑配送配置" : "新增配送配置" }}
          </button>
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
        <p v-else>未配置时购买端无法下单，请新增配送配置。</p>
      </article>
      <p v-else class="empty">暂未读取到配送配置，请刷新重试。</p>
    </template>
    <template v-else-if="selected">
      <button class="text-button back-orders" @click="selectedId = 0">
        ‹ 返回订单列表
      </button>
      <article class="surface order-detail">
        <div class="detail-heading">
          <div>
            <small>订单编号</small>
            <h3>{{ selected.number }}</h3>
          </div>
          <span class="state">{{
            orderStates[selected.status] ?? selected.status
          }}</span>
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
            <dt>支付状态</dt>
            <dd>
              {{
                paymentStates[selected.payment_state] ?? selected.payment_state
              }}
            </dd>
          </div>
          <div>
            <dt>支付方式</dt>
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
            <table>
              <thead>
                <tr>
                  <th>商品 / 规格</th>
                  <th>数量</th>
                  <th>成交单价</th>
                  <th>当时主理人单价</th>
                  <th>货款小计</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="line in selected.snapshot.lines" :key="line.skuId">
                  <td>
                    {{ line.name
                    }}<small>{{ line.skuName }} · SKU {{ line.skuId }}</small>
                  </td>
                  <td>{{ line.quantity }}{{ line.unit }}</td>
                  <td>{{ money(line.unitCents) }}</td>
                  <td>
                    {{
                      line.principalCents != null
                        ? money(line.principalCents)
                        : "—"
                    }}
                  </td>
                  <td>{{ money(line.unitCents * line.quantity) }}</td>
                </tr>
              </tbody>
            </table>
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
            <table>
              <thead>
                <tr>
                  <th>SKU / 行 ID</th>
                  <th>订购</th>
                  <th>已发货</th>
                  <th>已收货</th>
                  <th>已退款</th>
                  <th>退款占用</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="line in selected.lines" :key="line.id">
                  <td>{{ line.sku_id }} / {{ line.id }}</td>
                  <td>{{ line.quantity }}</td>
                  <td>{{ line.shipped_qty }}</td>
                  <td>{{ line.received_qty }}</td>
                  <td>{{ line.refunded_qty }}</td>
                  <td>{{ line.refund_held }}</td>
                </tr>
              </tbody>
            </table>
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
      </article>
    </template>
    <template v-else>
      <section class="surface order-list">
        <AdminForm class="order-filters">
          <label
            >搜索订单 / 购买者<input
              v-model="search"
              type="search"
              placeholder="订单号、用户 ID、收货人或电话" /></label
          ><label
            >订单状态<AdminSelect v-model="status">
              <option value="">全部状态</option>
              <option v-for="value in states" :key="value" :value="value">
                {{ orderStates[value] ?? value }}
              </option>
            </AdminSelect></label
          ><label
            >支付状态<AdminSelect v-model="paymentState">
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
          当前读取最新 {{ orders.length }} 笔（接口最多返回 200 笔），筛选后
          {{ filtered.length }} 笔。
        </p>
        <div class="table-wrap order-table">
          <table>
            <thead>
              <tr>
                <th>订单号 / 时间</th>
                <th>购买者 / 类型</th>
                <th>收货人</th>
                <th>订单状态</th>
                <th>支付状态</th>
                <th>订单金额</th>
                <th>详情</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="order in filtered" :key="order.id">
                <td>
                  {{ order.number }}<small>{{ time(order.create_time) }}</small>
                </td>
                <td>
                  {{ order.user_id }}<small>{{ kindName(order.kind) }}</small>
                </td>
                <td>{{ order.snapshot.address?.recipient ?? "—" }}</td>
                <td>
                  <span class="state">{{
                    orderStates[order.status] ?? order.status
                  }}</span>
                </td>
                <td>
                  {{
                    paymentStates[order.payment_state] ?? order.payment_state
                  }}
                </td>
                <td>{{ money(order.total_cents) }}</td>
                <td>
                  <button class="detail-button" @click="selectedId = order.id">
                    查看详情
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-if="!filtered.length" class="empty">
            {{ orders.length ? "没有符合条件的订单。" : "暂无订单记录。" }}
          </p>
        </div>
      </section>
    </template>
    <AdminDialog
      v-if="editingShipping"
      title="配送与待付款配置"
      :busy="saving"
      @close="!saving && (editingShipping = false)"
    >
      <p v-if="error" class="message error" role="alert">{{ error }}</p>
      <AdminForm class="shipping-form surface" @submit="saveShipping">
        <p v-if="!shippingConfigured" class="unconfigured">
          配送尚未配置，请完整填写后保存。未配置时购买端无法下单。
        </p>
        <fieldset :disabled="saving">
          <label
            >配送省份<textarea
              v-model="form.provinces"
              rows="6"
              required
              placeholder="每行填写一个完整省份名称，如：浙江省"
            /><small
              >须与收货地址省份一致；支持按行或逗号分隔。不在范围内的地址不可下单。</small
            ></label
          >
          <div class="shipping-fields">
            <label
              >固定运费（元）<input
                v-model="form.fee"
                required
                inputmode="decimal"
                pattern="[0-9]+(\.[0-9]{1,2})?"
                placeholder="0.00"
              /><small>0 表示包邮；最多两位小数。</small></label
            ><label
              >待付款有效期（分钟）<input
                v-model="form.pendingMinutes"
                required
                inputmode="numeric"
                pattern="[0-9]+"
                placeholder="5～1440"
              /><small>范围 5～1440 分钟。</small></label
            >
          </div>
          <label
            >发货说明<textarea
              v-model="form.instructions"
              required
              maxlength="2000"
              rows="4"
              placeholder="填写真实发货安排及配送说明"
            />
          </label>
          <label
            >修改原因<input
              v-model="form.reason"
              required
              maxlength="200"
              placeholder="用于操作记录与后续核对"
          /></label>
        </fieldset>
        <div class="dialog-actions">
          <button
            type="button"
            :disabled="saving"
            @click="editingShipping = false"
          >
            取消
          </button>
          <button class="primary save-shipping" :disabled="saving">
            {{ saving ? "保存中…" : "保存配送配置" }}
          </button>
        </div>
      </AdminForm>
    </AdminDialog>
  </section>
</template>

<style scoped>
.order-operations {
  min-width: 0;
  max-width: 100%;
  color: var(--admin-ink);
  font-size: 13px;
}
.order-operations *,
.order-operations *::before,
.order-operations *::after {
  box-sizing: border-box;
}
.operation-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--admin-border);
}
.operation-tabs button {
  padding: 8px 12px;
  border-bottom: 2px solid transparent;
}
.operation-tabs button[aria-current] {
  color: var(--admin-accent);
  border-bottom-color: var(--admin-accent);
  font-weight: 600;
}
.section-heading,
.detail-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}
.section-heading h2 {
  margin: 0;
  font-size: 17px;
  line-height: 1.5;
}
.section-heading p {
  margin: 4px 0 0;
  color: var(--admin-muted);
  font-size: 12px;
}
.surface {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: #fff;
}
.shipping-form,
.shipping-form fieldset {
  display: grid;
  gap: 16px;
}
.shipping-form fieldset {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}
.shipping-form label,
.order-filters label {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.order-operations input,
.order-operations select,
.order-operations textarea {
  min-width: 0;
  max-width: 100%;
  width: 100%;
}
.order-operations textarea {
  resize: vertical;
}
.order-operations small {
  color: var(--admin-muted);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.5;
}
.shipping-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.save-shipping {
  justify-self: start;
}
.unconfigured {
  margin: 0;
  padding: 12px;
  border-radius: 8px;
  background: var(--admin-accent-soft);
  color: var(--admin-accent);
  line-height: 1.6;
}
.loading,
.empty {
  padding: 24px 12px;
  color: var(--admin-muted);
  text-align: center;
}
.order-filters {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr;
  gap: 12px;
}
.list-caption {
  margin: 12px 0;
  color: var(--admin-muted);
  font-size: 12px;
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
.order-table table {
  min-width: 800px;
}
.order-operations td small {
  display: block;
  margin-top: 4px;
}
.detail-button {
  color: var(--admin-accent);
}
.state {
  display: inline-block;
  padding: 4px 8px;
  border-radius: 8px;
  background: var(--admin-accent-soft);
  color: var(--admin-accent);
  font-size: 12px;
}
.back-orders {
  margin-bottom: 12px;
}
.detail-heading h3 {
  margin: 4px 0 0;
  font-size: 15px;
  overflow-wrap: anywhere;
}
.section-heading > div,
.detail-heading > div {
  min-width: 0;
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
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--admin-border);
}
.snapshot-section h3 {
  margin: 0 0 12px;
  font-size: 14px;
}
.snapshot-section p {
  margin: 8px 0;
  color: var(--admin-muted);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.totals {
  display: grid;
  gap: 8px;
  max-width: 320px;
  margin: 16px 0 0 auto;
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
  border-radius: 8px;
  background: var(--admin-bg);
  font-size: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.order-operations .message {
  overflow-wrap: anywhere;
}
.order-operations button {
  max-width: 100%;
  white-space: normal;
  overflow-wrap: anywhere;
}
@media (max-width: 900px) {
  .shipping-form fieldset {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 650px) {
  .surface {
    padding: 12px;
  }
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
