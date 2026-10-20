const { request } = require("../../utils/api");
const { money } = require("../../utils/catalog");
const states = {
  submitted: "待到账",
  approved: "已激活",
  revoked: "资格已撤销",
  rejected: "未通过",
  paid: "待发货",
  shipped: "已发货",
  completed: "已收货",
  refunded: "已退款",
};
Page({
  data: { stores: [], principals: [], orders: [], busy: false, error: "" },
  onShow() {
    this.load();
  },
  async load() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: "" });
    try {
      const [team, orders] = await Promise.all([
        request("/team"),
        request("/team/orders"),
      ]);
      this.setData({
        stores: team.stores,
        principals: team.principals.map((p) => ({
          ...p,
          stateText: states[p.status] || p.status,
        })),
        orders: orders.map((o) => ({
          ...o,
          amountText: money(o.goods_cents),
          rewardText: money(o.reward_cents),
          stateText: states[o.status] || o.status,
          lines: typeof o.lines === "string" ? JSON.parse(o.lines) : o.lines,
        })),
      });
    } catch (e) {
      this.setData({
        stores: [],
        principals: [],
        orders: [],
        error: e.message,
      });
    } finally {
      this.setData({ busy: false });
    }
  },
});
