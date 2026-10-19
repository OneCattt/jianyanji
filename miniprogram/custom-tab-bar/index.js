Component({
  data: {
    selected: 0,
    switching: false,
    tabs: [
      { path: "/pages/home/index", label: "首页", icon: "home" },
      { path: "/pages/mall/index", label: "商城", icon: "mall" },
      { path: "/pages/profile/index", label: "我的", icon: "profile" },
    ],
  },
  lifetimes: {
    attached() {
      this.syncSelection();
    },
  },
  pageLifetimes: {
    show() {
      this.syncSelection();
    },
  },
  methods: {
    syncSelection() {
      const pages = getCurrentPages();
      const page = pages[pages.length - 1];
      if (!page) return;
      const selected = this.data.tabs.findIndex(
        (tab) => tab.path === "/" + page.route,
      );
      if (selected >= 0) this.setData({ selected });
    },
    switchTab(event) {
      const index = Number(event.currentTarget.dataset.index);
      if (
        this.data.switching ||
        index === this.data.selected ||
        !this.data.tabs[index]
      )
        return;
      this.setData({ switching: true });
      wx.switchTab({
        url: this.data.tabs[index].path,
        fail: () =>
          wx.showToast({ title: "页面暂时无法打开，请重试", icon: "none" }),
        complete: () => this.setData({ switching: false }),
      });
    },
  },
});
