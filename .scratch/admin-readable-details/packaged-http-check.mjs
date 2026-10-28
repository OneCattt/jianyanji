import { randomBytes, scryptSync } from "node:crypto";
import { spawnSync } from "node:child_process";
import { writeFile } from "node:fs/promises";
import assert from "node:assert/strict";
const base = "http://127.0.0.1:3123";
const stamp = Date.now();
const password = randomBytes(20).toString("hex");
const salt = randomBytes(16).toString("hex");
const hash = salt + ":" + scryptSync(password, salt, 64).toString("hex");
function sql(input) {
  const r = spawnSync(
    "/opt/homebrew/bin/mysql",
    [
      "-h",
      "127.0.0.1",
      "-P",
      "3308",
      "-u",
      "root",
      "--default-character-set=utf8mb4",
      "--batch",
      "--skip-column-names",
      "java_rewards_test_user_options_20261028",
    ],
    { input, encoding: "utf8" },
  );
  assert.equal(r.status, 0, "本地隔离数据库初始化失败");
  return r.stdout.trim();
}
const userId = Number(
  sql(
    `INSERT INTO users(openid,nickname) VALUES ('readable-http-${stamp}','本地HTTP客户'); SELECT LAST_INSERT_ID();`,
  ),
);
const otherId = Number(
  sql(
    `INSERT INTO users(openid,nickname) VALUES ('readable-other-${stamp}','其他客户'); SELECT LAST_INSERT_ID();`,
  ),
);
const deletedId = Number(
  sql(
    `INSERT INTO users(openid,nickname,is_deleted) VALUES ('readable-deleted-${stamp}','已删除客户',1); SELECT LAST_INSERT_ID();`,
  ),
);
for (let i = 0; i < 23; i++)
  sql(
    `INSERT INTO addresses(user_id,recipient,phone,province,city,district,detail,is_default) VALUES (${userId},'HTTP收货人${i}','13800000000','广东省','广州市','测试区','分页地址${i}',${i === 0 ? 1 : 0});`,
  );
sql(
  `INSERT INTO addresses(user_id,recipient,phone,province,city,district,detail) VALUES (${otherId},'其他用户收货人','13800000000','广东省','广州市','测试区','不可跨用户');`,
);
sql(
  `INSERT INTO addresses(user_id,recipient,phone,province,city,district,detail,is_deleted) VALUES (${userId},'已删除收货人','13800000000','广东省','广州市','测试区','不可选择',1);`,
);
const names = ["readable" + stamp, "unrelated" + stamp];
sql(
  `INSERT INTO staff(username,display_name,password_hash,permissions) VALUES ('${names[0]}','本地接口验收','${hash}','["orders:manage"]'),('${names[1]}','本地权限验收','${hash}','["content:manage"]');`,
);
const checks = [];
async function check(name, path, status, cookie = "", verify = () => true) {
  const r = await fetch(base + "/api" + path, {
    headers: cookie ? { Cookie: cookie } : {},
  });
  const body = await r.json();
  assert.equal(r.status, status, name);
  assert(verify(body), name);
  checks.push({ name, status, pass: true });
  return body;
}
async function login(username) {
  const r = await fetch(base + "/api/admin/login", {
    method: "POST",
    headers: { Origin: base, "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  assert.equal(r.status, 200, "本地账号登录");
  return r.headers.get("set-cookie").split(";")[0];
}
await check("匿名名称查询被拒绝", "/admin/user-labels?ids=" + userId, 401);
await check(
  "匿名地址查询被拒绝",
  `/admin/users/${userId}/address-options`,
  401,
);
const cookie = await login(names[0]),
  denied = await login(names[1]);
await check(
  "无权限名称查询被拒绝",
  "/admin/user-labels?ids=" + userId,
  403,
  denied,
);
await check(
  "无权限地址查询被拒绝",
  `/admin/users/${userId}/address-options`,
  403,
  denied,
);
await check(
  "去重名称仅返回编号及昵称，过滤已删除用户",
  `/admin/user-labels?ids=${userId},${otherId},${userId},${deletedId}`,
  200,
  cookie,
  (b) =>
    b.items.length === 2 &&
    b.items.every((u) => Object.keys(u).sort().join(",") === "id,nickname") &&
    b.items.some((u) => u.id === userId && u.nickname === "本地HTTP客户"),
);
await check(
  "非法名称编号被拒绝",
  "/admin/user-labels?ids=1%20OR%201=1",
  400,
  cookie,
);
await check(
  "名称批次超限被拒绝",
  "/admin/user-labels?ids=" + Array(101).fill(userId).join(","),
  400,
  cookie,
);
const path = `/admin/users/${userId}/address-options`;
await check(
  "首屏地址按默认优先且排除已删除和其他用户",
  path,
  200,
  cookie,
  (b) =>
    b.total === 23 &&
    b.items.length === 20 &&
    b.items[0].recipient === "HTTP收货人0" &&
    !("user_id" in b.items[0]),
);
await check(
  "地址第二页覆盖未加载内容",
  path + "?page=2",
  200,
  cookie,
  (b) => b.items.length === 3 && b.total === 23,
);
await check(
  "地址可按收货人搜索",
  path + "?q=" + encodeURIComponent("HTTP收货人22"),
  200,
  cookie,
  (b) => b.total === 1 && b.items[0].detail === "分页地址22",
);
await check(
  "不能搜索到其他用户地址",
  path + "?q=" + encodeURIComponent("其他用户收货人"),
  200,
  cookie,
  (b) => b.total === 0,
);
await check(
  "不能搜索到已删除地址",
  path + "?q=" + encodeURIComponent("已删除收货人"),
  200,
  cookie,
  (b) => b.total === 0,
);
await check("地址非法页码被拒绝", path + "?page=0", 400, cookie);
await check(
  "开发认证关闭",
  "/public/context",
  200,
  "",
  (b) => b.devAuth === false,
);
await writeFile(
  ".scratch/admin-readable-details/packaged-http-result.json",
  JSON.stringify(
    {
      environment:
        "实际提交JAR + 本机隔离MySQL + 真实HTTP登录会话，仅本地测试数据",
      checks,
    },
    null,
    2,
  ) + "\n",
);
console.log(JSON.stringify({ passed: checks.length, failed: 0 }));
