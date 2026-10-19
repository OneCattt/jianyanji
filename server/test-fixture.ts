import { test, type TestContext } from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createApp } from "./app.ts";
import {
  openDatabase,
  createStaff,
  permissions,
  type StaffRow,
  type UserRow,
} from "./db.ts";
import { WechatError } from "./wechat.ts";

export const password = "Test-password-2026!";
export async function fixture(
  t: TestContext,
  options: Partial<Parameters<typeof createApp>[1]> = {},
) {
  const db = openDatabase(":memory:");
  const manager = createStaff(
    db,
    "manager",
    "品牌管理员",
    password,
    permissions,
  );
  const operator = createStaff(db, "operator", "客服", password, [
    "users:read",
  ]);
  const server = createApp(db, { devAuth: true, ...options }).listen(
    0,
    "127.0.0.1",
  );
  await once(server, "listening");
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  const base = `http://127.0.0.1:${address.port}`;
  t.after(async () => {
    server.closeAllConnections();
    await new Promise<void>((resolve, reject) =>
      server.close((error) => (error ? reject(error) : resolve())),
    );
    db.close();
  });
  async function call(
    path: string,
    options: {
      method?: string;
      body?: unknown;
      token?: string;
      cookie?: string;
      origin?: string;
    } = {},
  ) {
    const response = await fetch(base + "/api" + path, {
      method: options.method || "GET",
      headers: {
        Origin: options.origin || base,
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
        ...(options.cookie ? { Cookie: options.cookie } : {}),
      },
      ...(options.body ? { body: JSON.stringify(options.body) } : {}),
    });
    return {
      status: response.status,
      data: await response.json(),
      cookie: response.headers.get("set-cookie")?.split(";")[0] || "",
      headers: response.headers,
    };
  }
  async function user(account = "visitor") {
    const result = await call("/auth/dev", {
      method: "POST",
      body: { account },
    });
    assert.equal(result.status, 200);
    return result.data as {
      token: string;
      user: { id: number; identity: string };
    };
  }
  async function admin(username = "manager") {
    const result = await call("/admin/login", {
      method: "POST",
      body: { username, password },
    });
    assert.equal(result.status, 200);
    return result.cookie;
  }
  return { db, call, user, admin, manager, operator };
}
