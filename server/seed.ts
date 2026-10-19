import {
  openDatabase,
  createStaff,
  permissions,
  recordAudit,
  transaction,
} from "./db.ts";
import { configFromEnv } from "./config.ts";

const config = configFromEnv();
if (!config.devAuth || config.production)
  throw new Error("仅允许在显式开启 DEV_AUTH 的本地环境创建演示数据");
const db = openDatabase(config.dbPath);
try {
  transaction(db, () => {
    for (const [account, name, identity] of [
      ["visitor", "新朋友", "D"],
      ["store", "林间调肤店", "C"],
      ["principal", "清欣调肤主理人", "B"],
    ]) {
      if (
        !db
          .prepare("SELECT id FROM users WHERE openid=? AND is_deleted=0")
          .get(`dev:${account}`)
      ) {
        db.prepare(
          "INSERT INTO users(openid,nickname,identity) VALUES(?,?,?)",
        ).run(`dev:${account}`, name, identity);
      }
    }
    for (const [username, name, grants] of [
      ["admin", "品牌管理员", permissions],
      ["operator", "客服小清", ["users:read"]],
    ] as const) {
      if (
        !db
          .prepare("SELECT id FROM staff WHERE username=? AND is_deleted=0")
          .get(username)
      ) {
        const id = createStaff(
          db,
          username,
          name,
          "Jianyanji-Local-2026!",
          grants,
        );
        recordAudit(db, {
          actorKind: "system",
          actorId: 0,
          action: "staff.seed",
          objectType: "staff",
          objectId: id,
          result: "success",
          reason: "显式创建本地演示账号",
          requestId: "local-seed",
        });
      }
    }
    const admin = db
      .prepare("SELECT id FROM staff WHERE username='admin' AND is_deleted=0")
      .get();
    if (admin) {
      db.prepare(
        "UPDATE staff SET permissions=?,update_time=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",
      ).run(JSON.stringify(permissions), admin.id);
      recordAudit(db, {
        actorKind: "system",
        actorId: 0,
        action: "staff.seed",
        objectType: "staff",
        objectId: Number(admin.id),
        result: "success",
        reason: "显式更新本地演示管理员授权",
        requestId: "local-seed",
      });
    }
  });
  console.info(
    "本地演示数据已就绪。后台账号 admin / operator；密码仅用于本地：Jianyanji-Local-2026!",
  );
} finally {
  db.close();
}
