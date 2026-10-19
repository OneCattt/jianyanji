import { fixture, password } from "./test-fixture.ts";
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

test("公开入口可匿名浏览，个人资料和后台必须登录", async (t) => {
  const f = await fixture(t);
  assert.equal((await f.call("/public/context")).status, 200);
  assert.equal((await f.call("/me")).status, 401);
  assert.equal((await f.call("/admin/staff")).status, 401);
});
test("首次及重复登录保持游客身份，不接受自行传入身份或 openid", async (t) => {
  const f = await fixture(t);
  const first = await f.user("new-customer");
  const second = await f.user("new-customer");
  assert.equal(first.user.identity, "D");
  assert.equal(first.user.id, second.user.id);
  assert.equal(
    (
      await f.call("/auth/dev", {
        method: "POST",
        body: { account: "new-customer", identity: "B" },
      })
    ).status,
    400,
  );
  assert.equal(
    (
      await f.call("/auth/wechat", {
        method: "POST",
        body: { code: "test", openid: "forged" },
      })
    ).status,
    400,
  );
  assert.equal(
    (
      f.db
        .prepare("SELECT count(*) AS n FROM users WHERE is_deleted=0")
        .get() as { n: number }
    ).n,
    1,
  );
});
test("真实微信登录使用渠道返回身份，同一用户重复登录不重复开户", async (t) => {
  const codes: string[] = [];
  const f = await fixture(t, {
    devAuth: false,
    exchangeCode: async (code) => {
      codes.push(code);
      return "wechat-verified-openid";
    },
  });
  const one = await f.call("/auth/wechat", {
    method: "POST",
    body: { code: "temporary-code-1" },
  });
  const two = await f.call("/auth/wechat", {
    method: "POST",
    body: { code: "temporary-code-2" },
  });
  assert.equal(one.status, 200);
  assert.equal(one.data.user.identity, "D");
  assert.equal(one.data.user.id, two.data.user.id);
  assert.deepEqual(codes, ["temporary-code-1", "temporary-code-2"]);
  assert.ok(!JSON.stringify(one.data).includes("openid"));
});
test("微信换取身份失败时不创建用户或会话", async (t) => {
  const f = await fixture(t, {
    exchangeCode: async () => {
      throw new WechatError("微信登录失败，请重新登录");
    },
  });
  assert.equal(
    (
      await f.call("/auth/wechat", {
        method: "POST",
        body: { code: "expired" },
      })
    ).status,
    502,
  );
  assert.equal(
    (f.db.prepare("SELECT count(*) AS n FROM users").get() as { n: number }).n,
    0,
  );
});
test("已有主理人与店主身份保留，过期资格按游客展示", async (t) => {
  const f = await fixture(t);
  for (const [account, identity] of [
    ["store", "C"],
    ["principal", "B"],
  ]) {
    f.db
      .prepare("INSERT INTO users(openid,identity) VALUES(?,?)")
      .run("dev:" + account, identity);
    assert.equal((await f.user(account)).user.identity, identity);
  }
  f.db
    .prepare(
      "INSERT INTO users(openid,identity,identity_valid_until) VALUES(?,?,?)",
    )
    .run("dev:expired", "B", "2020-01-01T00:00:00Z");
  assert.equal((await f.user("expired")).user.identity, "D");
});
test("更换用户编号不能读取或修改他人资料，不能通过资料接口提权", async (t) => {
  const f = await fixture(t);
  const one = await f.user("first"),
    two = await f.user("second");
  assert.equal(
    (await f.call("/users/" + two.user.id, { token: one.token })).status,
    404,
  );
  assert.equal(
    (
      await f.call("/users/" + two.user.id, {
        method: "PATCH",
        token: one.token,
        body: { nickname: "冒名", phone: "" },
      })
    ).status,
    404,
  );
  assert.equal(
    (
      await f.call("/me", {
        method: "PATCH",
        token: one.token,
        body: { nickname: "自己", phone: "", identity: "B" },
      })
    ).status,
    400,
  );
  assert.notEqual(
    (await f.call("/me", { token: two.token })).data.nickname,
    "冒名",
  );
});
test("本人能保存昵称电话，操作留痕且后台电话脱敏", async (t) => {
  const f = await fixture(t);
  const one = await f.user();
  const saved = await f.call("/me", {
    method: "PATCH",
    token: one.token,
    body: { nickname: "清欣用户", phone: "13800138000" },
  });
  assert.equal(saved.status, 200);
  assert.equal(saved.data.phone, "13800138000");
  const cookie = await f.admin();
  const users = await f.call("/admin/users", { cookie });
  assert.equal(users.data.items[0].phone, "138****8000");
  const audit = await f.call(
    "/admin/audit?objectType=user&objectId=" + one.user.id,
    { cookie },
  );
  assert.ok(
    audit.data.items.some(
      (e: { action: string; actor_id: number; reason: string }) =>
        e.action === "profile.update" && e.actor_id === one.user.id && e.reason,
    ),
  );
  assert.ok(!JSON.stringify(audit.data).includes("13800138000"));
});
test("后台账号和购买身份分开，顾客令牌不能用于后台", async (t) => {
  const f = await fixture(t);
  const buyer = await f.user("manager");
  const cookie = await f.admin();
  assert.equal(buyer.user.identity, "D");
  assert.equal(
    (await f.call("/admin/staff", { token: buyer.token })).status,
    401,
  );
  assert.equal((await f.call("/me", { cookie })).status, 401);
  assert.equal((await f.call("/admin/me", { cookie })).status, 200);
});
test("服务端拦截后台功能越权，并能按权限对象查到拦截记录", async (t) => {
  const f = await fixture(t);
  const cookie = await f.admin("operator");
  assert.equal((await f.call("/admin/users", { cookie })).status, 200);
  assert.equal((await f.call("/admin/staff", { cookie })).status, 403);
  assert.equal((await f.call("/admin/audit", { cookie })).status, 403);
  assert.equal(
    (
      await f.call("/admin/staff", {
        method: "POST",
        cookie,
        body: {
          username: "intruder",
          displayName: "非法",
          password,
          permissions: [],
          reason: "越权",
        },
      })
    ).status,
    403,
  );
  const manager = await f.admin();
  const audit = await f.call(
    "/admin/audit?objectType=permission&objectId=staff%3Amanage",
    { cookie: manager },
  );
  assert.ok(
    audit.data.items.some((e: { result: string }) => e.result === "denied"),
  );
});
test("管理员创建账号及授权，权限撤销对已有会话立即生效", async (t) => {
  const f = await fixture(t);
  const cookie = await f.admin();
  const created = await f.call("/admin/staff", {
    method: "POST",
    cookie,
    body: {
      username: "newstaff",
      displayName: "新同事",
      password,
      permissions: ["users:read"],
      reason: "新增客服",
    },
  });
  assert.equal(created.status, 201);
  assert.ok(!JSON.stringify(created.data).includes("password"));
  const newCookie = await f.admin("newstaff");
  assert.equal(
    (await f.call("/admin/users", { cookie: newCookie })).status,
    200,
  );
  await f.call("/admin/staff/" + created.data.id, {
    method: "PATCH",
    cookie,
    body: {
      displayName: "新同事",
      permissions: [],
      enabled: true,
      reason: "调整职责",
    },
  });
  assert.equal(
    (await f.call("/admin/users", { cookie: newCookie })).status,
    403,
  );
});
test("停用账号撤销旧会话，重新启用也必须重新登录", async (t) => {
  const f = await fixture(t);
  const cookie = await f.admin(),
    operatorCookie = await f.admin("operator");
  const body = {
    displayName: "客服",
    permissions: ["users:read"],
    enabled: false,
    reason: "离岗停用",
  };
  assert.equal(
    (
      await f.call("/admin/staff/" + f.operator, {
        method: "PATCH",
        cookie,
        body,
      })
    ).status,
    200,
  );
  assert.equal(
    (await f.call("/admin/me", { cookie: operatorCookie })).status,
    401,
  );
  assert.equal(
    (
      await f.call("/admin/login", {
        method: "POST",
        body: { username: "operator", password },
      })
    ).status,
    401,
  );
  await f.call("/admin/staff/" + f.operator, {
    method: "PATCH",
    cookie,
    body: { ...body, enabled: true, reason: "恢复工作" },
  });
  assert.equal(
    (await f.call("/admin/me", { cookie: operatorCookie })).status,
    401,
  );
  assert.ok(await f.admin("operator"));
});
test("不能停用、移除或剥夺最后一个启用管理员的管理权限", async (t) => {
  const f = await fixture(t);
  const cookie = await f.admin();
  assert.equal(
    (
      await f.call("/admin/staff/" + f.manager, {
        method: "PATCH",
        cookie,
        body: {
          displayName: "管理",
          permissions: [],
          enabled: true,
          reason: "清空权限",
        },
      })
    ).status,
    409,
  );
  assert.equal(
    (
      await f.call("/admin/staff/" + f.manager, {
        method: "PATCH",
        cookie,
        body: {
          displayName: "管理",
          permissions: [...permissions],
          enabled: false,
          reason: "停用",
        },
      })
    ).status,
    409,
  );
  assert.equal(
    (
      await f.call("/admin/staff/" + f.manager + "/remove", {
        method: "POST",
        cookie,
        body: { reason: "移除" },
      })
    ).status,
    409,
  );
});
test("移除仅做逻辑删除，保留历史并允许重新创建相同登录名", async (t) => {
  const f = await fixture(t);
  const cookie = await f.admin(),
    oldCookie = await f.admin("operator");
  assert.equal(
    (
      await f.call("/admin/staff/" + f.operator + "/remove", {
        method: "POST",
        cookie,
        body: { reason: "结束合作" },
      })
    ).status,
    200,
  );
  const old = f.db
    .prepare("SELECT * FROM staff WHERE id=?")
    .get(f.operator) as unknown as StaffRow;
  assert.equal(old.is_deleted, 1);
  assert.equal(old.enabled, 0);
  assert.equal((await f.call("/admin/me", { cookie: oldCookie })).status, 401);
  const created = await f.call("/admin/staff", {
    method: "POST",
    cookie,
    body: {
      username: "operator",
      displayName: "新的客服",
      password,
      permissions: ["users:read"],
      reason: "新同事使用账号",
    },
  });
  assert.equal(created.status, 201);
  assert.notEqual(created.data.id, f.operator);
  const audit = await f.call(
    "/admin/audit?objectType=staff&objectId=" + f.operator,
    { cookie },
  );
  assert.ok(
    audit.data.items.some(
      (e: { action: string; reason: string }) =>
        e.action === "staff.remove" && e.reason === "结束合作",
    ),
  );
});
test("后台写入拒绝跨站来源，登录 cookie 不暴露给脚本", async (t) => {
  const f = await fixture(t);
  assert.equal(
    (
      await f.call("/admin/login", {
        method: "POST",
        origin: "https://foreign.example",
        body: { username: "manager", password },
      })
    ).status,
    403,
  );
  const login = await f.call("/admin/login", {
    method: "POST",
    body: { username: "manager", password },
  });
  assert.match(login.headers.get("set-cookie") || "", /HttpOnly/);
  assert.match(login.headers.get("set-cookie") || "", /SameSite=Strict/);
  assert.equal(
    (
      await f.call("/admin/staff/" + f.operator + "/remove", {
        method: "POST",
        cookie: login.cookie,
        origin: "https://foreign.example",
        body: { reason: "跨站" },
      })
    ).status,
    403,
  );
});
test("过期与已退出的会话不可重用，令牌只以摘要保存", async (t) => {
  const f = await fixture(t);
  const buyer = await f.user();
  const sessions = f.db.prepare("SELECT token_hash FROM sessions").all();
  assert.ok(!JSON.stringify(sessions).includes(buyer.token));
  await f.call("/auth/logout", { method: "POST", token: buyer.token });
  assert.equal((await f.call("/me", { token: buyer.token })).status, 401);
  const another = await f.user("another");
  f.db
    .prepare(
      "UPDATE sessions SET expires_at='2020-01-01T00:00:00Z' WHERE subject_id=? AND kind='user'",
    )
    .run(another.user.id);
  assert.equal((await f.call("/me", { token: another.token })).status, 401);
  const cookie = await f.admin();
  await f.call("/admin/logout", { method: "POST", cookie });
  assert.equal((await f.call("/admin/me", { cookie })).status, 401);
});
test("生产配置禁止调试登录，关闭调试后接口不存在", async (t) => {
  const db = openDatabase(":memory:");
  try {
    assert.throws(
      () => createApp(db, { devAuth: true, production: true }),
      /生产环境/,
    );
  } finally {
    db.close();
  }
  const f = await fixture(t, { devAuth: false, production: true });
  assert.equal(
    (
      await f.call("/auth/dev", {
        method: "POST",
        body: { account: "visitor" },
      })
    ).status,
    404,
  );
  const login = await f.call("/admin/login", {
    method: "POST",
    body: { username: "manager", password },
  });
  assert.match(login.headers.get("set-cookie") || "", /Secure/);
});
test("重复失败登录被限流，错误响应不暴露内部密码或数据库信息", async (t) => {
  const f = await fixture(t);
  let last;
  for (let n = 0; n < 21; n++)
    last = await f.call("/admin/login", {
      method: "POST",
      body: { username: "manager", password: "wrong-password" },
    });
  assert.equal(last!.status, 429);
  assert.ok(last!.data.requestId);
  assert.ok(!JSON.stringify(last!.data).includes("password_hash"));
});
test("数据关闭重开后保留，正常查询排除逻辑删除用户", async () => {
  const dir = mkdtempSync(join(tmpdir(), "jianyanji-test-"));
  const path = join(dir, "test.sqlite");
  try {
    const first = openDatabase(path);
    first
      .prepare("INSERT INTO users(openid,nickname,identity) VALUES(?,?,?)")
      .run("preserved", "原主理人", "B");
    first.close();
    const second = openDatabase(path);
    const row = second
      .prepare("SELECT * FROM users WHERE openid=? AND is_deleted=0")
      .get("preserved") as unknown as UserRow;
    assert.equal(row.identity, "B");
    second
      .prepare("UPDATE users SET is_deleted=1,update_user_id=1 WHERE id=?")
      .run(row.id);
    assert.equal(
      second
        .prepare("SELECT * FROM users WHERE openid=? AND is_deleted=0")
        .get("preserved"),
      undefined,
    );
    second.prepare("INSERT INTO users(openid) VALUES(?)").run("preserved");
    assert.equal(
      (
        second
          .prepare("SELECT count(*) AS n FROM users WHERE openid=?")
          .get("preserved") as { n: number }
      ).n,
      2,
    );
    second.close();
  } finally {
    rmSync(dir, { recursive: true });
  }
});
