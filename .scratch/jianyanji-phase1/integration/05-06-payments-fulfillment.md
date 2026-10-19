# 05–06 支付与品牌仓履约接入

## 文件与挂载

- `server/payments.ts`：`migratePayments(db)`、`mountPayments(app,ctx,options?:PaymentsOptions)`、`settlePaid`、`assertNoUnresolvedPayment`、`reconcilePayments`。
- `server/wechat-pay.ts`：真实微信 V3 渠道；`WechatPayV3`、`paymentAdapterFromEnv(env?)`、`PayChannel`、`PaymentAdapter`、`createRefund(channel,input)`、`queryRefund(channel,outRefundNo)`。
- `server/fulfillment.ts`：`migrateFulfillment(db)`、`mountFulfillment(app,ctx)`、`shippableLines(db,orderId)`、`refreshFulfillment(db,orderId)`。
- `web/components/PaymentOperations.vue`：后台订单筛选、支付原单核查、发货、纠正物流、包裹收货时间及支付事件/异常到账证据；配送规则继续由 04 的配置组件维护。
- 原生页面：`pages/orders/index`、`pages/order/index`、`pages/order/logistics`。`pages/order/common.wxss`、`view.js` 和 `assets/*` 为页面私有依赖。图标来自指定 Figma 节点导出的 SVG，同时提供由原图栅格化的 3 倍 PNG 供微信原生 image 使用。
- 主线程维护中央文件、路由注册与导航。本切片未编辑 `app.ts`、`db.ts`、`index.ts`、`orders.ts`、`project*.json`。

迁移需在 catalog/orders 后；挂载需在 404/error handler 前。BusinessContext 负责用户认证、工作人员认证、`orders:manage` 授权和业务审计。定时核对由主线程 index 调用，不在模块内另建定时器。

## 原始通知字节

主线程全局 JSON 中间件需保留原始 Buffer，例如：

```ts
express.json({ verify(req, _res, bytes) {
  if (req.url?.startsWith('/api/payments/wechat/notify'))
    (req as typeof req & {rawBody:Buffer}).rawBody=Buffer.from(bytes);
}})
```

模块通知路由也有 `express.raw`，可在全局 JSON 解析之前使用；已经解析且没有 rawBody 时明确拒绝通知，不能把 JSON.stringify 后的内容用于验签。

## 09 结算约定

```ts
transaction(db, () => {
  // 调用方完成自身余额扣减、原始资金 lot 分摊等操作。
  const {alreadyPaid, order} = settlePaid(db, sourceOrder, 'points', `points:${sourceOrder.id}`);
});
```

- 函数同步，通过 `assertTransaction(db,'settlePaid')` 强制调用方事务；回滚涵盖余额、库存、订单与收款凭据。
- method 支持 `wechat | points | company`；只结算 pending，成功为 status=paid/payment_state=paid；扣减 skus.stock 和 reserved。
- 同一订单相同 method+transactionId 重复返回 alreadyPaid=true；冲突流水拒绝，不二扣库存。函数内部重新读取订单，不信任调用方旧状态。
- `assertNoUnresolvedPayment(db,userId,exceptOrderId?)` 检查用户未决微信交易。积分结算自身也执行该检查；新的微信交易检查其他订单未决交易。主线程已在创建替代订单处加入对应拦截。
- 公司产品包若核款时直接扣库存，保留 07 自身核款路径，不再调用此函数重复扣减；履约兼容 status=awaiting_shipment 和 payment_method=offline。

## 支付持久化与核对

- `payment_attempts` 保存原渠道商户单号、原 appid/mchid、金额、状态、预支付参数、发起/查询租约、关闭意图、异常、追踪号及核验到账原文/transaction_id。渠道异步期间无 SQLite 长事务。
- `payments` 只保存本地实际结算凭据，字段 `order_id,method,out_trade_no,transaction_id,cents,paid_at` 与标准审计列。无 status 列；一订单一个有效凭据，渠道流水亦唯一。
- `payments.out_trade_no` 是 `payment_attempts.number`，不是 `orders.number`。退款必须沿用此值与原 transaction_id。
- `payment_events` 与统一 audit_events 保存失败、查询及结算证据。后台返回所有尝试，已关闭交易的异常到账流水不会被 active 过滤丢弃。
- 未知结果保留订单与库存锁；取消/超时先查询、可信 close 成功才释放；close 遇到账竞争再次查询并结算；可信 PAYERROR 也先 close，才允许失败订单重新付款。
- 发起从未获得预支付凭据、原固定有效期已过、且原商户验签查询 ORDER_NOT_EXIST 时回收占用；过期前的不存在结果继续保留锁。配置换商户不得用新商户查询不存在来关闭旧单。
- 若已关闭订单出现违反渠道关闭终态的迟到到账，保留 verified_trade/transaction_id 与失败事件，后台展示异常，拒绝伪造本地结算成功。需品牌方按原流水核查/退款；查询提交不代表补偿完成。
- `reconcilePayments(db,adapter?,limit=20)` 每批有限逐项核查；结果中 ok 表示本次核对执行结果，state/status 表示实际业务状态，不能把 ok=true 的未付查询当成到账。

## 微信环境配置

全部服务端配置：

```dotenv
WECHAT_APP_ID=
WECHAT_PAY_MCH_ID=
WECHAT_PAY_SERIAL_NO=
WECHAT_PAY_PRIVATE_KEY_PATH=
WECHAT_PAY_PLATFORM_SERIAL=
WECHAT_PAY_PLATFORM_PUBLIC_KEY_PATH=
WECHAT_PAY_API_V3_KEY=
WECHAT_PAY_NOTIFY_URL=https://实际合法域名/api/payments/wechat/notify
```

商户 RSA 私钥与可信微信平台公钥均使用 PEM 文件；平台证书/公钥标识须与 PLATFORM_SERIAL 匹配，轮换时由部署方维护可信公钥和标识。API_V3_KEY 必须为 32 字节。缺配置 capability 返回不可付，发起明确 503；无默认模拟成功渠道。私钥不下发到客户端。生产接通真实商户后再验证小额付款、回调、查单、关单、退款与退款查询。

## 10 微信退款接口

真实渠道结构兼容主线程 RefundChannel：

```ts
interface RefundInput {
  outTradeNo:string; transactionId:string; outRefundNo:string;
  totalCents:number; refundCents:number; reason:string;
}
interface RefundResult {
  status:'processing'|'success'|'failed'|'unknown';
  outRefundNo:string; outTradeNo:string; refundCents:number; totalCents:number;
  refundId?:string; reason?:string;
}
```

真实渠道对请求签名、响应验签，申请使用原 transactionId。结果检查原 outTradeNo、outRefundNo、金额、币种；queryRefund 保留原退款单号。异常抛给售后模块，由售后保存 unknown 与占用；不会将网络异常映射为成功。`paymentAdapterFromEnv()` 的返回值可同时用于支付、退款；显式测试 adapter 可只实现 PaymentAdapter，售后仅在包含 refund/queryRefund 时复用。

## 履约与售后

- 可发货数量：`quantity-shipped_qty-refunded_unshipped-held_unshipped`。
- 发货请求带 requestKey 幂等，逐行条件更新，未付款/售后占用/超量拒绝。发货不再扣库存；物流纠正存 shipment_corrections 原值、新值、原因、操作人和 requestId。
- 原生按 shipment 确认本人包裹，已确认重复返回同一结果；仅计剩余未退款商品。所有剩余未退商品收货、包裹已确认且无未处理售后才标 completed。
- `refreshFulfillment` 在售后完成/驳回后同事务调用；全额退款由售后维护 refunded 终态。
- 首期仅展示实际登记的发货/收货记录、物流公司与复制运单号，不提供虚构实时轨迹或预计送达。
- 订单详情“申请售后”跳转 `pages/refunds/index?orderId=…`，该页受理流程由主线程 10–13 衔接。

## API

用户：GET `/api/payments/capabilities`；POST `/api/orders/:id/pay/wechat`；GET `/api/orders/:id/payment`；POST 同路径 `/query` 或 `/close`；POST `/api/orders/:id/shipments/:shipmentId/receive`。

后台（orders:manage）：GET `/api/admin/orders/:id/payment`；POST 同路径 `/query` 或 `/close`（close 必填 reason）；GET `/api/admin/orders/:id/fulfillment`；POST `/api/admin/orders/:id/shipments`；PUT `/api/admin/shipments/:id`。

渠道通知：POST `/api/payments/wechat/notify`，RSA 验签、时间窗、平台标识、AES-GCM 解密后再核对业务关系。

## 验证

- `npx tsx --test server/payments.test.ts`：17/17，包含真实算法与可注入测试渠道，未使用真实商户凭证。
- `npx tsx --test server/payments.test.ts server/wallet.test.ts server/aftersales.test.ts`：28/28。
- `npm run typecheck`：TypeScript 与 Vue 类型检查通过。
- Figma：读取 fileKey IDZgS1kFYRCrzhMmpwWjBg 的 1:1497、1:1682、1:1818 设计上下文及截图；按 376 设计宽适配 rpx，底部按钮包含安全区。
- 真实微信商户外部验收和真机效果仍需人工验收，不能以算法/测试渠道替代。

### 原生自动验证尝试

本机存在 `/Applications/wechatwebdevtools.app`。已复制小程序到临时目录，通过 miniprogram-automator 启动，使用独立内存 DB、真实模块 HTTP 路由与显式测试渠道准备已发货/待付款/完成订单；未修改用户 project*.json。工具启动被 `IDE service port disabled` 拒绝，未执行到页面编译或截图，因此不存在可交付的原生截图通过证据。开启开发者工具服务端口后再检查三页与真机安全区。
