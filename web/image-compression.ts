/** 公开展示图在浏览器内压缩，长图按宽度等比缩放，避免只限制长边导致文字过小。 */
export async function compressImage(
  file: File,
  signal: AbortSignal,
): Promise<File> {
  let image: ImageBitmap;
  try {
    image = await createImageBitmap(file);
  } catch {
    throw new Error("图片无法读取，请选择有效的 JPG、PNG 或 WebP 图片");
  }
  const canvas = document.createElement("canvas");
  try {
    signal.throwIfAborted();
    // 限制画布尺寸和总像素，避免大图编码占用过多内存；不放大小图。
    const scale = Math.min(
      1,
      1600 / image.width,
      16384 / image.height,
      Math.sqrt(16000000 / (image.width * image.height)),
    );
    canvas.width = Math.max(1, Math.round(image.width * scale));
    canvas.height = Math.max(1, Math.round(image.height * scale));
    const context = canvas.getContext("2d");
    if (!context) throw new Error("当前浏览器无法处理图片，请更换浏览器重试");
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob(
        (value) =>
          value
            ? resolve(value)
            : reject(new Error("图片压缩失败，请重新选择图片")),
        "image/webp",
        0.82,
      );
    });
    signal.throwIfAborted();
    // 编码未变小则保留输入，避免小图或浏览器不支持 WebP 时反而增大文件。
    return blob.size < file.size
      ? new File([blob], file.name, { type: blob.type })
      : file;
  } finally {
    image.close();
    canvas.width = canvas.height = 0;
  }
}
