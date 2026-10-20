<script setup lang="ts">
import FeedbackToast from "./FeedbackToast.vue";
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
  <section class="content-operations">
    <p class="muted">
      维护各身份实际权益、课程介绍和联系指引。公开展示权益对比，本人权益按当前有效身份显示。
    </p>
    <AdminForm
      class="module-editor course-editor"
      :disabled="busy"
      v-if="loaded"
      @submit="save"
      ><a-card
        v-for="role in roles"
        :key="role.id"
        :title="role.name"
        size="small"
        class="course-card"
      >
        <div class="confirmation-field">
          <a-checkbox v-model:checked="rights[role.id].enabled"
            >启用展示</a-checkbox
          >
        </div>
        <label
          >标题<a-input
            v-model:value="rights[role.id].title"
            :required="rights[role.id].enabled"
            :maxlength="100" /></label
        ><label
          >课程介绍<a-textarea
            v-model:value="rights[role.id].introduction"
            :required="rights[role.id].enabled"
            :maxlength="2000" /></label
        ><label
          >成长权益（每行一项，最多20项）<a-textarea
            v-model:value="benefits[role.id]"
            :required="rights[role.id].enabled" /></label
        ><label
          >联系 / 报名联系方式<a-input
            v-model:value="rights[role.id].contact"
            :required="rights[role.id].enabled"
            :maxlength="300" /></label
        ><label
          >联系与报名指引<a-textarea
            v-model:value="rights[role.id].enrollmentGuide"
            :required="rights[role.id].enabled"
            :maxlength="2000"
          />
        </label>
      </a-card>
      <label
        >修改原因<a-input
          v-model:value="reason"
          required
          :maxlength="200" /></label
      ><a-button html-type="submit" type="primary" :disabled="busy">
        {{ busy ? "保存中…" : "保存权益" }}
      </a-button></AdminForm
    >
  </section>
</template>

<style scoped>
.content-operations {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.panel-heading h2 {
  margin: 0;
  font-size: 16px;
}
.module-editor {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.module-editor > label {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.muted {
  margin: 0;
  color: var(--admin-muted);
  line-height: 1.6;
}
.table-cell-content {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.course-card :deep(.ant-card-body) {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.course-card :deep(.ant-card-body) > label {
  display: grid;
  gap: 6px;
  min-width: 0;
}
@media (max-width: 650px) {
  .course-card :deep(.ant-card-body) {
    grid-template-columns: minmax(0, 1fr);
  }
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
.sku-form > label {
  align-content: start;
}
</style>
