const { request } = require("../../utils/api");
const { imageUrl } = require("../../utils/image");
const { isFresh, remember } = require("../../utils/state");
const { listValues } = require("../../utils/list");
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
    if (!isFresh(this)) return this.load(true);
  },
  async onPullDownRefresh() {
    try {
      await this.load(true);
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  onReachBottom() {
    if (this.data.nextBefore) return this.load(false);
  },
  async load(reset) {
    if (this.data.loading) return;
    this.setData({
      loading: true,
      error: "",
    });
    try {
      const result = await request(
        "/public/posts?limit=20" +
          (!reset && this.data.nextBefore
            ? "&before=" + this.data.nextBefore
            : ""),
      );
      this.setData({
        ...listValues(
          this,
          "items",
          result.items.map((item) => ({
            ...item,
            coverDisplay: imageUrl(item.cover, "card"),
          })),
          reset,
        ),
        nextBefore: result.nextBefore,
        loaded: true,
      });
      remember(this);
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
