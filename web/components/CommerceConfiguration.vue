<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, type Staff } from "../client";
import AdminForm from "./AdminForm.vue";
import AdminProductSelect from "./AdminProductSelect.vue";
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
const activeTab = ref("offers");
const loaded = ref(false);
const reminderReason = ref("");
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
    loaded.value = true;
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
  if (busy.value || !loaded.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    if (kind === "offers") {
      if (offers.value.some((o) => o.lines.some((line) => !line.skuId)))
        throw new Error("请先选择每项明细的商品与规格");
      await api("/admin/offers", {
        method: "PUT",
        body: { offers: offers.value, reason: reason.value },
      });
    } else
      await api("/admin/storage-reminder-settings", {
        method: "PUT",
        body: {
          ...reminders.value,
          days: days.value.split(",").map(Number),
          expiryFields: JSON.parse(expiryFields.value),
          rejectedFields: JSON.parse(rejectedFields.value),
          reason: reminderReason.value,
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
  <section class="commerce-config config-surface">
    <FeedbackToast
      :error="error"
      :notice="notice"
      @dismiss-error="error = ''"
      @dismiss-notice="notice = ''"
    />
    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="offers" tab="活动与买赠套餐">
        <AdminForm class="config-form" @submit="save('offers')">
          <header class="config-section-heading">
            <p class="config-help">
              设置固定商品和价格；赠品单价为 0，商品分摊合计须等于套餐售价。
            </p>
            <a-button
              :disabled="busy || !loaded"
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
            >
          </header>
          <p v-if="!loaded" class="config-help">
            {{ error ? "配置读取失败，请刷新重试" : "正在读取套餐…" }}
          </p>
          <p v-else-if="!offers.length" class="config-empty">
            暂无活动套餐，点击“新增套餐”开始配置。
          </p>
          <section
            v-for="(offer, offerIndex) in offers"
            :key="offer.id"
            class="config-section offer-section"
          >
            <header class="config-section-heading">
              <h3>{{ offer.name || `未命名套餐 ${offerIndex + 1}` }}</h3>
              <a-button
                type="text"
                danger
                :disabled="busy"
                @click="offers = offers.filter((o) => o.id !== offer.id)"
                >移除套餐</a-button
              >
            </header>
            <div class="config-fields offer-basics">
              <label
                >套餐名称<a-input
                  v-model:value="offer.name"
                  required
                  :maxlength="80"
                  :disabled="busy"
                  placeholder="填写顾客看到的套餐名称"
              /></label>
              <label
                >套餐售价（元）<a-input-number
                  :value="offer.amountCents / 100"
                  :min="0.01"
                  :precision="2"
                  required
                  :disabled="busy"
                  @update:value="
                    offer.amountCents = Math.round(Number($event) * 100)
                  "
              /></label>
              <div class="config-check">
                <a-checkbox v-model:checked="offer.active" :disabled="busy"
                  >在小程序展示</a-checkbox
                >
              </div>
            </div>
            <div
              v-for="(line, index) in offer.lines"
              :key="index"
              class="offer-line"
            >
              <label class="offer-product"
                >商品与规格<AdminProductSelect
                  v-model="line.skuId"
                  :aria-label="`套餐${offerIndex + 1}第${index + 1}项商品与规格`"
                  required
                  :disabled="busy"
              /></label>
              <label
                >数量<a-input-number
                  v-model:value="line.quantity"
                  :min="1"
                  :max="999"
                  :precision="0"
                  required
                  :disabled="busy"
              /></label>
              <label
                >分摊单价（元）<a-input-number
                  :value="line.unitCents / 100"
                  :min="0"
                  :precision="2"
                  required
                  :disabled="busy || line.isGift"
                  @update:value="
                    line.unitCents = Math.round(Number($event) * 100)
                  "
              /></label>
              <div class="config-check">
                <a-checkbox
                  v-model:checked="line.isGift"
                  :disabled="busy"
                  @change="line.isGift && (line.unitCents = 0)"
                  >赠品</a-checkbox
                >
              </div>
              <a-button
                type="text"
                danger
                :disabled="busy"
                @click="offer.lines.splice(index, 1)"
                >移除</a-button
              >
            </div>
            <div class="offer-summary">
              <a-button
                :disabled="busy"
                @click="
                  offer.lines.push({
                    skuId: 0,
                    quantity: 1,
                    unitCents: 0,
                    isGift: false,
                  })
                "
                >添加商品</a-button
              >
              <span
                :class="{
                  'allocation-warning':
                    offer.lines.reduce(
                      (sum, line) => sum + line.quantity * line.unitCents,
                      0,
                    ) !== offer.amountCents,
                }"
                >已分摊 ¥{{
                  (
                    offer.lines.reduce(
                      (sum, line) => sum + line.quantity * line.unitCents,
                      0,
                    ) / 100
                  ).toFixed(2)
                }}
                / 售价 ¥{{ (offer.amountCents / 100).toFixed(2) }}</span
              >
            </div>
          </section>
          <footer class="config-save-bar">
            <label
              >变更原因<a-input
                v-model:value="reason"
                required
                :maxlength="200"
                :disabled="busy"
                placeholder="说明本次套餐调整"
            /></label>
            <a-button
              html-type="submit"
              type="primary"
              :loading="busy"
              :disabled="!loaded"
              >保存套餐</a-button
            >
          </footer>
        </AdminForm>
      </a-tab-pane>
      <a-tab-pane
        v-if="staff.permissions.includes('orders:manage')"
        key="reminders"
        tab="寄存消息提醒"
      >
        <AdminForm
          v-if="reminders"
          class="config-form"
          @submit="save('reminders')"
        >
          <p class="config-help">
            设置到期提醒和提货驳回提醒，实际发送结果可在“寄存与提货 →
            到期与消息”查看。
          </p>
          <section class="config-section">
            <h3>提醒规则</h3>
            <a-checkbox v-model:checked="reminders.enabled" :disabled="busy"
              >启用自动到期提醒</a-checkbox
            >
            <div class="config-fields">
              <label
                >到期前天数<a-input
                  v-model:value="days"
                  :disabled="busy"
                  placeholder="例如 30,7"
              /></label>
              <label
                >到期提醒模板 ID<a-input
                  v-model:value="reminders.expiryTemplateId"
                  :disabled="busy"
              /></label>
              <label
                >驳回提醒模板 ID<a-input
                  v-model:value="reminders.rejectedTemplateId"
                  :disabled="busy"
              /></label>
            </div>
          </section>
          <section class="config-section">
            <h3>微信模板字段</h3>
            <div class="config-fields">
              <label
                >到期模板字段映射<a-textarea
                  v-model:value="expiryFields"
                  :rows="4"
                  :disabled="busy"
                  placeholder='例如 {"thing1":"title","time2":"expiresOn"}'
              /></label>
              <label
                >驳回模板字段映射<a-textarea
                  v-model:value="rejectedFields"
                  :rows="4"
                  :disabled="busy"
                  placeholder='例如 {"thing1":"body"}'
              /></label>
            </div>
            <p class="config-help">
              字段须对应实际微信模板；未取得授权或未配置模板时，用户仍可查看站内消息。
            </p>
          </section>
          <footer class="config-save-bar">
            <label
              >变更原因<a-input
                v-model:value="reminderReason"
                required
                :maxlength="200"
                :disabled="busy"
                placeholder="说明本次提醒规则调整"
            /></label>
            <a-button html-type="submit" type="primary" :loading="busy"
              >保存提醒配置</a-button
            >
          </footer>
        </AdminForm>
      </a-tab-pane>
    </a-tabs>
  </section>
</template>
<style scoped>
.offer-basics {
  grid-template-columns: minmax(220px, 1fr) 170px minmax(150px, 0.6fr);
}
.offer-line {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 90px 150px auto auto;
  gap: 12px;
  align-items: end;
  padding: 16px 0;
  border-bottom: 1px solid var(--admin-border);
}
.offer-summary {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  padding-top: 16px;
  font-size: 13px;
  color: var(--admin-muted);
}
.allocation-warning {
  color: var(--admin-accent);
}
@media (max-width: 1100px) {
  .offer-line {
    grid-template-columns: minmax(200px, 1fr) 90px 140px;
  }
  .offer-basics {
    grid-template-columns: minmax(180px, 1fr) 150px;
  }
}
@media (max-width: 650px) {
  .offer-basics,
  .offer-line {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .offer-product {
    grid-column: 1 / -1;
  }
}
</style>
