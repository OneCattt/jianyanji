const { request } = require("../../utils/api");
const { money } = require("../../utils/catalog");
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember } = require("../../utils/state");
Page({
  data: { ...listData(), error: "" },
  onShow() {
    if (!isFresh(this) && !this.data.itemsLoading) return this.load();
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
    const names = {
      processing: "办理中，积分已占用",
      unknown: "结果待核查，保留占用",
      failed: "未退款，已释放积分",
      rejected: "已驳回，已释放积分",
      success: "已实际退现金并扣回积分",
    };
    if (
      await loadPage(this, {
        reset,
        url: "/mini/cash-refunds",
        map: (i) => ({
          ...i,
          statusName: names[i.status],
          amountText: money(i.amount_cents),
        }),
      })
    )
      remember(this);
  },
});
