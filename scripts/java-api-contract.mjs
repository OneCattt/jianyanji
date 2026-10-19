import assert from "node:assert/strict";

// 仅向显式指定的本机验收服务写入专属测试账号，不使用默认线上地址。
const base = process.env.JAVA_API_TEST_BASE;
if (
  process.env.JAVA_API_TEST_ISOLATED !== "1" ||
  !/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(base || "")
) {
  throw new Error(
    "请明确指定隔离服务 JAVA_API_TEST_BASE，并设置 JAVA_API_TEST_ISOLATED=1",
  );
}
let count = 0;
async function call(
  path,
  { method = "GET", cookie, token, body, status = 200, origin = base } = {},
) {
  const response = await fetch(base + "/api" + path, {
    method,
    headers: {
      Origin: origin,
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(cookie ? { Cookie: cookie } : {}),
      ...(token ? { Authorization: "Bearer " + token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const raw = await response.text();
  assert.equal(response.status, status, method + " " + path + ": " + raw);
  assert.ok(response.headers.get("x-request-id"));
  assert.equal(response.headers.get("cache-control"), "no-store");
  const value = JSON.parse(raw);
  count++;
  return { value, cookie: response.headers.get("set-cookie")?.split(";")[0] };
}
const { cookie } = await call("/admin/login", {
  method: "POST",
  body: { username: "admin", password: "Jianyanji-Local-2026!" },
});
const own = await call("/admin/me", { cookie });
assert.equal(own.value.permissions.length, 9);
for (const path of [
  "/admin/staff",
  "/admin/users",
  "/admin/audit",
  "/admin/content",
  "/admin/categories",
  "/admin/products",
  "/admin/shipping-settings",
  "/admin/orders",
  "/admin/aftersales-settings",
  "/admin/refunds",
  "/admin/membership-settings",
  "/admin/onboardings",
  "/admin/receipts",
  "/admin/cash-refunds",
  "/admin/reward-settings",
  "/admin/rewards",
  "/admin/reward-debts",
  "/admin/reward-settlements",
])
  await call(path, { cookie });
for (const path of [
  "/public/context",
  "/public/content",
  "/public/categories",
  "/products",
  "/membership",
])
  await call(path);
await call("/admin/staff", { status: 401 });
await call("/admin/login", {
  method: "POST",
  body: { username: "admin", password: "wrong" },
  status: 401,
});
await call("/admin/logout", {
  method: "POST",
  cookie,
  origin: "http://evil.example",
  status: 403,
});
const operator = await call("/admin/login", {
  method: "POST",
  body: { username: "operator", password: "Jianyanji-Local-2026!" },
});
await call("/admin/users", { cookie: operator.cookie });
await call("/admin/products", { cookie: operator.cookie, status: 403 });
const user = await call("/auth/dev", {
  method: "POST",
  body: { account: "contract_" + Date.now() },
});
const { token, user: person } = user.value;
await call("/me", { token });
await call("/users/" + (person.id + 10000), { token, status: 404 });
await call("/me", {
  method: "PATCH",
  token,
  body: { nickname: "Java接口验收", phone: "13800138000" },
});
const invalid = await call("/me", {
  method: "PATCH",
  token,
  body: { nickname: "错误", phone: "", identity: "B" },
  status: 400,
});
assert.equal(invalid.value.code, "VALIDATION_ERROR");
await call("/admin/me", { token, status: 401 });
await call("/me", { cookie, status: 401 });
for (const path of [
  "/addresses",
  "/cart",
  "/orders",
  "/onboardings",
  "/rewards",
])
  await call(path, { token });
const username = "verify_" + Date.now();
const created = await call("/admin/staff", {
  method: "POST",
  cookie,
  status: 201,
  body: {
    username,
    displayName: "Java验收客服",
    password: "Contract-Test-2026!",
    permissions: ["users:read"],
    reason: "独立接口验收",
  },
});
const id = created.value.id;
const staffSession = await call("/admin/login", {
  method: "POST",
  body: { username, password: "Contract-Test-2026!" },
});
await call("/admin/me", { cookie: staffSession.cookie });
await call("/admin/staff/" + id + "/remove", {
  method: "POST",
  cookie,
  body: { reason: "验收工作人员逻辑移除" },
});
await call("/admin/me", { cookie: staffSession.cookie, status: 401 });
const repeated = await call("/admin/staff", {
  method: "POST",
  cookie,
  status: 201,
  body: {
    username,
    displayName: "Java验收新客服",
    password: "Contract-Test-2026!",
    permissions: [],
    reason: "验收同名账号重新创建",
  },
});
assert.notEqual(repeated.value.id, id);
await call("/admin/staff/" + repeated.value.id + "/remove", {
  method: "POST",
  cookie,
  body: { reason: "验收后逻辑移除" },
});
await call("/auth/logout", { method: "POST", token });
await call("/me", { token, status: 401 });
await call("/not-a-real-endpoint", { status: 404 });
const malformed = await fetch(base + "/api/auth/dev", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: "{",
});
assert.equal(malformed.status, 400);
assert.equal((await malformed.json()).code, "INVALID_JSON");
count++;
console.log(JSON.stringify({ checks: count, result: "passed" }));
