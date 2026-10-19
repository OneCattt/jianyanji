# Java 首期迁移共同约定

现有 Node `server/*.ts` 是行为基准，保留所有 `/api` 路径、HTTP 状态、字段名、权限、业务校验及幂等。只迁移实现，不删除现有功能。Vue 与小程序不改接口。

- Java 8 / Spring Boot 2.7.18 / Maven / MySQL 8，包 `com.jianyanji`。各业务包使用 `@RestController` + `@Service`，构造注入。循环依赖使用 `@Lazy` 参数，禁止开启全局循环依赖。
- 所有 Java 类、相关字段有完整中文 Javadoc，日志中文，不用 Lombok。请求/行/快照用 Jackson `ObjectNode`/`JsonNode` 保留现有混合 snake_case/camelCase JSON 合同，领域约束在服务中明确验证。
- 通用层主线程提供，不要编辑 common/identity/Application/pom/资源迁移。
- `com.jianyanji.common.J` 静态工具：`obj(Object... keyValuePairs):ObjectNode`，`arr(Object... values):ArrayNode`，`json(Object):JsonNode`，`parse(String):JsonNode`，`stringify(Object):String`，`s(JsonNode,String):String`（缺失/空为""），`l(JsonNode,String):long`，`b(JsonNode,String):boolean`（boolean或1），`has(JsonNode,String):boolean`（非null），`now():String`（ISO UTC），`id():String`（无横线UUID），`list(JsonNode):List<JsonNode>`，`effectiveIdentity(JsonNode):String`。
- `Input` 静态校验：`strict(JsonNode,String... allowed):void`，`text(JsonNode,String key,int min,int max):String`（trim），`integer(JsonNode,String key,long min,long max):long`（只接受JSON整数），`bool(JsonNode,String key):boolean`（只接受JSON boolean），`array(JsonNode,String key,int min,int max):ArrayNode`，`id(long):long`（1..MAX_SAFE_INTEGER），`reason(JsonNode):String`，`require(boolean,String):void`（400）。可在业务服务中增加本域校验，但不要省略源代码严格字段/边界校验。
- `BusinessException` 构造 `(int status,String code,String message)`；静态 `conflict(String)` / `conflict(String code,String message)` 返回异常；`notFound(String)` 返回404异常；`invalid(String)` 返回400异常。使用throw。
- `Db` Spring组件：`one(String sql,Object... args):ObjectNode`（未命中null）；`list(...):List<ObjectNode>`；`insert(...):long`（新ID）；`update(...):int`（影响行）；`tx(Supplier<T>):T`，`tx(Runnable):void`。所有业务写操作在tx内；tx是REQUIRED，最外层以数据库业务互斥行FOR UPDATE保持现有SQLite写事务的顺序语义，渠道网络请求必须在事务外。SQL使用原有snake_case列，审计时间用CURRENT_TIMESTAMP(3)，业务时间沿用ISO字符串。`Db.setting(String):JsonNode`、`putSetting(String,Object,long actor):void`、`assertTransaction():void`。
- SQL采用MySQL语法；禁止DELETE/TRUNCATE/物理删除。所有更新维护update_user_id/update_time，正常查询过滤is_deleted=0。settings列为`key`，SQL中需反引号。
- `Access` Spring组件：`user():ObjectNode`（需Bearer会话）；`optionalUser():ObjectNode`（可null）；`staff(String permission):ObjectNode`（空字符串只需登录）；`actor():long`；`requestId():String`；`audit(String action,String objectType,Object objectId,String reason):void`；重载末尾`String result`。各controller先调用user/staff；不要仅在service内部调用。当前请求会话每次从数据库读取，后台写入由全局filter检查Origin及cookie。
- 注入Db/Access，用静态import J.* 与 BusinessException.*。Controller函数返回Object（可ObjectNode/List/JsonNode）；原接口返回 null 时显式返回 NullNode.getInstance()，确保响应正文为可解析的 JSON null。

## 跨模块固定接口（必须实现并保持一致）

`com.jianyanji.catalog.CatalogService`：`Long rolePrice(JsonNode sku,JsonNode user)`；`ObjectNode productView(ObjectNode product,JsonNode user,boolean admin)`。

`com.jianyanji.orders.OrderService`：`ObjectNode owned(long id,Long userId)`；`ObjectNode view(ObjectNode order)`；`boolean release(ObjectNode order)`；`void expire()`；`void refreshFulfillment(long orderId)`。

`com.jianyanji.payments.PaymentService`：`ObjectNode settlePaid(ObjectNode order,String method,String transactionId)`（返回{alreadyPaid,order}）；`void assertNoUnresolved(long userId,long exceptOrderId)`；`void reconcile()`。

`com.jianyanji.payments.WechatPayClient`：`boolean configured()`；`String appId()`；`String mchId()`；`JsonNode create(JsonNode input)`（number,cents,openid,description,expiresAt）；`JsonNode query(String number)`；`void close(String number)`；`JsonNode notification(byte[] raw,javax.servlet.http.HttpServletRequest request)`；`JsonNode refund(JsonNode input)`（outTradeNo,transactionId,outRefundNo,totalCents,refundCents,reason）；`JsonNode queryRefund(String number)`。外部失败抛带code的BusinessException，禁止模拟生产成功。

`com.jianyanji.wallet.WalletService`：`void restoreOrderPoints(long orderId,long refundId,long amount,long actor,String requestId)`；`ObjectNode requireAgent(long userId)`；`ObjectNode walletView(long userId)`。

`com.jianyanji.membership.MembershipService`：`void applyPackageRefundIdentity(ObjectNode order,boolean full,String decision,long actor,String reason)`（decision可null）。

`com.jianyanji.rewards.RewardService`：`void reconcileReversals(long orderId,long actor,String requestId)`。

测试前必须 `export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)` 并运行java -version确认。新代码只写自己的分配包；不更改Node参考实现，不commit。结构迁移由主线程根据现有DDL生成MySQL迁移。各模块在readme消息中报需要的额外列（原DDL已包含的无需重复）。
