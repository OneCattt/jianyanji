const { request } = require("../../utils/api");
const { decorate } = require("../../utils/catalog");
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
    try {
      const [r, p] = await Promise.all([
        request("/public/content"),
        request("/products"),
      ]);
      this.setData({
        content: r.content,
        featured: p.items.find((p) => p.featured)
          ? decorate(p.items.find((p) => p.featured))
          : null,
        error: "",
        ...(r.content
          ? {
              heroTitle: r.content.heroTitle,
              heroSubtitle: r.content.heroSubtitle,
              heroImage: r.content.heroImage,
            }
          : {}),
      });
    } catch (e) {
      this.setData({ error: e.message });
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
