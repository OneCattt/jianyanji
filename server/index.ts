import { reconcilePayments } from "./payments.ts";
import { paymentAdapterFromEnv } from "./wechat-pay.ts";
import { expireOrders } from "./orders.ts";
import { openDatabase } from "./db.ts";
import { createApp } from "./app.ts";
import { configFromEnv } from "./config.ts";

const config = configFromEnv();
const db = openDatabase(config.dbPath);
const server = createApp(db, config).listen(
  Number(process.env.PORT || 3100),
  process.env.HOST || "127.0.0.1",
  () =>
    console.info("清欣简妍肌服务已启动，端口：" + (process.env.PORT || 3100)),
);
let reconciliation: Promise<void> | undefined;
let adapter: ReturnType<typeof paymentAdapterFromEnv>;
try {
  adapter = paymentAdapterFromEnv();
} catch {
  console.error(
    JSON.stringify({
      message: "微信商户配置无效，支付和退款暂停，待付款库存清理继续运行",
    }),
  );
}
// 单进程防止定时重入；渠道业务使用数据库租约和幂等编号。
const reconciliationTimer = setInterval(() => {
  if (reconciliation) return;
  reconciliation = (async () => {
    try {
      expireOrders(db);
      await reconcilePayments(db, adapter, 20);
    } catch (error) {
      console.error(
        JSON.stringify({
          message: "支付核对任务失败",
          requestId: "payment-reconcile",
          error:
            error instanceof Error
              ? { name: error.name, message: error.message }
              : "UnknownError",
        }),
      );
    } finally {
      reconciliation = undefined;
    }
  })();
}, 30000);
reconciliationTimer.unref();
function shutdown() {
  clearInterval(reconciliationTimer);
  server.close(async () => {
    await reconciliation;
    db.close();
    process.exit(0);
  });
}
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
