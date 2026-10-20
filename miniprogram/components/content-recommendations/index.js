const { request } = require("../../utils/api");
const { isFresh, remember } = require("../../utils/state");
Component({
  data: { items: [], error: "", loading: true },
  pageLifetimes: {
    show() {
      if (!isFresh(this)) this.load();
    },
  },
  methods: {
    async load() {
      if (this._loading) return;
      this._loading = true;
      this.setData({ loading: true });
      try {
        const result = await request("/public/posts?limit=3");
        this.setData({ items: result.items, error: "" });
        remember(this);
      } catch (e) {
        this.setData({ error: e.message });
      } finally {
        this._loading = false;
        this.setData({ loading: false });
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
