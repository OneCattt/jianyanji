const { request } = require("../../utils/api");
Page({
  data: { content: null, error: "" },
  async onShow() {
    try {
      const r = await request("/public/content");
      this.setData({ content: r.content, error: "" });
    } catch (e) {
      this.setData({ error: e.message });
    }
  },
  openPrivacy() {
    wx.navigateTo({ url: "/pages/privacy/index" });
  },
});
