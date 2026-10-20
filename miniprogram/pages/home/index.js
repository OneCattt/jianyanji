const { request } = require("../../utils/api");
const { decorate } = require("../../utils/catalog");
const { imageUrl } = require("../../utils/image");
const { isFresh, remember } = require("../../utils/state");
Page({
  data: {
    content: null,
    featured: null,
    error: "",
    heroTitle: "让肌肤，回到\n它本来的光",
    heroSubtitle: "专业调肤方案 · 精准护理 · 长效陪伴",
    heroImage: "/assets/figma/Home-imgImage.png",
  },
  async onShow() {
    const tabBar = this.getTabBar && this.getTabBar();
    if (tabBar) tabBar.setData({ selected: 0 });
    if (isFresh(this) || this._loading) return;
    this._loading = true;
    try {
      this.setData({ error: "" });
      const results = await Promise.all(
        [
          request("/public/content").then((r) =>
            this.setData({
              content: r.content,
              ...(r.content
                ? {
                    heroTitle: r.content.heroTitle,
                    heroSubtitle: r.content.heroSubtitle,
                    heroImage: imageUrl(r.content.heroImage, "detail"),
                  }
                : {}),
            }),
          ),
          request("/mini/products?featured=true&limit=1&page=1").then((r) =>
            this.setData({
              featured: r.items[0] ? decorate(r.items[0]) : null,
            }),
          ),
        ].map((pending) =>
          pending.then(
            () => true,
            (error) => {
              this.setData({ error: error.message });
              return false;
            },
          ),
        ),
      );
      if (results.every(Boolean)) remember(this);
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this._loading = false;
    }
  },
  toMall() {
    wx.switchTab({ url: "/pages/mall/index" });
  },
  toProduct() {
    if (this.data.featured)
      wx.navigateTo({
        url: "/pages/product/index?id=" + this.data.featured.id,
      });
  },
  toInfo() {
    wx.navigateTo({ url: "/pages/info/index" });
  },
});
