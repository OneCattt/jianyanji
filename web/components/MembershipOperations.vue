<script setup lang="ts">
import { useAdminList } from "../useAdminList";
import QualificationOperations from "./QualificationOperations.vue";
import FeedbackToast from "./FeedbackToast.vue";
import ImageUploader from "./ImageUploader.vue";
import { computed, onMounted, ref } from "vue";
import { api } from "../client";
import AdminDialog from "./AdminDialog.vue";
import AdminForm from "./AdminForm.vue";
import AdminSelect from "./AdminSelect.vue";
import AdminDatePicker from "./AdminDatePicker.vue";
const props = withDefaults(defineProps<{ permissions?: string[] }>(), {
  permissions: () => ["onboarding:manage", "finance:manage", "catalog:manage"],
});
const imageUploads = ref<Record<string, boolean>>({});
const uploadingImage = computed(() =>
  Object.values(imageUploads.value).some(Boolean),
);
interface Package {
  targetIdentity: "B" | "C";
  name: string;
  amountCents: number;
  valueCents: number;
  selfUseLabel: string;
  benefits: string;
  conditions: string;
  agreement: string;
  rightsImage: string;
  active: boolean;
  lines: { skuId: number; quantity: number; unitCents: number }[];
}
interface PackageSku {
  id: number;
  productName: string;
  skuName: string;
  unit: string;
}
interface Order {
  id: number;
  number: string;
  status: string;
  shipments: {
    id: number;
    carrier: string;
    tracking: string;
    received_at: string | null;
  }[];
}
interface Application {
  id: number;
  user_id: number;
  nickname?: string;
  target_identity: "B" | "C";
  status: string;
  requested_inviter_id: number | null;
  inviter_id: number | null;
  principal_id: number | null;
  reward_cents: number;
  review_reason: string;
  package_snapshot: Package;
  receipt: {
    id: number;
    amount_cents: number;
    external_transaction_id: string;
  } | null;
  order: Order | null;
}
type PackageForm = Omit<Package, "amountCents" | "valueCents" | "lines"> & {
  amount: string;
  value: string;
  lines: { skuId: number; quantity: number; unit: string }[];
};
const dialog = ref<"packages" | "receipt" | null>(null);
const packageDraft = ref<PackageForm[]>([]);
const packageSkus = ref<PackageSku[]>([]);
const packages = ref<PackageForm[]>([]),
  selected = ref<Application | null>(null),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  reason = ref("");
const receipt = ref({
  amount: "",
  sourceChannel: "bank",
  accountRef: "",
  externalTransactionId: "",
  receivedAt: "",
  evidence: "",
  reason: "",
  verified: false,
});
const states: Record<string, string> = {
  submitted: "待核实到账",
  approved: "身份已激活",
  rejected: "已驳回",
  revoked: "本次身份已撤销",
};
const orderStates: Record<string, string> = {
  paid: "待发货",
  shipped: "已发货",
  completed: "已收货",
  refunded: "已退款",
};
const money = (v: number) => (v / 100).toFixed(2);
function cents(value: string) {
  if (!/^\d+(\.\d{1,2})?$/.test(value))
    throw new Error("金额须为最多两位小数的正数");
  const [w, p = ""] = value.split(".");
  const n = Number(w) * 100 + Number(p.padEnd(2, "0"));
  if (!Number.isSafeInteger(n) || n <= 0 || n > 100000000)
    throw new Error("金额超出允许范围");
  return n;
}
function lineSubtotal(line: PackageForm["lines"][number]): number | null {
  if (
    !Number.isInteger(line.quantity) ||
    line.quantity < 1 ||
    line.quantity > 999
  )
    return null;
  try {
    return cents(line.unit) * line.quantity;
  } catch {
    return null;
  }
}
const allocations = computed(() =>
  Object.fromEntries(
    packageDraft.value.map((p) => {
      const total = p.lines.reduce(
        (sum, line) => sum + (lineSubtotal(line) ?? 0),
        0,
      );
      let difference: number | null = null;
      try {
        difference = cents(p.amount) - total;
      } catch {
        // 货款尚未填好时先展示已分摊金额。
      }
      return [p.targetIdentity, { total, difference }];
    }),
  ),
);
function differenceLabel(difference: number | null): string {
  if (difference === null) return "请先填写入驻货款";
  if (difference === 0) return "已配平";
  return `${difference > 0 ? "还差" : "超出"} ¥${money(Math.abs(difference))}`;
}
function skuChoices(p: PackageForm, index: number) {
  const selected = new Set(
    p.lines.filter((_, i) => i !== index).map((l) => l.skuId),
  );
  return packageSkus.value.map((sku) => ({
    value: sku.id,
    label: `${sku.productName} · ${sku.skuName}（${sku.unit}）`,
    disabled: selected.has(sku.id),
  }));
}
function defaults(): PackageForm[] {
  return (["C", "B"] as const).map((targetIdentity) => ({
    targetIdentity,
    name: targetIdentity === "C" ? "店主入驻产品包" : "主理人入驻产品包",
    amount: "",
    value: "",
    selfUseLabel: "专属代理订货价",
    benefits: "",
    conditions: "",
    agreement: "",
    rightsImage: "",
    active: false,
    lines: [{ skuId: 0, quantity: 1, unit: "" }],
  }));
}
async function run(work: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await work();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "办理失败，请重试";
  } finally {
    busy.value = false;
  }
}
async function load() {
  const [settings, rows] = await Promise.all([
    api<{ packages: Package[] } | null>("/admin/membership-settings"),
    loadApplications(),
  ]);
  packages.value = settings
    ? settings.packages.map((p) => {
        const { amountCents, valueCents, lines, ...rest } = p;
        return {
          ...rest,
          amount: money(amountCents),
          value: money(valueCents),
          lines: lines.map((l) => ({
            skuId: l.skuId,
            quantity: l.quantity,
            unit: money(l.unitCents),
          })),
        };
      })
    : defaults();
  applications.value = rows;
  if (selected.value)
    selected.value = rows.find((a) => a.id === selected.value!.id) || null;
}
async function save() {
  if (uploadingImage.value) return;
  await run(async () => {
    const payload = packageDraft.value.map((p) => {
      const { amount, value, lines, ...rest } = p;
      if (
        lines.some(
          (line) =>
            !Number.isInteger(line.skuId) ||
            line.skuId < 1 ||
            !Number.isInteger(line.quantity) ||
            line.quantity < 1 ||
            line.quantity > 999,
        )
      )
        throw new Error("请选择商品规格，数量须为 1～999 的整数");
      const selected = new Set<number>();
      const packageLines = lines.map((line) => {
        if (!packageSkus.value.some((sku) => sku.id === line.skuId))
          throw new Error("所选商品规格已不可用，请重新选择");
        if (selected.has(line.skuId))
          throw new Error("同一产品包不能重复选择同一规格");
        selected.add(line.skuId);
        return {
          skuId: line.skuId,
          quantity: line.quantity,
          unitCents: cents(line.unit),
        };
      });
      const amountCents = cents(amount);
      const total = packageLines.reduce(
        (sum, line) => sum + line.quantity * line.unitCents,
        0,
      );
      if (total !== amountCents)
        throw new Error(
          `${p.targetIdentity === "C" ? "店主" : "主理人"}产品包分摊未配平，${differenceLabel(amountCents - total)}`,
        );
      return {
        ...rest,
        amountCents,
        valueCents: cents(value),
        lines: packageLines,
      };
    });
    await api("/admin/membership-settings", {
      method: "PUT",
      body: { packages: payload, reason: reason.value },
    });
    await load();
    dialog.value = null;
    notice.value = "产品包权益、条件与货款已保存；历史申请保持原快照";
  });
}
async function openPackages() {
  await run(async () => {
    packageSkus.value = await api<PackageSku[]>("/admin/membership-skus");
    packageDraft.value = packages.value.map((p) => ({
      ...p,
      lines: p.lines.map((line) => ({ ...line })),
    }));
    reason.value = "";
    dialog.value = "packages";
  });
}
function closeDialog() {
  if (!busy.value && !uploadingImage.value) dialog.value = null;
}
function select(a: Application) {
  selected.value = a;
}
function openApplicationDialog(kind: "receipt") {
  const a = selected.value;
  if (!a) return;
  error.value = "";
  receipt.value = {
    amount: money(a.package_snapshot.amountCents),
    sourceChannel: "bank",
    accountRef: "",
    externalTransactionId: "",
    receivedAt: "",
    evidence: "",
    reason: "",
    verified: false,
  };
  dialog.value = kind;
}
async function collect() {
  if (!selected.value) return;
  const id = selected.value.id;
  await run(async () => {
    await api("/admin/onboardings/" + id + "/receipt", {
      method: "POST",
      body: {
        sourceChannel: receipt.value.sourceChannel,
        accountRef: receipt.value.accountRef,
        externalTransactionId: receipt.value.externalTransactionId,
        amountCents: cents(receipt.value.amount),
        receivedAt: new Date(receipt.value.receivedAt).toISOString(),
        evidence: receipt.value.evidence,
        reason: receipt.value.reason,
        verified: receipt.value.verified,
      },
    });
    await load();
    dialog.value = null;
    notice.value =
      "已核实收款、扣实物库存并生成交付订单，身份已按付款前确认激活";
  });
}
onMounted(() => run(load));
const applicationColumns = [
  { title: "申请 / 申请人", key: "applicant", width: 180 },
  { title: "目标身份", key: "identity", width: 180 },
  { title: "资格状态", key: "status", width: 180 },
  { title: "核款 / 商品交付", key: "receipt", width: 180 },
  { title: "操作", key: "actions", width: 170, fixed: "right" as const },
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

const applicationQuery = ref("");
const applicationStatus = ref("");
const applicationIdentity = ref("");
const {
  rows: applications,
  pagination: applicationPagination,
  loading: listLoading,
  load: loadApplications,
} = useAdminList<Application>(
  "/admin/onboardings",
  () => ({
    q: applicationQuery.value,
    status: applicationStatus.value,
    identity: applicationIdentity.value,
  }),
  error,
);
const filteredApplications = computed(() => applications.value);

const packageLineColumns = [
  { title: "实际商品规格", key: "sku", width: 180 },
  { title: "数量", key: "quantity", width: 100 },
  { title: "原分摊单价", key: "unit", width: 180 },
];
</script>
<template>
  <section class="membership-operations" :aria-busy="busy">
    <Teleport defer to="#admin-page-actions">
      <a-button
        html-type="button"
        type="primary"
        :disabled="busy"
        @click="openPackages"
      >
        配置入驻产品包
      </a-button>
    </Teleport>
    <p class="admin-business-note">
      产品包货款用于商品交付，不发订货积分；入驻奖励按奖励规则核算，售后期按统一售后政策执行。
    </p>
    <FeedbackToast v-model:notice="notice" v-model:error="error" />
    <a-card size="small" class="application-list" aria-label="入驻申请列表">
      <div class="heading">
        <div>
          <h3>入驻申请</h3>
          <p class="muted">
            资格、协议与邀请归属在付款前确认，核实到账后自动激活身份。
          </p>
        </div>
      </div>
      <AdminForm class="filters" @submit="() => {}">
        <label class="field"
          >搜索申请 / 申请人<a-input
            v-model:value="applicationQuery"
            placeholder="申请 ID、用户 ID 或昵称"
            :disabled="busy"
        /></label>
        <label class="field"
          >目标身份<AdminSelect v-model="applicationIdentity" :disabled="busy"
            ><option value="">全部身份</option>
            <option value="B">调肤主理人</option>
            <option value="C">调肤店主</option></AdminSelect
          ></label
        >
        <label class="field"
          >资格状态<AdminSelect v-model="applicationStatus" :disabled="busy"
            ><option value="">全部状态</option>
            <option v-for="(label, key) in states" :key="key" :value="key">
              {{ label }}
            </option></AdminSelect
          ></label
        >
      </AdminForm>
      <div
        class="table-wrap"
        tabindex="0"
        role="region"
        aria-label="入驻申请，可横向滚动"
      >
        <a-table
          :columns="applicationColumns"
          :data-source="filteredApplications"
          row-key="id"
          :pagination="applicationPagination"
          :scroll="{ x: 900 }"
          size="small"
          :loading="busy || listLoading"
          :row-class-name="
            (record: Application) =>
              selected?.id === record.id ? 'selected-application' : ''
          "
          ><template #bodyCell="{ column, record: a }"
            ><template v-if="column.key === 'applicant'"
              >#{{ a.id }} · {{ a.nickname || "用户 #" + a.user_id }}</template
            ><template v-else-if="column.key === 'identity'">
              {{
                a.target_identity === "B" ? "调肤主理人" : "调肤店主"
              }} </template
            ><template v-else-if="column.key === 'status'">
              <a-tag :color="statusColor(a.status)">{{
                states[a.status] || a.status
              }}</a-tag
              ><small
                class="cell-note review-summary"
                :title="a.review_reason"
                >{{ a.review_reason }}</small
              > </template
            ><template v-else-if="column.key === 'receipt'">
              {{
                a.receipt
                  ? "到账 ¥" + money(a.receipt.amount_cents)
                  : "待核实真实到账"
              }}<small class="cell-note">{{
                a.order
                  ? orderStates[a.order.status] || a.order.status
                  : "尚未建立交付单"
              }}</small> </template
            ><template v-else-if="column.key === 'actions'">
              <a-button
                html-type="button"
                :disabled="busy"
                :aria-pressed="selected?.id === a.id"
                @click="select(a)"
              >
                核款与交付
              </a-button>
            </template></template
          ></a-table
        >
      </div>
    </a-card>
    <a-card size="small" v-if="selected" class="application-detail">
      <header class="detail-heading">
        <h3>
          入驻申请 #{{ selected.id }} · {{ selected.package_snapshot.name }}
        </h3>
        <p>
          用户 #{{ selected.user_id }} · 货款 ¥{{
            money(selected.package_snapshot.amountCents)
          }}
          · {{ states[selected.status] }}
        </p>
        <p v-if="selected.review_reason" class="muted">
          处理说明：{{ selected.review_reason }}
        </p>
        <p class="muted">
          当次入驻条件：{{ selected.package_snapshot.conditions }}
        </p>
        <a-descriptions size="small" bordered :column="2">
          <a-descriptions-item label="申请直接邀请人">{{
            selected.requested_inviter_id
              ? "用户 #" + selected.requested_inviter_id
              : "历史申请缺少确认，请本人重新确认"
          }}</a-descriptions-item>
          <a-descriptions-item label="确认直接邀请人">{{
            selected.inviter_id ? "用户 #" + selected.inviter_id : "尚未确认"
          }}</a-descriptions-item>
          <a-descriptions-item label="归属主理人">{{
            selected.principal_id
              ? "用户 #" + selected.principal_id
              : "尚未确认"
          }}</a-descriptions-item>
        </a-descriptions>
        <a-table
          :columns="packageLineColumns"
          :data-source="selected.package_snapshot.lines"
          row-key="skuId"
          :pagination="false"
          :scroll="{ x: 460 }"
          size="small"
        >
          <template #bodyCell="{ column, record: line }"
            ><template v-if="column.key === 'sku'"
              >规格 #{{ line.skuId }}</template
            ><template v-else-if="column.key === 'quantity'">{{
              line.quantity
            }}</template
            ><template v-else-if="column.key === 'unit'"
              >¥{{ money(line.unitCents) }}</template
            ></template
          >
        </a-table>
      </header>
      <a-space wrap>
        <a-button
          html-type="button"
          type="primary"
          v-if="
            selected.status === 'submitted' &&
            !selected.receipt &&
            props.permissions.includes('finance:manage')
          "
          :disabled="busy"
          @click="openApplicationDialog('receipt')"
        >
          核实真实到账
        </a-button>
        <a-button html-type="button" :disabled="busy" @click="selected = null">
          关闭详情
        </a-button>
      </a-space>
      <div class="review-workspace">
        <section
          v-if="selected.receipt || selected.order"
          class="workflow-panel"
        >
          <p v-if="selected.receipt">
            原收款 #{{ selected.receipt.id }} ·
            {{ selected.receipt.external_transaction_id }}
          </p>
          <p v-if="selected.order">
            商品交付订单 {{ selected.order.number }} ·
            {{
              orderStates[selected.order.status] || selected.order.status
            }}（请在订单与交付中安排发货）
          </p>
        </section>
      </div>
    </a-card>
    <a-collapse
      v-if="props.permissions.includes('onboarding:manage')"
      class="qualification-tools"
    >
      <a-collapse-panel key="qualifications" header="资格核验与归属调整">
        <QualificationOperations />
      </a-collapse-panel>
    </a-collapse>
    <AdminDialog
      v-if="dialog === 'packages'"
      title="配置入驻产品包"
      :busy="busy || uploadingImage"
      wide
      @close="closeDialog"
    >
      <AdminForm class="settings" @submit="save">
        <fieldset
          class="package-fields"
          v-for="p in packageDraft"
          :key="p.targetIdentity"
        >
          <legend>
            {{ p.targetIdentity === "B" ? "调肤主理人" : "调肤店主" }}
          </legend>
          <div class="grid">
            <label class="field"
              >产品包名称<a-input
                v-model:value="p.name"
                :maxlength="80"
                required
                :disabled="busy" /></label
            ><label class="field"
              >入驻货款（元）<a-input
                v-model:value="p.amount"
                pattern="[0-9]+([.][0-9]{1,2})?"
                data-error-message="请填写最多两位小数的金额"
                inputmode="decimal"
                required
                :disabled="busy" /></label
            ><label class="field"
              >产品包展示价值（元）<a-input
                v-model:value="p.value"
                pattern="[0-9]+([.][0-9]{1,2})?"
                data-error-message="请填写最多两位小数的金额"
                inputmode="decimal"
                required
                :disabled="busy" /></label
            ><label class="field"
              >自用权益说明<a-input
                v-model:value="p.selfUseLabel"
                :maxlength="40"
                required
                :disabled="busy"
            /></label>
            <label class="field wide"
              >身份权益<a-textarea
                :rows="3"
                v-model:value="p.benefits"
                :maxlength="2000"
                required
                :disabled="busy"
              ></a-textarea></label
            ><label class="field wide"
              >入驻条件<a-textarea
                :rows="3"
                v-model:value="p.conditions"
                :maxlength="2000"
                required
                :disabled="busy"
              ></a-textarea></label
            ><label class="field wide"
              >入驻协议<a-textarea
                :rows="3"
                v-model:value="p.agreement"
                :maxlength="2000"
                required
                :disabled="busy"
              ></a-textarea>
            </label>
            <ImageUploader
              v-model="p.rightsImage"
              label="权益说明图片"
              purpose="membership"
              :disabled="busy"
              @busy-change="imageUploads[p.targetIdentity] = $event"
            />
          </div>
          <a-checkbox class="check" v-model:checked="p.active" :disabled="busy"
            >开放该身份申请</a-checkbox
          >
          <h4>实际商品及原货款分摊</h4>
          <p class="muted">
            各商品分摊货款合计须等于产品包货款，用于交付与后续部分退款。
          </p>
          <div v-for="(line, i) in p.lines" :key="i" class="package-line">
            <label class="field package-line-product"
              >商品与规格<AdminSelect
                v-model="line.skuId"
                :aria-label="`${p.targetIdentity === 'C' ? '店主' : '主理人'}产品包第${i + 1}项商品与规格`"
                required
                :disabled="busy"
              >
                <option :value="0" disabled>
                  {{
                    packageSkus.length
                      ? "搜索商品名或规格名"
                      : "暂无在售规格，请先在商品管理中上架"
                  }}
                </option>
                <option
                  v-if="
                    line.skuId &&
                    !packageSkus.some((sku) => sku.id === line.skuId)
                  "
                  :value="line.skuId"
                  disabled
                >
                  原商品规格已不可用，请重新选择
                </option>
                <option
                  v-for="choice in skuChoices(p, i)"
                  :key="choice.value"
                  :value="choice.value"
                  :disabled="choice.disabled"
                >
                  {{ choice.label }}
                </option>
              </AdminSelect></label
            ><label class="field"
              >数量<a-input-number
                type="number"
                :step="1"
                v-model:value="line.quantity"
                :min="1"
                :max="999"
                required
                :disabled="busy" /></label
            ><label class="field"
              >分摊单价（元）<a-input
                v-model:value="line.unit"
                pattern="[0-9]+([.][0-9]{1,2})?"
                data-error-message="请填写最多两位小数的金额"
                inputmode="decimal"
                required
                :disabled="busy"
            /></label>
            <div class="package-line-footer">
              <span class="package-line-subtotal"
                >小计
                {{
                  lineSubtotal(line) === null
                    ? "—"
                    : `¥${money(lineSubtotal(line)!)}`
                }}</span
              >
              <a-button
                html-type="button"
                type="link"
                size="small"
                :disabled="busy || uploadingImage"
                v-if="p.lines.length > 1"
                @click="p.lines.splice(i, 1)"
              >
                移除规格
              </a-button>
            </div>
          </div>
          <a-button
            html-type="button"
            :disabled="busy || uploadingImage || p.lines.length >= 50"
            @click="p.lines.push({ skuId: 0, quantity: 1, unit: '' })"
          >
            添加商品规格
          </a-button>
          <dl class="allocation-summary" aria-live="polite" aria-atomic="true">
            <div>
              <dt>分摊合计</dt>
              <dd>¥{{ money(allocations[p.targetIdentity].total) }}</dd>
            </div>
            <div>
              <dt>差额</dt>
              <dd
                :class="{
                  'allocation-balanced':
                    allocations[p.targetIdentity].difference === 0,
                  'allocation-unbalanced':
                    allocations[p.targetIdentity].difference !== null &&
                    allocations[p.targetIdentity].difference !== 0,
                }"
              >
                {{ differenceLabel(allocations[p.targetIdentity].difference) }}
              </dd>
            </div>
          </dl>
        </fieldset>
        <label class="field"
          >修改原因<a-input
            v-model:value="reason"
            :maxlength="200"
            required
            :disabled="busy"
        /></label>
        <div class="dialog-actions wide">
          <a-button
            html-type="button"
            :disabled="busy || uploadingImage"
            @click="closeDialog"
          >
            取消
          </a-button>
          <a-button
            html-type="submit"
            type="primary"
            :disabled="busy || uploadingImage"
            :loading="busy || listLoading"
          >
            保存两类产品包
          </a-button>
        </div>
      </AdminForm>
    </AdminDialog>
    <AdminDialog
      v-if="dialog === 'receipt' && selected"
      :title="'入驻核款 · 申请 #' + selected.id"
      :busy="busy"
      @close="closeDialog"
    >
      <AdminForm class="grid" @submit="collect">
        <h4 class="wide">核实真实到账并安排实物交付</h4>
        <label class="field"
          >到账货款（元）<a-input
            v-model:value="receipt.amount"
            pattern="[0-9]+([.][0-9]{1,2})?"
            data-error-message="请填写最多两位小数的金额"
            required
            inputmode="decimal"
            :disabled="busy" /></label
        ><label class="field"
          >收款渠道<AdminSelect
            v-model="receipt.sourceChannel"
            :disabled="busy"
          >
            <option value="bank">公司银行账户</option>
            <option value="wechat_company">公司微信收款</option>
          </AdminSelect></label
        ><label class="field"
          >收款账户标识<a-input
            v-model:value="receipt.accountRef"
            required
            :maxlength="200"
            :disabled="busy" /></label
        ><label class="field"
          >原始流水号<a-input
            v-model:value="receipt.externalTransactionId"
            required
            :maxlength="200"
            :disabled="busy" /></label
        ><label class="field"
          >实际到账时间<AdminDatePicker
            v-model="receipt.receivedAt"
            required
            type="datetime-local"
            :disabled="busy" /></label
        ><label class="field"
          >凭证地址 / 归档编号<a-input
            v-model:value="receipt.evidence"
            required
            :maxlength="1000"
            :disabled="busy" /></label
        ><label class="field"
          >核实说明<a-input
            v-model:value="receipt.reason"
            required
            :maxlength="200"
            :disabled="busy" /></label
        ><a-checkbox
          class="check"
          v-model:checked="receipt.verified"
          required
          :disabled="busy"
          >我已核实公司实际到账</a-checkbox
        >
        <div class="dialog-actions wide">
          <a-button html-type="button" :disabled="busy" @click="closeDialog">
            取消
          </a-button>
          <a-button
            html-type="submit"
            type="primary"
            :disabled="busy"
            :loading="busy || listLoading"
            >确认收款并建立交付单</a-button
          >
        </div>
      </AdminForm>
    </AdminDialog>
  </section>
</template>
<style scoped>
.qualification-tools {
  margin-top: 16px;
}

.membership-operations {
  display: grid;
  gap: 16px;
  min-width: 0;
  line-height: 1.6;
}
.application-list,
.application-detail,
.table-wrap {
  min-width: 0;
}
.heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}
.heading > div {
  min-width: 0;
}
h3,
h4 {
  margin: 0;
  font-size: 15px;
  line-height: 1.5;
}
h4 {
  font-size: 14px;
}
p {
  margin: 0;
}
.detail-heading {
  min-width: 0;
  grid-template-columns: minmax(0, 1fr);
  display: grid;
  gap: 10px;
}
.application-detail :deep(.ant-card-body) {
  grid-template-columns: minmax(0, 1fr);
  display: grid;
  gap: 16px;
}
.settings {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: 20px;
}
.settings > .field,
.settings > .dialog-actions {
  grid-column: 1 / -1;
}
.package-fields {
  min-width: 0;
  margin: 0;
  padding: 16px;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
}
.package-fields > legend {
  width: auto;
  padding: 0 6px;
  margin-bottom: 0;
  font-size: 14px;
  font-weight: 600;
}
.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-content: start;
  gap: 16px;
}
.wide {
  grid-column: 1 / -1;
}
.field {
  display: grid;
  align-content: start;
  min-width: 0;
  gap: 6px;
}
.field :deep(.ant-input-number) {
  width: 100%;
}
.package-fields > .check {
  margin: 16px 0;
}
.package-line {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 16px 0;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--admin-border);
}
.package-line-product,
.package-line-footer {
  grid-column: 1 / -1;
}
.package-line-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}
.package-line-subtotal,
.allocation-summary dd {
  font-variant-numeric: tabular-nums;
}
.package-line-subtotal {
  color: var(--admin-muted);
}
.allocation-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 16px 0 0;
  padding-top: 16px;
  border-top: 1px solid var(--admin-border);
}
.allocation-summary dt {
  color: var(--admin-muted);
  font-size: 12px;
}
.allocation-summary dd {
  margin: 4px 0 0;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.allocation-balanced {
  color: #237346;
}
.allocation-unbalanced {
  color: var(--admin-accent);
}
.inline {
  display: flex;
  align-items: end;
  flex-wrap: wrap;
  gap: 12px;
}
.package-fields > .inline {
  margin: 12px 0;
}
.inline > .field {
  flex: 1 1 110px;
}
.muted,
.cell-note {
  color: var(--admin-muted);
}
.cell-note {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.filters {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
}
.workflow-panel {
  display: grid;
  gap: 8px;
}
.review-summary {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
}
.application-list :deep(.selected-application > .ant-table-cell) {
  background: var(--admin-accent-soft);
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  grid-column: 1 / -1;
  margin-top: 4px;
}
@media (max-width: 1100px) {
  .settings {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 650px) {
  .grid,
  .filters {
    grid-template-columns: minmax(0, 1fr);
  }
  .inline > .field {
    flex-basis: 100%;
  }
}
</style>
