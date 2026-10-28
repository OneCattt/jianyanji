# 清欣简妍肌

> 当前业务规则（2026-10-28）：所有登录客户先线下充值、总部核款发积分，商品、固定权益包及运费均使用积分。购买时可选择分次发货，未发部分按原购买明细寄存12个月。充值不授予身份，权益包成功扣积分后开通对应店主身份。最新规则以 [ADR 0007](docs/adr/0007-全客户积分与寄存分次履约.md) 和[积分寄存交付记录](docs/development/customer-storage-points-delivery.md)为准，替代 [V2.1](docs/requirements/清欣简妍肌首期需求规格说明V2.md) 中直接货款核款、固定折扣及退款自动关闭身份等旧规则；历史成交来源与快照不重算。

首期包含微信身份、品牌内容、独立三档售价与福利品、购物袋与积分订单、公司充值核款、寄存及跨订单提货、发货收货、权益包入驻、来源退款、奖励与月结抵扣。包含原生微信小程序和 Vue 品牌方后台，购买业务通过小程序使用。真实核款、仓储物流、微信登录分享及真机显示仍需实际环境验收。

## 寄存与配送

- 后台按商品规格开放寄存，秒杀、小样、临期商品禁寄存。客户按行填写本次发货数量，其余留仓；赠品同样保留数量与来源，货款为0。
- 活动套餐接口明细返回对应商品图片及区分主品/赠品的明细键，供套餐商品组合图展示；沿用已有商品查询，不增加逐商品请求。
- 提交购买订单先预占可售库存；成功扣积分后扣可售库存、建立客户专属寄存。后续提货只占用和扣减本人寄存，不重复扣商品货款或可售库存。
- 本次新购首发也生成配送单；纯新购付款后待发货，合并已有寄存时待总部审核。后台订单可直接进入对应配送单审核及发货。
- 客户可跨原订单合并寄存提货，一次地址、一次运费；按原成交价核算。纯新购免费；新购满1000元并带寄存免费；其余含寄存配送普通顾客/2980店主免费，19800店主本次所提寄存成交金额满1000元免费，否则10积分，赠品不凑额。
- 申请时占用寄存、扣运费；实际发货才减少寄存余量，客户按整个包裹确认收货。一张提货单仍整单发货、整包签收。
- 取消/驳回未发货提货仅释放寄存占用并退本次运费，商品退款另办。新购商品保留原购买关系，不自动转寄存，取消后未发新货从原订单继续安排配送。
- 原订单展示实际待配送、在途或寄存状态，含寄存筛选、待发货和运输中角标按完整数据库范围计算；寄存总量和寄存已提按批次展示，不混入首次配送。
- 寄存按上海付款日加12个日历月，到期当天有效；过期不清零，由后台人工提货或延期。退款不自动关闭身份，奖励按实际签收部分计算可结算额度，实际仍人工月结。

流程修复、验证及本次发布状态见 [2026-10-28寄存修复交付记录](docs/development/storage-flow-fix-20261028.md)。后端/管理端与小程序分别记录发布状态；后台上线不能代替小程序上传、发布和真机验收。

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

- 管理后台：<http://127.0.0.1:5173/admin>，根地址 <http://127.0.0.1:5173/> 同样显示后台；API：<http://127.0.0.1:3100/api>。
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
6. 提审前在后台“品牌与经营说明”的隐私保护分组填写真实运营主体及隐私联系方式，并在微信公众平台发布用户隐私保护指引。首次登录需主动同意；拒绝仍可浏览，设置中可撤回。配置与真机验收见 [小程序隐私接入说明](docs/development/miniprogram-privacy.md)。

后台先维护真实品牌/客服/充值说明、商品分类规格与 B/C/D 独立售价、库存、寄存白名单、配送及售后政策。固定权益包、活动套餐和奖励金额在对应页面配置。缺失必要配置会明确阻止业务，不使用原型示例金额。权益包先确认入驻条件、协议和邀请归属，再由客户足额扣积分购买并开通身份；货品按本次配送和寄存分别履约，不另发等额积分。

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

- Java 8 + Spring Boot 2.7.18 + JdbcTemplate 服务端；Vue 3 + Vite 提供品牌方后台；微信端使用原生 JS/WXML/WXSS。
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

首期线上支付、渠道退款与支付渠道定时核查已停用，旧调用返回明确的 `ONLINE_PAYMENT_DISABLED`；已有商户配置不启用线上支付。当前仅充值款由总部核实后发积分，普通商品、权益包及运费统一扣积分，余额不足须先充值。历史现金来源按原交易退款依据处理，现金实际退付后登记凭证；积分退款按原来源批次退回。

真实微信登录、分享、视频和真机安全区需使用实际 AppID、域名和设备验收；线下到账及退款由财务核实，本地测试不代表实际资金已收付。
