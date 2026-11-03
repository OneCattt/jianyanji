const { imageUrl } = require("../../utils/image");
const format = (cents) => (Number(cents) / 100).toFixed(2);
function dateText(value) {
  if (!value) return "";
  const date = new Date(value),
    pad = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
function decorateCombinedPickup(pickup) {
  const statusText = {
    awaiting_payment: "待积分支付",
    review: "待审核配送",
    approved: "待发货",
    shipped: "已发货",
    received: "已收货",
    cancelled: "已取消",
    rejected: "已驳回",
  }[pickup.status];
  return {
    ...pickup,
    statusText,
    count: pickup.lines.reduce((sum, line) => sum + line.quantity, 0),
    lines: pickup.lines.map((line) => ({
      ...line,
      imageThumb: imageUrl(line.image, "thumb"),
      sourceText:
        line.lot_id != null ? "寄存商品 · 不重复收费" : "本单新购商品",
    })),
  };
}
function decorate(order) {
  const paid = ["paid", "partially_refunded", "refunded"].includes(
    order.payment_state,
  );
  const locked = !paid && !["unpaid", "failed"].includes(order.payment_state);
  const labels = {
    pending: "待积分支付",
    paid: "待发货",
    awaiting_shipment: "待发货",
    shipped: "运输中",
    completed: "已完成",
    closed: "已关闭",
    refunded: "已退款",
  };
  const stateText = locked
    ? "原交易待核查"
    : order.payment_state === "refunded"
      ? "已退款"
      : order.fulfillmentText || labels[order.status] || "处理中";
  const amount = (cents) =>
    order.payment_method === "points" || !order.payment_method
      ? format(cents) + " 积分"
      : "¥" + format(cents);
  const snapshot = order.snapshot || {};
  return {
    ...order,
    stateText,
    paid,
    locked,
    canPay: order.status === "pending" && !locked && !paid,
    receiptText:
      order.payment_state === "refunded"
        ? "已退款"
        : paid
          ? order.payment_method === "points"
            ? "积分已扣减"
            : "已核款"
          : locked
            ? "待核查"
            : order.status === "closed"
              ? "已关闭"
              : "待积分支付",
    amountText: amount(order.total_cents),
    goodsText: amount(order.goods_cents),
    shippingText: amount(order.shipping_cents),
    methodText:
      {
        wechat: "历史微信付款",
        points: "积分钱包",
        offline: "公司转款",
        company: "公司转款",
      }[order.payment_method] || "积分支付",
    count: (snapshot.lines || []).reduce((n, l) => n + l.quantity, 0),
    displayLines: (snapshot.lines || []).map((line, index) => ({
      lineKey: line.skuId + ":" + Boolean(line.isGift) + ":" + index,
      ...line,
      imageThumb: imageUrl(line.image, "thumb"),
      priceText: amount(line.unitCents),
    })),
    hint: locked
      ? "请联系品牌方核实原交易，结果确认前请勿重复转款"
      : order.payment_state === "refunded"
        ? order.payment_method === "points"
          ? "退款积分已退回原批次"
          : "现金退款已由财务线下办理并登记"
        : order.fulfillmentState === "stored"
          ? "剩余" +
            order.storageRemainingQty +
            "件寄存于品牌仓，可到我的寄存申请提货"
          : order.fulfillmentState === "awaiting_shipment"
            ? "本次配送待总部处理，寄存余量可在我的寄存查看"
            : order.status === "paid" || order.status === "awaiting_shipment"
              ? "品牌仓正在配货"
              : order.status === "shipped"
                ? "品牌仓已登记出库，可查看物流单号"
                : order.status === "completed"
                  ? "订单已确认收货，感谢你的信任"
                  : order.status === "closed"
                    ? "订单已关闭，预占库存已释放"
                    : order.kind === "package"
                      ? "产品包成功扣积分后开通对应店主身份；货品固定不可调换"
                      : "请使用积分支付，余额不足请联系总部微信充值",
    createdText: dateText(order.create_time),
    receivedText: dateText(order.received_at),
  };
}
// 待发货/运输中展示实际履约数量；原购买快照与详情始终保留原件数及成交金额。
function decorateList(order, tab) {
  const view = decorate(order);
  if (tab !== 2 && tab !== 3) return view;
  const field = tab === 2 ? "pending_ship_qty" : "in_transit_qty";
  const snapshots = new Map();
  const key = (sku, gift) => sku + ":" + Boolean(gift);
  for (const line of view.displayLines) {
    const id = key(line.skuId, line.isGift);
    if (!snapshots.has(id)) snapshots.set(id, []);
    snapshots.get(id).push(line);
  }
  const displayLines = [];
  for (const line of order.lines || []) {
    const source = snapshots.get(key(line.sku_id, Number(line.is_gift)));
    const snapshot = source && source.shift();
    if (snapshot && line[field] > 0)
      displayLines.push({
        ...snapshot,
        lineKey: String(line.id),
        quantity: line[field],
      });
  }
  const scopeText = tab === 2 ? "待发货" : "运输中";
  return {
    ...view,
    displayLines,
    originalCount: view.count,
    count: displayLines.reduce((sum, line) => sum + line.quantity, 0),
    scopeText,
    stateText:
      tab === 2 && (order.pickups || []).some((p) => p.status === "review")
        ? "待审核配送"
        : scopeText,
    hint:
      tab === 2
        ? "仅展示当前待配送商品，其余商品按原订单寄存或履约"
        : "仅展示未签收包裹内商品，其余商品按原订单履约",
  };
}
module.exports = { decorate, decorateList, decorateCombinedPickup, dateText };
