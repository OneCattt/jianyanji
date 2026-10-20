<script setup lang="ts">
import FeedbackToast from "./FeedbackToast.vue";
import "../content-operations.css";
import { onMounted, ref } from "vue";
import { contentApi, type BrandPost, type PostInput } from "../content-client";
import ImageUploader from "./ImageUploader.vue";
import AdminDialog from "./AdminDialog.vue";
import AdminForm from "./AdminForm.vue";

const posts = ref<BrandPost[]>([]),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  editing = ref(false),
  selectedId = ref<number | null>(null),
  uploading = ref(false);
const names = { draft: "草稿", reviewed: "已审核", published: "已发布" };
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
const form = ref<PostInput>(empty()),
  images = ref(""),
  products = ref("");
const action = ref<{ post: BrandPost; kind: string } | null>(null),
  actionReason = ref("");
async function load() {
  try {
    posts.value = await contentApi.list();
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
        imageUrls: post.imageUrls,
        videoUrl: post.video_url,
        durationSeconds: post.duration_seconds,
        productIds: post.productIds,
        reason: "",
      }
    : empty();
  images.value = form.value.imageUrls.join("\n");
  products.value = form.value.productIds.join(",");
  editing.value = true;
  error.value = "";
}
async function save() {
  if (busy.value || uploading.value) return;
  const productIds = products.value.trim()
    ? products.value
        .split(/[,，\s]+/)
        .filter(Boolean)
        .map(Number)
    : [];
  if (productIds.some((id) => !Number.isSafeInteger(id) || id <= 0)) {
    error.value = "关联商品编号须为正整数";
    return;
  }
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await contentApi.save(selectedId.value, {
      ...form.value,
      imageUrls: images.value
        .split("\n")
        .map((s) => s.trim())
        .filter(Boolean),
      productIds,
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
</script>

<template>
  <FeedbackToast v-model:error="error" v-model:notice="notice" />
  <section class="table-panel content-operations">
    <div class="panel-heading">
      <h2>品牌种草内容</h2>
      <button class="primary" @click="edit()">新建内容</button>
    </div>
    <p class="muted">
      保存后为草稿，经审核再发布。短视频使用已配置媒体域名的 MP4
      地址，封面可直接上传。
    </p>
    <div class="table-scroll">
      <table>
        <thead>
          <tr>
            <th>标题</th>
            <th>类型</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="post in posts" :key="post.id">
            <td>{{ post.title }}</td>
            <td>{{ post.type === "video" ? "短视频" : "图文" }}</td>
            <td>{{ names[post.status] }}</td>
            <td class="actions">
              <button class="secondary" @click="edit(post)">编辑</button
              ><button
                class="secondary"
                v-if="post.status === 'draft'"
                @click="choose(post, 'review')"
              >
                审核通过</button
              ><button
                class="secondary"
                v-if="post.status === 'reviewed'"
                @click="choose(post, 'publish')"
              >
                发布</button
              ><button
                class="secondary"
                v-if="post.status === 'published'"
                @click="choose(post, 'unpublish')"
              >
                下架</button
              ><button class="secondary" @click="choose(post, 'remove')">
                移除
              </button>
            </td>
          </tr>
          <tr v-if="!posts.length">
            <td colspan="4" class="muted">
              暂无内容，请先录入经品牌方审核的真实素材。
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
  <AdminDialog
    v-if="editing"
    title="维护种草内容"
    :busy="busy || uploading"
    @close="editing = false"
  >
    <AdminForm class="content-editor" @submit="save">
      <label
        >内容类型<select v-model="form.type">
          <option value="article">图文</option>
          <option value="video">短视频</option>
        </select></label
      >
      <label>标题<input v-model="form.title" required maxlength="100" /></label
      ><label
        >摘要<textarea v-model="form.summary" required maxlength="300" />
      </label>
      <ImageUploader
        v-model="form.cover"
        label="内容封面"
        purpose="content"
        required
        @busy-change="uploading = $event"
      />
      <label
        >正文<textarea
          v-model="form.body"
          required
          maxlength="20000"
          rows="8"
        />
      </label>
      <label
        >正文图片地址（每行一张，最多9张）<textarea v-model="images" rows="3" />
      </label>
      <template v-if="form.type === 'video'"
        ><label
          >MP4 视频地址<input
            v-model="form.videoUrl"
            type="url"
            required
            maxlength="1000" /></label
        ><label
          >实际时长（秒，最多180）<input
            v-model.number="form.durationSeconds"
            type="number"
            required
            min="1"
            max="180"
            step="1"
        /></label>
        <p class="muted">
          先将审核后视频部署到配置的媒体域名，再填写稳定地址。图片上传入口不上传视频。
        </p></template
      >
      <label
        >关联商品编号（逗号分隔，最多10个）<input v-model="products"
      /></label>
      <label
        >修改原因<input v-model="form.reason" required maxlength="200"
      /></label>
      <div class="dialog-actions">
        <button
          class="secondary"
          type="button"
          :disabled="busy || uploading"
          @click="editing = false"
        >
          取消
        </button>
        <button class="primary" type="submit" :disabled="busy || uploading">
          {{ busy ? "保存中…" : "保存草稿" }}
        </button>
      </div>
    </AdminForm>
  </AdminDialog>
  <AdminDialog
    v-if="action"
    title="确认内容操作"
    :busy="busy"
    @close="action = null"
    ><AdminForm class="content-editor" @submit="perform"
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
        >操作原因<input v-model="actionReason" required maxlength="200"
      /></label>
      <div class="dialog-actions">
        <button
          class="secondary"
          type="button"
          :disabled="busy"
          @click="action = null"
        >
          取消
        </button>
        <button class="primary" type="submit" :disabled="busy">确认</button>
      </div></AdminForm
    ></AdminDialog
  >
</template>
