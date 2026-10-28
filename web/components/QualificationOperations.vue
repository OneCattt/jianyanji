<script setup lang="ts">
import AdminUserSelect from "./AdminUserSelect.vue";
import { ref, watch } from "vue";
import { api } from "../client";
import AdminForm from "./AdminForm.vue";
import AdminSelect from "./AdminSelect.vue";
import FeedbackToast from "./FeedbackToast.vue";
const action = ref("bootstrap"),
  busy = ref(false),
  error = ref(""),
  notice = ref("");
const form = ref({
  userId: "",
  identity: "C",
  principalId: "",
  inviterId: "",
  evidence: "",
  reason: "",
  addressId: "",
  confirmed: false,
});
watch(
  () => form.value.userId,
  () => {
    form.value.addressId = "";
    form.value.principalId = "";
    form.value.inviterId = "";
    form.value.confirmed = false;
  },
  { flush: "sync" },
);
async function submit() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const f = form.value;
    const positiveId = (value: string) =>
      /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value));
    if (!positiveId(f.userId)) throw new Error("用户编号须为正整数");
    if (action.value === "identity") {
      if (f.identity === "C" && !positiveId(f.principalId))
        throw new Error("2980店主须指定有效19800店主归属");
      await api(`/admin/users/${f.userId}/identity`, {
        method: "POST",
        body: {
          identity: f.identity,
          ...(f.identity === "C" ? { principalId: Number(f.principalId) } : {}),
          reason: f.reason,
          evidence: f.evidence,
          requestKey: crypto.randomUUID(),
        },
      });
      notice.value = "身份调整已登记；历史交易及积分保留";
      return;
    }

    if (
      (!["bootstrap", "initial-application"].includes(action.value) ||
        f.identity === "C") &&
      !positiveId(f.principalId)
    )
      throw new Error("归属或接管主理人编号须为正整数");
    if (action.value === "relation" && !positiveId(f.inviterId))
      throw new Error("直接邀请人编号须为正整数");
    if (
      action.value === "initial-application" &&
      (!positiveId(f.addressId) || !f.confirmed)
    )
      throw new Error("请填写本人有效地址编号并确认资格、协议与本次归属");
    const body =
      action.value === "bootstrap"
        ? {
            userId: Number(f.userId),
            identity: f.identity,
            principalId: f.identity === "B" ? null : Number(f.principalId),
            evidence: f.evidence,
            reason: f.reason,
          }
        : action.value === "handover"
          ? {
              userId: Number(f.userId),
              principalId: Number(f.principalId),
              reason: f.reason,
            }
          : {
              userId: Number(f.userId),
              inviterId: Number(f.inviterId),
              principalId: Number(f.principalId),
              reason: f.reason,
            };
    const payload =
      action.value === "initial-application"
        ? {
            userId: Number(f.userId),
            targetIdentity: f.identity,
            principalId: f.identity === "B" ? null : Number(f.principalId),
            addressId: Number(f.addressId),
            agreementAccepted: f.confirmed,
            conditionsMet: f.confirmed,
            evidence: f.evidence,
            reason: f.reason,
            requestKey:
              "initial-" +
              Date.now() +
              "-" +
              Math.random().toString(36).slice(2),
          }
        : body;
    await api(
      action.value === "relation"
        ? "/admin/relations"
        : "/admin/qualifications/" + action.value,
      { method: "POST", body: payload },
    );
    notice.value =
      action.value === "handover"
        ? "已记录接管安排，将在实际撤销资格时执行"
        : action.value === "relation"
          ? "关系已调整，待付款业务须本人重新确认"
          : action.value === "initial-application"
            ? "首批申请已建立，核实真实产品包付款后才激活"
            : "已按真实存量资格核验开通";
  } catch (e) {
    error.value = e instanceof Error ? e.message : "办理失败";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="qualification-operations">
    <h3>资格核验与关系办理</h3>
    <p>
      首批核验仅用于已有真实资格的代理；新购产品包请核实实际收款。核验不补发历史积分或奖励。
    </p>
    <FeedbackToast v-model:notice="notice" v-model:error="error" />
    <AdminForm class="qualification-form" @submit="submit">
      <label
        >办理事项<AdminSelect v-model="action"
          ><option value="bootstrap">首批存量资格核验</option>
          <option value="identity">独立开通、变更或关闭身份</option>
          <option value="initial-application">首批新入驻产品包申请</option>
          <option value="handover">指定撤销后的团队接管人</option>
          <option value="relation">调整邀请与归属关系</option></AdminSelect
        ></label
      >
      <label>用户编号<AdminUserSelect v-model="form.userId" required /></label>
      <label
        v-if="['bootstrap', 'initial-application', 'identity'].includes(action)"
        >核验身份<AdminSelect v-model="form.identity"
          ><option value="C">2980店主</option>
          <option value="B">19800店主</option>
          <option v-if="action === 'identity'" value="D">
            普通顾客（关闭店主身份）
          </option></AdminSelect
        ></label
      >
      <label v-if="action === 'relation'"
        >直接邀请人编号<AdminUserSelect
          v-model="form.inviterId"
          identity="agents"
          :disabled-ids="[form.userId]"
          required
      /></label>
      <label
        v-if="
          !['bootstrap', 'initial-application', 'identity'].includes(action) ||
          form.identity === 'C'
        "
        >{{ action === "handover" ? "指定有效接管主理人编号" : "归属主理人编号"
        }}<AdminUserSelect
          v-model="form.principalId"
          identity="B"
          :disabled-ids="[form.userId]"
          required
      /></label>
      <label
        v-if="['bootstrap', 'initial-application', 'identity'].includes(action)"
        >{{
          action === "initial-application"
            ? "资格、协议与申请依据"
            : "真实存量资格依据"
        }}<a-textarea
          v-model:value="form.evidence"
          :maxlength="2000"
          required
        />
      </label>
      <label v-if="action === 'initial-application'"
        >本人有效地址编号<a-input-number
          class="number-field"
          string-mode
          :precision="0"
          pattern="[0-9]+"
          v-model:value="form.addressId"
          :min="1"
          required
      /></label>
      <div class="confirmation-field" v-if="action === 'initial-application'">
        <a-checkbox v-model:checked="form.confirmed" required
          >已与申请人确认资格、产品包、协议及本次归属</a-checkbox
        >
      </div>
      <label
        >办理原因<a-textarea
          v-model:value="form.reason"
          :maxlength="2000"
          required
        />
      </label>
      <a-button html-type="submit" type="primary" :disabled="busy">
        {{ busy ? "办理中…" : "确认办理并留痕" }}
      </a-button>
    </AdminForm>
  </section>
</template>
<style scoped>
.qualification-operations {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: white;
  margin-bottom: 16px;
}
.qualification-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.qualification-form > label:not(.ant-checkbox-wrapper):not(.ant-radio-wrapper) {
  display: grid;
  gap: 6px;
}
p {
  color: var(--admin-muted);
  font-size: 13px;
}
@media (max-width: 650px) {
  .qualification-form {
    grid-template-columns: 1fr;
  }
}
.qualification-form > label:has(.ant-checkbox-wrapper),
.qualification-form > label:has(.ant-input-textarea),
.qualification-form > .ant-btn {
  grid-column: 1 / -1;
}
.qualification-form > .ant-btn {
  justify-self: start;
}

.number-field {
  width: 100%;
  min-width: 0;
}
.table-cell-content {
  min-width: 0;
  overflow-wrap: anywhere;
}
.table-cell-content > .ant-btn + .ant-btn {
  margin-left: 8px;
}
.section-heading {
  margin: 0 0 16px;
}
.confirmation-field {
  min-width: 0;
  grid-column: 1 / -1;
}
.admin-form > label,
.fields > label,
.result-fields > label,
.field-grid > label,
.sku-form > label:not(.ant-checkbox-wrapper):not(.ant-radio-wrapper) {
  align-content: start;
}
</style>
