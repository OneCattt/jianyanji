const format = (cents) => (Number(cents) / 100).toFixed(2);
function dateText(value) {
  if (!value) return "";
  const date = new Date(value),
    pad = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
function decorate(order) {
  const paid = ["paid", "partially_refunded", "refunded"].includes(
    order.payment_state,
  );
  const locked = !paid && !["unpaid", "failed"].includes(order.payment_state);
  const labels = {
    pending: "待付款",
    paid: "待发货",
    awaiting_shipment: "待发货",
    shipped: "运输中",
    completed: "已完成",
    closed: "已关闭",
    refunded: "已退款",
  };
  const failed = order.status === "pending" && order.payment_state === "failed";
  const stateText = locked
    ? "支付确认中"
    : failed
      ? "支付失败，待付款"
      : order.payment_state === "refunded"
        ? "已退款"
        : labels[order.status] || "处理中";
  const amount = (cents) =>
    order.payment_method === "points"
      ? format(cents) + " 积分"
      : "¥" + format(cents);
  const snapshot = order.snapshot || {};
  return {
    ...order,
    stateText,
    paid,
    locked,
    canPay: order.status === "pending" && !locked && !paid,
    amountText: amount(order.total_cents),
    goodsText: amount(order.goods_cents),
    shippingText: amount(order.shipping_cents),
    methodText:
      {
        wechat: "微信支付",
        points: "积分钱包",
        offline: "公司转款",
        company: "公司转款",
      }[order.payment_method] || "尚未支付",
    count: (snapshot.lines || []).reduce((n, l) => n + l.quantity, 0),
    displayLines: (snapshot.lines || []).map((line) => ({
      ...line,
      priceText: amount(line.unitCents),
    })),
    hint: locked
      ? "原交易结果待确认，请查询原支付结果"
      : failed
        ? "上次支付已结束，可继续付款或取消订单"
        : order.status === "paid" || order.status === "awaiting_shipment"
          ? "品牌仓正在配货"
          : order.status === "shipped"
            ? "品牌仓已登记出库，可查看物流单号"
            : order.status === "completed"
              ? "订单已确认收货，感谢你的信任"
              : order.status === "closed"
                ? "订单已关闭，预占库存已释放"
                : "请在有效期内完成付款",
    createdText: dateText(order.create_time),
    receivedText: dateText(order.received_at),
  };
}
module.exports = { decorate, dateText };
