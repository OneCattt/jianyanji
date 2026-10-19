# 07–09 收款、入驻、钱包接口（集成约定）

由本切片维护 server/membership.ts、wallet.ts，主线程接入 migrateMembership(db)、migrateWallet(db) 与 mountMembership(app,ctx)、mountWallet(app,ctx)。迁移在 catalog/orders 之后，挂载在 404/error handler 之前。均不需要额外 options。

## 11/13 资金表约定

- company_receipts: id,user_id,purpose ('package'|'points'),onboarding_id,source_channel ('bank'|'wechat_company'),account_ref,external_transaction_id,amount_cents,received_at,evidence,reason,confirmed_by,confirmed_at,cash_refunded_cents,cash_held_cents；唯一(source_channel,account_ref,external_transaction_id)，保留历史唯一性，禁止删除后重新发款。登记必须工作人员已核实真实到账；产品包款不发积分。
- wallet_lots: id,user_id,receipt_id UNIQUE,issued_cents,remaining_cents,held_cents。1 元 = 1 积分，均为内部百分之一整数；可花 remaining_cents-held_cents；实际现金退款扣 remaining_cents 并累计 receipt.cash_refunded_cents；办理中同步占用 lot.held_cents 和 receipt.cash_held_cents。
- wallet_spend_allocations: id,order_id,user_id,lot_id,receipt_id,spent_cents,refunded_cents；UNIQUE(order_id,lot_id)。退款逐原 allocation 增 refunded_cents，原 lot 恢复余额，禁止超过 spent_cents。
- wallet_ledger: id,user_id,receipt_id,lot_id,order_id,kind,delta_cents,reference,idempotency_key UNIQUE,reason + 标准审计列。kind=credit/spend/order_refund/cash_refund/correction。
- 导出 restoreOrderPoints(db,orderId,amountCents,reference,actor=0)：**调用者已在事务内**，按原 allocations 恢复原 lot，reference 必须退款业务唯一编号，幂等重复金额必须一致。返还积分不等于现金退款。
- 导出 walletView(db,userId)、requireAgent(db,userId)，无需持有第二份余额。

## 14 奖励依据

onboardings 保存 user_id,target_identity,package_snapshot,reward_cents,after_sales_days,inviter_id,principal_id,receipt_id,order_id,status,qualification_snapshot,approved_at,reviewed_by,review_reason。邀请人与归属分别确认；approval 后 users.inviter_id/principal_id/onboarding_id 对应更新。产品包交付订单 orders.kind='package',payment_method='offline',payment_state='paid',status='paid'，无需库存 reserve，核款创建时直接扣 skus.stock，保留其他订单 reserved。奖励档位和售后期必须配置，不默认为零。

## 主导航接入

后台独立组件 MembershipOperations.vue / WalletOperations.vue。原生路由 pages/onboarding/index、pages/wallet/index；个人钱包可带 ?orderId=… 完成积分支付，页面不隐藏入口。主线程维护 app.json、主导航与 orders 页面支付入口。

真实转款到账与付款凭证须品牌方人工核实；微信外部真机链路由支付切片验证，本模块不会生成外部成功事实。

## 最终集成补充

- 入驻奖励金额只读取 rewards.ts 的 rewardSettings(db)，配置入口 /admin/reward-settings（reward_settings 表）；membership 设置仅 packages，不存第二套金额。售后使用 settings.aftersales {days,policy}。
- 审批成立更新订单 snapshot，含 identity、onboardingApproved:true、rewardCents、inviterId、principalId、onboardingId、receiptId、afterSalesDays、afterSalesPolicy。
- 产品包核款额外建立 payments(method='offline',transaction_id='company_receipt:'+receiptId) 凭据；不调用会扣 reserve 的普通 settlePaid。
- POST /orders/:id/pay/points 使用 payments.settlePaid 在同事务完成库存与支付；native 钱包支持 ?orderId=…，后台 MembershipOperations 接收可选 permissions 字符串数组。
- applyPackageRefundIdentity(db,order,full,decision,actor,reason) 已导出，须在 db.transaction 内；累加 refunds.status='success' 的真实退款更新 receipt.cash_refunded_cents，onboarding_identity_events 保留原状态及退款依据，只有 users.onboarding_id 仍是本次才撤销当前身份。
- restoreOrderPoints 同样使用 db.assertTransaction，reference='refund:'+refundId。主线程默认 hooks 已接入。
- GET /admin/receipts 支持 userId、receiptId、externalTransactionId 精确筛选；同来源失败通过原流水及 requestId 审计定位。
- /pages/wallet/index 的现金退款查询入口为 /pages/cash-refunds/index，本模块不发起线上提现。
- 测试、Figma、原生编译器和真实业务验收边界详见 verification/07-09-verification.md。
