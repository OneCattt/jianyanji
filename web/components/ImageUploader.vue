<script setup lang="ts">
import FeedbackToast from "./FeedbackToast.vue";
import { onBeforeUnmount, ref, useId, watch } from "vue";
import { ImagePlus, Upload } from "lucide-vue-next";
import { uploadImage, type ImagePurpose } from "../image-upload";
import { imageUrl } from "../image-url";

const props = defineProps<{
  label: string;
  purpose: ImagePurpose;
  required?: boolean;
  disabled?: boolean;
}>();
const model = defineModel<string>({ required: true });
const emit = defineEmits<{ "busy-change": [value: boolean] }>();
const busy = ref(false),
  progress = ref(0),
  error = ref(""),
  previewFailed = ref(false);
const id = useId();
let controller: AbortController | undefined;
watch(model, () => (previewFailed.value = false));

async function selected(file: File) {
  if (busy.value || props.disabled) return false;
  controller = new AbortController();
  const current = controller;
  busy.value = true;
  progress.value = 0;
  error.value = "";
  emit("busy-change", true);
  try {
    const url = await uploadImage(
      file,
      props.purpose,
      current.signal,
      (value) => {
        progress.value = value;
      },
    );
    if (!current.signal.aborted) model.value = url;
  } catch (e) {
    if (!current.signal.aborted)
      error.value = e instanceof Error ? e.message : "图片上传失败，请重试";
  } finally {
    busy.value = false;
    emit("busy-change", false);
  }
  return false;
}
onBeforeUnmount(() => {
  controller?.abort();
  emit("busy-change", false);
});
</script>

<template>
  <FeedbackToast v-model:error="error" />
  <div class="image-uploader" data-admin-control :aria-busy="busy">
    <div class="image-uploader-preview">
      <img
        v-if="model && !previewFailed"
        :src="imageUrl(model, 'card')"
        :alt="`${label}预览`"
        @error="previewFailed = true"
      />
      <template v-else>
        <ImagePlus :size="24" aria-hidden="true" />
        <span>{{ model ? "原图无法预览" : "尚未上传" }}</span>
      </template>
    </div>
    <div class="image-uploader-controls">
      <span :id="`${id}-label`" class="image-uploader-label">
        <span v-if="required" class="image-uploader-required" aria-hidden="true"
          >*</span
        >{{ label }}
      </span>
      <a-input
        class="sr-only"
        :value="model"
        :required="required"
        :disabled="disabled || busy"
        :aria-label="label"
        tabindex="-1"
      />
      <div class="image-uploader-actions">
        <a-upload
          accept="image/jpeg,image/png,image/webp"
          :show-upload-list="false"
          :before-upload="selected"
          :disabled="disabled || busy"
        >
          <a-button
            html-type="button"
            data-admin-trigger
            :aria-labelledby="`${id}-action ${id}-label`"
            :aria-describedby="`${id}-help`"
            :disabled="disabled || busy"
          >
            <Upload :size="14" aria-hidden="true" />
            <span :id="`${id}-action`">{{
              busy
                ? progress
                  ? `上传中 ${progress}%`
                  : "准备图片中…"
                : model
                  ? "更换图片"
                  : "上传图片"
            }}</span>
          </a-button>
        </a-upload>
        <a-button
          html-type="button"
          v-if="model && !required"
          type="text"
          danger
          :disabled="disabled || busy"
          @click="model = ''"
        >
          清除
        </a-button>
      </div>
      <a-progress v-if="busy" :percent="progress" size="small" />
      <small :id="`${id}-help`"
        >JPG、PNG、WebP，单张不超过 5MB，上传时自动压缩</small
      >
      <span v-if="busy" class="sr-only" role="status"
        >{{ label }}正在上传，{{ progress }}%</span
      >
    </div>
  </div>
</template>

<style scoped>
.image-uploader .sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.image-uploader {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}
.image-uploader-preview {
  display: flex;
  flex: 0 0 96px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 96px;
  height: 108px;
  overflow: hidden;
  border: 1px dashed var(--admin-border);
  border-radius: 6px;
  background: var(--admin-bg);
  color: var(--admin-muted);
  font-size: 11px;
}
.image-uploader-preview img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.image-uploader-controls {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}
.image-uploader-label {
  color: var(--admin-ink);
  font-size: 13px;
  font-weight: 500;
}
.image-uploader-required,
.image-uploader-error {
  color: var(--admin-accent);
}
.image-uploader-required {
  margin-right: 4px;
}
.image-uploader-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.image-uploader-controls small {
  color: var(--admin-muted);
  font-size: 11px;
  line-height: 1.6;
}
</style>
