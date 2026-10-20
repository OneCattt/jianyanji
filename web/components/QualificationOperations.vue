<script setup lang="ts">
import { ref } from "vue";
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
async function submit() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const f = form.value;
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
    <AdminForm @submit="submit">
      <label
        >办理事项<AdminSelect v-model="action"
          ><option value="bootstrap">首批存量资格核验</option>
          <option value="initial-application">首批新入驻产品包申请</option>
          <option value="handover">指定撤销后的团队接管人</option>
          <option value="relation">调整邀请与归属关系</option></AdminSelect
        ></label
      >
      <label
        >用户编号<input v-model="form.userId" type="number" min="1" required
      /></label>
      <label v-if="['bootstrap', 'initial-application'].includes(action)"
        >核验身份<AdminSelect v-model="form.identity"
          ><option value="C">店主</option>
          <option value="B">主理人</option></AdminSelect
        ></label
      >
      <label v-if="action === 'relation'"
        >直接邀请人编号<input
          v-model="form.inviterId"
          type="number"
          min="1"
          required
      /></label>
      <label
        v-if="
          !['bootstrap', 'initial-application'].includes(action) ||
          form.identity === 'C'
        "
        >{{ action === "handover" ? "指定有效接管主理人编号" : "归属主理人编号"
        }}<input v-model="form.principalId" type="number" min="1" required
      /></label>
      <label v-if="['bootstrap', 'initial-application'].includes(action)"
        >真实存量资格依据<textarea
          v-model="form.evidence"
          maxlength="2000"
          required
        />
      </label>
      <label v-if="action === 'initial-application'"
        >本人有效地址编号<input
          v-model="form.addressId"
          type="number"
          min="1"
          required /></label
      ><label v-if="action === 'initial-application'"
        ><input
          v-model="form.confirmed"
          type="checkbox"
          required
        />已与申请人确认资格、产品包、协议及本次归属</label
      ><label
        >办理原因<textarea v-model="form.reason" maxlength="2000" required />
      </label>
      <button class="primary" :disabled="busy">
        {{ busy ? "办理中…" : "确认办理并留痕" }}
      </button>
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
form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
label {
  display: grid;
  gap: 6px;
}
textarea {
  min-height: 70px;
}
button {
  justify-self: start;
}
p {
  color: var(--admin-muted);
  font-size: 13px;
}
@media (max-width: 650px) {
  form {
    grid-template-columns: 1fr;
  }
}
</style>
