# V2 资格与资金路径定向复核

复核日期：2026-10-20。**两项发现均已由主线程修复，本轮回归通过。** 本轮仅转换／新增独立测试及更新本文件，未修改业务源码、数据库迁移或主线程任务文件。

## R1：在途微信付款的关系保护——已修复

- 当前实现：`InvitationService.adjust` 在同一 `Db.tx` 内、任何关系修改前，检查本人订单及本人直接邀请的待付款申请所关联的未决微信交易；存在时拒绝调整。`closed`／`success` 终态不阻断调整。`Db.tx` 与预支付创建共用业务互斥锁。
- JUnit：`MembershipBoundaryTest.unresolvedPaymentRejectsRelationAdjustmentBeforeAnyWrite` 验证未决交易抛出业务异常，未执行任何 `update`／`insert`，未记录成功审计。
- MySQL：`MembershipBoundaryMysqlTest.unresolvedOwnAndInvitedPaymentsPreserveRelationsUntilResolved` 验证直接邀请申请的 `ready` 和本人订单的 `unknown` 均拒绝调整，用户归属、关系版本、申请确认保持原样；交易分别变为 `closed`／`success` 后允许调整，并要求未付款申请重新确认。

## R2：按产品包资格来源检查退款交接——已修复

- 当前实现：`MembershipService.assertRefundRevocable` 按订单及用户读取原申请，调用 `QualificationService.assertSourceRevocable`；仅本次来源为 B、用户当前为有效 B、且排除本次来源后无其他有效 B 时检查团队交接。
- JUnit：`MembershipBoundaryTest.originalStoreRefundPreservesPrincipalButPrincipalRefundRequiresHandover` 验证退旧 C 包无需交接，实际撤销旧来源仍保留升级后的 B；撤销拥有团队的唯一 B 来源而无接管计划时仍被拒绝，且预检不执行资格更新。

## 执行证据

原独立诊断程序 `MembershipBoundaryProbe.java` 已转换为自动 JUnit 回归 `MembershipBoundaryTest.java`，不再保留旧的错误预期。另新增 `MembershipBoundaryMysqlTest.java`，复用既有 `WALLET_TEST_MYSQL_*` 环境约定和 `Db`，只构造本次 SQL 边界所需最小数据，不重复主线程六项场景。

显式选择并验证 Corretto JDK 8（1.8.0_452），Maven 的 Java 8 Enforcer 检查通过。工作目录为 `backend`：

```sh
export JAVA_HOME=/Users/jiangruliang/Library/Java/JavaVirtualMachines/corretto-1.8.0_452/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn -Dmaven.test.skip=false -DskipTests=false -Dtest=MembershipBoundaryTest test

WALLET_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3308/java_rewards_test_v2_20261020?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8' \
WALLET_TEST_MYSQL_USER=root WALLET_TEST_MYSQL_PASSWORD='' \
mvn -Dmaven.test.skip=false -DskipTests=false -Dtest=MembershipBoundaryMysqlTest test
```

| 测试类 | 执行时间（北京时间） | 运行 | 失败 | 错误 | 跳过 | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| MembershipBoundaryTest | 2026-10-20 12:36:34 | 2 | 0 | 0 | 0 | BUILD SUCCESS |
| MembershipBoundaryMysqlTest | 2026-10-20 12:37:22 | 1 | 0 | 0 | 0 | BUILD SUCCESS |

Surefire 报告：

- `backend/target/surefire-reports/com.jianyanji.membership.MembershipBoundaryTest.txt`
- `backend/target/surefire-reports/com.jianyanji.membership.MembershipBoundaryMysqlTest.txt`

MySQL 场景在同一事务内持有业务互斥行，并在结束时整体回滚；执行日志已确认 JDBC rollback。没有执行 Flyway、修改迁移记录、保留测试业务数据或物理删除业务记录。

验证边界：两项 JUnit 使用真实领域方法和 Db 替身；MySQL 场景使用真实数据库验证关系保护查询及状态保持。未调用真实微信，不代表外部资金渠道验收。
