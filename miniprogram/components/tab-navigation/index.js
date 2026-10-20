const windowInfo = wx.getWindowInfo();
const capsule = wx.getMenuButtonBoundingClientRect();
const statusBarHeight = windowInfo.statusBarHeight || 0;

Component({
  properties: {
    title: String,
    brand: Boolean,
  },
  data: {
    statusBarHeight,
    // 首次绘制前计算胶囊区域，切换页签时不再经过系统导航栏。
    navigationHeight: Math.max(
      statusBarHeight + 44,
      capsule.bottom + Math.max(0, capsule.top - statusBarHeight),
    ),
  },
});
