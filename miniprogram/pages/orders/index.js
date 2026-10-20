const { request } = require("../../utils/api");
const { decorate } = require("../order/view");
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember } = require("../../utils/state");
Page({
  data: {
    ...listData("orders"),
    tabs: ["全部", "待核款", "待发货", "运输中", "已完成"],
    tab: 0,
    error: "",
    loading: false,
  },
  onLoad(options) {
    const tab = Number(options.tab);
    if (Number.isInteger(tab) && tab >= 0 && tab < this.data.tabs.length)
      this.setData({ tab });
  },
  onShow() {
    if (!isFresh(this) && !this.data.ordersLoading) return this.load();
  },
  async load(reset = true) {
    const ok = await loadPage(this, {
      key: "orders",
      reset,
      url: "/mini/orders?tab=" + this.data.tab,
      map: decorate,
    });
    if (ok) remember(this);
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
  select(e) {
    const tab = Number(e.currentTarget.dataset.index);
    if (tab === this.data.tab) return;
    this.setData({ tab, orders: [] });
    return this.load();
  },
  detail(e) {
    wx.navigateTo({
      url: "/pages/order/index?id=" + e.currentTarget.dataset.id,
    });
  },
  back() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.reLaunch({ url: "/pages/profile/index" });
  },
});
