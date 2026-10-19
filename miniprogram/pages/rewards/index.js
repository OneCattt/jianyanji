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
Page({
  data: {
    tabs: ["全部", "待结算", "已结算", "已冲回"],
    tab: 0,
    items: [],
    visible: [],
    summary: null,
    error: "",
    loading: true,
  },
  onShow() {
    this.load();
  },
  async onPullDownRefresh() {
    await this.load();
    wx.stopPullDownRefresh();
  },
  async load() {
    this.setData({ loading: true, error: "" });
    try {
      const result = await request("/rewards");
      const items = result.items.map((r) => ({
        ...r,
        eventKey: "reward-" + r.id,
        title:
          r.kind === "onboarding"
            ? r.targetIdentity === "B"
              ? "主理人产品包奖励"
              : "店主产品包奖励"
            : "店主订货价差",
        amount: amount(r.amountCents),
        reversedAmount: amount(r.reversedCents),
        date: r.createTime.slice(5, 16).replace("T", " "),
        statusName: statusNames[r.status] || r.status,
      }));
      const reversals = result.reversals.map((r) => ({
        ...r,
        id: r.rewardId,
        eventKey: "reversal-" + r.id,
        isReversal: true,
        title: "订单退款冲回",
        amount: amount(r.amountCents),
        statusName: "已冲回",
        date: r.createTime.slice(5, 16).replace("T", " "),
      }));
      items.push(...reversals);
      items.sort((a, b) => b.createTime.localeCompare(a.createTime));
      this.setData({
        items,
        summary: Object.fromEntries(
          Object.entries(result.summary).map(([k, v]) => [
            k,
            k.endsWith("Cents") ? amount(v) : v,
          ]),
        ),
        loading: false,
      });
      this.filter();
    } catch (e) {
      this.setData({
        error: e.message,
        summary: null,
        items: [],
        visible: [],
        loading: false,
      });
    }
  },
  select(e) {
    this.setData({ tab: Number(e.currentTarget.dataset.index) });
    this.filter();
  },
  filter() {
    const tab = this.data.tab;
    this.setData({
      visible: this.data.items.filter(
        (r) =>
          !tab ||
          (tab === 1 && !r.isReversal && r.remainingCents > 0) ||
          (tab === 2 && !r.isReversal && r.settledCents > 0) ||
          (tab === 3 && r.isReversal),
      ),
    });
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
