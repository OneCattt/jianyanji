import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve, dirname, join } from "node:path";
import { spawn, spawnSync } from "node:child_process";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
if (existsSync(join(root, ".env"))) process.loadEnvFile(join(root, ".env"));
let javaHome = process.env.JAVA8_HOME || process.env.JAVA_HOME;
if (!javaHome && process.platform === "darwin") {
  const detected = spawnSync("/usr/libexec/java_home", ["-v", "1.8"], {
    encoding: "utf8",
  });
  if (detected.status === 0) javaHome = detected.stdout.trim();
}
const java = javaHome ? join(javaHome, "bin", "java") : "java";
const version = spawnSync(java, ["-version"], { encoding: "utf8" });
if (
  version.status !== 0 ||
  !/version "1\.8\./.test(version.stderr + version.stdout)
) {
  console.error("请安装 JDK 8，并通过 JAVA8_HOME 或 JAVA_HOME 指定目录。");
  process.exit(1);
}
const mode = process.argv[2] || "dev";
const env = { ...process.env, ...(javaHome ? { JAVA_HOME: javaHome } : {}) };
if (mode === "bootstrap") env.BOOTSTRAP_ADMIN = "true";
if (mode === "seed") env.SEED_DEMO = "true";
if (mode === "import" && !env.LEGACY_IMPORT_FILE)
  throw new Error("请通过 LEGACY_IMPORT_FILE 指定旧数据导出文件绝对路径");
if (mode === "integration") {
  const url = env.MYSQL_TEST_URL || "";
  if (
    !/^jdbc:mysql:\/\/(?:127\.0\.0\.1|localhost):[0-9]+\/java_rewards_test_[A-Za-z0-9_]+(?:\?.*)?$/.test(
      url,
    )
  )
    throw new Error(
      "MYSQL_TEST_URL 必须指向本机 java_rewards_test_ 开头的专用空测试库",
    );
  const user = env.MYSQL_TEST_USER || "root";
  const password = env.MYSQL_TEST_PASSWORD || "";
  for (const prefix of ["WALLET", "ORDERS", "PAYMENTS"]) {
    env[prefix + "_TEST_MYSQL_URL"] = url;
    env[prefix + "_TEST_MYSQL_USER"] = user;
    env[prefix + "_TEST_MYSQL_PASSWORD"] = password;
  }
  Object.assign(env, {
    JAVA_REWARDS_TEST_ISOLATED_DB: "1",
    REWARDS_TEST_JDBC_URL: url,
    REWARDS_TEST_MYSQL_USER: user,
    REWARDS_TEST_MYSQL_PASSWORD: password,
  });
}
const settings = process.env.MAVEN_SETTINGS
  ? ["-s", resolve(process.env.MAVEN_SETTINGS)]
  : [];
const mvn = process.platform === "win32" ? "mvn.cmd" : "mvn";
const modes = {
  dev: [mvn, [...settings, "spring-boot:run"]],
  build: [
    mvn,
    [...settings, "-Dmaven.test.skip=false", "-DskipTests=false", "verify"],
  ],
  test: [
    mvn,
    [...settings, "-Dmaven.test.skip=false", "-DskipTests=false", "test"],
  ],
  bootstrap: [mvn, [...settings, "spring-boot:run"]],
  seed: [mvn, [...settings, "spring-boot:run"]],
  import: [mvn, [...settings, "spring-boot:run"]],
  integration: [
    mvn,
    [...settings, "-Dmaven.test.skip=false", "-DskipTests=false", "test"],
  ],
  start: [java, ["-jar", "target/jianyanji-api-0.1.0.jar"]],
};
if (!modes[mode]) throw new Error("不支持的 Java 执行模式：" + mode);
const [command, args] = modes[mode];
if (mode === "integration") {
  const migrateEnv = {
    ...env,
    MYSQL_URL: env.MYSQL_TEST_URL,
    MYSQL_USER: env.MYSQL_TEST_USER || "root",
    MYSQL_PASSWORD: env.MYSQL_TEST_PASSWORD || "",
    MIGRATE_ONLY: "true",
    SEED_DEMO: "false",
    BOOTSTRAP_ADMIN: "false",
    LEGACY_IMPORT_FILE: "",
    DEV_AUTH: "false",
  };
  const migrated = await new Promise((accept, reject) => {
    const migration = spawn(mvn, [...settings, "spring-boot:run"], {
      cwd: join(root, "backend"),
      env: migrateEnv,
      stdio: "inherit",
    });
    migration.on("error", reject);
    migration.on("exit", (code) => accept(code));
  });
  if (migrated !== 0) process.exit(migrated || 1);
}
const child = spawn(command, args, {
  cwd: join(root, "backend"),
  env,
  stdio: "inherit",
});
for (const signal of ["SIGINT", "SIGTERM"])
  process.on(signal, () => child.kill(signal));
child.on("error", (error) => {
  console.error("Java 启动失败：" + error.message);
  process.exitCode = 1;
});
child.on("exit", (code) => {
  process.exitCode = code ?? 1;
});
