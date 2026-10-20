# Java 服务启动、部署与迁移

后端为 Java 8 / Spring Boot 2.7.18 / MySQL 8 单体。Vue 和小程序继续调用原 `/api` 接口，金额保持整数分。Node 只在开发、前端构建或旧 SQLite 导出时使用；正式服务直接运行 JAR。

## 构建与本地运行

显式设置 `JAVA_HOME` 为 JDK 8，确认 `java -version` 为 `1.8`；Maven Enforcer 也会拒绝其他 Java 版本。macOS：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
java -version
npm ci
cp .env.example .env
# 配置独立 MySQL 8 数据库和 .env；初始化 SQL 模板：deploy/mysql.sql。
npm run bootstrap:admin   # 首次正式账号，先填写 ADMIN_USERNAME/ADMIN_PASSWORD
# 本地体验可改为 npm run seed:demo，勿对正式库运行。
npm run dev
```

Flyway 自动执行 `backend/src/main/resources/db/migration`；已应用文件不可修改，后续新增版本文件。初始化命令执行成功后退出。脚本从根 `.env` 加载变量，再从 `backend` 工作目录启动 Java。

本项目工作区的独立本地数据库存于已忽略的 `data/mysql-java`，端口 3308；`.env` 中保留专用账号和本地启动参数。重启电脑后运行 `npm run dev:db`，保持终端开启，再运行 `npm run dev`。该辅助命令只启动已初始化的本地数据目录，不创建或清空数据库。采用现成 MySQL 服务时无需这些本地参数。

## Linux 服务器部署

1. 安装并确认 **JDK 8**、MySQL 8 和 Nginx。构建机还需 Maven 及 Node；服务器只接收构建产物时无需安装它们。
2. 修改并执行 `deploy/mysql.sql`，使用独立账号、独立密码和 `utf8mb4_bin` 数据库。账号只对业务库授权；业务数据仅逻辑删除。
3. 本地或 CI 构建：

```bash
export JAVA_HOME=/实际路径/jdk8
"$JAVA_HOME/bin/java" -version
npm ci
npm run typecheck
npm run build
npm run build:api
```

4. 上传以下目录结构：

```text
/opt/jianyanji/
  backend/jianyanji-api-0.1.0.jar   # 来自 backend/target/
  dist/                           # 完整前端构建目录
  miniprogram/assets/              # 原型图片素材
```

5. 把 `.env.example` 复制为服务器 `/etc/jianyanji.env`，配置真实数据库和微信参数，权限设为仅管理员及服务账号可读。必须设置：

```dotenv
NODE_ENV=production
APP_PRODUCTION=true
DEV_AUTH=false
HOST=127.0.0.1
PORT=3100
MYSQL_URL=jdbc:mysql://127.0.0.1:3306/jianyanji?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC
MYSQL_USER=jianyanji
MYSQL_PASSWORD=实际独立数据库密码
WEB_DIST=/opt/jianyanji/dist
ASSETS_DIR=/opt/jianyanji/miniprogram/assets
```

JAR 本身不自动读 `.env` 文件：systemd 的 `EnvironmentFile` 加载上述配置。直接在终端启动时须先向进程导出环境变量，或传入 Spring 参数。初始化正式管理员可在受控环境中临时设置 `BOOTSTRAP_ADMIN=true`、`ADMIN_USERNAME`、`ADMIN_PASSWORD`，运行一次 JAR，成功后取消这些初始化变量再启动常驻服务；不要保留 `SEED_DEMO` 或 `LEGACY_IMPORT_FILE`。

6. 创建系统账号 `jianyanji`，让它能读取应用、素材和微信 PEM 文件。复制 `deploy/jianyanji.service` 到 `/etc/systemd/system/`，将 `/opt/java8` 调整到已确认的 JDK 8 路径：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now jianyanji
sudo systemctl status jianyanji
sudo journalctl -u jianyanji -n 100 --no-pager
```

7. 按 [基础防刷与费用告警](basic-traffic-protection.md) 一起安装 `deploy/nginx.conf`、配套限流和封禁文件，配置真实域名和证书，执行 `nginx -t` 后重载。Nginx 保留外部 `Host` 并覆盖来源头，Java 只信任本机代理。Java 提供 `/`、`/admin`、`/assets/*`、`/api/*`。正式小程序 `apiBase` 改为 HTTPS API 地址，并在微信后台添加 request 合法域名、关联支付商户并配置 HTTPS 通知地址。

验证 `/api/public/context` 的 `devAuth=false`；浏览器打开 `/admin` 登录，检查商品保存、权限和退出。真实商户支付、退款与微信真机仍需使用实际账号验收。

## 保留旧本地数据

只迁入一个已由 Flyway 建好表、**所有业务表均为空**的目标库。导入保持账号编号、原权限、密码摘要、商品、交易和审计；包含逻辑删除记录。既有工作人员不会自动扩大权限。

```bash
# 停止旧 Node API，避免导出快照后仍写入旧库。
node scripts/export-sqlite.mjs data/jianyanji.sqlite artifacts/sqlite-export.json
# 在 .env 中切换到空 MySQL 目标库；不要 seed 或 bootstrap。
LEGACY_IMPORT_FILE=/绝对路径/artifacts/sqlite-export.json npm run import:legacy
# 成功后取消 LEGACY_IMPORT_FILE，启动新 API。
npm run dev
```

导出只读，文件权限为 0600，已有文件不会覆盖。Java 校验完整的表白名单和字段，检查目标空库；全部插入在单个事务中完成，任一记录失败则回滚。导入不会覆盖任何已有记录，也不禁用外键。原 SQLite 原样保留以供核对；导出文件含账号摘要与业务数据，请放在仓库外备份或保留于已忽略的 `artifacts/`。

## MySQL 回归测试

普通 `npm test` 不连接默认业务库；MySQL 测试必须显式提供独立库。使用本机测试实例创建空库 `java_rewards_test_integration` 后：

```bash
MYSQL_TEST_URL='jdbc:mysql://127.0.0.1:3306/java_rewards_test_integration?serverTimezone=UTC' \
MYSQL_TEST_USER=root MYSQL_TEST_PASSWORD=测试库密码 npm run test:api:mysql
```

集成命令先以非 Web 模式运行 Flyway，再执行全套测试，不占用 API 端口。所有测试使用专属记录或事务回滚，禁止清表。外部微信渠道使用替代实现，RSA 验签和 AES-GCM 另有本地算法测试；这些不等同真实微信商户验收。

HTTP 身份和后台合同检查：在独立库显式初始化演示账号并启动独立端口后，执行：

```bash
JAVA_API_TEST_BASE=http://127.0.0.1:3102 JAVA_API_TEST_ISOLATED=1 node scripts/java-api-contract.mjs
```

## 日志、备份与更新

错误日志包含 `requestId`、路径和相关订单/退款编号；接口响应返回请求编号，便于从后台报错定位服务日志。付款与退款未决状态、重试租约、核查事件和人工补偿依据持久化。渠道网络操作在数据库事务外执行；定时付款核查与人工操作共用租约，避免重复处理。

更新前备份 MySQL（例如 `mysqldump --single-transaction`），保留当前 JAR、dist、素材及配置。部署新版后检查 Flyway 成功、`/api/public/context` 和后台登录。代码回退不自动回退数据库结构，按迁移兼容性核对备份方案。正式数据库、密钥与备份均不提交 Git。
