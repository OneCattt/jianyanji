<script setup lang="ts">
import { computed, nextTick, onMounted, ref, useId } from "vue";
import { api } from "../client";
import AdminDrawer from "./AdminDrawer.vue";
import AdminForm from "./AdminForm.vue";
import AdminOrderLink from "./AdminOrderLink.vue";
import AdminUserName from "./AdminUserName.vue";
import FeedbackToast from "./FeedbackToast.vue";
interface Pickup {
  id: number;
  user_id: number;
  number?: string;
  status?: string;
  fee_cents?: number;
  fee_refunded?: boolean | number;
  carrier?: string;
  tracking?: string;
  create_time?: string;
  received_at?: string;
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

type Action = "approve" | "reject" | "ship" | "cancel" | "refund-fee";
const props = withDefaults(
  defineProps<{
    id: number;
    canOperate?: boolean;
    canFinance?: boolean;
    initialAction?: Action;
    orderNumber?: string;
  }>(),
  { canOperate: false, canFinance: false },
);
const emit = defineEmits<{ close: []; changed: [] }>();
const formId = useId();
const actionPanel = ref<HTMLElement>();
const selected = ref<Pickup | null>(null);
const sourceOrderOpen = ref(false),
  busy = ref(false),
  loading = ref(false);
const error = ref(""),
  notice = ref(""),
  action = ref<Action | "">("");
const reason = ref(""),
  carrier = ref(""),
  tracking = ref("");
const titles: Record<Action, string> = {
  approve: "审核通过",
  reject: "驳回配送申请",
  ship: "登记本次配送发货",
  cancel: "取消本次配送",
  "refund-fee": "退回本次运费",
};
const states: Record<string, string> = {
  review: "待审核",
  approved: "待发货",
  awaiting_payment: "待积分支付",
  shipped: "已发货",
  received: "已收货",
  rejected: "已驳回",
  cancelled: "已取消",
};
const allowedActions = computed<Action[]>(() => {
  const p = selected.value;
  if (!p) return [];
  const result: Action[] = [];
  if (props.canOperate) {
    if (p.status === "review") result.push("approve", "reject");
    if (p.status === "approved") result.push("ship");
    if (["review", "approved"].includes(p.status || "")) result.push("cancel");
  }
  if (
    props.canFinance &&
    ["shipped", "received"].includes(p.status || "") &&
    (p.fee_cents || 0) > 0 &&
    !p.fee_refunded
  )
    result.push("refund-fee");
  return result;
});
function openAction(kind: Action) {
  if (!allowedActions.value.includes(kind) || busy.value) return;
  action.value = kind;
  reason.value = carrier.value = tracking.value = "";
  error.value = "";
  void nextTick(() => {
    actionPanel.value?.scrollIntoView({ block: "start", behavior: "instant" });
    actionPanel.value
      ?.querySelector<HTMLElement>("input, textarea")
      ?.focus({ preventScroll: true });
  });
}
async function load() {
  loading.value = true;
  error.value = "";
  try {
    selected.value = await api<Pickup>(`/admin/pickups/${props.id}`);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
async function submit() {
  if (busy.value || !selected.value || !action.value) return;
  if (!allowedActions.value.includes(action.value)) {
    error.value = "当前状态或权限不允许此操作，请刷新后核实";
    return;
  }
  if (!reason.value.trim()) {
    error.value = "请填写操作原因";
    return;
  }
  if (
    action.value === "ship" &&
    (!carrier.value.trim() || !tracking.value.trim())
  ) {
    error.value = "请完整填写快递公司和快递单号";
    return;
  }
  busy.value = true;
  error.value = "";
  notice.value = "";
  const kind = action.value;
  try {
    // 写入接口返回最新配送单，先更新状态再通知父级刷新，避免刷新失败后重复办理。
    selected.value = await api<Pickup>(
      `/admin/pickups/${props.id}/${["approve", "reject"].includes(kind) ? "review" : kind}`,
      {
        method: "POST",
        body: {
          reason: reason.value.trim(),
          ...(["approve", "reject"].includes(kind)
            ? { approve: kind === "approve" }
            : {}),
          ...(kind === "ship"
            ? { carrier: carrier.value.trim(), tracking: tracking.value.trim() }
            : {}),
        },
      },
    );
    action.value = "";
    notice.value = `${titles[kind]}已完成`;
    emit("changed");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(async () => {
  await load();
  if (props.initialAction) openAction(props.initialAction);
});
const pickupLineColumns = [
  { title: "商品 / 规格", key: "product" },
  { title: "来源订单", key: "source", width: 180 },
  { title: "配送数量", key: "quantity", width: 90, align: "right" as const },
];
</script>
<template>
  <AdminDrawer
    title="配送详情与办理"
    wide
    :busy="busy || loading || sourceOrderOpen"
    :close-text="orderNumber ? '返回订单详情' : '关闭配送详情'"
    @close="emit('close')"
  >
    <FeedbackToast v-model:notice="notice" v-model:error="error" />
    <div v-if="orderNumber" class="order-context">
      <span>来自订单 {{ orderNumber }}</span
      ><a-button
        :disabled="busy || loading || sourceOrderOpen"
        @click="emit('close')"
        >返回订单详情</a-button
      >
    </div>
    <a-spin :spinning="loading">
      <div v-if="selected" class="pickup-detail">
        <header class="pickup-summary">
          <div>
            <span class="pickup-caption">配送单号</span
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
              new Date(selected.create_time).toLocaleString("zh-CN", {
                timeZone: "Asia/Shanghai",
                hour12: false,
              })
            }}</a-descriptions-item
          >
        </a-descriptions>
        <section
          v-if="action && selected"
          ref="actionPanel"
          class="action-panel"
        >
          <h3>{{ titles[action] }}</h3>
          <p v-if="action === 'cancel' || action === 'reject'">
            仅释放本次配送占用并退回本次运费积分；商品货款不退款，新购未发货商品仍保留在原订单。
          </p>
          <p v-if="action === 'ship'">
            请核对本次配送的全部商品和收货地址，再登记实际包裹。
          </p>
          <AdminForm :id="formId" class="pickup-form" @submit="submit">
            <div v-if="action === 'ship'" class="logistics-fields">
              <label
                >快递公司<a-input
                  v-model:value="carrier"
                  required
                  :maxlength="60"
                  :disabled="busy"
              /></label>
              <label
                >快递单号<a-input
                  v-model:value="tracking"
                  required
                  :maxlength="80"
                  :disabled="busy"
              /></label>
            </div>
            <label
              >操作原因<a-textarea
                v-model:value="reason"
                required
                :maxlength="2000"
                :disabled="busy"
            /></label>
          </AdminForm>
        </section>
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
                new Date(selected.received_at).toLocaleString("zh-CN", {
                  timeZone: "Asia/Shanghai",
                  hour12: false,
                })
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
    </a-spin>
    <template #actions>
      <template v-if="action">
        <a-button :disabled="busy || sourceOrderOpen" @click="action = ''"
          >取消办理</a-button
        >
        <a-button
          :form="formId"
          html-type="submit"
          type="primary"
          :loading="busy"
          :disabled="busy || sourceOrderOpen"
          >确认{{ titles[action] }}</a-button
        >
      </template>
      <template v-else>
        <a-button v-if="!selected && !loading" @click="load"
          >重新加载配送单</a-button
        >
        <a-button
          v-for="kind in allowedActions"
          :key="kind"
          :type="['ship', 'approve'].includes(kind) ? 'primary' : 'default'"
          :disabled="busy || loading || sourceOrderOpen"
          @click="openAction(kind)"
          >{{ titles[kind] }}</a-button
        >
      </template>
    </template>
  </AdminDrawer>
</template>
<style scoped>
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

.order-context {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
  padding: 12px 16px;
  background: var(--admin-bg);
  color: var(--admin-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.action-panel {
  margin-top: 24px;
  padding: 20px;
  border: 1px solid #e5cbd1;
  border-radius: 6px;
  background: #fcf9fa;
}
.action-panel h3 {
  font-size: 15px;
  margin: 0 0 12px;
}
.action-panel p {
  color: var(--admin-muted);
  line-height: 1.7;
}
.pickup-form,
.pickup-form label {
  display: grid;
  gap: 8px;
}
.pickup-form {
  gap: 16px;
}
.logistics-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
</style>
