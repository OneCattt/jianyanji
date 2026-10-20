const { request } = require("../../utils/api");
const amount = (n) =>
  (n / 100).toLocaleString("en-US", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
const statusNames = {
  not_due: "未到结算期",
  eligible: "待结算",
  settled: "已结算",
  reversed: "已冲回",
};
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember } = require("../../utils/state");
Page({
  data: {
    tabs: ["全部", "待结算", "已结算", "已冲回"],
    tab: 0,
    ...listData(),
    summary: null,
    error: "",
    loading: true,
  },
  onShow() {
    if (!isFresh(this) && !this.data.itemsLoading) return this.load();
  },
  async onPullDownRefresh() {
    await this.load();
    wx.stopPullDownRefresh();
  },
  async load(reset = true) {
    const ok = await loadPage(this, {
      reset,
      url: "/mini/rewards?tab=" + this.data.tab,
      map: (r) => ({
        ...r,
        title: r.isReversal
          ? "订单退款冲回"
          : r.kind === "onboarding"
            ? r.targetIdentity === "B"
              ? "主理人产品包奖励"
              : "店主产品包奖励"
            : r.kind === "retail"
              ? "零售佣金"
              : "店主订货价差",
        amount: amount(r.amountCents),
        reversedAmount: amount(r.reversedCents || 0),
        date: r.createTime.slice(5, 16).replace("T", " "),
        statusName: r.isReversal ? "已冲回" : statusNames[r.status] || r.status,
      }),
      extra: (r) =>
        r.summary
          ? {
              summary: Object.fromEntries(
                Object.entries(r.summary).map(([k, v]) => [
                  k,
                  k.endsWith("Cents") ? amount(v) : v,
                ]),
              ),
            }
          : {},
    });
    if (ok) remember(this);
  },
  onReachBottom() {
    return this.load(false);
  },
  select(e) {
    const tab = Number(e.currentTarget.dataset.index);
    if (tab === this.data.tab) return;
    this.setData({ tab, items: [] });
    return this.load();
  },
  detail(e) {
    wx.navigateTo({
      url: "/pages/rewards/detail?id=" + e.currentTarget.dataset.id,
    });
  },
  back() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.switchTab({ url: "/pages/profile/index" });
  },
});
