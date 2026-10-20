const { request } = require("../../utils/api");
Page({
  data: {
    items: [],
    loading: false,
    error: "",
    nextBefore: null,
    loaded: false,
  },
  onShow() {
    const tabBar = this.getTabBar && this.getTabBar();
    if (tabBar) tabBar.setData({ selected: 2 });
    this.load(true);
  },
  async onPullDownRefresh() {
    try {
      await this.load(true);
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  onReachBottom() {
    if (this.data.nextBefore) this.load(false);
  },
  async load(reset) {
    if (this.data.loading) return;
    this.setData({
      loading: true,
      error: "",
      ...(reset ? { items: [], nextBefore: null } : {}),
    });
    try {
      const result = await request(
        "/public/posts?limit=12" +
          (!reset && this.data.nextBefore
            ? "&before=" + this.data.nextBefore
            : ""),
      );
      this.setData({
        items: reset ? result.items : this.data.items.concat(result.items),
        nextBefore: result.nextBefore,
        loaded: true,
      });
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  retry() {
    this.load(!this.data.items.length);
  },
  open(e) {
    wx.navigateTo({
      url: "/pages/post/index?id=" + e.currentTarget.dataset.id,
    });
  },
  onShareAppMessage() {
    return { title: "清欣简妍肌 · 品牌种草空间", path: "/pages/seeds/index" };
  },
});
