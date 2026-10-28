<script setup lang="ts">
import AdminOrderLink from "./AdminOrderLink.vue";
const sourceOrderOpen = ref(false);
import AdminUserName from "./AdminUserName.vue";
import AdminAddressSelect from "./AdminAddressSelect.vue";
import AdminUserSelect from "./AdminUserSelect.vue";
import { computed, onMounted, ref, watch } from "vue";
import { Radio as AntRadio, Select as ASelect } from "ant-design-vue";
const ARadioGroup = AntRadio.Group,
  ARadioButton = AntRadio.Button;
import { api, type Staff } from "../client";
import { useAdminList } from "../useAdminList";
import AdminForm from "./AdminForm.vue";
import AdminFilterBar from "./AdminFilterBar.vue";
import AdminDatePicker from "./AdminDatePicker.vue";
import AdminProductSelect from "./AdminProductSelect.vue";
import AdminDialog from "./AdminDialog.vue";
import AdminDrawer from "./AdminDrawer.vue";
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
  carrier = ref(""),
  tracking = ref(""),
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
      case "approve":
      case "reject":
        return api(`/admin/pickups/${id}/review`, {
          method: "POST",
          body: {
            approve: operation.value === "approve",
            reason: reason.value,
          },
        });
      case "ship":
        return api(`/admin/pickups/${id}/ship`, {
          method: "POST",
          body: {
            carrier: carrier.value,
            tracking: tracking.value,
            reason: reason.value,
          },
        });
      case "refund-fee":
        return api(`/admin/pickups/${id}/refund-fee`, {
          method: "POST",
          body: { reason: reason.value },
        });
      case "cancel":
        return api(`/admin/pickups/${id}/cancel`, {
          method: "POST",
          body: { reason: reason.value },
        });
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
  try {
    selected.value = await api<Row>(`/admin/pickups/${row.id}`);
    operation.value = "detail";
  } catch (e) {
    error.value = (e as Error).message;
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
  detail: "提货详情",
  ship: "登记提货发货",
  manual: "人工办理提货",
  import: "历史未发货转寄存",
  identity: "调整用户身份",
  approve: "审核提货申请",
  reject: "驳回提货申请",
  cancel: "取消本次配送",
  extend: "延长寄存有效期",
  consumption: "确认权益包消耗",
  "refund-fee": "退回本次运费",
};
const pickupLineColumns = [
  { title: "商品 / 规格", key: "product" },
  { title: "来源订单", key: "source", width: 150 },
  { title: "提货数量", key: "quantity", width: 90, align: "right" as const },
];
const columns = computed(() =>
  tab.value === "lots"
    ? [
        { title: "用户 / 来源", key: "source" },
        { title: "商品", key: "goods" },
        { title: "数量", key: "quantity" },
        { title: "有效期", key: "expiry" },
        { title: "操作", key: "actions" },
      ]
    : tab.value === "pickups"
      ? [
          { title: "提货单 / 用户", key: "source" },
          { title: "状态", key: "status" },
          { title: "运费积分", key: "fee" },
          { title: "操作", key: "actions" },
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
</script>
<template>
  <section class="panel storage-operations">
    <FeedbackToast
      :error="error"
      :notice="notice"
      @dismiss-error="error = ''"
      @dismiss-notice="notice = ''"
    />
    <ARadioGroup v-model:value="tab" button-style="solid"
      ><ARadioButton value="lots">寄存台账</ARadioButton
      ><ARadioButton value="pickups">提货审核</ARadioButton
      ><ARadioButton v-if="canIdentity" value="consumption"
        >权益包消耗</ARadioButton
      ><ARadioButton value="messages">到期与消息</ARadioButton></ARadioGroup
    >
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
        >用户ID<AdminUserSelect
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
            aria-label="寄存商品筛选" /></label
        ><label>寄存起日<AdminDatePicker v-model="from" type="date" /></label
        ><label>寄存止日<AdminDatePicker v-model="to" type="date" /></label
        ><a-checkbox v-model:checked="expiring"
          >30天内到期及已到期</a-checkbox
        ></template
      >
      <template #actions>
        <a-button html-type="submit" :loading="loading">刷新</a-button>
        <a-button v-if="tab !== 'messages'" :disabled="busy" @click="download"
          >导出Excel</a-button
        >
      </template>
    </AdminFilterBar>
    <div class="admin-action-bar">
      <a-button v-if="canOperate" @click="open('import')"
        >历史未发货转寄存</a-button
      ><a-button v-if="canOperate" :disabled="!userId" @click="open('manual')"
        >为该用户人工提货</a-button
      ><a-button
        v-if="canIdentity"
        :disabled="!userId"
        @click="open('identity')"
        >调整该用户身份</a-button
      >
    </div>
    <a-table
      :data-source="rows"
      :columns="columns"
      row-key="id"
      :pagination="pagination"
      :loading="loading"
      :scroll="{ x: 760 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'source'"
          ><strong>{{
            record.order_number ||
            record.number ||
            record.title ||
            "权益包#" + record.id
          }}</strong>
          <div>
            <AdminUserName :id="record.user_id" /><span v-if="tab === 'lots'">
              ·
              {{ record.source_kind === "package" ? "店主权益包" : "普通订单" }}
              · {{ record.package_name }}</span
            >
          </div></template
        >
        <template v-else-if="column.key === 'goods'"
          >{{ record.name }} {{ record.sku_name
          }}<span v-if="record.is_gift"> · 赠品</span
          >{{ record.last_error }}</template
        >
        <template v-else-if="column.key === 'quantity'"
          ><template v-if="tab === 'lots'"
            >寄存总量 {{ record.total_qty }} / 寄存已提
            {{ record.shipped_qty }} / 剩余寄存 {{ record.remaining_qty }} /
            占用 {{ record.held_qty }} / 可提
            {{ record.available_qty }}</template
          ><template v-else
            >{{ record.consumed_qty }} / {{ record.total_qty }}</template
          ></template
        >
        <template v-else-if="column.key === 'expiry'"
          >{{ record.starts_on }} 至 {{ record.expires_on }}
          <div v-if="record.expired">已到期，需人工办理</div></template
        >
        <template v-else-if="column.key === 'fee'"
          >{{ (record.fee_cents / 100).toFixed(2)
          }}<span v-if="record.fee_refunded">（已退回）</span
          ><span
            v-else-if="
              record.status === 'awaiting_payment' ||
              (record.status === 'cancelled' && record.fee_cents > 0)
            "
            >（未扣费）</span
          ></template
        >
        <template v-else-if="column.key === 'status'">{{
          tab === "consumption"
            ? record.consumption_confirmed
              ? "人工确认完成"
              : "按实际收货统计"
            : states[record.status || record.delivery_state] || record.status
        }}</template>
        <template v-else-if="column.key === 'actions'"
          ><a-space wrap>
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
                  ><a-button size="small" @click="open('approve', record)"
                    >通过</a-button
                  ><a-button size="small" @click="open('reject', record)"
                    >驳回</a-button
                  ></template
                ><a-button
                  v-if="record.status === 'approved'"
                  size="small"
                  @click="open('ship', record)"
                  >发货</a-button
                ><a-button
                  v-if="['review', 'approved'].includes(record.status)"
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
    <component
      :is="operation === 'detail' ? AdminDrawer : AdminDialog"
      :drawer="['import', 'manual', 'identity', 'ship'].includes(operation)"
      v-if="operation"
      :title="operationTitles[operation] || '寄存与身份办理'"
      :busy="busy || sourceOrderOpen"
      @close="operation = ''"
    >
      <div v-if="operation === 'detail' && selected" class="pickup-detail">
        <header class="pickup-summary">
          <div>
            <span class="pickup-caption">提货单号</span
            ><strong class="pickup-number">{{ selected.number }}</strong>
          </div>
          <a-tag
            :color="
              selected.status === 'approved'
                ? 'processing'
                : ['shipped', 'received'].includes(selected.status || '')
                  ? 'success'
                  : 'default'
            "
            >{{ states[selected.status || ""] || selected.status }}</a-tag
          >
        </header>
        <a-descriptions size="small" :column="{ xs: 1, sm: 2 }">
          <a-descriptions-item label="所属用户"
            ><AdminUserName :id="selected.user_id"
          /></a-descriptions-item>
          <a-descriptions-item label="运费积分"
            >{{
              selected.fee_cents == null
                ? "—"
                : (selected.fee_cents / 100).toFixed(2)
            }}<span v-if="selected.fee_refunded"
              >（已退回）</span
            ></a-descriptions-item
          >
          <a-descriptions-item
            v-if="selected.create_time"
            label="申请时间"
            :span="2"
            >{{
              new Date(selected.create_time).toLocaleString("zh-CN")
            }}</a-descriptions-item
          >
        </a-descriptions>
        <section class="pickup-section" aria-label="收货信息">
          <h3>收货信息</h3>
          <a-descriptions
            v-if="selected.address"
            size="small"
            :column="{ xs: 1, sm: 2 }"
          >
            <a-descriptions-item label="收货人">{{
              selected.address.recipient
            }}</a-descriptions-item>
            <a-descriptions-item label="联系电话">{{
              selected.address.phone
            }}</a-descriptions-item>
            <a-descriptions-item label="收货地址" :span="2"
              >{{ selected.address.province }}{{ selected.address.city
              }}{{ selected.address.district
              }}{{ selected.address.detail }}</a-descriptions-item
            >
          </a-descriptions>
          <p v-else class="pickup-empty">暂无收货地址记录</p>
        </section>
        <section class="pickup-section" aria-label="提货商品明细">
          <div class="pickup-section-heading">
            <h3>商品明细</h3>
            <span
              >共 {{ selected.lines?.length || 0 }} 项 ·
              {{
                selected.lines?.reduce((sum, item) => sum + item.quantity, 0) ||
                0
              }}
              件</span
            >
          </div>
          <a-table
            :columns="pickupLineColumns"
            :data-source="selected.lines || []"
            row-key="id"
            :pagination="false"
            size="small"
            :scroll="{ x: 520 }"
            :locale="{ emptyText: '暂无提货商品记录' }"
          >
            <template #bodyCell="{ column, record: item }">
              <template v-if="column.key === 'product'"
                ><strong>{{ item.name }}</strong
                ><a-tag v-if="item.is_gift" class="pickup-gift">赠品</a-tag
                ><small class="pickup-caption"
                  >{{ item.sku_name
                  }}<span v-if="item.unit"> · {{ item.unit }}</span></small
                ></template
              >
              <template v-else-if="column.key === 'source'"
                ><AdminOrderLink
                  :id="item.order_id"
                  @open-change="sourceOrderOpen = $event"
                /><small class="pickup-caption">{{
                  item.lot_id ? "寄存提货" : "新购直发"
                }}</small></template
              >
              <template v-else-if="column.key === 'quantity'"
                ><strong>{{ item.quantity }}</strong></template
              >
            </template>
          </a-table>
        </section>
        <section
          v-if="selected.carrier || selected.tracking || selected.received_at"
          class="pickup-section"
          aria-label="物流信息"
        >
          <h3>物流信息</h3>
          <a-descriptions size="small" :column="{ xs: 1, sm: 2 }">
            <a-descriptions-item label="快递公司">{{
              selected.carrier || "—"
            }}</a-descriptions-item>
            <a-descriptions-item label="快递单号">{{
              selected.tracking || "—"
            }}</a-descriptions-item>
            <a-descriptions-item
              v-if="selected.received_at"
              label="收货时间"
              :span="2"
              >{{
                new Date(selected.received_at).toLocaleString("zh-CN")
              }}</a-descriptions-item
            >
          </a-descriptions>
        </section>
        <section class="pickup-section" aria-label="审核与处理备注">
          <h3>审核与处理备注</h3>
          <p
            class="pickup-note"
            :class="{ 'pickup-empty': !selected.review_reason }"
          >
            {{ selected.review_reason || "暂无处理备注" }}
          </p>
        </section>
      </div>
      <template #actions>
        <a-space v-if="operation === 'detail' && canOperate && selected" wrap>
          <a-button
            v-if="selected.status === 'review'"
            @click="open('approve', selected)"
            >审核通过</a-button
          >
          <a-button
            v-if="selected.status === 'review'"
            @click="open('reject', selected)"
            >驳回</a-button
          >
          <a-button
            v-if="selected.status === 'approved'"
            type="primary"
            @click="open('ship', selected)"
            >登记本次配送发货</a-button
          >
          <a-button
            v-if="['review', 'approved'].includes(selected.status || '')"
            @click="open('cancel', selected)"
            >取消本次配送</a-button
          >
        </a-space>
      </template>
      <AdminForm
        v-if="operation !== 'detail'"
        class="storage-form config-form"
        @submit="submit"
      >
        <div v-if="selected?.number" class="storage-operation-context">
          <span>当前提货单</span><strong>{{ selected.number }}</strong
          ><AdminUserName :id="selected.user_id" />
        </div>
        <p v-if="operation === 'cancel' || operation === 'reject'">
          取消或驳回仅释放寄存提货占用并退本次运费，商品货款不退款。
          <span v-if="selected?.newGoodsQuantity"
            >新购商品继续保留在原订单，未发部分从订单管理安排配送，不自动转寄存。</span
          >
        </p>
        <label v-if="operation === 'extend'"
          >新到期日<AdminDatePicker v-model="expiry" type="date" required
        /></label>
        <template v-if="operation === 'ship'"
          ><label>快递公司<a-input v-model:value="carrier" required /></label
          ><label>快递单号<a-input v-model:value="tracking" required /></label
        ></template>
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
    </component>
  </section>
</template>
<style scoped>
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

.pickup-detail {
  display: grid;
  gap: 24px;
}
.pickup-summary,
.pickup-section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.pickup-summary > div {
  min-width: 0;
}
.pickup-summary :deep(.ant-tag) {
  flex-shrink: 0;
  margin: 0;
}
.pickup-caption {
  display: block;
  margin-top: 4px;
  color: var(--admin-muted);
  font-size: 12px;
  line-height: 1.6;
}
.pickup-number {
  display: block;
  margin-top: 7px;
  font-size: 16px;
  line-height: 1.6;
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}
.pickup-section {
  min-width: 0;
  padding-top: 22px;
  border-top: 1px solid var(--admin-border);
}
.pickup-section h3 {
  margin: 0 0 16px;
  font-size: 14px;
  font-weight: 600;
}
.pickup-section-heading > span {
  color: var(--admin-muted);
  font-size: 12px;
  white-space: nowrap;
}
.pickup-detail :deep(.ant-descriptions-item-label) {
  color: var(--admin-muted);
}
.pickup-detail :deep(.ant-table-cell) {
  vertical-align: top;
  overflow-wrap: anywhere;
}
.pickup-gift {
  margin-left: 8px;
}
.pickup-note {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.8;
}
.pickup-empty {
  color: var(--admin-muted);
}

.admin-filter-bar .storage-product-filter {
  --admin-filter-width: 300px;
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
