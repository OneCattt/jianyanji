export interface ImageCrop {
  x: number;
  y: number;
  size: number;
}

const clamp = (value: number, min: number, max: number) =>
  Math.max(min, Math.min(max, value));

/** 用原图像素计算正方形裁切区域，百分比位置方便鼠标和键盘使用同一套逻辑。 */
export function squareCrop(
  width: number,
  height: number,
  zoom: number,
  horizontal: number,
  vertical: number,
): ImageCrop {
  const size = Math.min(width, height) / clamp(zoom, 1, 4);
  return {
    x: ((width - size) * clamp(horizontal, 0, 100)) / 100,
    y: ((height - size) * clamp(vertical, 0, 100)) / 100,
    size,
  };
}

/** 封面最多输出 1200 像素；小图保留实际像素，不通过插值放大。 */
export function coverSize(crop: ImageCrop) {
  return Math.max(1, Math.min(1200, Math.floor(crop.size)));
}

/** 拖动画面时，裁切窗口在原图中向相反方向移动。 */
export function dragPosition(
  start: number,
  delta: number,
  displaySize: number,
  cropSize: number,
  imageSize: number,
) {
  const remaining = imageSize - cropSize;
  return remaining > 0
    ? clamp(
        start - ((delta / displaySize) * cropSize * 100) / remaining,
        0,
        100,
      )
    : 50;
}
