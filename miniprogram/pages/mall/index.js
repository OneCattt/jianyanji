const { request } = require("../../utils/api");
const { decorate } = require("../../utils/catalog");
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember } = require("../../utils/state");
const { loadBadge } = require("../../utils/badges");
Page({
  data: {
    ...listData("products"),
    categories: [],
    categoryId: 0,
    identityName: "游客",
    cartCount: null,
    cartCountError: "",
    error: "",
  },
  cart() {
    wx.navigateTo({ url: "/pages/cart/index" });
  },
  onShow() {
    const tabBar = this.getTabBar && this.getTabBar();
    if (tabBar) tabBar.setData({ selected: 1 });
    const products =
      !isFresh(this) && !this.data.productsLoading
        ? this.load()
        : Promise.resolve();
    return Promise.all([products, loadBadge(this, "cart")]);
  },
  loadCartCount() {
    return loadBadge(this, "cart", true);
  },
  async load(reset = true) {
    const categoryId = this.data.categoryId;
    const categories =
      reset && (!this._categoriesAt || Date.now() - this._categoriesAt > 60000)
        ? request("/public/categories")
            .then((items) => {
              this._categoriesAt = Date.now();
              this.setData({ categories: items });
            })
            .catch((e) => this.setData({ error: e.message }))
        : Promise.resolve();
    const success = await loadPage(this, {
      key: "products",
      reset,
      url: "/mini/products" + (categoryId ? "?categoryId=" + categoryId : ""),
      map: decorate,
      extra: (r) => ({
        identityName: { D: "游客", C: "调肤店主", B: "调肤主理人" }[r.identity],
      }),
    });
    await categories;
    if (success) remember(this);
  },
  async onPullDownRefresh() {
    try {
      await Promise.all([this.load(), loadBadge(this, "cart", true)]);
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  onReachBottom() {
    return this.load(false);
  },
  selectCategory(e) {
    const categoryId = Number(e.currentTarget.dataset.id);
    if (categoryId === this.data.categoryId) return;
    this.setData({ categoryId, products: [] });
    return this.load();
  },
  open(e) {
    wx.navigateTo({
      url: "/pages/product/index?id=" + e.currentTarget.dataset.id,
    });
  },
});
