import { DatabaseSync } from "node:sqlite";
import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";

const source = resolve(process.argv[2] || "data/jianyanji.sqlite");
const target = resolve(process.argv[3] || "artifacts/sqlite-export.json");
const db = new DatabaseSync(source, { readOnly: true });
try {
  // 单个只读事务固定快照；导入前应先停旧 API，避免快照后仍写入旧库。
  db.exec("BEGIN");
  const names = db
    .prepare(
      "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY rowid",
    )
    .all();
  const tables = {};
  for (const { name } of names) {
    if (!/^[a-z_]+$/.test(name)) throw new Error("不支持的旧表名：" + name);
    tables[name] = db.prepare('SELECT * FROM "' + name + '" ORDER BY id').all();
  }
  db.exec("COMMIT");
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, JSON.stringify({ version: 1, tables }), {
    mode: 0o600,
    flag: "wx",
  });
  console.log(
    "只读导出完成：" + target + "；包含账号摘要和业务数据，请妥善保管。",
  );
} finally {
  db.close();
}
