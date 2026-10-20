# V2 第 19 项：普通商品折扣、福利品及原订货收益证据

日期：2026-10-20。此记录仅对应第 19 项与主线程追加的两个付款／释放边界；任务状态由主线程更新。

## 实现

- Java `CatalogService.priceForIdentity` 是实际运行的服务端统一价格依据。普通 SKU 按零售价计算 C 70%、B 35%，正整数分四舍五入；D 使用零售价。旧手填 store/principal 字段不参与新单定价，后台请求也不接受手填两档价。
- SKU `productType=regular/welfare`；福利品只使用 `welfareCents`，各身份一致。必要价格为空、类型或金额无效时不能购买。
- 商品页、购物车、结算及两种支付使用服务端价格；后台独立 `SkuPricingFields.vue` 沿用现有样式，自动预览身份价。
- V4 只扩展既有表。SKU 增加类型／福利价；订单行增加类型、零售价、价格规则版本快照。没有新建业务表，没有重写历史订单／奖励或物理删除。
- 新订单保存实际成交单价、原主理人单价、商品类型、零售价／福利价和 `v2-fixed-role`。历史行类型和规则为空时沿用原订单价差，不读取当前 SKU。
- 订货收益按原两档已取整单价的差额乘数量；福利品、运费不贡献收益。福利品保留原退款对照行，冲回增量为 0；普通商品按原行和退款数量冲回，重复退款不重复冲回，已付款奖励仍按原收益人形成待收回。
- 已付款后改变价格、商品类型、身份或归属，不改变原收益和退款依据。

## 主线程追加的边界

- 已新增精确签名：`public void OrderService.assertRelationsCurrent(ObjectNode order)`。普通未付款／明确失败订单在付款前重新读取有效购买身份和 C 的 `principal_id`，与快照不同时报 `ORDER_RELATIONS_CHANGED`、提示重新确认；已付款订单跳过检查，历史快照不改。
- `WalletService.payWithPoints` 已调用该方法，产品包提示更新为“产品包支持微信或公司转款，不支持积分”。**微信 `PaymentService.start` 的调用由主线程接入**；本记录中的 40 项结果尚不代表该调用已经接入。
- `OrderService.release` 成功关闭未付款产品包订单后，同事务将该订单仍为 `submitted` 的申请改为 `rejected`，原因为“原待付款订单已关闭，请重新申请”。`unknown` 等未决状态仍禁止释放。

## 已执行验证

所有 Java 工具显式使用并验证 `/usr/libexec/java_home -v 1.8`：Corretto `1.8.0_452`。测试数据库为本机 MySQL `8.0.45`，只创建本次专用的 `java_rewards_test_v2_price19*` 库。

1. 独立价格切片副本：16 个单元测试通过；另 21 个 MySQL／HTTP 合同测试通过。
2. 最终当前共享源码副本 `/tmp/jianyanji-19-current-integration`：**40 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS**，包含 V1～V7 迁移后的真实 MySQL。副本及独立 target 避免与主线程编译竞争；未运行共享 backend/target。
3. Vue：`node_modules/.bin/vue-tsc --noEmit -p tsconfig.web.json` 通过；未执行前端完整构建。
4. 小程序：`node scripts/check-miniprogram.cjs` 通过源码编译和页面完整性检查，未注册页面和缺失文件均为空。
5. 本切片 `git diff --check` 通过。

测试类：CatalogServiceTest、CatalogControllerTest、OrderServiceTest、OrderInputTest、OrdersMysqlTest、RewardsFundsContractTest。

关键实测：

| 场景 | 结果 |
| --- | --- |
| 零售价 100.01 元 | C 70.01 元；B 35.00 元；单位价差 35.01 元 |
| 普通 3 件＋12.34 元福利品 2 件＋运费 10 元 | 微信／积分均实付 244.71 元，收益仅 105.03 元 |
| 混单先退福利品 | 收益冲回 0 元 |
| 再退普通 1 件、随后退剩余 2 件 | 先冲回 35.01 元，最终累计 105.03 元，重复执行不增加 |
| 改现价、改类型、买方改身份及归属 | 原收益对象、价格和退款冲回仍保留 |
| 旧手填成交价 80 元、原主理人价 60 元、3 件 | 即使现商品改为福利品，原收益仍为 60 元 |
| 手填身份价／前端传入成交价 | 请求被拒绝；无商品权限账号 403，普通用户不能管理商品 |
| 未付款改主理人／改身份后积分支付 | 提示重新确认，未生成支付记录，原快照不变 |
| 未付款产品包释放／支付未知释放 | 前者同步关闭申请；后者不释放、不关闭申请 |

日志：`/tmp/jianyanji-19-unit.log`、`/tmp/jianyanji-19-contract.log`、`/tmp/jianyanji-19-integrated.log`、`/tmp/jianyanji-19-miniprogram.log`。

最终 JDK8 命令（在独立副本执行；使用本机无密码专用测试库）：

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
export PATH="$JAVA_HOME/bin:$PATH"
java -version
JAVA_REWARDS_TEST_ISOLATED_DB=1 \
REWARDS_TEST_JDBC_URL='jdbc:mysql://127.0.0.1:3308/java_rewards_test_v2_price19_integrated?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC' \
REWARDS_TEST_MYSQL_USER=root REWARDS_TEST_MYSQL_PASSWORD='' \
ORDERS_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3308/java_rewards_test_v2_price19_integrated?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC' \
ORDERS_TEST_MYSQL_USER=root \
mvn -f /tmp/jianyanji-19-current-integration/pom.xml \
-Dtest=CatalogServiceTest,CatalogControllerTest,OrderServiceTest,OrderInputTest,RewardsFundsContractTest,OrdersMysqlTest \
-Dmaven.test.skip=false -DskipTests=false test
```

## 验证边界与交接

- 微信渠道使用明确的测试替身；真实 AppID、商户登录／支付／通知／退款及真机未测，不代表外部验收完成。
- 未修改 membership、aftersales 或其测试。售后负责人需同步旧 `PaymentsAftersalesMysqlTest` 的手填价夹具及断言：原 SKU 零售价 12000、手填 C=10000/B=8000，在 V2 实际按 C=8400/B=4200；原奖励及退款数值预期需据此调整。本次没有宣称全量后端套件已通过。
- 第 26／27／33 项未在此记录中实施。26 以入驻来源快照 `inviterIdentity` 拆分 C 邀请双方各 500 元／B 邀请 1000 元，不用兼容 `rewardCents` 重复计奖；依赖主线程 23 的激活来源。后续迁移预留 V8／V9。
- 保留共享工作区其他改动，未提交、未推送。

## 本切片文件路径

```text
backend/src/main/resources/db/migration/V4__fixed_role_prices_and_welfare.sql
backend/src/main/java/com/jianyanji/catalog/CatalogService.java
backend/src/main/java/com/jianyanji/orders/OrderService.java
backend/src/main/java/com/jianyanji/rewards/RewardService.java
backend/src/main/java/com/jianyanji/wallet/WalletService.java
backend/src/test/java/com/jianyanji/catalog/CatalogServiceTest.java
backend/src/test/java/com/jianyanji/orders/OrderServiceTest.java
backend/src/test/java/com/jianyanji/orders/OrdersMysqlTest.java
backend/src/test/java/com/jianyanji/rewards/RewardsFundsContractTest.java
web/components/SkuPricingFields.vue
web/components/BusinessSettings.vue
web/components/Storefront.vue
web/components/CommerceView.vue
miniprogram/utils/catalog.js
miniprogram/pages/mall/index.wxml
miniprogram/pages/product/index.wxml
miniprogram/pages/cart/index.wxml
miniprogram/pages/checkout/index.wxml
docs/development/v2-pricing-rewards-evidence.md
```
