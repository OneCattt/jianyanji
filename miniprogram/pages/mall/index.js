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
    categoryName: "全部商品",
    searchValue: "",
    keyword: "",
    productsScrollTop: 0,
    refreshing: false,
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
    const query = [];
    if (categoryId) query.push("categoryId=" + categoryId);
    if (this.data.keyword)
      query.push("keyword=" + encodeURIComponent(this.data.keyword));
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
      url: "/mini/products" + (query.length ? "?" + query.join("&") : ""),
      map: decorate,
      extra: (r) => ({
        identityName: { D: "游客", C: "调肤店主", B: "调肤主理人" }[r.identity],
      }),
    });
    await categories;
    if (success) remember(this);
  },
  async onPullDownRefresh() {
    this.setData({ refreshing: true });
    try {
      await Promise.all([this.load(), loadBadge(this, "cart", true)]);
    } finally {
      this.setData({ refreshing: false });
      wx.stopPullDownRefresh();
    }
  },
  onReachBottom() {
    return this.load(false);
  },
  selectCategory(e) {
    const categoryId = Number(e.currentTarget.dataset.id);
    if (categoryId === this.data.categoryId) return;
    const category = this.data.categories.find(
      (item) => item.id === categoryId,
    );
    this.setData({
      categoryId,
      categoryName: category ? category.name : "全部商品",
    });
    return this.resetProducts();
  },
  inputSearch(e) {
    this.setData({ searchValue: e.detail.value });
    if (!e.detail.value.trim() && this.data.keyword) return this.clearSearch();
  },
  search() {
    const keyword = this.data.searchValue.trim();
    if (keyword === this.data.keyword) return;
    this.setData({ keyword, searchValue: keyword });
    return this.resetProducts();
  },
  clearSearch() {
    this.setData({ searchValue: "", keyword: "" });
    return this.resetProducts();
  },
  resetProducts() {
    // 仅切换条件时同步位置，再回顶；避免绑定值始终为0导致第二次回顶失效。
    this.setData(
      {
        products: [],
        productsLoaded: false,
        productsScrollTop: this._productsScrollTop || 0,
      },
      () => this.setData({ productsScrollTop: 0 }),
    );
    return this.load();
  },
  trackScroll(e) {
    // 滚动期间只在逻辑层记录，不能用setData写回scroll-top干预原生惯性滚动。
    this._productsScrollTop = e.detail.scrollTop;
  },
  retry() {
    return this.load(!this.data.productsLoaded);
  },
  open(e) {
    wx.navigateTo({
      url: "/pages/product/index?id=" + e.currentTarget.dataset.id,
    });
  },
});
