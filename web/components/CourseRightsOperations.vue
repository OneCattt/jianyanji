<script setup lang="ts">
import FeedbackToast from "./FeedbackToast.vue";
import "../content-operations.css";
import { onMounted, ref } from "vue";
import {
  contentApi,
  type CourseRight,
  type CourseRights,
} from "../content-client";
import AdminForm from "./AdminForm.vue";
const blank = (): CourseRight => ({
  enabled: false,
  title: "",
  introduction: "",
  benefits: [],
  contact: "",
  enrollmentGuide: "",
});
const rights = ref<CourseRights>({ D: blank(), C: blank(), B: blank() }),
  benefits = ref({ D: "", C: "", B: "" }),
  reason = ref(""),
  error = ref(""),
  notice = ref(""),
  busy = ref(false),
  loaded = ref(false);
const roles = [
  { id: "D", name: "游客" },
  { id: "C", name: "调肤店主" },
  { id: "B", name: "调肤主理人" },
] as const;
onMounted(async () => {
  try {
    const value = await contentApi.courses();
    for (const role of roles) {
      rights.value[role.id] = value[role.id] || blank();
      benefits.value[role.id] = rights.value[role.id].benefits.join("\n");
    }
    loaded.value = true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "权益读取失败";
  }
});
async function save() {
  if (busy.value || !loaded.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const body = { ...rights.value };
    for (const role of roles)
      body[role.id] = {
        ...body[role.id],
        benefits: benefits.value[role.id]
          .split("\n")
          .map((s) => s.trim())
          .filter(Boolean),
      };
    await contentApi.saveCourses({ ...body, reason: reason.value });
    notice.value = "课程权益已保存";
    reason.value = "";
  } catch (e) {
    error.value = e instanceof Error ? e.message : "保存失败";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <FeedbackToast v-model:error="error" v-model:notice="notice" />
  <section class="table-panel content-operations">
    <div class="panel-heading"><h2>课程与成长权益</h2></div>
    <p class="muted">
      维护各身份实际权益、课程介绍和联系指引。公开展示权益对比，本人权益按当前有效身份显示。
    </p>
    <AdminForm class="content-editor" v-if="loaded" @submit="save"
      ><fieldset v-for="role in roles" :key="role.id" :disabled="busy">
        <legend>{{ role.name }}</legend>
        <label
          ><input v-model="rights[role.id].enabled" type="checkbox" />
          启用展示</label
        ><label
          >标题<input
            v-model="rights[role.id].title"
            :required="rights[role.id].enabled"
            maxlength="100" /></label
        ><label
          >课程介绍<textarea
            v-model="rights[role.id].introduction"
            :required="rights[role.id].enabled"
            maxlength="2000"
          /></label
        ><label
          >成长权益（每行一项，最多20项）<textarea
            v-model="benefits[role.id]"
            :required="rights[role.id].enabled"
          /></label
        ><label
          >联系 / 报名联系方式<input
            v-model="rights[role.id].contact"
            :required="rights[role.id].enabled"
            maxlength="300" /></label
        ><label
          >联系与报名指引<textarea
            v-model="rights[role.id].enrollmentGuide"
            :required="rights[role.id].enabled"
            maxlength="2000"
          />
        </label>
      </fieldset>
      <label>修改原因<input v-model="reason" required maxlength="200" /></label
      ><button class="primary" type="submit" :disabled="busy">
        {{ busy ? "保存中…" : "保存权益" }}
      </button></AdminForm
    >
  </section>
</template>
