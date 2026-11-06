const { request } = require("../../utils/api");
const { decorateList } = require("../order/view");
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember } = require("../../utils/state");
Page({
  data: {
    ...listData("orders"),
    tabs: ["全部", "待付款", "待发货", "运输中", "已完成", "含寄存"],
    tab: 0,
    keyword: "",
    keywordInput: "",
    error: "",
    loading: false,
  },
  onLoad(options) {
    const tab = Number(options.tab);
    if (Number.isInteger(tab) && tab >= 0 && tab < this.data.tabs.length)
      this.setData({ tab });
    if (options.keyword)
      this.setData({
        keyword: String(options.keyword).slice(0, 200),
        keywordInput: String(options.keyword).slice(0, 200),
      });
  },
  onShow() {
    if (!isFresh(this) && !this.data.ordersLoading) return this.load();
  },
  async load(reset = true) {
    const keyword = this.data.keyword.trim();
    const ok = await loadPage(this, {
      key: "orders",
      reset,
      url:
        "/mini/orders?tab=" +
        this.data.tab +
        (keyword ? "&keyword=" + encodeURIComponent(keyword) : ""),
      map: (order) => decorateList(order, this.data.tab),
    });
    if (ok) remember(this);
  },
  keywordInput(e) {
    this.setData({ keywordInput: e.detail.value });
  },
  search() {
    const keyword = this.data.keywordInput.trim().slice(0, 200);
    if (keyword === this.data.keyword) return this.load();
    this.setData({ keyword, orders: [] });
    return this.load();
  },
  clearSearch() {
    if (!this.data.keyword && !this.data.keywordInput) return;
    this.setData({ keyword: "", keywordInput: "", orders: [] });
    return this.load();
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
  primary(e) {
    const { id, action } = e.currentTarget.dataset;
    if (action === "pay")
      return wx.navigateTo({ url: "/pages/wallet/index?orderId=" + id });
    if (action === "pickup")
      return wx.navigateTo({ url: "/pages/storage/index?orderId=" + id });
    if (action === "logistics")
      return wx.navigateTo({ url: "/pages/order/logistics?id=" + id });
    return this.detail(e);
  },
  back() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.reLaunch({ url: "/pages/profile/index" });
  },
});
