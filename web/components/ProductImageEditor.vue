<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import AdminDialog from "./AdminDialog.vue";
import { Alert } from "ant-design-vue";
import { coverSize, dragPosition, squareCrop } from "../image-crop";

const props = defineProps<{
  file: File;
  mode: "cover" | "detail";
  productName?: string;
  uploading: boolean;
  progress: number;
  uploadError: string;
}>();
const emit = defineEmits<{ confirm: [file: File]; cancel: [] }>();
const width = ref(0),
  height = ref(0),
  zoom = ref(1);
const horizontal = ref(50),
  vertical = ref(50);
const loading = ref(true),
  preparing = ref(false),
  error = ref("");
const canvas = ref<HTMLCanvasElement>();
const homeCanvas = ref<HTMLCanvasElement>();
const mallCanvas = ref<HTMLCanvasElement>();
const sourceUrl = URL.createObjectURL(props.file);
let image: ImageBitmap | undefined;
let disposed = false;
const crop = computed(() =>
  squareCrop(
    width.value,
    height.value,
    zoom.value,
    horizontal.value,
    vertical.value,
  ),
);
const outputSize = computed(() => coverSize(crop.value));
const lowResolution = computed(() =>
  props.mode === "cover" ? outputSize.value < 600 : width.value < 750,
);
const title = computed(() => props.productName || "商品名称");
const locked = computed(() => props.uploading || preparing.value);

function draw() {
  const target = canvas.value;
  if (!target || !image) return;
  const ctx = target.getContext("2d");
  if (!ctx) {
    error.value = "当前浏览器无法处理图片，请更换浏览器重试";
    return;
  }
  const area = crop.value;
  ctx.clearRect(0, 0, target.width, target.height);
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(
    image,
    area.x,
    area.y,
    area.size,
    area.size,
    0,
    0,
    target.width,
    target.height,
  );
  for (const preview of [homeCanvas.value, mallCanvas.value]) {
    const context = preview?.getContext("2d");
    if (!preview || !context) continue;
    context.clearRect(0, 0, preview.width, preview.height);
    // 首页左图为竖框、商城为方框，与小程序 aspectFill 使用相同的居中裁切。
    const visibleWidth = (target.height * preview.width) / preview.height;
    context.drawImage(
      target,
      (target.width - visibleWidth) / 2,
      0,
      visibleWidth,
      target.height,
      0,
      0,
      preview.width,
      preview.height,
    );
  }
}
function reset() {
  zoom.value = 1;
  horizontal.value = vertical.value = 50;
  draw();
}

let drag:
  | {
      id: number;
      x: number;
      y: number;
      horizontal: number;
      vertical: number;
      displaySize: number;
    }
  | undefined;
function startDrag(event: PointerEvent) {
  if (locked.value || !canvas.value || !image) return;
  canvas.value.setPointerCapture(event.pointerId);
  drag = {
    id: event.pointerId,
    x: event.clientX,
    y: event.clientY,
    horizontal: horizontal.value,
    vertical: vertical.value,
    displaySize: canvas.value.getBoundingClientRect().width,
  };
}
function moveDrag(event: PointerEvent) {
  if (!drag || drag.id !== event.pointerId || locked.value) return;
  horizontal.value = dragPosition(
    drag.horizontal,
    event.clientX - drag.x,
    drag.displaySize,
    crop.value.size,
    width.value,
  );
  vertical.value = dragPosition(
    drag.vertical,
    event.clientY - drag.y,
    drag.displaySize,
    crop.value.size,
    height.value,
  );
  draw();
}
function endDrag() {
  drag = undefined;
}

async function confirm() {
  if (loading.value || locked.value || !image || error.value) return;
  if (props.mode === "detail") {
    emit("confirm", props.file);
    return;
  }
  preparing.value = true;
  const output = document.createElement("canvas");
  try {
    output.width = output.height = outputSize.value;
    const context = output.getContext("2d");
    if (!context) throw new Error("当前浏览器无法处理图片，请更换浏览器重试");
    const area = crop.value;
    context.imageSmoothingQuality = "high";
    context.drawImage(
      image,
      area.x,
      area.y,
      area.size,
      area.size,
      0,
      0,
      output.width,
      output.height,
    );
    const blob = await new Promise<Blob>((resolve, reject) =>
      output.toBlob(
        (value) =>
          value
            ? resolve(value)
            : reject(new Error("封面生成失败，请重新选择图片")),
        "image/webp",
        0.9,
      ),
    );
    if (!disposed)
      emit(
        "confirm",
        new File([blob], "product-cover.webp", { type: blob.type }),
      );
  } catch (e) {
    if (!disposed)
      error.value = e instanceof Error ? e.message : "封面生成失败";
  } finally {
    preparing.value = false;
    output.width = output.height = 0;
  }
}
onMounted(async () => {
  try {
    const bitmap = await createImageBitmap(props.file);
    if (disposed) {
      bitmap.close();
      return;
    }
    image = bitmap;
    width.value = bitmap.width;
    height.value = bitmap.height;
    await nextTick();
    draw();
  } catch {
    if (!disposed)
      error.value = "图片无法读取，请选择有效的 JPG、PNG 或 WebP 图片";
  } finally {
    loading.value = false;
  }
});
onBeforeUnmount(() => {
  disposed = true;
  image?.close();
  URL.revokeObjectURL(sourceUrl);
});
</script>

<template>
  <AdminDialog
    :title="mode === 'cover' ? '制作商品列表封面' : '确认商品详情图片'"
    :busy="locked"
    wide
    @close="emit('cancel')"
  >
    <p class="editor-intro">
      {{
        mode === "cover"
          ? "拖动、缩放图片，让商品居中且主体清楚。建议主体占画面 70%～80%，尽量少放宣传小字。"
          : "保留完整海报及原有比例，用于商品详情展示。"
      }}
    </p>
    <Alert
      v-if="error || uploadError"
      type="error"
      show-icon
      :message="error || uploadError"
    />
    <div
      class="image-edit-layout"
      :class="{ 'detail-layout': mode === 'detail' }"
    >
      <div>
        <canvas
          v-if="mode === 'cover'"
          ref="canvas"
          class="crop-canvas"
          width="600"
          height="600"
          aria-label="商品封面裁剪预览，可拖动图片或使用下方滑块调整"
          @pointerdown="startDrag"
          @pointermove="moveDrag"
          @pointerup="endDrag"
          @pointercancel="endDrag"
          @lostpointercapture="endDrag"
        />
        <img
          v-else
          class="detail-preview"
          :src="sourceUrl"
          alt="详情图片完整预览"
        />
        <p v-if="loading" role="status">正在读取图片…</p>
        <template v-else-if="width">
          <p class="image-size">
            原图 {{ width }} × {{ height }} px<span v-if="mode === 'cover'">
              · 封面 {{ outputSize }} × {{ outputSize }} px</span
            >
          </p>
          <Alert
            v-if="lowResolution"
            type="warning"
            show-icon
            :message="
              mode === 'cover'
                ? '当前裁剪区域不足 600px，建议换清晰原图或减少放大；仍可继续上传。'
                : '图片宽度不足 750px，建议换清晰原图，避免详情放大后模糊。'
            "
          />
        </template>
        <fieldset
          v-if="mode === 'cover'"
          class="crop-controls"
          :disabled="locked || loading || !!error"
        >
          <label
            >主体大小 <output>{{ zoom.toFixed(2) }} 倍</output
            ><input
              v-model.number="zoom"
              type="range"
              min="1"
              max="4"
              step="0.01"
              aria-label="主体大小"
              @input="draw"
          /></label>
          <label
            >左右位置<input
              v-model.number="horizontal"
              type="range"
              min="0"
              max="100"
              step="0.1"
              aria-label="左右位置"
              @input="draw"
          /></label>
          <label
            >上下位置<input
              v-model.number="vertical"
              type="range"
              min="0"
              max="100"
              step="0.1"
              aria-label="上下位置"
              @input="draw"
          /></label>
          <a-button
            html-type="button"
            :disabled="locked || loading"
            @click="reset"
            >重置裁剪</a-button
          >
        </fieldset>
      </div>
      <aside v-if="mode === 'cover'" class="placement-previews">
        <h4>小程序展示预览</h4>
        <p>检查商品主体在两种画面里是否完整、清楚。</p>
        <h5>首页 · 今日臻选</h5>
        <div class="home-placement">
          <canvas
            ref="homeCanvas"
            width="340"
            height="480"
            aria-label="首页臻选图片预览"
          />
          <div>
            <small>明星单品</small><strong>{{ title }}</strong
            ><span>价格与购买入口</span
            ><span class="preview-button">立即选购</span>
          </div>
        </div>
        <p class="crop-hint">首页竖框会裁去封面左右边缘，请将商品放在中间。</p>
        <h5>商城 · 商品列表</h5>
        <div class="mall-placement">
          <canvas
            ref="mallCanvas"
            width="280"
            height="280"
            aria-label="商城列表图片预览"
          /><strong>{{ title }}</strong
          ><span>商品价格</span>
        </div>
      </aside>
    </div>
    <a-progress v-if="uploading" :percent="progress" size="small" />
    <p class="save-hint">确认上传后仅更新表单，保存商品后才会正式生效。</p>
    <footer class="image-edit-actions">
      <a-button html-type="button" :disabled="locked" @click="emit('cancel')"
        >取消</a-button
      ><a-button
        html-type="button"
        type="primary"
        :loading="locked"
        :disabled="loading || !!error"
        @click="confirm"
        >{{
          uploading
            ? "正在上传"
            : preparing
              ? "正在生成封面"
              : uploadError
                ? "重新上传"
                : "确认上传"
        }}</a-button
      >
    </footer>
  </AdminDialog>
</template>

<style scoped>
.editor-intro,
.placement-previews > p,
.image-size,
.save-hint {
  color: var(--admin-muted);
  font-size: 12px;
  line-height: 1.8;
}
.image-edit-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 28px;
  margin: 20px 0;
}
.crop-canvas {
  display: block;
  width: 100%;
  aspect-ratio: 1;
  background: #f5f6f8;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  touch-action: none;
  cursor: grab;
}
.crop-canvas:active {
  cursor: grabbing;
}
.crop-controls {
  border: 0;
  padding: 16px 0 0;
  margin: 0;
}
.crop-controls label {
  display: block;
  font-size: 12px;
  margin-bottom: 12px;
}
.crop-controls output {
  float: right;
  color: var(--admin-muted);
}
.crop-controls input {
  display: block;
  width: 100%;
  margin: 10px 0 0;
  accent-color: var(--admin-accent);
}
.placement-previews h4 {
  margin: 0;
  font-size: 14px;
}
.placement-previews h5 {
  margin: 16px 0 10px;
  font-size: 12px;
}
.home-placement {
  display: flex;
  background: #f0eae2;
  width: 100%;
  max-width: 319px;
}
.home-placement canvas {
  display: block;
  width: 53%;
  height: auto;
  aspect-ratio: 17 / 24;
  align-self: flex-start;
}
.home-placement > div {
  flex: 1;
  min-width: 0;
  padding: 20px 12px;
}
.home-placement small,
.home-placement strong,
.home-placement span,
.mall-placement strong,
.mall-placement span {
  display: block;
}
.home-placement small {
  font-size: 10px;
  letter-spacing: 2px;
  color: #8c827d;
}
.home-placement strong {
  font-family: STSong, "Songti SC", serif;
  color: #2c221e;
  font-size: 18px;
  font-weight: 400;
  line-height: 1.5;
  margin: 8px 0;
  overflow-wrap: anywhere;
}
.home-placement span {
  font-size: 11px;
  color: #8c827d;
}
.home-placement .preview-button {
  margin-top: 16px;
  padding: 7px 4px;
  text-align: center;
  background: #6e2c2c;
  color: #fff;
}
.crop-hint {
  margin: 8px 0 0;
}
.mall-placement {
  width: 140px;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  overflow: hidden;
}
.mall-placement canvas {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 1;
  background: #f5f4f2;
}
.mall-placement strong {
  padding: 8px 8px 0;
  font-size: 12px;
  font-weight: 500;
  overflow-wrap: anywhere;
}
.mall-placement span {
  padding: 4px 8px 8px;
  color: #7a1c1c;
  font-size: 11px;
}
.detail-layout {
  display: block;
}
.detail-preview {
  display: block;
  max-width: 100%;
  max-height: 400px;
  width: auto;
  height: auto;
  margin: auto;
  object-fit: contain;
}
.image-edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
}
@media (max-width: 640px) {
  .image-edit-layout {
    grid-template-columns: minmax(0, 1fr);
    gap: 20px;
  }
}
</style>
