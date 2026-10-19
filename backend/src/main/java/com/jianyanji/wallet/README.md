# 公司收款、订货积分与现金退款迁移

行为基准：`server/wallet.ts`、`server/cash-refunds.ts`。金额统一为整数分/百分之一积分；原接口的 snake_case 行字段及 camelCase 汇总字段保留。

## 端点（11个）

- GET /api/wallet
- GET /api/wallet/transfer
- GET /api/admin/receipts
- POST /api/admin/receipts
- GET /api/admin/wallets/{id}
- POST /api/admin/wallet-lots/{id}/correct
- POST /api/orders/{id}/pay/points
- GET /api/cash-refunds
- GET /api/admin/cash-refunds
- POST /api/admin/cash-refunds
- POST /api/admin/cash-refunds/{id}/resolve

后台接口均要求 finance:manage；用户接口均先认证。转款说明和积分支付要求当前有效代理；历史余额查询、原积分返还和现金退款不因身份失效抹除历史账务。

## 固定跨包接口

- WalletService.requireAgent(long userId)
- WalletService.walletView(long userId)
- WalletService.restoreOrderPoints(long orderId,long refundId,long amount,long actor,String requestId)

退款服务需在同一成功退款事务内调用 restoreOrderPoints，尚未将订单状态改为全额退款时执行。原退款ID作为 reference，按分摊记录逐原批次返还；同ID金额不同拒绝、累计超原支付拒绝。

会员模块另调用 companyTransfer、validateReceipt、registerReceipt，现金退款模块调用 ledger；registerReceipt 与 ledger 要求调用者已有事务。账务写入、库存结算、幂等判断依赖公共 Db.tx 的 MySQL 业务互斥行锁，不在包内另开连接或提交。

## 资金约束

同一 source_channel/account_ref/external_transaction_id 不得跨用途重复登记。产品包不发积分。积分支付使用原订单成交快照与统一 PaymentService.settlePaid，不能切换未决微信交易，现金退款占用部分不可再花。现金退款结果 unknown 保留积分与现金双重占用；仅确认未实际付款才能释放；成功须原收款人、过去UTC付款时间、凭证与确认，重复成功不会二扣。

## 字段容量审计及V2迁移

不新增列、不修改已应用V1。`V2__business_field_capacity.sql` 对齐原Node边界，仅扩宽5列，保持默认值、可空性和原联合唯一索引。

| 输入/来源 | Node及Java输入边界 | 存储列及V2结果 |
| --- | --- | --- |
| accountRef | trim后1..200字符 | company_receipts.account_ref：VARCHAR(191)→VARCHAR(200) |
| externalTransactionId | trim后1..200字符 | company_receipts.external_transaction_id：VARCHAR(191)→VARCHAR(200) |
| evidence | trim后1..1000字符 | company_receipts.evidence：VARCHAR(2000)，足够 |
| 收款、纠错、现金退款reason | trim后1..200字符 | 相应reason/last_error：VARCHAR(2000)，足够 |
| 纠错及现金退款requestKey | 12..100字符 | request_key/reference：VARCHAR(191)；纠错幂等键最长111字符，足够 |
| 现金退款recipient | trim后0..100字符 | cash_refunds.recipient：VARCHAR(191)，足够 |
| 现金退款proof | trim后0..1000字符 | cash_refunds.proof：VARCHAR(2000)，足够 |
| 现金退款paidAt | 原Node仅要求字符串；成功时额外要求过去的ISO UTC | cash_refunds.paid_at：VARCHAR(191)→LONGTEXT，可空；失败/驳回保持原始字符串，不新增长度限制 |
| 收款到账与服务端生成业务时间 | ISO时间；收款不得在未来 | received_at/confirmed_at/completed_at：VARCHAR(191)，常规ISO时间足够 |
| request_id | 服务端UUID或有限系统追踪标识 | VARCHAR(191)，足够 |
| 钱包流水reference/idempotency_key | 原收款ID、固定契约退款ID、订单编号、纠错请求编号及固定前缀 | VARCHAR(191)，覆盖当前固定契约的生成长度 |
| purpose/source_channel/kind/status | 固定枚举 | VARCHAR(191)，足够 |
| 金额、积分、分摊、占用及退款累计 | 请求1..100000000，累计由原收款/原支付限制 | BIGINT，足够；保留原金额及非负CHECK |

产品包及身份相关列审计见membership包说明。联合收款唯一索引在MySQL实查仍覆盖全字段，SUB_PART均为NULL。

## 回归

WalletServiceTest 8项、CashRefundServiceTest 8项；会员包另有7项。WalletMysqlTest 7项真实数据库回归覆盖并发重复收款、现金退款与积分支付争余额、库存失败全事务回滚、跨批次返还与现金上限、并发成功仅扣一次，另覆盖200字符中文账户/流水及3000字符失败付款时间的无截断写入。

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn -f backend/pom.xml -Dmaven.test.skip=false -DskipTests=false -Dtest=WalletServiceTest,CashRefundServiceTest,MembershipServiceTest test
```

MySQL回归默认跳过；设置 WALLET_TEST_MYSQL_URL 指向已初始化的**独立测试库**后显式选 WalletMysqlTest。可设置 WALLET_TEST_MYSQL_USER（默认root）、WALLET_TEST_MYSQL_PASSWORD（默认空）。各用例新增独立用户与商品，不删除已有数据，不触碰线上库。

## 本次执行结果（2026-10-19，V2后）

- JDK 8（Corretto 1.8.0_452），指定settings执行Maven：BUILD SUCCESS。
- 单元回归23项、真实MySQL回归8项（钱包7项、会员1项），总计31项通过，0失败、0错误、0跳过。
- 最初5项真实钱包并发/事务用例在公共Db.row时间映射修复后全部通过；未在测试注册额外模块规避生产逻辑。
- 按授权首先在jianyanji_schema_check直接执行V2并查验5列宽度。主线程重启后，只读确认jianyanji_java.flyway_schema_history的version=2、success=1，以及5列实际类型一致；没有对jianyanji_java直接执行DDL。
- 中文字段边界使用真实MySQL回读精确比较，并校验账户/流水201字符被拒绝。会员回归验证最大配置输入→申请→核款交付→独立审批（资格快照超过4000字符）→成功退款并以2000字符原因撤销身份。
- 测试均新增独立数据，不物理删除。未验证浏览器及微信真实渠道；未提交commit。
