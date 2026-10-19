# 奖励模块迁移交接

## 路由

后台统一要求 `rewards:manage`；本人接口要求用户 Bearer 会话。

| 方法 | 路径 |
| --- | --- |
| GET / PUT | `/api/admin/reward-settings` |
| GET | `/api/admin/rewards/source/{id}` |
| GET / POST | `/api/admin/rewards` |
| GET | `/api/admin/rewards/{id}` |
| POST | `/api/admin/rewards/{id}/reconcile` |
| GET | `/api/rewards` |
| GET | `/api/rewards/{id}` |
| GET | `/api/admin/reward-debts` |
| GET / POST | `/api/admin/reward-settlements` |
| POST | `/api/admin/reward-settlements/{id}/result` |
| POST | `/api/admin/reward-recoveries` |

保留创建月结响应的 `plan` 字符串；月结列表和奖励详情将该字段解析为对象。奖励设置未配置时返回空值。原因、凭证按原奖励接口限制为 1～1000 字，收款人 1～100 字，金额按整数分 1～100000000 校验。

## 整合点

- 售后成功时在原事务调用 `RewardService.reconcileReversals(orderId, actor, requestId)`，发生冲回错误应回滚整笔售后；没有自动登记奖励或自动付款。
- 只依赖共同层 `Db / Access / Input / J / BusinessException`，无业务服务循环依赖。
- 所有公开业务操作均通过 `Db.tx` 执行，依赖共同层 `business_mutex FOR UPDATE` 的顺序事务语义，尤其用于付款确认与实收竞争、来源登记和请求幂等。
- 冲回审计直接写 `audit_events`，保留调用方传入的操作人及请求编号，可用于异步售后追踪。其他后台写入使用 `Access.audit`。
- SQL 均为 MySQL；正常查询过滤 `is_deleted=0`，更新维护 `update_user_id / update_time`。没有物理删除接口。
- 无额外数据库列。沿用原奖励六张表及 `orders / order_lines / users / refunds / audit_events / settings`；有效来源、有效退款冲回、有效月结月份、请求键的唯一索引由主线程公共迁移提供。月结月份索引应排除 cancelled 和已逻辑删除记录，使用可空生成列保留多次取消后的重建能力。
- 完整首期迁移要求 `refunds` 表已存在；不在请求中查询 MySQL 元数据探测模块。
- 按共同资源配置，连接时区 UTC，审计 `DATETIME(3)` 存 UTC；本人当月统计按 UTC 加八小时计算上海自然月。

## HTTP 验证

`scripts/java-rewards-contract.mjs` 不启动服务，不连接数据库。由主线程在独立验收库准备下列夹具，再配置 `BASE_URL / JAVA_REWARDS_TEST_ISOLATED_DB=1 / REWARDS_FIXTURE` 运行。不得对真实业务数据库运行；脚本会修改该验收库奖励参数并保留测试财务流水，不清库。

夹具 JSON：

```json
{
  "isolated": true,
  "baseUrl": "http://127.0.0.1:3187",
  "runId": "java-rewards-12345678",
  "adminCookie": "jyj_admin=独立验收管理员会话",
  "limitedCookie": "jyj_admin=不含奖励权限的工作人员会话",
  "ownerToken": "奖励收款人会话",
  "otherToken": "另一奖励收款人会话",
  "ownerId": 101,
  "otherOwnerId": 102,
  "orders": {
    "debt": {"id": 201, "refundItems": [{"lineId": 301, "quantity": 10}]},
    "offset": {"id": 202},
    "stale": {"id": 203},
    "other": {"id": 204},
    "principal": {"id": 205}
  }
}
```

五笔订单都必须：已付、已收货、售后天数快照为零、没有未完售后、尚未登记奖励；订单号以 `runId + '-'` 开头，归属已存在的独立测试用户。

- `debt`：店主入驻产品包，`offline` 公司转款，历史奖励 10000 分归 owner；`refundItems` 覆盖全部货款商品；审批与收款链有效，允许全退撤销本次入驻身份。
- `offset / stale`：另两位测试购买者的店主产品包，分别冻结 8000 分入驻奖励，均归 owner。
- `other`：店主普通订货，历史价差收益 6000 分归 otherOwner，含历史商品行及主理人价。
- `principal`：主理人入驻产品包，冻结 50000 分奖励归 owner。

脚本覆盖 14 个奖励 HTTP 路由，以及售后成功触发冲回的跨模块钩子。最多两个并发请求。包含原快照、严格输入、权限和本人隔离、重复来源、月结幂等、已付记录保护、全退已付欠款、同人后续抵扣、真实实收及去重、禁止超收、过期抵扣计划拦截、未明付款禁止取消、取消后同月重建。商品部分退款、多次退款舍入、乱序退款以及数据库事务回滚应由主线程统一回归继续覆盖。

## Spring Boot 真实 MySQL 资金合同

`backend/src/test/java/com/jianyanji/rewards/RewardsFundsContractTest.java` 使用完整 Spring Boot 上下文和 MockMvc HTTP 分发链，仅 `@MockBean WechatPayClient` 替换外部微信渠道；钱包、支付、售后、奖励及 MySQL 事务都是真实实现。使用 MOCK Web 环境，不监听 3100 或其他端口。

默认跳过测试。启用时必须使用本机 `java_rewards_test_` 前缀的独立库；动态数据源在 Spring Boot/Flyway 加载前校验地址，不会退回默认 `jianyanji` 业务库。测试会写入带随机前缀的新用户、会话、商品和订单；通过 `Db.update` 回溯本次测试购买者的收货时间，先验证原七天售后期内不可结算，再验证期满可结算。不清空、不物理删除数据，可重复运行。

如需自定义 Maven 仓库，请自备 settings 文件，将 `MAVEN_SETTINGS` 设为其绝对路径，并在下方 `mvn` 命令中添加 `-s "$MAVEN_SETTINGS"`。

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
"$JAVA_HOME/bin/java" -version
# 此连接仅用于已授权的本地临时 MySQL，不用于生产。
mysql --protocol=tcp -h127.0.0.1 -P3308 -uroot -e "CREATE DATABASE IF NOT EXISTS java_rewards_test_lifecycle_20261019 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"
JAVA_REWARDS_TEST_ISOLATED_DB=1 \
REWARDS_TEST_JDBC_URL='jdbc:mysql://127.0.0.1:3308/java_rewards_test_lifecycle_20261019?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC' \
REWARDS_TEST_MYSQL_USER=root REWARDS_TEST_MYSQL_PASSWORD='' \
mvn -f backend/pom.xml \
  -Dtest=RewardsFundsContractTest -Dmaven.test.skip=false -DskipTests=false test
```

资金生命周期：微信实付 51000 分，货款 50000 分、运费 1000 分，登记价差奖励 10000 分并付款；只退货款触发已付奖励欠款 10000 分。再以真实公司收款发放 100000 分订货积分，积分订单支付 41000 分，价差奖励 8000 分；先人工收回 3000 分使旧抵扣计划失效，核实失败并取消后按欠款 7000 分重新抵扣、实付 1000 分。积分商品退一件恢复原批次 10000 分并冲回价差 2000 分，人工收回已付奖励 2000 分。最终购买者积分 69000 分，奖励收款人积分仍为零；奖励实付 11000 分减已实收 5000 分，等于保留商品对应奖励 6000 分。

另验证产品包 1 分奖励、6 分货款的累计比例舍入、乱序完成退款、零增量证据、重复冲回与外层事务失败回滚。奖励设置的未配置响应显式使用 `NullNode.getInstance()` 保证可被 `response.json()` 解析。

### 当前验证记录

奖励包 JDK 8 定向编译、Node/Java 14 路由逐项比对、SQL 静态扫描和 HTTP 脚本 `node --check` 已通过。公共 `LocalDateTime` 映射修复后，真实 MySQL 专用库已由 Flyway 自动应用 V2，未修改 V1。

2026-10-19 23:37 在 JDK 8、真实 MySQL 隔离库上完成整轮验证：3 个用例全部通过，0 失败、0 错误、0 跳过，Maven `BUILD SUCCESS`。保留现有测试范围，未新增用例。

- `historicalMarginSnapshotAndRefundBeforeRegistrationRemainTraceable` 通过：当前改价不影响订单快照，登记前成功退款会同步冲回，退款价格依据被篡改时拒绝继续冲回。
- `packageRoundingOutOfOrderRefundAndOuterRollbackKeepHistoricalReversals` 通过：比例舍入、乱序完成、零增量证据、幂等及外层事务回滚。
- `wechatPointsRefundOffsetAndRecoveryKeepFundsOwnership` 通过：完整真实 HTTP 资金生命周期，包括微信支付及付款查询幂等、售后期到期判断、本人隔离、奖励付款幂等、真实售后钩子冲回、同人后续抵扣、并发实收幂等、过期计划拦截、未明付款禁止取消、核实失败后同月重建、积分退款原批次恢复及已付奖励再冲回和追回。末态购买者积分为 69000 分，奖励收款人积分为零；奖励实付 11000 分减实收追回 5000 分，等于保留商品对应奖励 6000 分。

售后负责方修复 `lines` 保留字后，此轮完整链路未再出现 SQL 或业务异常。报告为 `backend/target/surefire-reports/TEST-com.jianyanji.rewards.RewardsFundsContractTest.xml`，运行日志为 `/tmp/java-rewards-lifecycle.log`。只模拟微信渠道；本轮不能证明真实微信网络、签名或实际银行到账。
