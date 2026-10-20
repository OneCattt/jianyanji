Component({
  data: {
    visible: false,
    title: "",
    content: "",
    confirmText: "确认",
    cancelText: "暂不操作",
    showCancel: true,
    mode: "confirm",
    items: [],
    selectedIndex: -1,
  },
  lifetimes: {
    detached() {
      this.finish({ confirm: false });
    },
  },
  pageLifetimes: {
    hide() {
      this.finish({ confirm: false });
    },
  },
  methods: {
    open(options) {
      this.finish({ confirm: false });
      return new Promise((resolve) => {
        this.resolve = resolve;
        this.setData({
          title: options.title || "请确认",
          content: options.content || "",
          confirmText: options.confirmText || "确认",
          cancelText: options.cancelText || "暂不操作",
          showCancel: options.showCancel !== false,
          mode: options.mode || "confirm",
          items: options.items || [],
          selectedIndex: options.selectedIndex ?? -1,
          visible: true,
        });
      });
    },
    finish(result) {
      const resolve = this.resolve;
      if (!resolve) return;
      this.resolve = null;
      this.setData({ visible: false });
      resolve(result);
    },
    confirm() {
      this.finish({ confirm: true });
    },
    cancel() {
      this.finish({ confirm: false });
    },
    select(e) {
      const tapIndex = Number(e.currentTarget.dataset.index);
      if (!Number.isInteger(tapIndex) || !this.data.items[tapIndex]) return;
      this.finish({ confirm: true, tapIndex });
    },
    stop() {},
  },
});
