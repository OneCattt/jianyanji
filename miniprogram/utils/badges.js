const { request } = require("./api");
const { session, isFresh, remember } = require("./state");

const badges = {
  cart: { url: "/mini/cart/count", field: "cartCount", label: "购物袋" },
  orders: { url: "/mini/orders/counts", field: "orderCounts", label: "订单" },
};

// 数量独立缓存，不依赖商品或用户资料是否已缓存；业务变更沿用统一失效机制。
function loadBadge(page, kind, force = false) {
  const { url, field, label } = badges[kind];
  const errorField = field + "Error";
  const identity = session();
  const records = page._badgeLoads || (page._badgeLoads = {});
  const previous = records[kind];
  if (!identity) {
    delete records[kind];
    page.setData({ [field]: null, [errorField]: "" });
    return Promise.resolve();
  }
  if (!force && previous && isFresh(previous))
    return previous.promise || Promise.resolve();
  if (!previous || previous._loadedSession !== identity)
    page.setData({ [field]: null });
  const current = {};
  records[kind] = current;
  // 在请求发出时记录版本，期间发生的加购、下单等操作不会被旧响应覆盖。
  remember(current);
  page.setData({ [errorField]: "" });
  current.promise = request(url)
    .then((result) => {
      if (records[kind] !== current || !isFresh(current)) return;
      page.setData({ [field]: kind === "cart" ? result.count : result });
    })
    .catch(() => {
      if (records[kind] !== current) return;
      current._loadedAt = 0;
      page.setData({
        [field]: null,
        [errorField]: session() === identity ? `${label}数量暂不可读取` : "",
      });
    });
  return current.promise;
}

module.exports = { loadBadge };
