<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "../client";
const props = defineProps<{ token: string; orderId?: number }>();
const emit = defineEmits<{ close: [] }>();
const orders = ref<any[]>([]),
  selected = ref<any>(null),
  error = ref(""),
  notice = ref(""),
  busy = ref(false);
const names: Record<string, string> = {
  pending: "待付款",
  paid: "待发货",
  shipped: "已发货",
  completed: "已完成",
  closed: "已关闭",
  refunded: "已退款",
};
const money = (v: number) => "¥" + (v / 100).toFixed(2);
async function load() {
  orders.value = await api<any[]>("/orders", { token: props.token });
  if (props.orderId && !selected.value)
    selected.value = orders.value.find((o) => o.id === props.orderId) || null;
  else if (selected.value)
    selected.value =
      orders.value.find((o) => o.id === selected.value.id) || null;
}
async function run(work: () => Promise<any>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await work();
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(() => run(load));
async function act(action: string) {
  await run(async () => {
    if (action === "pay/points" && !confirm("确认使用现有积分支付整笔订单？"))
      return;
    await api("/orders/" + selected.value.id + "/" + action, {
      method: "POST",
      token: props.token,
    });
    notice.value = "处理结果已更新，请核对订单状态";
  });
}
</script>
<template>
  <section class="orders-preview">
    <button @click="emit('close')">‹ 返回</button>
    <h1>我的订单</h1>
    <p v-if="error" role="alert" class="message error">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <button @click="run(load)" :disabled="busy">刷新订单</button
    ><template v-if="!selected"
      ><article v-for="o in orders" :key="o.id">
        <strong
          >{{ names[o.status] || o.status }} ·
          {{ money(o.total_cents) }}</strong
        >
        <p>{{ o.number }}</p>
        <button @click="selected = o">查看订单</button>
      </article>
      <p v-if="!orders.length">暂无订单</p></template
    ><template v-else
      ><article>
        <h2>{{ names[selected.status] || selected.status }}</h2>
        <p>{{ selected.number }}</p>
        <p>
          支付状态：{{
            selected.payment_state === "paid"
              ? "已实际付款"
              : ["unknown", "review", "paying"].includes(selected.payment_state)
                ? "支付结果核对中"
                : selected.payment_state === "unpaid"
                  ? "未付款"
                  : selected.payment_state
          }}
        </p>
        <p>
          {{ selected.snapshot.address.recipient }}
          {{ selected.snapshot.address.phone }}
        </p>
        <p>
          {{ selected.snapshot.address.province
          }}{{ selected.snapshot.address.city
          }}{{ selected.snapshot.address.district
          }}{{ selected.snapshot.address.detail }}
        </p>
        <div v-for="l in selected.snapshot.lines" :key="l.skuId" class="line">
          <img :src="l.image" alt="商品" />
          <div>
            {{ l.name }}<small>{{ l.skuName }} ×{{ l.quantity }}</small
            ><strong>{{ money(l.unitCents) }}</strong>
          </div>
        </div>
        <p>
          商品 {{ money(selected.goods_cents) }} · 运费
          {{ money(selected.shipping_cents) }}
        </p>
        <strong>合计 {{ money(selected.total_cents) }}</strong>
      </article>
      <article v-for="s in selected.shipments" :key="s.id">
        <strong>{{ s.carrier }}</strong>
        <p>{{ s.tracking }}</p>
        <p>{{ s.received_at ? "已确认收货" : "尚未确认收货" }}</p>
        <button
          v-if="!s.received_at"
          @click="
            run(async () => {
              await api(
                '/orders/' + selected.id + '/shipments/' + s.id + '/receive',
                { method: 'POST', token },
              );
            })
          "
          :disabled="busy"
        >
          确认这批商品收货
        </button>
      </article>
      <div v-if="selected.status === 'pending'">
        <p>微信付款请在微信小程序完成，结果以服务端核验为准。</p>
        <button
          v-if="['unpaid', 'failed'].includes(selected.payment_state)"
          @click="act('pay/points')"
          :disabled="busy"
        >
          使用已有积分整单付款</button
        ><button @click="act('payment/query')" :disabled="busy">
          查询原微信支付</button
        ><button
          v-if="['unpaid', 'failed'].includes(selected.payment_state)"
          @click="act('cancel')"
          :disabled="busy"
        >
          取消未付款订单</button
        ><button v-else @click="act('payment/close')" :disabled="busy">
          核对并关闭原微信支付
        </button>
      </div>
      <button @click="selected = null">返回订单列表</button></template
    >
  </section>
</template>
<style scoped>
.orders-preview {
  padding: 20px;
  background: #f8f5ee;
  color: #251d1a;
  min-height: 70vh;
  font-size: 12px;
}
.orders-preview h1,
.orders-preview h2 {
  font-family: STSong, "Songti SC", serif;
  font-weight: 400;
}
article {
  padding: 18px;
  background: #fffdfa;
  margin: 16px 0;
  overflow-wrap: anywhere;
}
.line {
  display: flex;
  gap: 12px;
  margin-top: 18px;
}
.line img {
  width: 70px;
  height: 76px;
  object-fit: cover;
}
.line small {
  display: block;
  color: #8e8178;
  margin: 8px 0;
}
p {
  color: #8e8178;
  line-height: 1.8;
}
button {
  padding: 10px 14px;
  border: 1px solid #4530261f;
  background: #fffdfa;
  color: #8d1724;
  margin: 8px 6px 0 0;
  cursor: pointer;
}
strong {
  color: #8d1724;
}
</style>
