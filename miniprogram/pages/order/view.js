const { imageUrl } = require("../../utils/image");
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
    pending: "待核款",
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
              : "待核款",
    amountText: amount(order.total_cents),
    goodsText: amount(order.goods_cents),
    shippingText: amount(order.shipping_cents),
    methodText:
      {
        wechat: "历史微信付款",
        points: "积分钱包",
        offline: "公司转款",
        company: "公司转款",
      }[order.payment_method] || "线下付款",
    count: (snapshot.lines || []).reduce((n, l) => n + l.quantity, 0),
    displayLines: (snapshot.lines || []).map((line) => ({
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
        : order.status === "paid" || order.status === "awaiting_shipment"
          ? "品牌仓正在配货"
          : order.status === "shipped"
            ? "品牌仓已登记出库，可查看物流单号"
            : order.status === "completed"
              ? "订单已确认收货，感谢你的信任"
              : order.status === "closed"
                ? "订单已关闭，预占库存已释放"
                : order.kind === "package"
                  ? "产品包请线下转款并联系财务核款，核实到账后激活身份"
                  : "请按订单号线下付款并联系财务核款；代理也可使用已有积分订货",
    createdText: dateText(order.create_time),
    receivedText: dateText(order.received_at),
  };
}
module.exports = { decorate, dateText };
