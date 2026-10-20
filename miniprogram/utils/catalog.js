const { imageUrl } = require("./image");
const money = (value) =>
  value == null ? "价格待配置" : "¥" + (value / 100).toFixed(2);
function decorate(product) {
  const skus = product.skus.map((s) => ({
    ...s,
    priceText: money(s.priceCents),
    retailText: money(s.retailCents),
    priceLabel: s.productType === "welfare" ? "统一福利价" : "当前身份价",
  }));
  const first = skus.find((s) => s.available) || skus[0];
  return {
    ...product,
    imageCard: imageUrl(product.image, "card"),
    detailImageDisplay: imageUrl(product.detailImage, "detail"),
    skus,
    priceText: first ? first.priceText : "价格待配置",
    available: skus.some((s) => s.available),
  };
}
module.exports = { money, decorate };
