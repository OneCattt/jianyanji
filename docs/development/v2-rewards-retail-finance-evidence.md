# V2 第26、27、33项实施与交接证据

日期：2026-10-20。本记录补充第19项记录，任务状态与总集成由主线程维护。未提交、未推送；保留共享工作区其他改动，未编辑 membership、aftersales 文件。没有派生 agent。

## 26：按收益人登记入驻奖励

- 来源读取已付款订单的真实关系快照：目标C且 `inviterIdentity=C` 时，直接邀请C、原归属B各50000分；`inviterIdentity=B` 时仅直接B为100000分。目标B奖励仍关闭。
- 新来源不以兼容字段 `rewardCents` 计算奖励。旧无邀请身份快照的历史来源继续使用原有历史核算口径，不回填或重算既有奖励。
- 返回 `candidates`；人工选择收益人、核对金额并登记。同一来源、类型、收益人仅允许一笔有效奖励。登记不自动产生付款、不转积分。
- V8先新增按订单／类型／收益人的有效唯一索引，再移除旧的订单／类型限制；原审计及逻辑删除语义保留。
- 两笔奖励独立经过收货、售后期限、未决售后等结算校验，各自形成退款冲回、已付欠款及同人抵扣／实际追回。普通商品货款累计退半时，两笔500各冲回250；全退补齐原额。
- 首批免邀请申请特例：仅消费主线程内部写入的 `snapshot.initialVerified=true`，返回空 `candidates`，不因 `inviterId=null` 阻断来源查询，不发奖励。标记创建及真实产品包付款激活由主线程资格／入驻服务负责；本模块没有开放写标记入口。
- 第23项仍是实际业务前置：付款与资格快照必须由真实入驻链形成。本切片的已付来源夹具只检验奖励合同，不独立证明真实银行、微信入驻链路。

## 27：逐单销售归属与零售佣金

- 新 `SalesAttributionService` 根据服务端分享码核实有效B/C，只返回必要候选资料。入驻邀请关系不会自动成为零售销售归属。
- D报价时须确认本单销售代理；建单时重核候选、身份及比例，随后保留订单快照。代理身份价订货不能附加本单销售代理。没有代理的D仍可正常下单。
- 小程序商品页代理生成销售分享，顾客结算页核验、确认或移除；有效候选未经确认不报价。失效分享码保留已加载地址并允许显式移除后继续无代理购买，不能静默换人。下单成功清除候选，下次结算重新确认。
- Web结算提供独立 `SalesAttributionFields.vue`；新结算清空上次销售归属确认，不形成顾客永久绑定。
- V9仅扩展 `rewards.kind` 的允许值，新增 `retail`；C30%、B65%，基数为原普通商品货款，排除运费与福利品，不另叠加店主订货收益。
- 原订单行数量和实际单价核对退款范围，累计普通商品退款基数按原比例取整，全退补齐；原代理后来升级、改归属及商品后来改价／改类型，原收益人及原比例不重算。

## 33：财务授权导出

新增 `GET /api/admin/exports/finance/{kind}`，独立Controller与后台组件；AdminView只增加import和组件入口。

| 类型 | 原查询权限 | 筛选及核对内容 |
| --- | --- | --- |
| receipts / points / cash-refunds | finance:manage | 公司实收、退回／占用、积分变动及未用积分退现金；points必须指定userId |
| payments / refunds | orders:manage | 原订单支付及退款货款／运费／总额；payments必须指定orderId |
| rewards / settlements / reversals / offsets / recoveries | rewards:manage | 各收益人原奖励、冲回、结算额、应付／抵扣／实转、已付欠款和实际追回 |

- 保留原功能权限，支持userId筛选；payments／refunds经原订单所属人筛选，冲回／抵扣／追回经原奖励所属人筛选。orderId筛选仅适用于具有原订单字段的支付、退款、积分、奖励。
- 正常查询全部过滤逻辑删除，每次最多200行；beforeId可继续下一页，按ID倒序。不是一次全库导出。
- 字段白名单不包含proof、evidence、完整账户或付款凭证；CSV带UTF-8 BOM，处理引号、换行及公式前缀（包括前导空白）。金额字段以分为单位；积分delta_cents为百分之一积分。
- 成功导出记录 `finance.export` 审计，含类型、用户／订单／续页筛选及行数，由公共审计保存操作者和requestId。无权限先拒绝，不执行业务查询。

## 实际验证

Java工具显式选择并验证Corretto JDK `1.8.0_452`。在当前共享源码独立副本 `/tmp/jianyanji-19-current-integration` 使用独立target；测试库仅为本机 `java_rewards_test_v2_price19_integrated`，未触碰正式数据。最终测试加载V1～V10；V8/V9也已由主线程确认在其隔离库迁移成功。

| 测试类 | 通过数 |
| --- | ---: |
| CatalogControllerTest | 1 |
| CatalogServiceTest | 5 |
| OrderServiceTest | 8 |
| OrderInputTest | 4 |
| OrdersMysqlTest | 14 |
| RewardsFundsContractTest | 12 |
| FinanceExportControllerTest | 3 |
| 合计 | **47，0 failures / 0 errors / 0 skipped，BUILD SUCCESS** |

覆盖的关键金额与边界：

- 100.01元 → C70.01元、B35.00元、单位订货价差35.01元。
- 普通商品与福利品混单，微信／积分同价；原已付价格、类型、身份与归属不随现值重算。
- C邀请双方各500，重复与错人登记拒绝，半退各250，全退各500；已付款只形成原收益人欠款，原合同仍验证同人抵扣与实际追回。
- 夹具故意使用错误兼容rewardCents=999999，新邀请奖励仍按500／1000计算；B包关闭；首批免邀请来源空候选、拒绝奖励登记。
- D两单分别选择C和B；100.01元普通货款×3加福利品，佣金分别90.01元、195.02元；无代理不发，代理自购附归属拒绝。分次普通退款佣金累计30.00、60.01、90.01元，历史人及比例不改。
- 实际HTTP/MySQL执行十类财务导出，各类SQL字段真实可用，十笔成功审计；按用户筛选、逻辑删除过滤、用户令牌及无权限人员被拒绝。单测另验证全部十类各自权限、必要筛选、白名单与CSV公式／换行转义。
- Vue：`npx vue-tsc --noEmit -p tsconfig.web.json`通过。未运行前端完整构建。
- 小程序结算JS隔离执行两条路径通过：有效候选必须本单确认；无效候选可显式移除并无代理报价。
- `git diff --check`通过。

日志：

```text
/tmp/jianyanji-26-27-33.log
/tmp/jianyanji-26-27-33-vue.log
/tmp/jianyanji-26-27-33-miniprogram.log
/tmp/jianyanji-27-checkout-recovery.log
```

## 未完成或未验证清单（交主线程）

1. `node scripts/check-miniprogram.cjs`：本次结算页编译已通过；全小程序检查仍被主线程入驻文件 `miniprogram/pages/onboarding/index.wxml:68` 的表达式字符串跨行阻断，报 `unexpected #`。该文件不在本切片编辑范围，已向主线程报告。当前检测24个WXML、25个WXSS、29个JS、28个JSON，无未注册页面和缺失文件。
2. 外部真实微信登录、分享、付款、通知和退款、银行实际转款及真机未测；渠道使用测试替身，不能据此声称外部验收通过。
3. 完整后端／MySQL套件及最终前端构建由主线程统一执行，本切片只报告上述针对结果。主线程的 `MaintenanceMode` 及scheduledExpire首行改动已重读并保留；PaymentService.start已确认调用assertRelationsCurrent。
4. 产品包实际商品清单、业务真实凭证及首批initialEvidence仍由品牌方提供，未虚构资金或奖励来源。

## 本业务线完整文件清单

包含19及本次26／27／33；共享文件这里只记录本业务线增量，不主张整个diff均为本线程变更。

```text
backend/src/main/resources/db/migration/V4__fixed_role_prices_and_welfare.sql
backend/src/main/resources/db/migration/V8__reward_recipient_uniqueness.sql
backend/src/main/resources/db/migration/V9__retail_commission.sql
backend/src/main/java/com/jianyanji/catalog/CatalogController.java
backend/src/main/java/com/jianyanji/catalog/CatalogService.java
backend/src/main/java/com/jianyanji/catalog/SalesAttributionService.java
backend/src/main/java/com/jianyanji/orders/OrderInput.java
backend/src/main/java/com/jianyanji/orders/OrderService.java
backend/src/main/java/com/jianyanji/rewards/RewardService.java
backend/src/main/java/com/jianyanji/wallet/WalletService.java
backend/src/main/java/com/jianyanji/exports/FinanceExportController.java
backend/src/test/java/com/jianyanji/catalog/CatalogServiceTest.java
backend/src/test/java/com/jianyanji/orders/OrderServiceTest.java
backend/src/test/java/com/jianyanji/orders/OrdersMysqlTest.java
backend/src/test/java/com/jianyanji/rewards/RewardsFundsContractTest.java
backend/src/test/java/com/jianyanji/exports/FinanceExportControllerTest.java
web/components/SkuPricingFields.vue
web/components/SalesAttributionFields.vue
web/components/FinanceExports.vue
web/components/BusinessSettings.vue
web/components/Storefront.vue
web/components/CommerceView.vue
web/components/RewardOperations.vue
web/components/AdminView.vue
miniprogram/utils/catalog.js
miniprogram/pages/mall/index.wxml
miniprogram/pages/product/index.js
miniprogram/pages/product/index.wxml
miniprogram/pages/cart/index.wxml
miniprogram/pages/checkout/index.js
miniprogram/pages/checkout/index.wxml
miniprogram/pages/rewards/index.js
miniprogram/pages/rewards/detail.js
docs/development/v2-pricing-rewards-evidence.md
docs/development/v2-rewards-retail-finance-evidence.md
```

## 主线程最终集成补充

2026-10-20：原入驻WXML字符串跨行问题已修复；22页全小程序检查通过。当前源码全量Java8／隔离MySQL回归187项、0跳过；最终Web类型检查及构建通过，实际后台13类导出均200。以 [V2落地与验收](V2落地与验收.md) 为联合结果，外部条件仍待验收。
