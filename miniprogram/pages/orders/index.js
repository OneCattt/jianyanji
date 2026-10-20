const { request } = require("../../utils/api");
const { decorate } = require("../order/view");
Page({
  data: {
    orders: [],
    visible: [],
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
    this.load();
  },
  async load() {
    this.setData({ loading: true, error: "" });
    try {
      const orders = (await request("/orders")).map(decorate);
      this.setData({ orders });
      this.filter();
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  async onPullDownRefresh() {
    await this.load();
    wx.stopPullDownRefresh();
  },
  select(e) {
    this.setData({ tab: Number(e.currentTarget.dataset.index) });
    this.filter();
  },
  filter() {
    const status = [
      null,
      ["pending"],
      ["paid", "awaiting_shipment"],
      ["shipped"],
      ["completed"],
    ][this.data.tab];
    this.setData({
      visible: this.data.orders.filter(
        (o) => !status || status.includes(o.status),
      ),
    });
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
