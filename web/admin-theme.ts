// 后台标准控件共用的尺寸、色彩和字型；业务页面只负责字段与布局。
export const adminTheme = {
  token: {
    colorPrimary: "#8d2638",
    colorInfo: "#8d2638",
    colorText: "#252831",
    colorTextSecondary: "#687080",
    colorBgLayout: "#f5f6f8",
    colorBorder: "#d9dde5",
    borderRadius: 6,
    controlHeight: 38,
    fontSize: 14,
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif',
  },
  components: {
    Table: { padding: 14, colorFillAlter: "#f5f6f8", headerColor: "#687080" },
    Card: { paddingLG: 24, headerHeight: 52, boxShadowTertiary: "none" },
    Menu: {
      controlHeightLG: 38,
      itemHeight: 38,
      itemMarginInline: 12,
      colorItemBgSelected: "#f7edf0",
      colorItemTextSelected: "#8d2638",
    },
    Modal: { paddingContentHorizontalLG: 24 },
  },
};
