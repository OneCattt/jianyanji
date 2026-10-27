<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, type Staff } from "../client";
import FeedbackToast from "./FeedbackToast.vue";
interface OfferLine {
  skuId: number;
  quantity: number;
  unitCents: number;
  isGift: boolean;
}
interface Offer {
  id: number;
  name: string;
  amountCents: number;
  active: boolean;
  lines: OfferLine[];
}
interface Reminders {
  enabled: boolean;
  days: number[];
  expiryTemplateId: string;
  rejectedTemplateId: string;
  expiryFields: Record<string, string>;
  rejectedFields: Record<string, string>;
}
const props = defineProps<{ staff: Staff }>();
const offers = ref<Offer[]>([]),
  reminders = ref<Reminders | null>(null),
  reason = ref(""),
  error = ref(""),
  notice = ref(""),
  busy = ref(false),
  days = ref("30,7"),
  expiryFields = ref("{}"),
  rejectedFields = ref("{}");
onMounted(async () => {
  try {
    offers.value = (await api<{ offers: Offer[] }>("/admin/offers")).offers;
    if (props.staff.permissions.includes("orders:manage")) {
      reminders.value = await api<Reminders>(
        "/admin/storage-reminder-settings",
      );
      days.value = reminders.value.days.join(",");
      expiryFields.value = JSON.stringify(reminders.value.expiryFields);
      rejectedFields.value = JSON.stringify(reminders.value.rejectedFields);
    }
  } catch (e) {
    error.value = (e as Error).message;
  }
});
async function save(kind: string) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    if (kind === "offers")
      await api("/admin/offers", {
        method: "PUT",
        body: { offers: offers.value, reason: reason.value },
      });
    else
      await api("/admin/storage-reminder-settings", {
        method: "PUT",
        body: {
          ...reminders.value,
          days: days.value.split(",").map(Number),
          expiryFields: JSON.parse(expiryFields.value),
          rejectedFields: JSON.parse(rejectedFields.value),
          reason: reason.value,
        },
      });
    notice.value = "配置已保存，仅影响后续业务";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="panel commerce-config">
    <FeedbackToast
      :error="error"
      :notice="notice"
      @dismiss-error="error = ''"
      @dismiss-notice="notice = ''"
    />
    <h2>活动与买赠套餐</h2>
    <p>
      固定商品和价格，不允许顾客换货；赠品单价为0，明细分摊合计须等于套餐售价。
    </p>
    <fieldset v-for="offer in offers" :key="offer.id">
      <legend>套餐 {{ offer.id }}</legend>
      <div class="config-grid">
        <label>名称<a-input v-model:value="offer.name" /></label
        ><label
          >套餐售价（元）<a-input-number
            :value="offer.amountCents / 100"
            :min="0.01"
            :precision="2"
            @update:value="
              offer.amountCents = Math.round(Number($event) * 100)
            " /></label
        ><label
          ><a-checkbox v-model:checked="offer.active"
            >在小程序展示</a-checkbox
          ></label
        >
      </div>
      <div
        v-for="(line, index) in offer.lines"
        :key="index"
        class="config-grid"
      >
        <label
          >商品规格ID<a-input-number
            v-model:value="line.skuId"
            :min="1"
            :precision="0" /></label
        ><label
          >数量<a-input-number
            v-model:value="line.quantity"
            :min="1"
            :precision="0" /></label
        ><label
          >分摊单价（元）<a-input-number
            :value="line.unitCents / 100"
            :min="0"
            :precision="2"
            :disabled="line.isGift"
            @update:value="
              line.unitCents = Math.round(Number($event) * 100)
            " /></label
        ><label
          ><a-checkbox
            v-model:checked="line.isGift"
            @change="line.isGift && (line.unitCents = 0)"
            >赠品</a-checkbox
          ></label
        ><a-button @click="offer.lines.splice(index, 1)">移除此明细</a-button>
      </div>
      <a-button
        @click="
          offer.lines.push({
            skuId: 1,
            quantity: 1,
            unitCents: 0,
            isGift: false,
          })
        "
        >添加商品</a-button
      ><a-button @click="offers = offers.filter((o) => o.id !== offer.id)"
        >移除套餐配置</a-button
      >
    </fieldset>
    <a-button
      @click="
        offers.push({
          id: Math.max(0, ...offers.map((o) => o.id)) + 1,
          name: '',
          amountCents: 0,
          active: false,
          lines: [],
        })
      "
      >新增套餐</a-button
    ><label class="reason"
      >配置变更原因<a-textarea
        v-model:value="reason"
        :maxlength="2000" /></label
    ><a-button type="primary" :loading="busy" @click="save('offers')"
      >保存套餐</a-button
    >
    <template v-if="reminders"
      ><h2>寄存消息提醒</h2>
      <div class="config-grid">
        <label
          ><a-checkbox v-model:checked="reminders.enabled"
            >启用自动到期提醒</a-checkbox
          ></label
        ><label>到期前天数（逗号分隔）<a-input v-model:value="days" /></label
        ><label
          >到期提醒模板ID<a-input
            v-model:value="reminders.expiryTemplateId" /></label
        ><label
          >驳回提醒模板ID<a-input v-model:value="reminders.rejectedTemplateId"
        /></label>
      </div>
      <label class="reason"
        >到期模板字段映射<a-textarea
          v-model:value="expiryFields"
          placeholder='例如 {"thing1":"title","time2":"expiresOn"}，须对应实际模板' /></label
      ><label class="reason"
        >驳回模板字段映射<a-textarea
          v-model:value="rejectedFields"
          placeholder='例如 {"thing1":"body"}，须对应实际模板'
      /></label>
      <p>
        未取得用户授权或未配置微信模板时，站内消息仍可查询；实际发送结果在“寄存与提货
        → 到期与消息”查看。
      </p>
      <a-button type="primary" :loading="busy" @click="save('reminders')"
        >保存提醒配置</a-button
      ></template
    >
  </section>
</template>
<style scoped>
.commerce-config h2 {
  margin: 24px 0 12px;
}
.commerce-config fieldset {
  border: 1px solid var(--border, #ddd);
  border-radius: 10px;
  padding: 16px;
  margin: 16px 0;
}
.config-grid {
  display: flex;
  align-items: end;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.config-grid label,
.reason {
  display: grid;
  gap: 6px;
}
.reason {
  margin: 16px 0;
  max-width: 640px;
}
.commerce-config button {
  min-height: 36px;
  margin-right: 8px;
}
</style>
