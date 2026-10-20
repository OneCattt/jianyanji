# 清欣简妍肌

首期 01–18 的代码已接入：微信身份、品牌内容、商品三档价格、购物袋与订单、微信/积分付款、发货收货、产品包入驻、公司核款积分、售后退款、奖励与月结抵扣。包含原生微信小程序、Vue 品牌方后台及浏览器购买体验入口。真实微信交易和真机显示仍待外部环境验收。

## 本地启动

使用 **JDK 8、Maven 3.6+、MySQL 8**。Node.js 22.13+ 仅用于前端构建和开发命令；生产由 Java JAR 提供 API 和静态页面。

```bash
# 必须先显式选择 Java 8，macOS 示例：
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
java -version
npm ci
cp .env.example .env
# 先修改 deploy/mysql.sql 的密码并用 MySQL 管理员执行；
# 再将 .env 中 MYSQL_URL、MYSQL_USER、MYSQL_PASSWORD 改为实际连接。
npm run seed:demo
npm run dev
```

- 购买体验：<http://127.0.0.1:5173/>；管理后台：<http://127.0.0.1:5173/admin>；API：<http://127.0.0.1:3100/api>。
- 本地后台演示账号：`admin`（全部权限）、`operator`（只查看用户），密码均为 `Jianyanji-Local-2026!`。只有显式运行 `seed:demo` 才创建，已有账号、密码和权限不会被覆盖。
- `seed:demo` 创建游客、店主和主理人体验账号，不填入商品价格、公司收款或资金数据。开发登录一个不存在的账号只会创建游客。
- 若使用本项目已配置的独立本地 MySQL 实例，重启电脑后先在一个终端运行 `npm run dev:db`，再在另一终端运行 `npm run dev`；外部数据库服务无需此命令。
- Flyway 自动建立、升级 MySQL 表。数据库必须预先创建；`.env`、数据库、截图、构建产物不进入 Git。不要把测试库作为正式业务库。
- 如机器配置了企业 Maven 镜像，可通过 `MAVEN_SETTINGS` 指定个人 settings.xml；构建脚本不会强制项目镜像。`JAVA8_HOME` 可显式指定 JDK 8。

## 微信原生小程序

1. 微信开发者工具导入 `miniprogram` 目录，正式联调将 `project.config.json` 中的 AppID 改为实际小程序 AppID。
2. 服务端 `.env` 填写 `WECHAT_APP_ID` 和 `WECHAT_APP_SECRET`，密钥只放服务端。小程序通过 `wx.login` 获取临时 code，再由服务端调用微信换取 openid。
3. 真机联调将 `miniprogram/config.js` 的 `apiBase` 改为实际 HTTPS API 地址，并在小程序平台设置 request 合法域名。默认本机地址供开发工具使用。
4. 仅本地模拟体验时，可把小程序配置中的 `devAuth` 显式改为 `true`，同时保持服务端本地 `DEV_AUTH=true`；开发工具访问本机 HTTP 时按工具要求关闭本地合法域名校验。正式环境保持 `devAuth=false`。
5. 首页、商城、商品和经营说明可匿名访问；“我的”登录后可进入订单、入驻、钱包、奖励、售后和地址。个人资料与退出在设置中；联系手机号为自行填写的资料，不等同微信认证手机号。

后台先维护真实品牌/客服/收款说明、商品分类规格与 B/C/D 价格、库存、配送及售后政策。产品包和奖励金额在对应页面配置。缺失必要配置会明确阻止业务，不使用原型示例金额。产品包须向公司转款、核实到账、交付实物和独立审批；不会额外发等额积分。

## 首次正式管理员

填写 `.env` 的 `ADMIN_USERNAME`、`ADMIN_PASSWORD`（至少 12 位），执行：

```bash
npm run bootstrap:admin
```

仅在没有启用的账号管理员时初始化，不覆盖现有管理员。之后由后台新增工作人员、分配权限、启停或移除账号。密码用随机盐 scrypt 摘要保存；后台使用 HttpOnly、SameSite=Strict 的 cookie 会话，生产环境增加 Secure。移除仅逻辑删除并保留历史，重新创建同名账号获得新的编号。

正式部署见 [服务器部署说明](docs/development/java-deployment.md)，包含 MySQL、JAR、systemd、Nginx 和旧数据迁移。生产设置 `APP_PRODUCTION=true`、`NODE_ENV=production`、`DEV_AUTH=false`，通过 HTTPS 同源访问。

上线前一并落实 [基础防刷与费用告警](docs/development/basic-traffic-protection.md)：Nginx 限流、来源识别、封禁及临时暂停入口、OSS 权限和云端告警。云端设置需在实际账号启用，预算告警不代表费用封顶。

```bash
npm run build       # Vue 静态页面
npm run build:api   # Java 测试并打包
npm start           # 本地用 JDK 8 运行已打包 JAR
```

生产可直接使用 `java -jar`，无需安装 Node、tsx 或 Vite。前端构建目录、原型素材和 JAR 一起部署，路径见部署说明。

## 技术与权限约定

- Java 8 + Spring Boot 2.7.18 + JdbcTemplate 服务端；Vue 3 + Vite 提供后台和浏览器购买端；微信端使用原生 JS/WXML/WXSS。
- MySQL 8 + Flyway 管理数据库结构，审计字段采用毫秒时间和逻辑删除。`server/` 下旧 TypeScript 实现仅保留为迁移参考，`npm run test:legacy` 仅验证旧实现，不作为 Java 验收结果。
- 业务表均包含创建修改用户、创建修改时间和逻辑删除标记；正常查询过滤已删除记录，生效中的登录名和微信身份使用条件生成列唯一索引，允许逻辑删除后重新创建。
- 购买身份仅为有效的 B 主理人、C 店主、D 游客；品牌方是后台功能授权体系。登录、修改个人资料、后台授权均不会自动授予购买优惠。
- 购买端只访问本人资料和业务；后台除 `staff:manage`、`users:read`、`audit:read` 外，增加 `content:manage`、`catalog:manage`、`orders:manage`、`onboarding:manage`、`finance:manage`、`rewards:manage`。前端隐藏无权限入口，服务端独立校验。
- 身份、停用状态和权限在每次请求时读取，避免已有令牌携带过时身份。停用或移除工作人员同时撤销旧会话，重新启用必须重新登录。
- 操作记录保留对象编号、操作者类型及编号、动作、结果、原因和请求编号。资料越权、功能越权、登录失败同样有记录；日志不保存密码、微信密钥或临时 code。
- 金额与积分均使用整数百分之一；订单保存原身份、价格、归属、地址、配送和售后依据。公司收款及钱包逐批次追溯；支付、退款、发积分和结算保留幂等编号及状态。实际未决交易不能作为成功或释放其占用。

接口和权限详见 [身份接口说明](docs/development/identity-api.md)，任务和证据详见 [01 实施记录](docs/development/01-implementation.md)、[02–18 实施记录](docs/development/phase1-implementation.md)和[工单索引](.scratch/jianyanji-phase1/README.md)。

## 验证

```bash
npm run typecheck
npm test            # Java 单元测试；MySQL 资金合同测试须单独启用
npm run build:api   # Java verify + 可执行 JAR
npm run build
# macOS 安装微信开发者工具后，可额外检查全部原生模板、样式及脚本
npm run check:miniprogram
```

Java 单元测试验证严格输入、认证摘要、报价、订单、钱包、支付与售后约束。真实 MySQL 集成回归必须使用独立测试库，运行命令和实测边界见部署说明；只运行 `npm test` 时这些环境门控用例会跳过。HTTP 验收脚本为 `scripts/java-api-contract.mjs`，只允许显式指定隔离服务。本轮结果见 [Java 迁移验证记录](docs/development/java-migration-verification.md)。旧 TypeScript 测试结果不代表 Java 服务验证通过。

微信支付 V3 参数见 `.env.example`；私钥、平台公钥 PEM 文件和 API V3 密钥只存服务端。未配置商户时明确提示不可支付，不模拟成功。每 30 秒定时查询未决付款，退款由后台按原交易提交或核查。

真实微信登录、商户支付/退款、微信开发工具与真机安全区需使用实际 AppID、商户和设备完成验收；本地算法、替代渠道及浏览器结果不等同真实微信交易验收。
