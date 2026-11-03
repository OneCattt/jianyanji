// 返回购买页时仅保留仍对应同一商品和数量的发货选择，配置变化按当前配送方式重新选择。
function shippingLines(lines, previous = [], mode = "ship") {
  return lines.map((line, index) => {
    const old = previous[index];
    const keep =
      old &&
      old.skuId === line.skuId &&
      old.quantity === line.quantity &&
      !!old.isGift === !!line.isGift &&
      Number.isInteger(old.shipQuantity) &&
      old.shipQuantity >= 0 &&
      old.shipQuantity <= line.quantity;
    return {
      ...line,
      shipQuantity: keep
        ? old.shipQuantity
        : mode === "store"
          ? 0
          : line.quantity,
    };
  });
}
module.exports = { shippingLines };
