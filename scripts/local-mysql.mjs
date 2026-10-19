import { existsSync } from "node:fs";
import { spawn } from "node:child_process";
import { resolve } from "node:path";

if (existsSync(".env")) process.loadEnvFile(".env");
// 仅用于本项目显式配置的本地实例，不初始化或覆盖已有数据库。
const data = process.env.MYSQL_LOCAL_DATA;
const executable = process.env.MYSQLD_PATH;
if (!data || !executable || !existsSync(resolve(data, "auto.cnf"))) {
  throw new Error(
    "请在 .env 配置 MYSQLD_PATH 和已初始化的 MYSQL_LOCAL_DATA；外部 MySQL 服务无需运行此命令",
  );
}
const child = spawn(
  executable,
  [
    "--no-defaults",
    "--datadir=" + resolve(data),
    "--port=" + (process.env.MYSQL_LOCAL_PORT || "3308"),
    "--bind-address=127.0.0.1",
    "--mysqlx=0",
    "--socket=/tmp/jianyanji-java-mysql.sock",
  ],
  { stdio: "inherit" },
);
for (const signal of ["SIGINT", "SIGTERM"])
  process.on(signal, () => child.kill(signal));
child.on("error", (error) => {
  console.error("本地 MySQL 启动失败：" + error.message);
  process.exitCode = 1;
});
child.on("exit", (code) => {
  process.exitCode = code || 0;
});
