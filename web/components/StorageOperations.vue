<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { Radio as AntRadio, Select as ASelect } from "ant-design-vue";
const ARadioGroup = AntRadio.Group,
  ARadioButton = AntRadio.Button;
import { api, type Staff } from "../client";
import { useAdminList } from "../useAdminList";
import AdminForm from "./AdminForm.vue";
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
  held_qty?: number;
  starts_on?: string;
  expires_on?: string;
  status?: string;
  purchased_qty?: number;
  package_name?: string;
  source_kind?: string;
  fee_cents?: number;
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
    lot_id?: number;
    line_id: number;
    order_id: number;
    name: string;
    sku_name: string;
    quantity: number;
    is_gift: boolean;
  }[];
}
const props = defineProps<{ staff: Staff }>();
const sourceKind = ref("");
const tab = ref("lots"),
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
onMounted(reload);
function open(kind: string, row?: Row) {
  lineId.value = 0;
  manualItems.value = [{ lotId: 0, quantity: 1 }];
  quantity.value = 1;
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
async function detail(row: Row) {
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
    <div class="storage-filters">
      <label
        >用户ID<a-input v-model:value="userId" placeholder="全部用户" /></label
      ><template v-if="tab === 'lots'"
        ><label
          >来源类型<ASelect
            v-model:value="sourceKind"
            style="min-width: 180px"
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
        ><label>寄存起日<input v-model="from" type="date" /></label
        ><label>寄存止日<input v-model="to" type="date" /></label
        ><a-checkbox v-model:checked="expiring"
          >30天内到期及已到期</a-checkbox
        ></template
      ><a-button :loading="loading" @click="reload">刷新</a-button
      ><a-button v-if="tab !== 'messages'" :disabled="busy" @click="download"
        >导出Excel</a-button
      >
    </div>
    <div class="storage-filters">
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
            用户 {{ record.user_id
            }}<span v-if="tab === 'lots'">
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
            >购买总量 {{ record.purchased_qty }}（寄存 {{ record.total_qty }}）
            / 已发 {{ record.total_shipped_qty }} / 剩余
            {{ record.remaining_qty }} / 占用 {{ record.held_qty }} / 可提
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
    <AdminDialog
      v-if="operation"
      :title="operation === 'detail' ? '提货详情' : '寄存与身份办理'"
      :busy="busy"
      @close="operation = ''"
    >
      <template v-if="operation === 'detail'"
        ><p>{{ selected?.number }} · {{ states[selected?.status || ""] }}</p>
        <p>
          {{ selected?.address?.recipient }} {{ selected?.address?.phone }}
          {{ selected?.address?.province }}{{ selected?.address?.city
          }}{{ selected?.address?.district }}{{ selected?.address?.detail }}
        </p>
        <p v-for="item in selected?.lines" :key="item.id">
          原订单#{{ item.order_id }} · {{ item.name }} {{ item.sku_name }} ×{{
            item.quantity
          }}{{ item.is_gift ? "（赠品）" : "" }}
        </p>
        <p>{{ selected?.review_reason }}</p></template
      >
      <AdminForm v-else class="storage-form config-form" @submit="submit">
        <label v-if="operation === 'extend'"
          >新到期日<input v-model="expiry" type="date" required
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
            >该用户收货地址ID<a-input v-model:value="addressId" required
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
            >归属19800店主ID<a-input
              v-model:value="principal"
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
            :maxlength="2000" /></label
        ><a-button type="primary" html-type="submit" :loading="busy"
          >确认办理</a-button
        >
      </AdminForm>
    </AdminDialog>
  </section>
</template>
<style scoped>
.storage-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 12px;
  margin: 20px 0;
}
.storage-filters label {
  display: grid;
  gap: 6px;
  max-width: 180px;
}
.storage-filters .storage-product-filter {
  width: 300px;
  max-width: 100%;
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
.storage-form label {
  display: grid;
  gap: 6px;
}
.storage-form input[type="date"],
.storage-filters input[type="date"] {
  padding: 7px;
  border: 1px solid var(--border, #ddd);
  border-radius: 6px;
}
.storage-operations :deep(button) {
  min-height: 36px;
}
</style>
