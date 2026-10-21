# catalog / orders 迁移交付

## 文件

生产代码（均仅在分配包内）：

- `catalog/CatalogController.java`：内容、分类、商品 13 个端点。
- `catalog/CatalogService.java`：严格输入、身份档价、可售与真实库存展示、规格维护、逻辑移除。
- `orders/OrderController.java`：配送配置、地址、购物车、报价与订单 15 个端点。
- `orders/OrderInput.java`：本域输入校验与规范化，不替代公共校验层。
- `orders/OrderService.java`：服务端快照、订单幂等、库存占用/释放、过期清理、履约完成状态。
- `orders/FulfillmentController.java`：履约 4 个端点。
- `orders/FulfillmentService.java`：可发货数量、分包发货、物流纠正、本人收货。

测试代码：`catalog/CatalogServiceTest.java`、`catalog/CatalogControllerTest.java`、`orders/OrderInputTest.java`、`orders/OrderServiceTest.java`、`orders/OrderControllerTest.java`、`orders/FulfillmentServiceTest.java`、`orders/OrdersMysqlTest.java`。

## 端点清单

以下路径均以 `/api` 开头，合计 32 个方法/路径组合。

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | /public/content | 公开 |
| GET、PUT | /admin/content | content:manage |
| GET | /public/categories | 公开 |
| GET、POST | /admin/categories | catalog:manage |
| PUT | /admin/categories/{id} | catalog:manage |
| GET | /products | 可选用户身份 |
| GET | /products/{id} | 可选用户身份 |
| GET、POST | /admin/products | catalog:manage |
| PUT | /admin/products/{id} | catalog:manage |
| POST | /admin/products/{id}/remove | catalog:manage |
| GET、PUT | /admin/shipping-settings | orders:manage |
| GET、POST | /addresses | 用户 |
| PUT | /addresses/{id} | 本人 |
| POST | /addresses/{id}/remove | 本人 |
| GET | /cart | 用户 |
| PUT | /cart/{id} | 本人；id 为规格编号 |
| POST | /cart/{id}/remove | 本人；id 为规格编号 |
| POST | /checkout/quote | 用户 |
| GET、POST | /orders | 用户 |
| GET | /orders/{id} | 本人 |
| GET | /admin/orders | orders:manage |
| POST | /orders/{id}/cancel | 本人 |
| GET | /admin/orders/{id}/fulfillment | orders:manage |
| POST | /admin/orders/{id}/shipments | orders:manage |
| PUT | /admin/shipments/{id} | orders:manage |
| POST | /orders/{id}/shipments/{shipmentId}/receive | 本人 |

## 一致性与边界

- 全部写操作使用 `Db.tx`，依赖公共层 `business_mutex FOR UPDATE` 与 REQUIRED 嵌套事务。没有渠道网络调用。
- 商品维护严格校验多余字段、规格重复、价格顺序与待付占用量；分类停售仍遵循原查询行为。购物车保留失效商品显示不可用。
- 已有规格只改资料时省略 `stock`，保留数据库当前库存；主动调整同时提交 `stock` 新总库存与 `expectedStock` 编辑前总库存，在业务事务内比对。原值不符或缺失返回 `409 STOCK_CHANGED` 并回滚整次商品保存，新总库存仍不得低于当前占用量。新增规格必须提交初始 `stock`。旧编辑页须刷新后使用新协议，不能无校验覆盖库存。
- 报价只由服务端构建，保存身份、主理人归属与单价、商品版本、地址、运费、售后政策；十分钟有效。下单重建后逐字比较快照，变化返回原 `QUOTE_CHANGED`。
- 同用户请求编号重放先返回原订单；换报价拒绝复用。新增订单前调用 `PaymentService.assertNoUnresolved(userId, 0)`。占库存但不扣款，支付/退款由对应模块处理。
- 库存条件更新保证不会超占；释放时事务内重读，只释放 pending 且 unpaid/failed，拒绝旧对象重复释放。库存占用不一致抛出 `STOCK_RESERVATION_MISMATCH`、回滚整笔事务并记录订单/规格/请求编号，不把不一致订单标记为已关闭。
- `scheduledExpire()` 每三十秒执行（`orders.expire-delay-ms`，默认 30000），无需微信商户配置；HTTP 查询和报价也保留原过期清理。后台执行的操作人为系统 0。
- 发货余量为 `quantity-shipped_qty-refunded_unshipped-held_unshipped`，按订单和请求编号幂等；任一明细失败回滚整个包裹。物流纠正保留前后值、原因、请求编号，不重新出库。
- requested/approved/processing/unknown/failed 售后或 refund_held 阻断收货；收货按仍有效商品数量计入。所有有效商品发出并收完、有真实收货、没有未收包裹及未决售后才 completed；全退不虚构收货完成。
- 未配置的后台内容和配送设置显式返回 `NullNode`，HTTP 回归已验证响应体为字面 JSON `null`。
- MySQL 保留字 `lines` 在本域 SQL 中加反引号，JSON 字段名保持不变。未新增数据库列。

## 验证

使用 JDK 8（Corretto 1.8.0_452）及指定 Maven settings：

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn -s backend/.mvn/settings.xml -f backend/pom.xml \
  -Dtest=CatalogServiceTest,CatalogControllerTest,OrderInputTest,OrderServiceTest,OrderControllerTest,FulfillmentServiceTest \
  -Dmaven.test.skip=false -DskipTests=false test
```

21 个定向测试通过：档价/身份过期、公开价格范围、未完整收款资料、规格重复和价格倒挂、严格数量/地址/物流校验、重复释放和支付处理中不释放、库存异常不关闭、下单幂等、快照冻结与变更、退款和售后完成边界、发货幂等/余量、售后阻断收货、退款后收货数量、物流纠正留痕、两个设置端点 JSON null。

上述 21 个定向测试使用 Mockito 数据库替身；两个设置端点使用真实 Spring MVC 序列化。真实 MySQL 验证见下一节。跨模块真实支付/退款结算、产品包身份处理和完整 HTTP 生命周期仍由主线程集成验证。公共接口均按 `PORTING-CONTRACT.md` 实现，无额外签名要求。


## 真实 MySQL 独立回归（2026-10-19）

新增 `backend/src/test/java/com/jianyanji/orders/OrdersMysqlTest.java`，仅通过 `ORDERS_TEST_MYSQL_URL` 显式启用，支持本机（127.0.0.1、localhost、[::1]）任意端口的 `java_rewards_test_[A-Za-z0-9_]+` 专用 MySQL 8.0 库，同时保留原 `127.0.0.1:3308/jianyanji_schema_check` 或 localhost 同端口隔离库。连接前检查 URL，连接后再检查实际 DATABASE()；普通业务库和远程库均不会进入样例写入。Db、CatalogService、OrderService、FulfillmentService 均为真实实现；仅 mock Access 和 PaymentService，无网络渠道调用。最多五个工作线程，真实数据库连接并发。样例使用唯一用户、请求编号；结束后逻辑清理样例行并恢复原设置，无 DDL 或物理删除。

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
export PATH="$JAVA_HOME/bin:$PATH"
export ORDERS_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3308/java_rewards_test_integration?serverTimezone=UTC'
java -version
mvn -s backend/.mvn/settings.xml -f backend/pom.xml \
  -Dtest=OrdersMysqlTest -Dmaven.test.skip=false -DskipTests=false test
```

最终结果：真实 MySQL 13 项全部通过；连同原 21 个定向回归合计 34 项通过，0 失败、0 错误、0 跳过。验证内容：

- 内容、分类、商品、地址、购物车真实 SQL；移除后重加、默认地址互斥、权限归属、分类停售。
- 报价过期不占库存；订单过期只释放 unpaid，processing 继续保留占用；重复释放不多扣。
- 配送变更触发 QUOTE_CHANGED；成交后地址、政策、身份、主理人单价仍使用原快照。
- 五线程争抢 10 件库存，每单 3 件：恰好成功 3 单、冲突 2 单，实际 reserved=9、stock=10。
- 五线程重放同一请求：均返回同一订单，仅一单、一笔 4 件库存占用；换报价拒绝复用。
- 末尾审计失败时订单头、明细、库存全部回滚；发货第二条失败时第一条出库量与包裹全部回滚。
- 未发货退款占用、分三包发货/逐包收货、重复收货幂等、五种未决售后状态阻断收货、全退未真实收货不完成。
- 物流纠正保存原物流、请求编号、原因，明细不重复出库，订单视图正常解析 MySQL 的 lines 字段。

### 实测发现并修复的履约缺陷

复现方法：`shippedRefundBeforeReceiptMustNotCountUnshippedRemainderAsReceived`；修复前 12 项中此项失败，修复后该用例及新增的退货后历史收货计数回归均通过。

可达前置状态：买 6 件，发 4 件；第一笔售后占用 2 件未发货量，第二笔售后退回已发货中的 2 件；第二笔验货退款成功、第一笔被驳回，最终仍有 2 件未发货，2 件有效已发货，且没有未决售后。测试显式准备此状态，不声称验证渠道付款/退款。

修复前确认该 4 件包裹时，实际 received_qty=4（预期有效收货 2），status=completed（预期 shipped），并提前写 received_at。根因：`FulfillmentService.receive` 只以 quantity-refunded_qty-received_qty 限制收货，包含尚未发出的数量；`OrderService.refreshFulfillment` 又未单独检查尚未发出的有效数量。Node 参考实现也使用相同算法。

迁移联调中完成以下修复：

- `FulfillmentService.receive` 以 `shipped_qty-refunded_qty+refunded_unshipped-received_qty` 限制本次可累计收货量，未发货商品不会被包裹提前计入。
- `OrderService.refreshFulfillment` 加入 `quantity-shipped_qty-refunded_unshipped>0` 的未发货检查，历史收货数量包含后退商品时也不会提前完成。
- 回归不仅验证首次只收 2 件、保持 shipped，还会继续发出剩余 2 件，确认最终收到 4 件有效商品后才 completed；另覆盖先收货再退货时完成重算的边界。
- 现有 `FulfillmentServiceTest` 补全真实应有的 shipped_qty/refunded_unshipped 前置字段，保持原有效商品收货数量断言。

没有修改通用层、Node 参考代码或已应用的 V1；无需新增列。已付款和退款完成事实由明确的数据库样例准备，本测试不声称验证真实渠道或其他模块的到账/退款逻辑。

## 字段容量复核

已额外逐项核对 catalog/orders/fulfillment 的输入最大长度与 V1 列宽。发现且仅发现 `products.image`、`products.detail_image` 两列的接口1000字符上限大于 VARCHAR(191)，真实 MySQL 在192和1000字符均复现1406。完整对照表、事务回滚探针证据与给 Bernoulli 的 V2 增补 SQL 见 `catalog/SCHEMA-CAPACITY-AUDIT.md`。本次未修改已应用的 V1 或正在协调的 V2。

专用库入口已复核：`ORDERS_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:3308/java_rewards_test_integration?serverTimezone=UTC`，JDK 8 + 指定 Maven settings 仅运行 OrdersMysqlTest，13 项全部通过、0 跳过；无需修改其他测试类。

OrdersMysqlTest 数据库账号支持 `ORDERS_TEST_MYSQL_USER` / `ORDERS_TEST_MYSQL_PASSWORD`，未设置时默认 `root` / 空密码，与 java.mjs 保持一致。

V3 图片容量回归：新增 `thousandCharacterProductImagesPersistAndReadBackOnCreateAndUpdate`，通过真实 CatalogService 将 image/detailImage 各1000字符新增落库、回读完整原文，再交换两个不同地址执行更新并回读新值。该项沿用真实数据库和逻辑清理；按要求该项已纳入主线程全套106项回归（含34项真实MySQL测试）并通过；OrdersMysqlTest 当前14项全部通过。图片扩容现由 `V3__catalog_image_capacity.sql` 承接。
