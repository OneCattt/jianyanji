# 07–09 实施与验证证据

日期：2026-10-19。实现已进入共享工作区，中央迁移/挂载和后台导航由主线程集成。未执行 git commit/reset；未改动用户 project*.json。

## 本切片文件

- server/membership.ts：migrateMembership、mountMembership、applyPackageRefundIdentity。
- server/wallet.ts：migrateReceipts、migrateWallet、mountWallet、registerReceipt、walletView、ledger、requireAgent、companyTransfer、payWithPoints、restoreOrderPoints。
- server/membership-fixture.ts、membership.test.ts、wallet.test.ts、membership-mini.test.ts：专用测试与真实 API 夹具。
- web/components/MembershipOperations.vue、WalletOperations.vue：后台实物产品包、审批、真实收款、来源积分纠错与追查。
- miniprogram/pages/onboarding/index.{js,json,wxml,wxss}、assets/*：加入弹层、产品包详情、地址/协议、申请状态、转款说明。
- miniprogram/pages/wallet/index.{js,json,wxml,wxss}、assets/*：本人钱包、分类流水、公司转款说明、整单积分确认付款；现金退款入口跳转 pages/cash-refunds/index（主线程维护）。

## 自动化结果

`npm run typecheck`：TypeScript 和 Vue 类型检查通过。

`npx tsx --test server/membership.test.ts server/wallet.test.ts server/membership-mini.test.ts server/cash-refunds.test.ts`：最新一次 19 项全部通过。其中本切片 14 项（入驻与退款身份 6、钱包资金 6、原生页脚本实际接口 2）；现金退款 5 项由主线程维护。

- 无邀请或 C 邀请，直接邀请人与归属 B 分别确认；两档奖励读取 reward_settings 唯一来源；未核款或未配置奖励不能通过。
- 产品包核款：唯一原流水 + 原金额校验 + 无 reserve 时直接扣可用实物 stock + offline 已付商品订单 + payments 凭据同事务；身份审批独立、产品包不发积分。
- 产品包从申请、核款、审批、真实接口发货到本人确认收货；审批前后角色价格变化、原价/邀请/奖励/售后快照不随配置重算。
- 同一收款并发登记仅一笔成功；产品包原流水不能再发订货积分；收款失败回滚库存与款项。
- 两订单争用余额、同单重复付款、微信未明和当前身份过期拦截、库存结算失败后余额/allocations/payments 全部回滚。
- 消费跨原收款 lot 分摊、原源退积分、重复退款幂等、金额上限、返还后再消费仍追溯原 receipt；原收款可按用户、receiptId 或 externalTransactionId 查询。
- 原生 JS 直接调用真实 createApp 接口，核对申请待审批、付款后余额更新、分类流水及现金退款查询导航；微信宿主由测试夹具代替，业务返回没有 mock。
- 产品包部分实际退款保留身份、全退取消本次身份、旧产品包退款保留后来取得的另一身份；累计现金退款与退款身份事件留存。
- 与主线程 13 联测：办理中余额占用、未知结果不释放、失败核实后释放、并发确认去重、原公司收款现金上限、退款与消费互斥。

两个退款 helper 使用 db.assertTransaction；兼容当前 Node 23 运行时，无需 Node 24 的 isTransaction 属性。

## Figma 与原生格式检查

读取 get_design_context：1:2065（首页背景）、同画板 1:2244（实际 BenefitsModal）、1:2630（钱包）。以 376 设计宽换算 rpx；原图标下载并转换 PNG 供原生 image 使用，SVG 源文件保留。

本机微信开发者工具原生编译器均退出 0：

- `wcc miniprogram/pages/onboarding/index.wxml miniprogram/pages/wallet/index.wxml -o .scratch/jianyanji-phase1/verification/07-09-wxml-compiled.js`
- `wcsc -lc miniprogram/pages/onboarding/index.wxss miniprogram/pages/wallet/index.wxss -o .scratch/jianyanji-phase1/verification/07-09-wxss-compiled.js`

编译器位置：/Applications/wechatwebdevtools.app/Contents/Resources/app.asar.unpacked/node_modules/wcc-exec/。

浏览器静态布局对照截图：onboarding-native-layout.png、wallet-native-layout.png。render-membership-wallet.cjs 将真实 WXML/WXSS 转换为浏览器检查页，使用明确隔离的布局数据。该截图不是微信运行时或真实资金交易证明。产品包权益图片、金额和协议显示后台实际配置，未将 Figma 示例价格写成真实产品配置。

## 尚需品牌方和真机人工验收

- 提供并核实真实公司账户、客服说明、两类产品包及奖励金额、售后政策；以真实原收款流水核实到账和凭证，不能把提交申请或截图当作公司到账。
- 微信商户证书、通知、原交易查询与真机付款由支付模块联验；本切片只验证支付状态互斥，不伪造外部微信成功。
- 真机检查屏幕安全区域、中文字体和权益图、滚动/键盘/协议/返回交互，以及真实收货、部分/全额线下退款操作。原生编译、脚本接口测试与浏览器静态截图均不能代替真机验收。

上述边界是外部真实资金/设备验收，不代表接口或持久化使用 mock。工单状态按项目既有标签置为 ready-for-human，供品牌方最终验收。

## 最终收尾补充

主线程已接入 MembershipOperations / WalletOperations，后台实页检查无错误（主线程反馈）。入驻 login 改用 navigateTo 跳转 settings，并将带 inviterId 的原入驻路径编码为 next；无邀请时返回普通入驻路径。通过页面脚本检查 settings 的 next 白名单接受和登录后跳转，邀请参数能返回入驻 onLoad，不再经 profile 丢失。

全项目格式化时请保留 WXML 插值内字符串的完整性：通用 HTML 格式化可能在 {{...}} 字符串内插入换行；最终应再运行原生 wcc 编译验证。

本地编译产物、浏览器布局HTML、PNG与一次性渲染脚本已归档到忽略目录 `artifacts/phase1/membership/`；不纳入业务源码。最终全量编译可运行仓库脚本 `npm run check:miniprogram`，其产物写入系统临时目录并输出报告位置。
