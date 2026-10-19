import { openDatabase } from "./db.ts";
import { createApp } from "./app.ts";
import { configFromEnv } from "./config.ts";

const config = configFromEnv();
const db = openDatabase(config.dbPath);
const server = createApp(db, config).listen(
  Number(process.env.PORT || 3100),
  process.env.HOST || "127.0.0.1",
  () => {
    console.info("清欣简妍肌服务已启动，端口：" + (process.env.PORT || 3100));
  },
);
function shutdown() {
  server.close(() => {
    db.close();
    process.exit(0);
  });
}
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
