<script setup lang="ts">
import { onBeforeUnmount, ref, useId } from "vue";
import { Upload } from "lucide-vue-next";
import { Image as AntImage } from "ant-design-vue";
import { uploadImage } from "../image-upload";
import { imageUrl } from "../image-url";

const props = defineProps<{ disabled?: boolean }>();
const model = defineModel<string[]>({ required: true });
const emit = defineEmits<{ "busy-change": [value: boolean] }>();
const limit = 9;
const busy = ref(false),
  progress = ref(0),
  currentImage = ref(0),
  totalImages = ref(0),
  error = ref("");
const id = useId();
let controller: AbortController | undefined;

async function selected(file: File, files: File[]) {
  // AntD 为每个文件调用一次；仅由首个回调按选择顺序处理整批图片。
  if (file !== files[0] || busy.value || props.disabled) return false;
  if (model.value.length + files.length > limit) {
    error.value = `正文图片最多 ${limit} 张，还可上传 ${limit - model.value.length} 张`;
    return false;
  }
  controller = new AbortController();
  const current = controller;
  busy.value = true;
  error.value = "";
  totalImages.value = files.length;
  emit("busy-change", true);
  try {
    for (let i = 0; i < files.length; i++) {
      current.signal.throwIfAborted();
      currentImage.value = i + 1;
      progress.value = 0;
      const url = await uploadImage(
        files[i],
        "content",
        current.signal,
        (value) => {
          progress.value = value;
        },
      );
      current.signal.throwIfAborted();
      // 每张成功后立即保留，后续上传失败不会丢掉已完成的图片。
      model.value = [...model.value, url];
    }
  } catch (e) {
    if (!current.signal.aborted)
      error.value = `第 ${currentImage.value} 张上传未完成，已成功的图片已保留。${e instanceof Error ? e.message : "请重新选择图片重试"}`;
  } finally {
    busy.value = false;
    emit("busy-change", false);
  }
  return false;
}

function remove(index: number) {
  if (busy.value || props.disabled) return;
  model.value = model.value.filter((_, i) => i !== index);
  error.value = "";
}

function previewWithKeyboard(event: KeyboardEvent) {
  (event.currentTarget as HTMLElement)
    .querySelector<HTMLElement>(".ant-image")
    ?.click();
}

onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <div
    class="image-list-uploader"
    role="group"
    :aria-labelledby="`${id}-label`"
    :aria-busy="busy"
  >
    <div class="image-list-heading">
      <span :id="`${id}-label`">正文图片</span>
      <span class="image-list-count">{{ model.length }} / {{ limit }}</span>
    </div>
    <div v-if="model.length" class="image-list-grid">
      <div v-for="(url, index) in model" :key="index" class="image-list-item">
        <div
          class="image-list-preview"
          role="button"
          tabindex="0"
          :aria-label="`预览正文图片，第 ${index + 1} 张`"
          @keydown.enter.prevent="previewWithKeyboard"
          @keydown.space.prevent="previewWithKeyboard"
        >
          <AntImage
            :src="imageUrl(url, 'thumb')"
            :preview="{ src: url }"
            :alt="`正文图片，第 ${index + 1} 张`"
            :width="96"
            :height="96"
          />
        </div>
        <div class="image-list-item-actions">
          <span>第 {{ index + 1 }} 张</span>
          <a-button
            html-type="button"
            type="text"
            danger
            size="small"
            :aria-label="`移除正文图片，第 ${index + 1} 张`"
            :disabled="disabled || busy"
            @click="remove(index)"
            >移除</a-button
          >
        </div>
      </div>
    </div>
    <a-upload
      accept="image/jpeg,image/png,image/webp"
      multiple
      :show-upload-list="false"
      :before-upload="selected"
      :disabled="disabled || busy || model.length >= limit"
    >
      <a-button
        html-type="button"
        :aria-describedby="`${id}-help`"
        :disabled="disabled || busy || model.length >= limit"
      >
        <Upload :size="14" aria-hidden="true" />
        {{
          busy
            ? "上传中…"
            : model.length >= limit
              ? "已达 9 张上限"
              : "上传正文图片"
        }}
      </a-button>
    </a-upload>
    <div v-if="busy" role="status">
      正在上传第 {{ currentImage }} / {{ totalImages }} 张
      <a-progress :percent="progress" size="small" />
    </div>
    <small :id="`${id}-help`"
      >最多 9 张，可一次选择多张；JPG、PNG、WebP，单张不超过
      5MB，上传时自动压缩。图片按上传顺序展示，点击可预览。</small
    >
    <span v-if="error" class="image-list-error" role="alert">{{ error }}</span>
  </div>
</template>

<style scoped>
.image-list-uploader {
  display: grid;
  gap: 12px;
  min-width: 0;
}
.image-list-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--admin-ink);
  font-weight: 500;
}
.image-list-count,
.image-list-uploader small,
.image-list-item-actions {
  color: var(--admin-muted);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.6;
}
.image-list-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.image-list-item {
  width: 96px;
}
.image-list-preview:focus-visible {
  outline: 2px solid var(--admin-accent);
  outline-offset: 3px;
}
.image-list-item :deep(.ant-image) {
  overflow: hidden;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  background: var(--admin-bg);
}
.image-list-item :deep(.ant-image-img) {
  height: 100%;
  object-fit: contain;
}
.image-list-item-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 4px;
}
.image-list-error {
  color: var(--admin-accent);
  font-size: 12px;
}
</style>
