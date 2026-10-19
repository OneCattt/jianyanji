# 奖励模块接入约定（14–18）

- 中央 db.ts 调用 `migrateRewards(db)`；app.ts 在错误处理器前 `mountRewards(app, ctx, options?)`。
- 后台挂载 `web/components/RewardOperations.vue`；原生注册 `pages/rewards/index`、`pages/rewards/detail`，我的入口跳到 index，不隐藏入口。
- `rewardSettings(db)` 返回真实配置或 null，含 storeRewardCents / principalRewardCents / settlementDay。配置 API `/api/admin/reward-settings`。
- 普通订单快照需要 `afterSalesDays`（下单时适用期限）；C 的 principalId 和 order_lines 原价依据已经使用。缺失历史期限不可用当前配置静默填补。
- 入驻订单快照需要 `identity: B|C, inviterId, rewardCents, afterSalesDays, onboardingApproved: true, onboardingId`，付款使用 orders.payment_state=paid；登记不自动生成奖励。如实际入驻表结构不同，通过可选 `resolveOnboarding(db, order)` 返回上述快照证据（见 rewards.ts 类型）。
- 退款模块的 `refunds` 使用约定字段；只有 status=success 计入冲回。成功持久化后，在同一事务内调用 `reconcileRewardReversals(db, orderId, actorId, requestId)`，函数使用 SAVEPOINT，可在原事务回滚；函数只处理已人工登记的奖励，不生成奖励。
- 默认售后检查使用 refunds(order_id,status,is_deleted)，终态为 success/rejected；可选 `hasOpenAfterSales(db, orderId)` 适配。未接入退款表时阻止结算。
- 所有管理端 API 使用 rewards:manage；本人端 `/api/rewards`、`/api/rewards/:id` 仅返回本人奖励及关联冲回、月结、抵扣、实收。

## 配置与页面说明

- 奖励参数 API 的 PUT 只接收两档金额、settlementDay、reason；重复的 afterSalesDays 输入已移除。GET 额外只读展示统一 settings.aftersales.days，旧 reward_settings JSON 中已有的重复字段不参与新业务，未改写任何历史订单/奖励快照。
- 当前 membership 审批订单快照可直接接入，无需额外 options；真实收款/审批到人工奖励登记的集成测试已通过。保留可选适配器仅供不同接入方使用。
- 本人列表返回 items、逐笔 reversals、summary，已冲回页签及全部台账用独立负数冲回行展示；本人明细包含月结、冲回、逐笔抵扣和真实收回记录，不返回订单地址与价格依据。
- 小程序原图标由 Figma 导出 SVG，再用 sips 转成小程序可直接使用的 PNG；不使用临时 Figma URL。

## 2026-10-19 验证

- `npx tsx --test server/rewards.test.ts`：8/8 通过，使用主线程已集成的真实 `fixture` 和真实权限/审计路由。
- 覆盖两身份档位与直接邀请归属、历史快照、本人隔离、微信/积分同价差、分次比例与尾差、退款乱序完成、失败不冲回、重复处理、外层事务回滚、收货/原售后期/未完售后/漏冲回拦截、付款凭证与重复确认、欠100后续80抵扣剩20实收、实收与待付计划竞争。
- `npm run typecheck` 通过；原生两个 JS 的 `node --check` 通过。
- 微信开发者工具自带 `wcc`、`wcsc` 编译两个页面通过，未修改 project*.json。
- `integration/rewards-figma-browser-preview.png` 是 376×830 的 WXML/CSS 换算浏览器预览，使用明确的设计示例数据进行视觉对照；不是微信真机或实际付款证明。未完成真机视觉验收，外部真实公司转账由工作人员实际执行后登记。
- 未执行完整前端构建；未修改中央文件、未提交 Git。

## 交付文件

- server/rewards.ts、server/rewards.test.ts
- web/components/RewardOperations.vue
- miniprogram/pages/rewards/index.{js,json,wxml,wxss}、detail.{js,json,wxml,wxss}
- miniprogram/pages/rewards/assets/{back,wallet,reversal,shield}.{svg,png}
- 本文件、工单 14–18、integration/rewards-figma-browser-preview.png
