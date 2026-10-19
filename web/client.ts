export type Permission = "staff:manage" | "users:read" | "audit:read";
export interface User {
  id: number;
  nickname: string;
  phone: string;
  identity: "B" | "C" | "D";
  identityName: string;
}
export interface Staff {
  id: number;
  username: string;
  displayName: string;
  permissions: Permission[];
  enabled: boolean;
}
export interface Audit {
  id: number;
  actor_kind: string;
  actor_id: number;
  action: string;
  object_type: string;
  object_id: string;
  result: string;
  reason: string;
  request_id: string;
  create_time: string;
}
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  options: { method?: string; body?: unknown; token?: string } = {},
): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method: options.method || "GET",
    credentials: "same-origin",
    headers: {
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
    },
    ...(options.body ? { body: JSON.stringify(options.body) } : {}),
  });
  const data = await response.json();
  if (!response.ok)
    throw new ApiError(data.message || "请求失败，请稍后重试", response.status);
  return data;
}
export const permissionNames: Record<Permission, string> = {
  "staff:manage": "工作人员管理",
  "users:read": "用户资料查看",
  "audit:read": "操作记录查看",
};
