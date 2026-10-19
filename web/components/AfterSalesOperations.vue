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
  <section class="aftersales">
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-if="notice" class="message success" role="status">{{ notice }}</p>
    <form
      class="surface"
      @submit.prevent="
        run(async () => {
          await api('/admin/aftersales-settings', {
            method: 'PUT',
            body: { ...policy, reason },
          });
        })
      "
    >
      <h3>售后政策</h3>
      <label
        >收货后售后天数<input
          v-model.number="policy.days"
          type="number"
          min="0"
          max="365"
          required /></label
      ><label
        >实际售后政策<textarea
          v-model="policy.policy"
          rows="4"
          required
        /></label
      ><label>修改原因<input v-model="reason" required /></label
      ><button class="primary" :disabled="busy">保存政策</button>
      <p>新政策只用于新交易；历史订单保留购买时的政策。</p>
    </form>
    <form class="surface" @submit.prevent="create">
      <h3>客服受理商品售后</h3>
      <label
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
            {{ o.number }} · 用户{{ o.user_id }} · {{ money(o.total_cents) }}
          </option>
        </select></label
      ><template v-if="current"
        ><div v-for="l in current.lines" :key="l.id" class="line">
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
      ><label>受理原因<input v-model="reason" required maxlength="200" /></label
      ><template v-if="props.canFinance"
        ><label class="exception-check"
          ><input
            v-model="exceptionApproved"
            type="checkbox"
          />品牌方确认按特殊原因受理超期售后</label
        ><label v-if="exceptionApproved"
          >特批依据及原因<input
            v-model="exceptionReason"
            required
            maxlength="200" /></label></template
      ><button class="primary" :disabled="busy || !current">
        登记售后并占用可退数量
      </button>
    </form>
    <section v-if="selected && action" class="surface">
      <h3>处理售后 {{ selected.number }}</h3>
      <form @submit.prevent="submit">
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
          ><label
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
          ><label
            ><input
              v-model="confirmed"
              type="checkbox"
              required
            />公司已实际退款，资料核实无误</label
          ></template
        ><label
          >处理原因<input v-model="reason" required maxlength="200" /></label
        ><button class="primary" :disabled="busy">确认处理</button
        ><button type="button" @click="action = ''">取消</button>
      </form>
    </section>
    <section class="surface">
      <h3>售后处理记录</h3>
      <article v-for="r in refunds" :key="r.id">
        <div class="line">
          <strong>{{ r.number }}</strong
          ><span>{{ names[r.status] }} · {{ money(r.total_cents) }}</span>
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
      <p v-if="!refunds.length">暂无售后记录</p>
    </section>
  </section>
</template>
<style scoped>
.surface {
  background: white;
  padding: 24px;
  border: 1px solid #e0e5df;
  margin-bottom: 20px;
}
form {
  display: grid;
  gap: 16px;
}
label {
  display: grid;
  gap: 7px;
  font-size: 13px;
}
textarea,
select {
  padding: 12px;
  border: 1px solid #d7ddd7;
  border-radius: 6px;
  font: inherit;
  width: 100%;
}
input[type="checkbox"] {
  width: 20px;
  height: 20px;
}
.line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
article {
  padding: 20px 0;
  border-top: 1px solid #e4e8e2;
  overflow-wrap: anywhere;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
p {
  color: #738076;
  font-size: 13px;
  line-height: 1.8;
}
button {
  cursor: pointer;
}
.line label {
  max-width: 150px;
}
</style>
