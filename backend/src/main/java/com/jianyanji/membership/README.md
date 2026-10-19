# 入驻产品包迁移

行为基准：`server/membership.ts`，完整迁移配置、公开查询、本人申请、核款交付、独立身份审批及退款身份处理。

## 端点（9个）

- GET /api/membership（公开）
- GET /api/admin/membership-settings（onboarding:manage，未配置返回可解析JSON null）
- PUT /api/admin/membership-settings（onboarding:manage）
- POST /api/onboardings（用户）
- GET /api/onboardings（用户）
- GET /api/onboardings/{id}（本人）
- GET /api/admin/onboardings（onboarding:manage）
- POST /api/admin/onboardings/{id}/receipt（finance:manage）
- POST /api/admin/onboardings/{id}/review（onboarding:manage）

## 合同与事务

固定接口 MembershipService.applyPackageRefundIdentity(ObjectNode order,boolean full,String decision,long actor,String reason) 要求处于实际成功退款的同一事务。

产品包必须分别配置B/C档，规格不可重复，明细数量乘成交单价之和等于产品包货款。申请冻结产品、地址、协议、售后及奖励；本人相同申请编号不能更换内容。核款直接扣可用实物，不扣其他订单reserved，原收款、交付订单、支付凭据、明细与申请绑定同事务提交，不另发积分。

核款不改变代理身份；审批单独检查有效原款、已付产品包订单、未决售后、当前有效邀请人与主理人及资格确认，冻结独立邀请/归属和奖励证据。全额实际退款强制revoke；部分退款须明确retain/revoke。同一累计成功退款金额的身份结论不可改写。撤销用户身份带 onboarding_id 条件，保留之后另次入驻身份、历史账务及订单。

## 字段容量审计及V2迁移

无新增列，已通过V2扩宽原字段；未修改已应用V1。

| 输入/来源 | Node及Java约束 | 存储与结论 |
| --- | --- | --- |
| 产品包name/selfUseLabel | trim后最多80/40字符 | settings.value及package_snapshot为LONGTEXT，足够 |
| benefits/conditions/agreement | trim后各最多2000字符 | 同上；qualification_snapshot包含conditions及售后政策，已由VARCHAR(191)扩至LONGTEXT，可空 |
| rightsImage | 最多1000字符，空/HTTPS/本站素材 | JSON配置及快照LONGTEXT，足够 |
| lines | 1..50项，规格不重复；quantity 1..999；unitCents 1..100000000；合计等于产品包货款 | JSON快照LONGTEXT，明细数字BIGINT，足够 |
| afterSalesPolicy | 从统一售后政策冻结 | JSON快照LONGTEXT，足够；不另缩小来源输入上限 |
| 申请requestKey | trim后12..100字符 | onboardings.request_key VARCHAR(191)，足够 |
| 审批reason | trim后1..200字符 | onboardings.review_reason原191不足，V2扩至VARCHAR(2000)并保留NOT NULL DEFAULT空字符串 |
| applyPackageRefundIdentity原因 | 非空，原Node上限2000字符 | review_reason按V2为VARCHAR(2000)，身份事件reason原VARCHAR(2000)已足够 |
| user_snapshot/订单snapshot | 用户与历史交易的JSON快照 | 原LONGTEXT，足够 |
| identity/status/decision | 固定枚举 | VARCHAR(191)，足够 |
| reward_cents/after_sales_days及业务ID | 整数及安全整数编号 | BIGINT，足够；保留原约束 |

## 验证

MembershipServiceTest 7项：两档/分摊/重复规格/严格字段；无原款及交付订单不得审批；全退仅撤销该次入驻；部分退款必须明确结论；原收款及成功事实金额一致；重复身份结论不可更改；调用须持有事务。

MembershipMysqlTest新增1项真实MySQL容量及完整生命周期回归：最大配置输入、100字符申请编号、200字符中文收款账户/流水、200字符审批原因、超过4000字符资格快照、2000字符退款撤销原因均完整存储并回读；核款不发积分、不改变身份、不动其他订单reserved；独立审批通过后实际全额退款撤销该次身份。

V2在jianyanji_schema_check执行后，本包及钱包共31项回归全部通过（23单元+8真实MySQL，0跳过）。主线程重启的jianyanji_java已只读确认Flyway V2成功应用。浏览器和真实微信渠道未纳入此次验证。
