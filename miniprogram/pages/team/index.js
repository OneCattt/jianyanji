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
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember } = require("../../utils/state");
Page({
  data: {
    ...listData("stores"),
    ...listData("principals"),
    ...listData("orders"),
    busy: false,
    error: "",
  },
  onShow() {
    if (!isFresh(this)) return this.load();
  },
  onReachBottom() {
    return this.load(false);
  },
  async onPullDownRefresh() {
    try {
      await this.load();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  async load(reset = true) {
    if (this.data.busy) return;
    this.setData({ busy: true });
    try {
      const results = await Promise.all(
        ["stores", "principals", "orders"].map((key) =>
          loadPage(this, {
            key,
            reset,
            url: "/mini/team/" + key,
            map: (item) =>
              key === "stores"
                ? item
                : key === "principals"
                  ? { ...item, stateText: states[item.status] || item.status }
                  : {
                      ...item,
                      amountText: money(item.goods_cents),
                      rewardText: money(item.reward_cents),
                      stateText: states[item.status] || item.status,
                      lines:
                        typeof item.lines === "string"
                          ? JSON.parse(item.lines)
                          : item.lines,
                    },
          }),
        ),
      );
      if (results.every(Boolean)) remember(this);
    } finally {
      this.setData({ busy: false });
    }
  },
});
