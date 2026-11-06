const { imageUrl } = require("./image");

const statusNames = {
  awaiting_payment: "待付款",
  review: "待审核",
  approved: "待发货",
  rejected: "已驳回",
  shipped: "已发货",
  received: "已收货",
  cancelled: "已取消",
};

function productLine(line) {
  return { ...line, imageThumb: imageUrl(line.image, "thumb") };
}

function pickupView(pickup) {
  const lines = (pickup.lines || []).map(productLine);
  return {
    ...pickup,
    lines,
    previewLines: lines.slice(0, 2),
    moreKinds: Math.max(0, lines.length - 2),
    quantity: lines.reduce((sum, line) => sum + line.quantity, 0),
    statusText: statusNames[pickup.status] || pickup.status,
    statusMuted: ["cancelled", "rejected", "received"].includes(pickup.status),
    feeText: (Number(pickup.fee_cents || 0) / 100).toFixed(2),
  };
}

module.exports = { productLine, pickupView };
