<script setup lang="ts">
import AdminPickupDrawer from "./AdminPickupDrawer.vue";
const pickupDialog = ref<{
  id: number;
  action?: "approve" | "reject" | "ship" | "cancel" | "refund-fee";
} | null>(null);
import AdminUserName from "./AdminUserName.vue";
import AdminAddressSelect from "./AdminAddressSelect.vue";
import AdminUserSelect from "./AdminUserSelect.vue";
import { computed, onMounted, ref, watch } from "vue";
import { Select as ASelect } from "ant-design-vue";
import { Package } from "lucide-vue-next";
import { api, type Staff } from "../client";
import { useAdminList } from "../useAdminList";
import AdminForm from "./AdminForm.vue";
import AdminFilterBar from "./AdminFilterBar.vue";
import AdminDatePicker from "./AdminDatePicker.vue";
import AdminProductSelect from "./AdminProductSelect.vue";
import AdminDialog from "./AdminDialog.vue";
import FeedbackToast from "./FeedbackToast.vue";
interface Row {
  id: number;
  user_id: number;
  name?: string;
  sku_name?: string;
  unit?: string;
  order_number?: string;
  number?: string;
  remaining_qty?: number;
  available_qty?: number;
  total_qty?: number;
  total_shipped_qty?: number;
  newGoodsQuantity?: number;
  storedGoodsQuantity?: number;
  shipped_qty?: number;
  held_qty?: number;
  starts_on?: string;
  expires_on?: string;
  status?: string;
  purchased_qty?: number;
  package_name?: string;
  source_kind?: string;
  is_gift?: boolean;
  expired?: boolean;
  fee_cents?: number;
  fee_refunded?: boolean | number;
  carrier?: string;
  tracking?: string;
  create_time?: string;
  received_at?: string;
  consumed_qty?: number;
  consumption_confirmed?: boolean;
  consumption_note?: string;
  target_identity?: string;
  delivery_state?: string;
  title?: string;
  body?: string;
  last_error?: string;
  review_reason?: string;
  address?: {
    recipient: string;
    phone: string;
    province: string;
    city: string;
    district: string;
    detail: string;
  };
  lines?: {
    id: number;
    lot_id?: number | null;
    line_id: number;
    order_id: number;
    name: string;
    sku_name: string;
    quantity: number;
    is_gift: boolean;
    unit?: string;
  }[];
}
const props = defineProps<{
  staff: Staff;
  pickup?: { id: number; number: string };
}>();
const pickupFilter = ref(props.pickup?.number || "");
const sourceKind = ref("");
const tab = ref(props.pickup ? "pickups" : "lots"),
  userId = ref(""),
  skuId = ref(0),
  from = ref(""),
  to = ref(""),
  expiring = ref(false),
  error = ref(""),
  notice = ref(""),
  busy = ref(false),
  selected = ref<Row | null>(null),
  operation = ref("");
const reason = ref(""),
  expiry = ref(""),
  lineId = ref(0),
  quantity = ref(1),
  identity = ref("D"),
  principal = ref(""),
  evidence = ref(""),
  addressId = ref(""),
  manualItems = ref<{ lotId: number; quantity: number }[]>([]),
  actionKey = ref(crypto.randomUUID());
watch(
  userId,
  () => {
    lineId.value = 0;
    addressId.value = "";
    principal.value = "";
    manualItems.value = [{ lotId: 0, quantity: 1 }];
  },
  { flush: "sync" },
);
const canOperate = computed(() =>
  props.staff.permissions.includes("orders:manage"),
);
const canIdentity = computed(() =>
  props.staff.permissions.includes("onboarding:manage"),
);
const paths: Record<string, string> = {
  lots: "/admin/storage",
  pickups: "/admin/pickups",
  consumption: "/admin/package-consumption",
  messages: "/admin/storage-messages",
};
const states: Record<string, string> = {
  review: "待审核",
  approved: "待发货",
  awaiting_payment: "待积分支付",
  shipped: "已发货",
  received: "已收货",
  rejected: "已驳回",
  cancelled: "已取消",
  pending: "待发送",
  sent: "已发送",
  failed: "发送失败",
  unavailable: "未授权或未配置",
  unknown: "结果待核实",
  sending: "发送中",
};
const filters = () => ({
  q: tab.value === "pickups" ? pickupFilter.value : "",
  kind: tab.value === "lots" ? sourceKind.value : "",
  userId: userId.value,
  skuId: tab.value === "lots" && skuId.value ? String(skuId.value) : "",
  from: tab.value === "lots" ? from.value : "",
  to: tab.value === "lots" ? to.value : "",
  expiring: tab.value === "lots" && expiring.value ? "true" : "",
});
const { rows, total, pagination, loading, load } = useAdminList<Row>(
  () => paths[tab.value],
  () => ({ ...filters(), type: tab.value }),
  error,
);
async function reload() {
  try {
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  }
}
onMounted(async () => {
  await reload();
  if (props.pickup) {
    try {
      await detail({ id: props.pickup.id });
    } catch (e) {
      error.value = (e as Error).message;
    }
  }
});
function open(kind: string, row?: Row) {
  if (
    row &&
    ["approve", "reject", "ship", "cancel", "refund-fee"].includes(kind)
  ) {
    pickupDialog.value = {
      id: row.id,
      action: kind as "approve" | "reject" | "ship" | "cancel" | "refund-fee",
    };
    return;
  }
  lineId.value = 0;
  manualItems.value = [{ lotId: 0, quantity: 1 }];
  quantity.value = 1;
  if (kind === "manual") addressId.value = "";
  operation.value = kind;
  selected.value = row || null;
  reason.value = "";
  expiry.value = row?.expires_on || "";
  actionKey.value = crypto.randomUUID();
  error.value = "";
  notice.value = "";
}
async function act(work: () => Promise<unknown>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  let saved = false;
  try {
    await work();
    saved = true;
    operation.value = "";
    notice.value = "办理成功";
    await load();
  } catch (e) {
    error.value =
      (saved ? "办理已成功，列表刷新失败，请刷新查看：" : "") +
      (e as Error).message;
  } finally {
    busy.value = false;
  }
}
function submit() {
  return act(async () => {
    if (operation.value === "import" && !lineId.value)
      throw new Error("请选择原订单商品");
    if (
      operation.value === "manual" &&
      (!manualItems.value.length ||
        manualItems.value.some(
          (item) =>
            !item.lotId ||
            !Number.isInteger(item.quantity) ||
            item.quantity < 1,
        ))
    )
      throw new Error("请选择寄存商品并填写有效数量");
    const id = selected.value?.id;
    switch (operation.value) {
      case "extend":
        return api(`/admin/storage/${id}/extend`, {
          method: "POST",
          body: { expiresOn: expiry.value, reason: reason.value },
        });
      case "consumption":
        return api(`/admin/onboardings/${id}/consumption`, {
          method: "POST",
          body: {
            confirmed: !selected.value?.consumption_confirmed,
            reason: reason.value,
          },
        });
      case "import":
        return api(`/admin/order-lines/${lineId.value}/storage`, {
          method: "POST",
          body: { quantity: quantity.value, reason: reason.value },
        });
      case "identity":
        return api(`/admin/users/${userId.value}/identity`, {
          method: "POST",
          body: {
            identity: identity.value,
            ...(identity.value === "C"
              ? { principalId: Number(principal.value) }
              : {}),
            reason: reason.value,
            evidence: evidence.value,
            requestKey: actionKey.value,
          },
        });
      case "manual":
        return api("/admin/pickups", {
          method: "POST",
          body: {
            userId: Number(userId.value),
            addressId: Number(addressId.value),
            storedItems: manualItems.value.map((item) => ({ ...item })),
            reason: reason.value,
            requestKey: actionKey.value,
          },
        });
    }
  });
}
async function detail(row: Pick<Row, "id">) {
  pickupDialog.value = { id: row.id };
}
async function pickupChanged() {
  try {
    await load();
  } catch (e) {
    error.value =
      "配送办理已成功，列表刷新失败，请刷新查看：" + (e as Error).message;
  }
}
async function download() {
  await act(async () => {
    const response = await fetch(
      `/api/admin/exports/storage/${tab.value}?${new URLSearchParams(filters())}`,
      { credentials: "same-origin" },
    );
    if (!response.ok) throw new Error("导出失败，请检查权限或筛选条件");
    const url = URL.createObjectURL(await response.blob());
    const a = document.createElement("a");
    a.href = url;
    a.download = `寄存-${tab.value}.xlsx`;
    a.click();
    URL.revokeObjectURL(url);
  });
}
const operationTitles: Record<string, string> = {
  manual: "人工办理提货",
  import: "历史未发货转寄存",
  identity: "调整用户身份",
  extend: "延长寄存有效期",
  consumption: "确认权益包消耗",
};
const columns = computed(() =>
  tab.value === "lots"
    ? [
        { title: "商品 / 原订单", key: "goods", width: 240 },
        { title: "所属客户", key: "source", width: 150 },
        { title: "可提数量", key: "available", width: 100 },
        { title: "寄存数量", key: "quantity", width: 180 },
        { title: "到期时间", key: "expiry", width: 150 },
        { title: "操作", key: "actions", width: 120 },
      ]
    : tab.value === "pickups"
      ? [
          { title: "提货单 / 客户", key: "source", width: 250 },
          { title: "本次配送", key: "delivery", width: 180 },
          { title: "处理状态", key: "status", width: 130 },
          {
            title: "运费积分",
            key: "fee",
            width: 100,
            align: "right" as const,
          },
          { title: "操作", key: "actions", width: 200 },
        ]
      : tab.value === "consumption"
        ? [
            { title: "用户 / 权益包", key: "source" },
            { title: "实际消耗", key: "quantity" },
            { title: "人工统计", key: "status" },
            { title: "操作", key: "actions" },
          ]
        : [
            { title: "消息", key: "source" },
            { title: "发送结果", key: "status" },
            { title: "说明", key: "goods" },
            { title: "操作", key: "actions" },
          ],
);
const listDescriptions: Record<
  string,
  { title: string; unit: string; help: string; empty: string }
> = {
  lots: {
    title: "客户寄存明细",
    unit: "个寄存批次",
    help: "按原购买批次查看商品；提货占用不代表已发货。",
    empty: "暂无符合条件的寄存记录，可调整客户、商品或日期筛选。",
  },
  pickups: {
    title: "提货处理记录",
    unit: "张提货单",
    help: "先审核，再登记发货；一张提货单按整个包裹签收。",
    empty: "暂无符合条件的提货单，客户提交提货申请后将在这里显示。",
  },
  consumption: {
    title: "权益包消耗记录",
    unit: "条记录",
    help: "按实际收货统计，人工确认只影响消耗记录。",
    empty: "暂无权益包消耗记录。",
  },
  messages: {
    title: "到期提醒记录",
    unit: "条消息",
    help: "查看提醒发送结果；发送失败或结果未明时先核实再重试。",
    empty: "暂无到期提醒记录。",
  },
};
function stateColor(value?: string) {
  if (
    ["review", "awaiting_payment", "unknown", "unavailable"].includes(
      value || "",
    )
  )
    return "warning";
  if (["approved", "shipped", "sending", "pending"].includes(value || ""))
    return "processing";
  if (["received", "sent"].includes(value || "")) return "success";
  if (["failed", "rejected"].includes(value || "")) return "error";
  return "default";
}
</script>
<template>
  <section class="panel storage-operations">
    <FeedbackToast
      :error="error"
      :notice="notice"
      @dismiss-error="error = ''"
      @dismiss-notice="notice = ''"
    />
    <Teleport defer to="#admin-page-actions">
      <a-button
        v-if="canOperate && ['lots', 'pickups'].includes(tab)"
        type="primary"
        :disabled="!userId || busy"
        @click="open('manual')"
        >为该用户人工提货</a-button
      >
    </Teleport>
    <a-tabs
      v-model:active-key="tab"
      :animated="false"
      class="storage-tabs"
      aria-label="寄存与提货业务分类"
    >
      <a-tab-pane key="lots" tab="寄存台账" />
      <a-tab-pane key="pickups" tab="提货审核" />
      <a-tab-pane v-if="canIdentity" key="consumption" tab="权益包消耗" />
      <a-tab-pane key="messages" tab="到期与消息" />
    </a-tabs>
    <AdminFilterBar @submit="reload">
      <a-button
        v-if="tab === 'pickups' && pickupFilter"
        @click="
          pickupFilter = '';
          reload();
        "
        >查看全部配送单</a-button
      >
      <label
        >客户<AdminUserSelect
          v-model="userId"
          placeholder="搜索用户（留空为全部）"
          :disabled="busy" /></label
      ><template v-if="tab === 'lots'"
        ><label
          >来源类型<ASelect
            v-model:value="sourceKind"
            :options="[
              { value: '', label: '全部来源' },
              { value: 'goods', label: '普通商品 / 活动套餐' },
              { value: 'package', label: '店主权益包' },
            ]" /></label
        ><label class="storage-product-filter"
          >商品与规格<AdminProductSelect
            v-model="skuId"
            include-inactive
            placeholder="搜索商品，筛选寄存记录"
            aria-label="寄存商品筛选"
        /></label>
        <div class="storage-date-range">
          <label>寄存起日<AdminDatePicker v-model="from" type="date" /></label
          ><label>寄存止日<AdminDatePicker v-model="to" type="date" /></label>
        </div>
        <a-checkbox v-model:checked="expiring"
          >30天内到期及已到期</a-checkbox
        ></template
      >
      <label v-if="tab === 'pickups'" class="storage-pickup-search"
        >提货单号<a-input
          v-model:value="pickupFilter"
          placeholder="搜索提货单号"
          allow-clear
          :disabled="busy"
      /></label>
      <template #actions>
        <a-button html-type="submit" type="primary" :loading="loading"
          >查询</a-button
        >
        <a-button v-if="tab !== 'messages'" :disabled="busy" @click="download"
          >导出Excel</a-button
        >
      </template>
    </AdminFilterBar>
    <header class="storage-list-heading">
      <div>
        <h3>{{ listDescriptions[tab].title }}</h3>
        <p>{{ listDescriptions[tab].help }}</p>
      </div>
      <span>共 {{ total }} {{ listDescriptions[tab].unit }}</span>
    </header>
    <a-table
      :data-source="rows"
      :columns="columns"
      row-key="id"
      :pagination="pagination"
      :loading="loading"
      :scroll="{ x: tab === 'lots' ? 940 : 860 }"
      class="storage-table"
    >
      <template #emptyText
        ><div class="storage-empty">
          <Package :size="28" aria-hidden="true" />
          <p>{{ listDescriptions[tab].empty }}</p>
        </div></template
      >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'source'">
          <div class="storage-customer">
            <strong v-if="tab !== 'lots'" class="storage-document-number">{{
              record.number ||
              record.order_number ||
              record.title ||
              "权益包#" + record.id
            }}</strong>
            <AdminUserName :id="record.user_id" />
            <span
              v-if="tab === 'consumption' && record.package_name"
              class="storage-secondary"
              >{{ record.package_name }}</span
            >
          </div>
        </template>
        <template v-else-if="column.key === 'goods'">
          <div v-if="tab === 'lots'" class="storage-product">
            <span class="storage-product-icon"
              ><Package :size="22" aria-hidden="true"
            /></span>
            <div>
              <strong>{{ record.name }}</strong
              ><a-tag v-if="record.is_gift" class="storage-gift">赠品</a-tag>
              <span class="storage-secondary"
                >{{ record.sku_name
                }}<template v-if="record.unit">
                  · {{ record.unit }}</template
                ></span
              >
              <span class="storage-secondary"
                >原订单 {{ record.order_number || "—" }}</span
              >
              <span
                v-if="record.source_kind === 'package'"
                class="storage-secondary"
                >店主权益包 · {{ record.package_name }}</span
              >
            </div>
          </div>
          <template v-else
            ><span>{{ record.body }}</span
            ><span v-if="record.last_error" class="storage-error">{{
              record.last_error
            }}</span></template
          >
        </template>
        <template v-else-if="column.key === 'available'">
          <strong
            class="storage-available"
            :class="{ 'storage-zero': !record.available_qty }"
            >{{ record.available_qty ?? "—" }}</strong
          >
          <span class="storage-secondary">{{ record.unit || "件" }}</span>
        </template>
        <template v-else-if="column.key === 'quantity'">
          <dl v-if="tab === 'lots'" class="storage-quantities">
            <div>
              <dt>寄存总量</dt>
              <dd>{{ record.total_qty ?? "—" }}</dd>
              <dt>已提</dt>
              <dd>{{ record.shipped_qty ?? "—" }}</dd>
            </div>
            <div>
              <dt>剩余寄存</dt>
              <dd>{{ record.remaining_qty ?? "—" }}</dd>
              <dt>占用</dt>
              <dd>{{ record.held_qty ?? "—" }}</dd>
            </div>
          </dl>
          <template v-else
            ><strong>{{ record.consumed_qty }} / {{ record.total_qty }}</strong
            ><span class="storage-secondary"
              >实际消耗 / 权益包总量</span
            ></template
          >
        </template>
        <template v-else-if="column.key === 'expiry'">
          <strong class="storage-expiry">{{ record.expires_on }}</strong>
          <span v-if="record.starts_on" class="storage-secondary"
            >寄存起日 {{ record.starts_on }}</span
          >
          <a-tag v-if="record.expired" color="error" class="storage-expired"
            >已到期，需人工办理</a-tag
          >
        </template>
        <template v-else-if="column.key === 'delivery'">
          <div class="storage-delivery-counts">
            <span v-if="record.newGoodsQuantity !== undefined"
              >新购 <strong>{{ record.newGoodsQuantity }}</strong> 件</span
            >
            <span v-if="record.storedGoodsQuantity !== undefined"
              >寄存 <strong>{{ record.storedGoodsQuantity }}</strong> 件</span
            >
            <span
              v-if="
                record.newGoodsQuantity === undefined &&
                record.storedGoodsQuantity === undefined
              "
              class="storage-secondary"
              >查看明细确认商品</span
            >
          </div>
          <span
            v-if="record.carrier || record.tracking"
            class="storage-secondary"
            >{{ record.carrier }} {{ record.tracking }}</span
          >
          <span v-if="record.address?.recipient" class="storage-secondary"
            >收货人 {{ record.address.recipient }}</span
          >
        </template>
        <template v-else-if="column.key === 'fee'">
          <strong>{{
            record.fee_cents == null ? "—" : (record.fee_cents / 100).toFixed(2)
          }}</strong>
          <span v-if="record.fee_refunded" class="storage-secondary"
            >已退回</span
          >
          <span
            v-else-if="
              record.status === 'awaiting_payment' ||
              (record.status === 'cancelled' && record.fee_cents > 0)
            "
            class="storage-secondary"
            >未扣费</span
          >
        </template>
        <template v-else-if="column.key === 'status'">
          <template v-if="tab === 'consumption'"
            ><a-tag
              :color="record.consumption_confirmed ? 'success' : 'default'"
              >{{
                record.consumption_confirmed ? "人工确认完成" : "按实际收货统计"
              }}</a-tag
            ></template
          >
          <a-tag
            v-else
            :color="stateColor(record.status || record.delivery_state)"
            >{{
              states[record.status || record.delivery_state] ||
              record.status ||
              record.delivery_state
            }}</a-tag
          >
          <span v-if="record.create_time" class="storage-secondary">{{
            new Date(record.create_time).toLocaleString("zh-CN", {
              hour12: false,
            })
          }}</span>
        </template>
        <template v-else-if="column.key === 'actions'"
          ><a-space wrap class="storage-row-actions">
            <template v-if="tab === 'lots' && canOperate"
              ><a-button size="small" @click="open('extend', record)"
                >延期</a-button
              ><a-button
                size="small"
                @click="
                  act(() =>
                    api(`/admin/storage/${record.id}/remind`, {
                      method: 'POST',
                    }),
                  )
                "
                >提醒</a-button
              ></template
            >
            <template v-if="tab === 'pickups'"
              ><a-button size="small" @click="detail(record)">明细</a-button
              ><a-button
                v-if="
                  staff.permissions.includes('finance:manage') &&
                  ['shipped', 'received'].includes(record.status) &&
                  record.fee_cents > 0 &&
                  !record.fee_refunded
                "
                size="small"
                @click="open('refund-fee', record)"
                >退本次运费</a-button
              ><template v-if="canOperate"
                ><template v-if="record.status === 'review'"
                  ><a-button
                    size="small"
                    type="primary"
                    @click="open('approve', record)"
                    >通过</a-button
                  ><a-button
                    size="small"
                    danger
                    type="text"
                    @click="open('reject', record)"
                    >驳回</a-button
                  ></template
                ><a-button
                  v-if="record.status === 'approved'"
                  type="primary"
                  size="small"
                  @click="open('ship', record)"
                  >发货</a-button
                ><a-button
                  v-if="['review', 'approved'].includes(record.status)"
                  type="text"
                  size="small"
                  @click="open('cancel', record)"
                  >取消</a-button
                ></template
              ></template
            >
            <a-button
              v-if="tab === 'consumption' && canIdentity"
              size="small"
              @click="open('consumption', record)"
              >人工确认 / 撤销确认</a-button
            ><a-button
              v-if="
                tab === 'messages' &&
                canOperate &&
                ['failed', 'unknown', 'unavailable'].includes(
                  record.delivery_state,
                )
              "
              size="small"
              @click="
                act(() =>
                  api(`/admin/storage-messages/${record.id}/retry`, {
                    method: 'POST',
                  }),
                )
              "
              >核实后重试</a-button
            >
          </a-space></template
        >
      </template>
    </a-table>
    <details v-if="canOperate || canIdentity" class="storage-tools">
      <summary>历史与资格办理</summary>
      <div class="storage-tools-actions">
        <a-button v-if="canOperate" :disabled="busy" @click="open('import')"
          >历史未发货转寄存</a-button
        >
        <a-button
          v-if="canIdentity"
          :disabled="!userId || busy"
          @click="open('identity')"
          >调整该用户身份</a-button
        >
        <span>选择客户后可人工提货或调整身份；历史转寄存需核实原订单。</span>
      </div>
    </details>
    <AdminPickupDrawer
      v-if="pickupDialog"
      :id="pickupDialog.id"
      :initial-action="pickupDialog.action"
      :can-operate="canOperate"
      :can-finance="staff.permissions.includes('finance:manage')"
      @close="pickupDialog = null"
      @changed="pickupChanged"
    />
    <AdminDialog
      v-if="operation"
      :drawer="['import', 'manual', 'identity'].includes(operation)"
      :title="operationTitles[operation] || '寄存与身份办理'"
      :busy="busy"
      @close="operation = ''"
    >
      <AdminForm
        v-if="operation !== 'detail'"
        class="storage-form config-form"
        @submit="submit"
      >
        <div v-if="selected?.number" class="storage-operation-context">
          <span>当前提货单</span><strong>{{ selected.number }}</strong
          ><AdminUserName :id="selected.user_id" />
        </div>
        <label v-if="operation === 'extend'"
          >新到期日<AdminDatePicker v-model="expiry" type="date" required
        /></label>
        <template v-if="operation === 'import'"
          ><label
            >原订单商品<AdminProductSelect
              v-model="lineId"
              kind="orderLine"
              :user-id="userId"
              :disabled="busy"
              required
              placeholder="搜索商品名称、规格或原订单号"
              @select="
                quantity = Math.min(quantity, $event.quantity || 1)
              " /></label
          ><label
            >转换寄存数量<a-input-number
              v-model:value="quantity"
              :min="1"
              :precision="0"
          /></label>
          <p>
            仅转换真实已付款、未发货、无申请占用且允许寄存的数量，不重复扣款。
          </p></template
        >
        <template v-if="operation === 'manual'"
          ><label
            >收货地址<AdminAddressSelect
              v-model="addressId"
              :user-id="userId"
              :disabled="busy"
              required
          /></label>
          <div
            v-for="(item, i) in manualItems"
            :key="i"
            class="storage-item-row"
          >
            <label
              >寄存商品<AdminProductSelect
                v-model="item.lotId"
                kind="lot"
                :user-id="userId"
                :disabled="busy"
                :disabled-ids="
                  manualItems
                    .filter((_, index) => index !== i)
                    .map((row) => row.lotId)
                "
                required
                placeholder="搜索该用户的商品或来源订单"
            /></label>
            <label
              >提货数量<a-input-number
                v-model:value="item.quantity"
                :min="1"
                :precision="0"
                required
                :disabled="busy"
            /></label>
            <a-button
              type="text"
              danger
              :disabled="busy"
              @click="manualItems.splice(i, 1)"
              >移除</a-button
            >
          </div>
          <a-button
            :disabled="busy || manualItems.length >= 100"
            @click="manualItems.push({ lotId: 0, quantity: 1 })"
            >添加寄存商品</a-button
          >
          <p>用于过期寄存等人工办理；仍须扣足额运费积分并审核。</p></template
        >
        <template v-if="operation === 'identity'"
          ><label
            >目标身份<ASelect
              v-model:value="identity"
              :options="[
                { value: 'D', label: '普通顾客' },
                { value: 'C', label: '2980店主' },
                { value: 'B', label: '19800店主' },
              ]" /></label
          ><label v-if="identity === 'C'"
            >归属19800店主ID<AdminUserSelect
              v-model="principal"
              identity="B"
              :disabled-ids="[userId]"
              required /></label
          ><label
            >资格核验依据<a-textarea v-model:value="evidence" required
          /></label>
          <p>19800档降级或关闭前，须先在身份管理中准备团队交接。</p></template
        >
        <label
          >办理原因<a-textarea
            v-model:value="reason"
            required
            :maxlength="2000"
        /></label>
        <div class="dialog-actions">
          <a-button :disabled="busy" @click="operation = ''">取消</a-button
          ><a-button
            type="primary"
            html-type="submit"
            :loading="busy"
            :disabled="busy"
            >{{ operation === "ship" ? "确认发货" : "确认办理" }}</a-button
          >
        </div>
      </AdminForm>
    </AdminDialog>
  </section>
</template>
<style scoped>
.storage-tabs :deep(.ant-tabs-nav) {
  margin: 0 0 20px;
}
.storage-tabs :deep(.ant-tabs-tab) {
  padding: 10px 0 16px;
  font-weight: 600;
}
.storage-date-range {
  display: flex;
  gap: 12px;
}
.storage-date-range label {
  display: grid;
  gap: 6px;
  width: 148px;
}
.admin-filter-bar .storage-pickup-search {
  --admin-filter-width: 280px;
}
.storage-list-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  margin: 24px 0 16px;
}
.storage-list-heading h3 {
  margin: 0 0 5px;
  font-size: 15px;
}
.storage-list-heading p,
.storage-list-heading > span {
  margin: 0;
  color: var(--admin-muted);
  font-size: 12px;
  line-height: 1.6;
}
.storage-list-heading > span {
  white-space: nowrap;
}
.storage-table :deep(.ant-table-cell) {
  vertical-align: top;
  overflow-wrap: anywhere;
}
.storage-customer {
  display: grid;
  gap: 6px;
}
.storage-document-number {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.storage-product {
  display: flex;
  gap: 12px;
}
.storage-product > div {
  min-width: 0;
}
.storage-product strong {
  line-height: 1.6;
}
.storage-product-icon {
  display: grid;
  place-items: center;
  width: 44px;
  height: 48px;
  flex-shrink: 0;
  color: var(--admin-muted);
  background: var(--admin-bg);
  border: 1px solid var(--admin-border);
  border-radius: 4px;
}
.storage-secondary {
  display: block;
  margin-top: 5px;
  color: var(--admin-muted);
  font-size: 12px;
  line-height: 1.6;
}
.storage-gift {
  margin: 0 0 0 6px;
  color: var(--admin-accent);
  background: #f7edf0;
  border: 0;
  font-size: 11px;
}
.storage-error {
  display: block;
  margin-top: 6px;
  color: #b42318;
  font-size: 12px;
}
.storage-available {
  color: var(--admin-accent);
  font-size: 24px;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.storage-zero {
  color: var(--admin-muted);
}
.storage-quantities {
  display: grid;
  gap: 9px;
  margin: 0;
  font-size: 12px;
}
.storage-quantities > div {
  display: grid;
  grid-template-columns: auto 1fr auto 1fr;
  gap: 7px;
  align-items: baseline;
}
.storage-quantities dt {
  color: var(--admin-muted);
}
.storage-quantities dd {
  margin: 0;
  text-align: right;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}
.storage-expiry {
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.storage-expired {
  margin: 7px 0 0;
  white-space: normal;
}
.storage-delivery-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
}
.storage-delivery-counts strong {
  font-variant-numeric: tabular-nums;
}
.storage-row-actions {
  row-gap: 8px;
}
.storage-empty {
  padding: 36px 20px;
  color: var(--admin-muted);
}
.storage-empty p {
  margin: 12px 0 0;
}
.storage-tools {
  margin-top: 24px;
  padding-top: 18px;
  border-top: 1px solid var(--admin-border);
}
.storage-tools summary {
  width: fit-content;
  cursor: pointer;
  color: var(--admin-muted);
  font-size: 13px;
}
.storage-tools-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding-top: 16px;
}
.storage-tools-actions > span {
  color: var(--admin-muted);
  font-size: 12px;
}

.storage-operation-context {
  display: grid;
  gap: 6px;
  padding: 16px;
  background: var(--admin-bg);
  border-radius: 6px;
  overflow-wrap: anywhere;
}
.storage-operation-context > span:first-child {
  color: var(--admin-muted);
  font-size: 12px;
}

.admin-filter-bar .storage-product-filter {
  --admin-filter-width: 260px;
}
.storage-item-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 100px auto;
  gap: 12px;
  align-items: end;
}
@media (max-width: 650px) {
  .storage-item-row {
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .storage-item-row > label:first-child {
    grid-column: 1 / -1;
  }
}
.storage-form {
  display: grid;
  gap: 16px;
}
.storage-form label:not(.ant-checkbox-wrapper):not(.ant-radio-wrapper) {
  display: grid;
  gap: 6px;
}
</style>
