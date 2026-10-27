<script setup lang="ts">
import { useAdminList } from "../useAdminList";
import FeedbackToast from "./FeedbackToast.vue";
import { computed, onMounted, ref } from "vue";
import { contentApi, type BrandPost, type PostInput } from "../content-client";
import ImageUploader from "./ImageUploader.vue";
import ImageListUploader from "./ImageListUploader.vue";
import AdminDialog from "./AdminDialog.vue";
import AdminForm from "./AdminForm.vue";
import AdminProductSelect from "./AdminProductSelect.vue";
import AdminSelect from "./AdminSelect.vue";

const busy = ref(false),
  error = ref(""),
  notice = ref(""),
  editing = ref(false),
  selectedId = ref<number | null>(null),
  coverUploading = ref(false),
  imagesUploading = ref(false);
const uploading = computed(() => coverUploading.value || imagesUploading.value);
const names: Record<string, string> = {
  draft: "草稿",
  reviewed: "已审核",
  published: "已发布",
};
const empty = (): PostInput => ({
  type: "article",
  title: "",
  summary: "",
  body: "",
  cover: "",
  imageUrls: [],
  videoUrl: "",
  durationSeconds: 0,
  productIds: [],
  reason: "",
});
const form = ref<PostInput>(empty());
const action = ref<{ post: BrandPost; kind: string } | null>(null),
  actionReason = ref("");
async function load() {
  try {
    await loadPosts();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "内容读取失败";
  }
}
function edit(post?: BrandPost) {
  selectedId.value = post?.id || null;
  form.value = post
    ? {
        type: post.type,
        title: post.title,
        summary: post.summary,
        body: post.body,
        cover: post.cover,
        imageUrls: [...post.imageUrls],
        videoUrl: post.video_url,
        durationSeconds: post.duration_seconds,
        productIds: [...post.productIds],
        reason: "",
      }
    : empty();
  editing.value = true;
  error.value = "";
}
async function save() {
  if (busy.value || uploading.value) return;
  if (
    form.value.type === "video" &&
    (!Number.isInteger(form.value.durationSeconds) ||
      form.value.durationSeconds < 1 ||
      form.value.durationSeconds > 180)
  ) {
    error.value = "视频时长须为 1 至 180 秒的整数";
    return;
  }
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await contentApi.save(selectedId.value, {
      ...form.value,
      videoUrl: form.value.type === "video" ? form.value.videoUrl : "",
      durationSeconds:
        form.value.type === "video" ? Number(form.value.durationSeconds) : 0,
    });
    editing.value = false;
    notice.value = "已保存为草稿，请审核当前内容后发布";
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "保存失败";
  } finally {
    busy.value = false;
  }
}
async function perform() {
  if (!action.value || busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await contentApi.action(
      action.value.post.id,
      action.value.kind,
      actionReason.value,
    );
    action.value = null;
    notice.value = "内容状态已更新";
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "操作失败";
  } finally {
    busy.value = false;
  }
}
function choose(post: BrandPost, kind: string) {
  action.value = { post, kind };
  actionReason.value = "";
}
onMounted(load);

const search = ref(""),
  status = ref("");
const {
  rows: posts,
  pagination: postPagination,
  loading: listLoading,
  load: loadPosts,
} = useAdminList<BrandPost>(
  "/admin/posts",
  () => ({ q: search.value, status: status.value }),
  error,
);
const filteredPosts = computed(() => posts.value);

const postColumns = [
  { title: "标题", key: "c0", width: 220 },
  { title: "类型", key: "c1", width: 160 },
  { title: "状态", key: "c2", width: 160 },
  { title: "操作", key: "c3", width: 200 },
];
</script>

<template>
  <FeedbackToast v-model:error="error" v-model:notice="notice" />
  <section class="content-operations">
    <Teleport defer to="#admin-page-actions">
      <a-button
        html-type="button"
        type="primary"
        :disabled="busy"
        @click="edit()"
        >新建内容</a-button
      >
    </Teleport>
    <a-card size="small" title="内容列表">
      <AdminForm class="post-filters" @submit="() => {}">
        <label
          >标题或摘要<a-input
            v-model:value="search"
            allow-clear
            placeholder="搜索已有内容"
        /></label>
        <label
          >发布状态<AdminSelect v-model="status">
            <option value="">全部状态</option>
            <option v-for="(name, value) in names" :key="value" :value="value">
              {{ name }}
            </option>
          </AdminSelect></label
        >
        <span class="muted">{{ filteredPosts.length }} 篇内容</span>
      </AdminForm>
      <div class="table-scroll">
        <a-table
          :data-source="filteredPosts"
          :columns="postColumns"
          row-key="id"
          size="middle"
          :loading="busy || listLoading"
          :scroll="{ x: 680 }"
          :pagination="postPagination"
          ><template #bodyCell="{ column, record: post }"
            ><template v-if="column.key === 'c0'"
              ><div class="table-cell-content">{{ post.title }}</div></template
            >
            <template v-else-if="column.key === 'c1'"
              ><div class="table-cell-content">
                {{ post.type === "video" ? "短视频" : "图文" }}
              </div></template
            >
            <template v-else-if="column.key === 'c2'"
              ><div class="table-cell-content">
                <a-tag
                  :color="
                    post.status === 'published'
                      ? 'green'
                      : post.status === 'reviewed'
                        ? 'blue'
                        : 'default'
                  "
                  >{{ names[post.status] }}</a-tag
                >
              </div></template
            >
            <template v-else-if="column.key === 'c3'"
              ><div class="table-cell-content">
                <a-button html-type="button" @click="edit(post)">编辑</a-button
                ><a-button
                  html-type="button"
                  v-if="post.status === 'draft'"
                  @click="choose(post, 'review')"
                >
                  审核通过</a-button
                ><a-button
                  html-type="button"
                  v-if="post.status === 'reviewed'"
                  @click="choose(post, 'publish')"
                >
                  发布</a-button
                ><a-button
                  html-type="button"
                  v-if="post.status === 'published'"
                  @click="choose(post, 'unpublish')"
                >
                  下架</a-button
                ><a-button
                  html-type="button"
                  danger
                  @click="choose(post, 'remove')"
                >
                  移除
                </a-button>
              </div></template
            ></template
          ><template #emptyText>
            暂无符合条件的内容，可调整筛选条件或新建内容。
          </template></a-table
        >
      </div>
    </a-card>
  </section>
  <AdminDialog
    v-if="editing"
    title="维护种草内容"
    :busy="busy || uploading"
    @close="editing = false"
  >
    <AdminForm class="module-editor" @submit="save">
      <label
        >内容类型<AdminSelect v-model="form.type">
          <option value="article">图文</option>
          <option value="video">短视频</option>
        </AdminSelect></label
      >
      <label
        >标题<a-input
          v-model:value="form.title"
          required
          :maxlength="100" /></label
      ><label
        >摘要<a-textarea
          v-model:value="form.summary"
          required
          :maxlength="300"
        />
      </label>
      <ImageUploader
        v-model="form.cover"
        label="内容封面"
        purpose="content"
        required
        :disabled="busy || uploading"
        @busy-change="coverUploading = $event"
      />
      <label
        >正文<a-textarea
          v-model:value="form.body"
          required
          :maxlength="20000"
          :rows="8"
        />
      </label>
      <ImageListUploader
        v-model="form.imageUrls"
        :disabled="busy || uploading"
        @busy-change="imagesUploading = $event"
      />
      <template v-if="form.type === 'video'"
        ><label
          >MP4 视频地址<a-input
            v-model:value="form.videoUrl"
            type="url"
            required
            :maxlength="1000" /></label
        ><label
          >实际时长（秒，最多180）<a-input-number
            class="number-field"
            :precision="0"
            pattern="[0-9]+"
            v-model:value="form.durationSeconds"
            required
            :min="1"
            :max="180"
            :step="1"
        /></label>
        <p class="muted">
          填写可播放的 MP4 视频链接，最长 180 秒；封面通过图片入口上传。
        </p></template
      >
      <label
        >关联商品（最多 10 个）<AdminProductSelect
          v-model="form.productIds"
          kind="product"
          multiple
          :disabled="busy || uploading"
          aria-label="内容关联商品"
      /></label>
      <label
        >修改原因<a-input v-model:value="form.reason" required :maxlength="200"
      /></label>
      <div class="dialog-actions">
        <a-button
          html-type="button"
          :disabled="busy || uploading"
          @click="editing = false"
        >
          取消
        </a-button>
        <a-button
          html-type="submit"
          type="primary"
          :disabled="busy || uploading"
        >
          {{ busy ? "保存中…" : "保存草稿" }}
        </a-button>
      </div>
    </AdminForm>
  </AdminDialog>
  <AdminDialog
    v-if="action"
    title="确认内容操作"
    :busy="busy"
    @close="action = null"
    ><AdminForm class="module-editor" @submit="perform"
      ><p>
        {{ action.post.title }} ·
        {{
          {
            review: "审核通过",
            publish: "发布",
            unpublish: "下架",
            remove: "移除",
          }[action.kind]
        }}
      </p>
      <label
        >操作原因<a-input
          v-model:value="actionReason"
          required
          :maxlength="200"
      /></label>
      <div class="dialog-actions">
        <a-button html-type="button" :disabled="busy" @click="action = null">
          取消
        </a-button>
        <a-button html-type="submit" type="primary" :disabled="busy"
          >确认</a-button
        >
      </div></AdminForm
    ></AdminDialog
  >
</template>

<style scoped>
.post-filters {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 20px;
}
.post-filters > label {
  flex: 1 1 180px;
  max-width: 320px;
}
.post-filters > span {
  padding-bottom: 10px;
  font-size: 12px;
}

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
</style>
