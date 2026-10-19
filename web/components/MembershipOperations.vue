<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "../client";
import AdminDialog from "./AdminDialog.vue";
import AdminForm from "./AdminForm.vue";
import AdminSelect from "./AdminSelect.vue";
import AdminDatePicker from "./AdminDatePicker.vue";
const props = withDefaults(defineProps<{ permissions?: string[] }>(), {
  permissions: () => ["onboarding:manage", "finance:manage", "catalog:manage"],
});
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
const dialog = ref<"packages" | "receipt" | "review" | null>(null);
const packageDraft = ref<PackageForm[]>([]);
const reviewDecision = ref<"approved" | "rejected">("approved");
const packages = ref<PackageForm[]>([]),
  applications = ref<Application[]>([]),
  selected = ref<Application | null>(null),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  reason = ref("");
const review = ref({
  inviterId: "",
  principalId: "",
  conditionsMet: false,
  relationshipsConfirmed: false,
  reason: "",
});
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
  submitted: "待核款 / 待审批",
  approved: "身份已通过",
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
    api<Application[]>("/admin/onboardings"),
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
  await run(async () => {
    const payload = packageDraft.value.map((p) => {
      const { amount, value, lines, ...rest } = p;
      return {
        ...rest,
        amountCents: cents(amount),
        valueCents: cents(value),
        lines: lines.map((l) => ({
          skuId: Number(l.skuId),
          quantity: Number(l.quantity),
          unitCents: cents(l.unit),
        })),
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
function openPackages() {
  packageDraft.value = packages.value.map((p) => ({
    ...p,
    lines: p.lines.map((line) => ({ ...line })),
  }));
  reason.value = "";
  error.value = "";
  dialog.value = "packages";
}
function closeDialog() {
  if (!busy.value) dialog.value = null;
}
function select(a: Application) {
  selected.value = a;
}
function openApplicationDialog(kind: "receipt" | "review") {
  const a = selected.value;
  if (!a) return;
  error.value = "";
  reviewDecision.value = "approved";
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
  review.value = {
    inviterId: String(a.inviter_id ?? a.requested_inviter_id ?? ""),
    principalId: String(a.principal_id ?? ""),
    conditionsMet: false,
    relationshipsConfirmed: false,
    reason: "",
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
    notice.value = "已核实收款、扣实物库存并生成交付订单，身份仍须独立审批";
  });
}
async function decide(decision: "approved" | "rejected") {
  if (!selected.value) return;
  const id = selected.value.id;
  await run(async () => {
    await api("/admin/onboardings/" + id + "/review", {
      method: "POST",
      body: {
        decision,
        reason: review.value.reason,
        ...(decision === "approved"
          ? {
              inviterId: review.value.inviterId
                ? Number(review.value.inviterId)
                : null,
              principalId: Number(review.value.principalId),
              conditionsMet: review.value.conditionsMet,
              relationshipsConfirmed: review.value.relationshipsConfirmed,
            }
          : {}),
      },
    });
    await load();
    dialog.value = null;
    notice.value =
      decision === "approved"
        ? "代理身份已启用，商品交付状态请在订单中继续核对"
        : "已记录驳回原因；已收款申请仍须按售后处理实际货款";
  });
}
onMounted(() => run(load));
</script>
<template>
  <section class="membership-operations" :aria-busy="busy">
    <header class="membership-page-heading">
      <h2>代理入驻与产品包</h2>
      <p class="muted">
        产品包只接受公司转款，用于实际商品交付，不发订货积分。两档入驻奖励从“奖励规则”读取，售后期从统一售后政策读取。
      </p>
      <div class="inline">
        <button :disabled="busy" @click="openPackages">配置入驻产品包</button>
      </div>
    </header>
    <p v-if="error && !dialog" role="alert" class="feedback error">
      {{ error }}
    </p>
    <p v-if="notice" role="status" class="feedback notice">{{ notice }}</p>
    <section class="surface application-list" aria-label="入驻申请列表">
      <div class="heading">
        <div>
          <h3>入驻申请</h3>
          <p class="muted">核对公司到账与商品交付，再独立审批代理身份。</p>
        </div>
        <button :disabled="busy" @click="run(load)">刷新</button>
      </div>
      <div
        class="table-wrap"
        tabindex="0"
        role="region"
        aria-label="入驻申请，可横向滚动"
      >
        <table>
          <thead>
            <tr>
              <th>申请 / 申请人</th>
              <th>目标身份</th>
              <th>身份审批</th>
              <th>核款 / 商品交付</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="a in applications"
              :key="a.id"
              :class="{ selected: selected?.id === a.id }"
            >
              <td>#{{ a.id }} · {{ a.nickname || "用户 #" + a.user_id }}</td>
              <td>
                {{ a.target_identity === "B" ? "调肤主理人" : "调肤店主" }}
              </td>
              <td>
                {{ states[a.status] }}<small>{{ a.review_reason }}</small>
              </td>
              <td>
                {{
                  a.receipt
                    ? "到账 ¥" + money(a.receipt.amount_cents)
                    : "待核实真实到账"
                }}<small>{{
                  a.order
                    ? orderStates[a.order.status] || a.order.status
                    : "尚未建立交付单"
                }}</small>
              </td>
              <td>
                <button
                  :aria-pressed="selected?.id === a.id"
                  @click="select(a)"
                >
                  核款与审核
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="busy && !applications.length" class="empty-state" role="status">
        正在加载入驻申请…
      </p>
      <p v-else-if="!error && !applications.length" class="empty-state">
        暂无入驻申请，收到新的申请后可在此核款与审核。
      </p>
    </section>
    <article v-if="selected" class="surface application-detail">
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
        <p class="muted">
          当次入驻条件：{{ selected.package_snapshot.conditions }}
        </p>
        <p>
          申请直接邀请人：{{
            selected.requested_inviter_id
              ? "用户 #" + selected.requested_inviter_id
              : "无邀请人，归属主理人由品牌方指定"
          }}
        </p>
        <ul>
          <li v-for="l in selected.package_snapshot.lines" :key="l.skuId">
            规格 #{{ l.skuId }} × {{ l.quantity }} · 原分摊单价 ¥{{
              money(l.unitCents)
            }}
          </li>
        </ul>
      </header>
      <div class="inline">
        <button
          v-if="
            selected.status === 'submitted' &&
            !selected.receipt &&
            props.permissions.includes('finance:manage')
          "
          :disabled="busy"
          @click="openApplicationDialog('receipt')"
        >
          核实真实到账
        </button>
        <button
          v-if="selected.status === 'submitted'"
          :disabled="busy"
          @click="openApplicationDialog('review')"
        >
          审核代理身份
        </button>
        <button :disabled="busy" @click="selected = null">关闭详情</button>
      </div>
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
    </article>
    <AdminDialog
      v-if="dialog === 'packages'"
      title="配置入驻产品包"
      :busy="busy"
      wide
      @close="closeDialog"
    >
      <p v-if="error" role="alert" class="feedback error">{{ error }}</p>
      <AdminForm class="settings" @submit="save">
        <fieldset v-for="p in packageDraft" :key="p.targetIdentity">
          <legend>
            {{ p.targetIdentity === "B" ? "调肤主理人" : "调肤店主" }}
          </legend>
          <div class="grid">
            <label
              >产品包名称<input
                v-model="p.name"
                maxlength="80"
                required /></label
            ><label
              >入驻货款（元）<input
                v-model="p.amount"
                pattern="[0-9]+([.][0-9]{1,2})?"
                data-error-message="请填写最多两位小数的金额"
                inputmode="decimal"
                required /></label
            ><label
              >产品包展示价值（元）<input
                v-model="p.value"
                pattern="[0-9]+([.][0-9]{1,2})?"
                data-error-message="请填写最多两位小数的金额"
                inputmode="decimal"
                required /></label
            ><label
              >自用权益说明<input
                v-model="p.selfUseLabel"
                maxlength="40"
                required
            /></label>
            <label
              >身份权益<textarea
                v-model="p.benefits"
                maxlength="2000"
                required
              ></textarea></label
            ><label
              >入驻条件<textarea
                v-model="p.conditions"
                maxlength="2000"
                required
              ></textarea></label
            ><label
              >入驻协议<textarea
                v-model="p.agreement"
                maxlength="2000"
                required
              ></textarea></label
            ><label
              >权益说明图片地址<input
                v-model="p.rightsImage"
                placeholder="https://… 或 /assets/…"
            /></label>
          </div>
          <label class="check"
            ><input v-model="p.active" type="checkbox" />开放该身份申请</label
          >
          <h4>实际商品及原货款分摊</h4>
          <p class="muted">
            各商品分摊货款合计须等于产品包货款，用于交付与后续部分退款。
          </p>
          <div v-for="(line, i) in p.lines" :key="i" class="inline">
            <label
              >商品规格编号<input
                v-model.number="line.skuId"
                type="number"
                min="1"
                required /></label
            ><label
              >数量<input
                v-model.number="line.quantity"
                type="number"
                min="1"
                max="999"
                required /></label
            ><label
              >分摊单价（元）<input
                v-model="line.unit"
                pattern="[0-9]+([.][0-9]{1,2})?"
                data-error-message="请填写最多两位小数的金额"
                inputmode="decimal"
                required /></label
            ><button
              v-if="p.lines.length > 1"
              type="button"
              @click="p.lines.splice(i, 1)"
            >
              移除规格
            </button>
          </div>
          <button
            type="button"
            @click="p.lines.push({ skuId: 0, quantity: 1, unit: '' })"
          >
            添加商品规格
          </button>
        </fieldset>
        <label
          >修改原因<input v-model="reason" maxlength="200" required
        /></label>
        <div class="dialog-actions wide">
          <button type="button" :disabled="busy" @click="closeDialog">
            取消
          </button>
          <button class="primary" :disabled="busy">保存两类产品包</button>
        </div>
      </AdminForm>
    </AdminDialog>
    <AdminDialog
      v-if="dialog === 'receipt' && selected"
      :title="'入驻核款 · 申请 #' + selected.id"
      :busy="busy"
      @close="closeDialog"
    >
      <p v-if="error" role="alert" class="feedback error">{{ error }}</p>
      <AdminForm class="grid" @submit="collect">
        <h4 class="wide">核实真实到账并安排实物交付</h4>
        <label
          >到账货款（元）<input
            v-model="receipt.amount"
            pattern="[0-9]+([.][0-9]{1,2})?"
            data-error-message="请填写最多两位小数的金额"
            required
            inputmode="decimal" /></label
        ><label
          >收款渠道<AdminSelect v-model="receipt.sourceChannel">
            <option value="bank">公司银行账户</option>
            <option value="wechat_company">公司微信收款</option>
          </AdminSelect></label
        ><label
          >收款账户标识<input
            v-model="receipt.accountRef"
            required
            maxlength="200" /></label
        ><label
          >原始流水号<input
            v-model="receipt.externalTransactionId"
            required
            maxlength="200" /></label
        ><label
          >实际到账时间<AdminDatePicker
            v-model="receipt.receivedAt"
            required
            type="datetime-local" /></label
        ><label
          >凭证地址 / 归档编号<input
            v-model="receipt.evidence"
            required
            maxlength="1000" /></label
        ><label
          >核实说明<input
            v-model="receipt.reason"
            required
            maxlength="200" /></label
        ><label class="check"
          ><input
            v-model="receipt.verified"
            type="checkbox"
            required
          />我已核实公司实际到账</label
        >
        <div class="dialog-actions wide">
          <button type="button" :disabled="busy" @click="closeDialog">
            取消
          </button>
          <button class="primary" :disabled="busy">确认收款并建立交付单</button>
        </div>
      </AdminForm>
    </AdminDialog>
    <AdminDialog
      v-if="dialog === 'review' && selected"
      :title="'身份审核 · 申请 #' + selected.id"
      :busy="busy"
      @close="closeDialog"
    >
      <p v-if="error" role="alert" class="feedback error">{{ error }}</p>
      <AdminForm class="grid review" @submit="decide(reviewDecision)">
        <h4 class="wide">独立审批身份与邀请归属</h4>
        <p v-if="!selected.receipt" class="muted wide">
          通过身份审批前，须先完成公司到账核实。
        </p>
        <label class="wide"
          >审核结果<AdminSelect v-model="reviewDecision">
            <option value="approved">通过并启用身份</option>
            <option value="rejected">驳回并记录原因</option>
          </AdminSelect></label
        >
        <template v-if="reviewDecision === 'approved'">
          <label
            >确认直接邀请人编号（无邀请人留空）<input
              v-model="review.inviterId"
              type="number"
              min="1" /></label
          ><label
            >品牌方确认归属主理人编号<input
              v-model="review.principalId"
              type="number"
              min="1"
              required /></label
          ><label class="check"
            ><input
              v-model="review.conditionsMet"
              type="checkbox"
              required
            />入驻资格已核实符合当次条件</label
          ><label class="check"
            ><input
              v-model="review.relationshipsConfirmed"
              type="checkbox"
              required
            />邀请人和归属主理人已分别确认</label
          >
        </template>
        <label class="wide"
          >审批原因<input v-model="review.reason" required maxlength="200"
        /></label>
        <div class="dialog-actions wide">
          <button type="button" :disabled="busy" @click="closeDialog">
            取消
          </button>
          <button
            class="primary"
            :disabled="
              busy || (reviewDecision === 'approved' && !selected.receipt)
            "
          >
            {{
              reviewDecision === "approved"
                ? "通过并启用身份"
                : "驳回并记录原因"
            }}
          </button>
        </div>
      </AdminForm>
    </AdminDialog>
  </section>
</template>
<style scoped>
.membership-operations {
  display: grid;
  gap: 16px;
  min-width: 0;
  color: var(--admin-ink);
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.membership-page-heading,
.detail-heading {
  display: grid;
  gap: 6px;
}
h2,
h3,
h4,
p {
  margin: 0;
}
h2 {
  font-size: 18px;
}
h3 {
  font-size: 15px;
}
h4 {
  font-size: 13px;
}
.surface {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: #fff;
}
.heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}
.heading > div {
  min-width: 0;
}
.settings {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: 16px;
  margin-top: 16px;
}
.settings > label,
.settings > button {
  grid-column: 1 / -1;
}
.settings > button {
  justify-self: start;
}
fieldset {
  min-width: 0;
  margin: 0;
  padding: 12px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
legend {
  padding: 0 6px;
  font-weight: 600;
}
.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-content: start;
  gap: 12px;
}
.wide {
  grid-column: 1 / -1;
}
label {
  display: grid;
  min-width: 0;
  gap: 5px;
  font-size: 13px;
}
input,
select,
textarea {
  min-width: 0;
  max-width: 100%;
  width: 100%;
  box-sizing: border-box;
}
textarea {
  min-height: 80px;
  resize: vertical;
}
.check {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
fieldset > .check {
  margin: 12px 0;
}
.check input {
  flex: 0 0 16px;
  width: 16px;
  height: 16px;
  margin: 2px 0 0;
  accent-color: var(--admin-accent);
}
.inline {
  display: flex;
  align-items: end;
  flex-wrap: wrap;
  gap: 8px;
}
fieldset > .inline {
  margin: 12px 0;
}
.inline > label {
  flex: 1 1 100px;
}
.grid > button {
  justify-self: start;
}
.muted,
small {
  color: var(--admin-muted);
}
small {
  display: block;
  margin-top: 4px;
  overflow-wrap: anywhere;
}
.feedback {
  padding: 10px 12px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
.error,
.notice {
  color: var(--admin-accent);
  background: var(--admin-accent-soft);
}
.error {
  border-color: var(--admin-accent);
}
.application-detail {
  display: grid;
  gap: 16px;
}
.detail-heading ul {
  margin: 2px 0 0;
  padding-left: 20px;
}
.review-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
  gap: 16px;
}
.workflow-panel {
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
.workflow-panel:only-child {
  grid-column: 1 / -1;
}
.workflow-panel > p + p {
  margin-top: 8px;
}
.table-wrap {
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
}
td {
  white-space: normal;
}
table {
  min-width: 680px;
}
tr.selected {
  background: var(--admin-accent-soft);
}
.empty-state {
  padding: 24px 12px;
  text-align: center;
  color: var(--admin-muted);
}
.table-wrap:focus-visible {
  outline: 2px solid var(--admin-accent);
  outline-offset: 3px;
}
@media (max-width: 1100px) {
  .settings,
  .review-workspace {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 650px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .surface {
    padding: 12px;
  }
  fieldset,
  .workflow-panel {
    padding: 10px;
  }
  .inline > label {
    flex-basis: 100%;
  }
}
</style>
