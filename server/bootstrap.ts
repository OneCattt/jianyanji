import {
  openDatabase,
  createStaff,
  permissions,
  recordAudit,
  transaction,
} from "./db.ts";
import { configFromEnv } from "./config.ts";
import { z } from "zod";

const config = configFromEnv();
const username = z
  .string()
  .regex(/^[a-zA-Z][a-zA-Z0-9_-]{2,31}$/)
  .parse(process.env.ADMIN_USERNAME);
const password = z.string().min(12).max(128).parse(process.env.ADMIN_PASSWORD);
const db = openDatabase(config.dbPath);
try {
  transaction(db, () => {
    if (
      db
        .prepare(
          "SELECT id FROM staff WHERE is_deleted=0 AND enabled=1 AND EXISTS(SELECT 1 FROM json_each(staff.permissions) WHERE value='staff:manage')",
        )
        .get()
    )
      throw new Error("已有启用的账号管理员，请通过后台管理账号");
    const id = createStaff(db, username, "品牌管理员", password, permissions);
    recordAudit(db, {
      actorKind: "system",
      actorId: 0,
      action: "staff.bootstrap",
      objectType: "staff",
      objectId: id,
      result: "success",
      reason: "首次初始化品牌管理员",
      requestId: "bootstrap-admin",
    });
  });
  console.info("品牌管理员初始化完成，请使用配置的账号登录");
} finally {
  db.close();
}
