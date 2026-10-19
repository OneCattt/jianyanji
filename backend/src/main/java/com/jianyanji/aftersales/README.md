# 支付售后 MySQL 验证交接

## 给 Mendel 的 lines 修复同步

共享工作区中 `AftersalesService.java` 的退款 INSERT 已将 MySQL 保留字 `lines` 写为反引号标识符：

```sql
INSERT INTO refunds(order_id,number,kind,goods_cents,shipping_cents,total_cents,`lines`,reason,policy_snapshot,request_key,request_id,create_user_id,update_user_id)
```

已检查 payments/aftersales 生产包和对应测试：生产代码仅此处 SQL 显式引用 `lines` 列；真实 MySQL 测试中两处 UPDATE 均使用反引号。其余 `lines` 是 JSON 字段或 Java 变量，保持原接口名称。

奖励包 README 中“退款 INSERT 因 lines 返回 500”的记录对应修复前执行结果；Mendel 可重新执行奖励真实全链测试。没有修改奖励包、共同层或迁移。

## 本模块验证

`PaymentsAftersalesMysqlTest` 仅在 `PAYMENTS_TEST_MYSQL_URL` 非空时运行；未设置时整类跳过。允许的地址为：

- `127.0.0.1` 或 `localhost` 的任意显式端口，库名匹配 `java_rewards_test_[A-Za-z0-9_]+`。
- 兼容原 `127.0.0.1:3308` 或 `localhost:3308` 的 `jianyanji_schema_check` 隔离库。

统一验收默认共用 `java_rewards_test_integration`，库需提前创建。测试类在首个用例前自动执行 `classpath:db/migration` 的 Flyway 迁移，仅执行一次；通过 `npm run test:api:mysql` 运行时，统一入口也会先迁移，测试类随后校验迁移版本。使用 `PAYMENTS_TEST_MYSQL_USER` / `PAYMENTS_TEST_MYSQL_PASSWORD` 指定账号，缺省为 `root` / 空密码。

使用真实 Db、Access、Order、Wallet、Membership、Fulfillment、Reward 和 Aftersales 服务，只有外部微信网络为测试 stub。按唯一业务键逻辑清理测试数据，不物理删除。

默认使用本机 Maven 配置。需要自备镜像或仓库配置时，可设置 `MAVEN_SETTINGS` 为自备 settings 文件的绝对路径，`npm run test:api:mysql` 会自动使用；直接调用 Maven 时需增加 `-s "$MAVEN_SETTINGS"`。本地临时镜像配置不随项目提交。

主线程统一执行：

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
"$JAVA_HOME/bin/java" -version
MYSQL_TEST_URL='jdbc:mysql://127.0.0.1:3308/java_rewards_test_integration?serverTimezone=UTC' \
npm run test:api:mysql
```

仅运行支付售后模块：

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
"$JAVA_HOME/bin/java" -version
PAYMENTS_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3308/java_rewards_test_integration?serverTimezone=UTC' \
mvn -f backend/pom.xml \
  -Dtest=WechatPayClientTest,PaymentServiceTest,AftersalesServiceTest,PaymentsAftersalesMysqlTest \
  -Dmaven.test.skip=false -DskipTests=false test
```

此前在原隔离库完成真实 MySQL 9 项与单测 20 项，合计 29 项通过，失败、错误、跳过均为 0。覆盖积分支付、部分发货退货、部分退款、原积分批次恢复、奖励冲回、事务失败回滚、并发退款幂等、微信退款超时后查单以及持久租约竞争。本次扩展地址守卫及自动迁移后按主线程要求不重复执行模块测试，等待统一验收。
