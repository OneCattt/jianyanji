<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import { api } from "../client";
const props = defineProps<{ canFinance: boolean }>();
const orders = ref<any[]>([]),
  refunds = ref<any[]>([]),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  reason = ref(""),
  policy = ref({ days: 7, policy: "" }),
  selectedOrderId = ref(0),
  quantities = ref<Record<number, number>>({}),
  shipping = ref("0.00");
const current = computed(() =>
  orders.value.find((o) => o.id === selectedOrderId.value),
);
const exceptionApproved = ref(false),
  exceptionReason = ref("");
const action = ref(""),
  selected = ref<any>(null),
  decision = ref<"retain" | "revoke">("retain"),
  restock = ref(false),
  inspection = ref(""),
  recipient = ref(""),
  paidAt = ref(""),
  proof = ref(""),
  confirmed = ref(false);
const names: Record<string, string> = {
  requested: "待审核",
  approved: "已通过待退款",
  processing: "退款处理中",
  unknown: "结果待核查",
  failed: "渠道失败待处理",
  rejected: "已驳回",
  success: "实际退款完成",
};
const money = (v: number) => "¥" + (v / 100).toFixed(2);
function cents(v: string) {
  if (!/^\d+(\.\d{1,2})?$/.test(v)) throw new Error("金额最多两位小数");
  const [a, b = ""] = v.split(".");
  return Number(a) * 100 + Number(b.padEnd(2, "0"));
}
async function load() {
  [orders.value, refunds.value] = await Promise.all([
    api<any[]>("/admin/orders"),
    api<any[]>("/admin/refunds"),
  ]);
  const p = await api<any>("/admin/aftersales-settings");
  if (p) policy.value = p;
}
onMounted(() => run(load, false));
async function run(work: () => Promise<any>, message = true) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await work();
    if (message) {
      notice.value = "操作已记录，请以实际处理状态为准";
      await load();
    }
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function create() {
  await run(async () => {
    const items = Object.entries(quantities.value)
      .filter(([, q]) => q > 0)
      .map(([lineId, quantity]) => ({
        lineId: Number(lineId),
        quantity: Number(quantity),
      }));
    if (!items.length && cents(shipping.value) === 0)
      throw new Error("请选择实际退款商品数量或需退运费");
    await api("/admin/refunds", {
      method: "POST",
      body: {
        orderId: selectedOrderId.value,
        items,
        shippingCents: cents(shipping.value),
        reason: reason.value,
        requestKey: crypto.randomUUID(),
        ...(exceptionApproved.value
          ? { exceptionReason: exceptionReason.value }
          : {}),
      },
    });
    quantities.value = {};
    exceptionApproved.value = false;
    exceptionReason.value = "";
  });
}
function open(r: any, key: string) {
  selected.value = r;
  action.value = key;
  reason.value = "";
  confirmed.value = false;
  proof.value = "";
  recipient.value = "";
  paidAt.value = "";
  inspection.value = "";
  restock.value = false;
}
async function submit() {
  await run(async () => {
    const id = selected.value.id;
    let body: any = { reason: reason.value };
    let endpoint = action.value;
    if (action.value === "approve" || action.value === "reject") {
      endpoint = "review";
      body = {
        ...body,
        approve: action.value === "approve",
        identityDecision:
          selected.value.kind === "package" ? decision.value : null,
      };
    }
    if (action.value === "inspect")
      body = {
        ...body,
        returnedAt: new Date(paidAt.value).toISOString(),
        inspection: inspection.value,
        restock: restock.value,
      };
    if (action.value === "offline")
      body = {
        ...body,
        recipient: recipient.value,
        paidAt: new Date(paidAt.value).toISOString(),
        proof: proof.value,
        confirmed: confirmed.value,
      };
    await api(`/admin/refunds/${id}/${endpoint}`, { method: "POST", body });
    action.value = "";
  });
}
</script>
<template>
  <section class="aftersales" :aria-busy="busy">
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-if="notice" class="message success" role="status">{{ notice }}</p>
    <div class="setup-panels">
      <details class="surface setup-panel">
        <summary>
          <span class="summary-copy">
            <strong>售后政策</strong>
            <small>配置新交易适用的售后期限与说明</small>
          </span>
        </summary>
        <form
          class="form-grid setup-form"
          @submit.prevent="
            run(async () => {
              await api('/admin/aftersales-settings', {
                method: 'PUT',
                body: { ...policy, reason },
              });
            })
          "
        >
          <label
            >收货后售后天数<input
              v-model.number="policy.days"
              type="number"
              min="0"
              max="365"
              required /></label
          ><label class="wide-field"
            >实际售后政策<textarea
              v-model="policy.policy"
              rows="4"
              required
            /></label
          ><label class="wide-field"
            >修改原因<input v-model="reason" required /></label
          ><button class="primary form-submit" :disabled="busy">
            保存政策
          </button>
          <p class="wide-field">
            新政策只用于新交易；历史订单保留购买时的政策。
          </p>
        </form>
      </details>
      <details class="surface setup-panel">
        <summary>
          <span class="summary-copy">
            <strong>客服受理商品售后</strong>
            <small>选择原订单，登记退款商品与受理原因</small>
          </span>
        </summary>
        <form class="form-grid setup-form" @submit.prevent="create">
          <label class="wide-field"
            >原订单<select
              v-model.number="selectedOrderId"
              required
              @change="quantities = {}"
            >
              <option :value="0" disabled>选择已付款订单</option>
              <option
                v-for="o in orders.filter((o) => o.payment_state === 'paid')"
                :key="o.id"
                :value="o.id"
              >
                {{ o.number }} · 用户{{ o.user_id }} ·
                {{ money(o.total_cents) }}
              </option>
            </select></label
          ><template v-if="current"
            ><div
              v-for="l in current.lines"
              :key="l.id"
              class="line return-line wide-field"
            >
              <span
                >{{
                  current.snapshot.lines.find((i: any) => i.skuId === l.sku_id)
                    ?.name
                }}
                · 已发{{ l.shipped_qty }} 已退{{ l.refunded_qty }} 办理中{{
                  l.refund_held
                }}</span
              ><label
                >本次退货数量<input
                  v-model.number="quantities[l.id]"
                  type="number"
                  min="0"
                  :max="l.quantity - l.refunded_qty - l.refund_held"
                  placeholder="0"
              /></label></div></template
          ><label
            >退运费（元）<input
              v-model="shipping"
              inputmode="decimal"
              required /></label
          ><label
            >受理原因<input v-model="reason" required maxlength="200" /></label
          ><template v-if="props.canFinance"
            ><label class="check-field wide-field"
              ><input
                v-model="exceptionApproved"
                type="checkbox"
              />品牌方确认按特殊原因受理超期售后</label
            ><label v-if="exceptionApproved" class="wide-field"
              >特批依据及原因<input
                v-model="exceptionReason"
                required
                maxlength="200" /></label></template
          ><button class="primary form-submit" :disabled="busy || !current">
            登记售后并占用可退数量
          </button>
        </form>
      </details>
    </div>
    <section v-if="selected && action" class="surface handling-panel">
      <header class="section-heading">
        <h3>处理售后 {{ selected.number }}</h3>
      </header>
      <form class="form-grid" @submit.prevent="submit">
        <template
          v-if="
            ['approve', 'reject'].includes(action) &&
            selected.kind === 'package'
          "
          ><label
            >产品包部分退款后身份<select v-model="decision">
              <option value="retain">仍满足入驻条件，保留</option>
              <option value="revoke">不再满足条件，撤销该次身份</option>
            </select></label
          ></template
        ><template v-if="action === 'inspect'"
          ><label
            >实际退货收到时间<input
              v-model="paidAt"
              type="datetime-local"
              required /></label
          ><label>验货依据<textarea v-model="inspection" required /></label
          ><label class="check-field wide-field"
            ><input
              v-model="restock"
              type="checkbox"
            />实际验货确认可重新销售并回库存</label
          ></template
        ><template v-if="action === 'offline'"
          ><label>实际收款人<input v-model="recipient" required /></label
          ><label
            >实际付款时间<input
              v-model="paidAt"
              type="datetime-local"
              required /></label
          ><label>实际转账凭证<input v-model="proof" required /></label
          ><label class="check-field wide-field"
            ><input
              v-model="confirmed"
              type="checkbox"
              required
            />公司已实际退款，资料核实无误</label
          ></template
        ><label class="wide-field"
          >处理原因<input v-model="reason" required maxlength="200"
        /></label>
        <div class="actions wide-field">
          <button class="primary" :disabled="busy">确认处理</button
          ><button type="button" @click="action = ''">取消</button>
        </div>
      </form>
    </section>
    <section class="surface refund-list">
      <header class="section-heading">
        <h3>售后处理记录</h3>
        <span class="list-count">{{ refunds.length }} 笔记录</span>
      </header>
      <p v-if="busy" class="loading" role="status">正在读取或处理售后…</p>
      <article v-for="r in refunds" :key="r.id" class="refund-record">
        <div class="line record-heading">
          <strong>{{ r.number }}</strong
          ><span class="state"
            >{{ names[r.status] }} · {{ money(r.total_cents) }}</span
          >
        </div>
        <p>原订单 #{{ r.order_id }} · {{ r.reason }}</p>
        <p v-if="r.policy.exceptionReason">
          品牌方特批：{{ r.policy.exceptionReason }}
        </p>
        <p v-if="r.last_error" class="message error">{{ r.last_error }}</p>
        <p v-for="l in r.lines" :key="l.lineId">
          商品行 #{{ l.lineId }}：退{{ l.quantity }}件，需收回{{
            l.returnQuantity
          }}件
        </p>
        <div class="actions">
          <button v-if="r.status === 'requested'" @click="open(r, 'approve')">
            审核同意</button
          ><button v-if="r.status === 'requested'" @click="open(r, 'reject')">
            驳回</button
          ><button
            v-if="
              r.status !== 'requested' &&
              r.status !== 'rejected' &&
              !r.returned_at &&
              r.lines.some((l: any) => l.returnQuantity > 0)
            "
            @click="open(r, 'inspect')"
          >
            登记实际退货验收</button
          ><template
            v-if="props.canFinance && ['approved', 'failed'].includes(r.status)"
            ><button @click="open(r, 'wechat')">原微信退款</button
            ><button @click="open(r, 'points')">退回原积分</button
            ><button v-if="r.kind === 'package'" @click="open(r, 'offline')">
              登记产品包实际线下退款
            </button></template
          ><button
            v-if="
              props.canFinance &&
              ['processing', 'unknown', 'failed'].includes(r.status)
            "
            @click="open(r, 'query')"
          >
            查询原微信退款
          </button>
        </div>
      </article>
      <p v-if="!busy && !refunds.length" class="empty">暂无售后记录</p>
    </section>
  </section>
</template>
<style scoped>
.aftersales {
  display: grid;
  gap: 16px;
  min-width: 0;
  max-width: 100%;
  color: var(--admin-ink);
  font-size: 13px;
}
.aftersales *,
.aftersales *::before,
.aftersales *::after {
  box-sizing: border-box;
}
.surface {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: #fff;
}
.setup-panels {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: 16px;
  min-width: 0;
}
.setup-panel summary {
  color: var(--admin-accent);
  cursor: pointer;
}
.summary-copy {
  display: inline-grid;
  gap: 4px;
  max-width: calc(100% - 24px);
  vertical-align: top;
  overflow-wrap: anywhere;
}
.summary-copy strong {
  color: var(--admin-ink);
  font-size: 14px;
}
.summary-copy small,
.list-count {
  color: var(--admin-muted);
  font-size: 12px;
}
.setup-form {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--admin-border);
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: 12px;
}
label {
  display: grid;
  gap: 6px;
  min-width: 0;
  line-height: 1.5;
}
input,
textarea,
select {
  min-width: 0;
  max-width: 100%;
}
label > input:not([type="checkbox"]),
label > textarea,
label > select {
  width: 100%;
}
textarea {
  resize: vertical;
}
.wide-field,
.form-submit {
  grid-column: 1 / -1;
}
.form-submit {
  justify-self: start;
}
.check-field {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.check-field input[type="checkbox"] {
  flex: 0 0 auto;
  width: 16px;
  height: 16px;
  padding: 0;
  margin: 2px 0 0;
}
.line,
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  min-width: 0;
}
.section-heading {
  margin-bottom: 16px;
}
h3 {
  min-width: 0;
  margin: 0;
  color: var(--admin-ink);
  font-size: 15px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.return-line {
  padding: 12px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: var(--admin-bg);
}
.return-line > span {
  flex: 1 1 160px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.return-line label {
  flex: 0 1 140px;
}
.handling-panel {
  border-color: var(--admin-accent);
}
.refund-list > .section-heading {
  margin-bottom: 0;
}
.refund-record {
  min-width: 0;
  padding: 16px 0;
  border-top: 1px solid var(--admin-border);
  overflow-wrap: anywhere;
}
.refund-record:first-of-type {
  margin-top: 12px;
}
.refund-record:last-of-type {
  padding-bottom: 0;
}
.record-heading strong {
  color: var(--admin-ink);
}
.state {
  padding: 4px 8px;
  border-radius: 8px;
  background: var(--admin-accent-soft);
  color: var(--admin-accent);
  font-size: 12px;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.refund-record .actions {
  margin-top: 12px;
}
button {
  max-width: 100%;
  white-space: normal;
  overflow-wrap: anywhere;
}
p {
  margin: 8px 0;
  line-height: 1.6;
}
p:not(.message) {
  color: var(--admin-muted);
}
.setup-form p {
  margin: 0;
}
.aftersales > .message {
  margin: 0;
  overflow-wrap: anywhere;
}
.loading,
.empty {
  padding: 16px 12px;
  text-align: center;
}
@media (max-width: 900px) {
  .setup-panels {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 650px) {
  .surface {
    padding: 12px;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
