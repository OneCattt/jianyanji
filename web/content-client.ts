import { api } from "./client";

export interface BrandPost {
  id: number;
  type: "article" | "video";
  title: string;
  summary: string;
  body: string;
  cover: string;
  imageUrls: string[];
  video_url: string;
  duration_seconds: number;
  productIds: number[];
  status: "draft" | "reviewed" | "published";
}
export interface PostInput {
  type: "article" | "video";
  title: string;
  summary: string;
  body: string;
  cover: string;
  imageUrls: string[];
  videoUrl: string;
  durationSeconds: number;
  productIds: number[];
  reason: string;
}
export interface CourseRight {
  enabled: boolean;
  title: string;
  introduction: string;
  benefits: string[];
  contact: string;
  enrollmentGuide: string;
}
export type CourseRights = Record<"D" | "C" | "B", CourseRight>;
export const contentApi = {
  list: () => api<BrandPost[]>("/admin/posts"),
  save: (id: number | null, body: PostInput) =>
    api<{ id: number; status: string }>("/admin/posts" + (id ? "/" + id : ""), {
      method: id ? "PUT" : "POST",
      body,
    }),
  action: (id: number, action: string, reason: string) =>
    api("/admin/posts/" + id + "/" + action, {
      method: "POST",
      body: { reason },
    }),
  courses: () => api<Partial<CourseRights>>("/admin/course-rights"),
  saveCourses: (body: CourseRights & { reason: string }) =>
    api<CourseRights>("/admin/course-rights", { method: "PUT", body }),
};
