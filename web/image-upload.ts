import { api } from "./client";
import { compressImage } from "./image-compression";

export type ImagePurpose = "catalog" | "content" | "membership";
export const IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"];
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

export function validateImageFile(file: File) {
  if (!IMAGE_TYPES.includes(file.type))
    throw new Error("请选择 JPG、PNG 或 WebP 图片");
  if (!file.size || file.size > MAX_IMAGE_BYTES)
    throw new Error("图片大小需在 1 字节至 5MB 之间");
}

interface UploadPolicy {
  host: string;
  url: string;
  key: string;
  fields: Record<string, string>;
  requestId: string;
}

export async function uploadImage(
  file: File,
  purpose: ImagePurpose,
  signal: AbortSignal,
  onProgress: (value: number) => void,
  options: { prepared?: boolean } = {},
) {
  validateImageFile(file);
  // 裁剪封面已经完成尺寸限制及编码，避免再次有损压缩。
  if (!options.prepared) file = await compressImage(file, signal);
  signal.throwIfAborted();
  const policy = await api<UploadPolicy>("/admin/image-uploads/policy", {
    method: "POST",
    body: { purpose, contentType: file.type, size: file.size },
    signal,
  });
  await new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    signal.addEventListener("abort", abort, { once: true });
    xhr.open("POST", policy.host);
    xhr.timeout = 60000;
    // 失败仅记录业务键和请求号，不上传图片内容或签名到日志接口。
    const fail = () => {
      const ossRequestId = xhr.getResponseHeader("x-oss-request-id") || "";
      void api("/admin/image-uploads/failure", {
        method: "POST",
        body: { purpose, key: policy.key, status: xhr.status, ossRequestId },
      }).catch(() => {});
      reject(
        new Error(
          `图片上传失败，请重新选择图片重试（请求号：${ossRequestId || policy.requestId}）`,
        ),
      );
    };
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable)
        onProgress(
          Math.min(99, Math.round((event.loaded / event.total) * 100)),
        );
    };
    xhr.onload = () => (xhr.status === 204 ? resolve() : fail());
    xhr.onerror = fail;
    xhr.ontimeout = fail;
    xhr.onabort = () => reject(new DOMException("上传已取消", "AbortError"));
    xhr.onloadend = () => signal.removeEventListener("abort", abort);
    const data = new FormData();
    for (const [name, value] of Object.entries(policy.fields))
      data.append(name, value);
    // OSS 要求 file 是表单最后一个字段。
    data.append("file", file);
    if (signal.aborted) {
      signal.removeEventListener("abort", abort);
      reject(new DOMException("上传已取消", "AbortError"));
    } else xhr.send(data);
  });
  return policy.url;
}
