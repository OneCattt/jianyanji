const { request } = require("../../utils/api");
const { decorate } = require("../../utils/catalog");
Page({
  data: {
    categories: [],
    products: [],
    categoryId: 0,
    identityName: "游客",
    error: "",
  },
  cart() {
    wx.navigateTo({ url: "/pages/cart/index" });
  },
  async onShow() {
    const tabBar = this.getTabBar && this.getTabBar();
    if (tabBar) tabBar.setData({ selected: 1 });
    await this.load();
  },
  async load() {
    try {
      const [categories, r] = await Promise.all([
        request("/public/categories"),
        request(
          "/products" +
            (this.data.categoryId ? "?categoryId=" + this.data.categoryId : ""),
        ),
      ]);
      this.setData({
        categories,
        products: r.items.map(decorate),
        identityName: { D: "游客", C: "调肤店主", B: "调肤主理人" }[r.identity],
        error: "",
      });
    } catch (e) {
      this.setData({ error: e.message });
    }
  },
  selectCategory(e) {
    this.setData({ categoryId: Number(e.currentTarget.dataset.id) });
    this.load();
  },
  open(e) {
    wx.navigateTo({
      url: "/pages/product/index?id=" + e.currentTarget.dataset.id,
    });
  },
});
