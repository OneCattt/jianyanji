const { request } = require("../../utils/api");
Component({
  data: { items: [], error: "" },
  pageLifetimes: {
    show() {
      this.load();
    },
  },
  methods: {
    async load() {
      try {
        const result = await request("/public/posts?limit=3");
        this.setData({ items: result.items, error: "" });
      } catch (e) {
        this.setData({ items: [], error: e.message });
      }
    },
    open(e) {
      wx.navigateTo({
        url: "/pages/post/index?id=" + e.currentTarget.dataset.id,
      });
    },
    all() {
      wx.switchTab({ url: "/pages/seeds/index" });
    },
  },
});
