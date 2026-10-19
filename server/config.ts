export function configFromEnv() {
  const production = process.env.NODE_ENV === "production";
  if (production && process.env.DEV_AUTH === "true")
    throw new Error("生产环境禁止开启调试登录");
  return {
    production,
    devAuth: !production && process.env.DEV_AUTH === "true",
    dbPath: process.env.DB_PATH || "./data/jianyanji.sqlite",
    appId: process.env.WECHAT_APP_ID || "",
    appSecret: process.env.WECHAT_APP_SECRET || "",
  };
}
