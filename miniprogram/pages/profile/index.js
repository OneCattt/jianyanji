const { request } = require("../../utils/api");
const { isFresh, remember, session } = require("../../utils/state");
const destinations = {
  orders: "/pages/orders/index",
  team: "/pages/team/index",
  addresses: "/pages/addresses/index",
  onboarding: "/pages/onboarding/index",
  wallet: "/pages/wallet/index",
  rewards: "/pages/rewards/index",
  refunds: "/pages/refunds/index",
  cashRefunds: "/pages/cash-refunds/index",
  info: "/pages/info/index",
  courses: "/pages/courses/index",
  settings: "/pages/settings/index",
};
Page({
  data: {
    user: null,
    shareCode: "",
    isAgent: false,
    busy: false,
    navigating: false,
    error: "",
    walletError: "",
    availablePoints: "—",
    walletLoaded: false,
    transferConfigured: false,
    headerTop: 62,
    headerHeight: 250,
    orderShortcuts: [
      { tab: 1, name: "待核款", icon: "pending" },
      { tab: 2, name: "待发货", icon: "paid" },
      { tab: 3, name: "运输中", icon: "shipped" },
      { tab: 4, name: "已完成", icon: "completed" },
    ],
    menuItems: [
      {
        key: "team",
        title: "我的团队",
        hint: "归属店主与订货",
        icon: "members",
        principalOnly: true,
      },
      {
        key: "courses",
        title: "课程与成长权益",
        hint: "身份权益与联系报名",
        icon: "members",
      },
      {
        key: "addresses",
        title: "收货地址",
        hint: "管理收货信息",
        icon: "address",
      },
      {
        key: "onboarding",
        title: "代理入驻",
        hint: "身份权益与申请",
        icon: "members",
      },
      {
        key: "invite",
        title: "邀请伙伴",
        hint: "分享入驻申请",
        icon: "share",
        agentOnly: true,
      },
      {
        key: "rewards",
        title: "代理奖励",
        hint: "核算与结算明细",
        icon: "members",
        agentOnly: true,
      },
      {
        key: "refunds",
        title: "退款售后",
        hint: "商品订单退款记录",
        icon: "refund",
      },
      {
        key: "cashRefunds",
        title: "积分余额退款",
        hint: "公司转款退款记录",
        icon: "wallet",
        agentOnly: true,
      },
      {
        key: "info",
        title: "品牌与服务",
        hint: "品牌介绍与联系",
        icon: "service",
      },
    ],
  },
  onLoad() {
    const windowInfo = wx.getWindowInfo();
    const scale = windowInfo.windowWidth / 376;
    const capsule = wx.getMenuButtonBoundingClientRect();
    // 内容避开微信胶囊；胶囊下方继续按 Figma 的 376px 画板比例布局。
    const headerTop = Math.max(
      62 * scale,
      (capsule.bottom || windowInfo.statusBarHeight) + 12 * scale,
    );
    this.setData({
      headerTop,
      headerHeight: 250 * scale + headerTop - 62 * scale,
    });
  },
  onShow() {
    const tabBar = this.getTabBar && this.getTabBar();
    if (tabBar) tabBar.setData({ selected: 3 });
    if (!isFresh(this)) return this.refresh();
  },
  async onPullDownRefresh() {
    try {
      await this.refresh();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  setUser(user) {
    const changed = !user || !this.data.user || user.id !== this.data.user.id;
    this.setData({
      user,
      isAgent: !!user && ["B", "C"].includes(user.identity),
      ...(changed
        ? {
            availablePoints: "—",
            walletLoaded: false,
            transferConfigured: false,
            walletError: "",
            shareCode: "",
          }
        : {}),
    });
  },
  async refresh() {
    if (this._refreshing) return;
    this._refreshing = true;
    const identity = session();
    if (this._loadedSession !== undefined && this._loadedSession !== identity)
      this.setUser(null);
    this.setData({ busy: true, error: "" });
    try {
      if (!identity) {
        this.setUser(null);
        remember(this);
        return;
      }
      const user = await request("/me");
      if (session() !== identity) return;
      this.setUser(user);
      // 身份已确认即可操作入口，邀请和积分独立加载。
      this.setData({ busy: false });
      if (!this.data.isAgent) {
        this.setData({ shareCode: "" });
        remember(this);
        return;
      }
      await Promise.all([
        request("/invitations/mine")
          .then((invitation) => {
            if (session() === identity)
              this.setData({ shareCode: invitation.shareCode });
          })
          .catch((error) => {
            if (session() === identity)
              this.setData({ shareCode: "", error: error.message });
          }),
        request("/mini/wallet")
          .then((wallet) => {
            if (session() !== identity) return;
            if (
              !Number.isSafeInteger(wallet.availableCents) ||
              wallet.availableCents < 0
            )
              throw new Error("可用积分暂不可读取，请稍后重试");
            this.setData({
              availablePoints: (wallet.availableCents / 100)
                .toFixed(2)
                .replace(/\B(?=(\d{3})+(?!\d))/g, ","),
              walletLoaded: true,
              transferConfigured: !!(
                wallet.transfer && wallet.transfer.configured
              ),
              walletError: "",
            });
          })
          .catch((error) => {
            if (error.status === 401) {
              this.setUser(null);
              this.setData({ error: "登录已失效，请重新登录" });
            } else if (session() === identity)
              this.setData({ walletError: error.message });
          }),
      ]);
      if (session() === identity) remember(this);
    } catch (error) {
      if (session() === identity || error.status === 401) {
        this.setUser(null);
        this.setData({ error: error.message });
      }
    } finally {
      this._refreshing = false;
      this.setData({ busy: false });
    }
  },
  onNavigate(event) {
    if (this.data.navigating) return;
    const key = event.currentTarget.dataset.key;
    if (this.data.busy && !["settings", "info", "courses"].includes(key))
      return;
    let url = destinations[key];
    if (!url) return;
    const tab = Number(event.currentTarget.dataset.tab);
    if (key === "orders" && tab >= 1 && tab <= 4) url += "?tab=" + tab;
    if (key !== "info" && key !== "settings" && key !== "courses") {
      if (!this.data.user || !wx.getStorageSync("jyj_token")) {
        url = destinations.settings + "?next=" + encodeURIComponent(url);
      } else if (
        ["wallet", "rewards", "cashRefunds"].includes(key) &&
        !this.data.isAgent
      ) {
        return;
      }
    }
    this.setData({ navigating: true, error: "" });
    wx.navigateTo({
      url,
      fail: () => this.setData({ error: "页面暂时无法打开，请稍后重试" }),
      complete: () => this.setData({ navigating: false }),
    });
  },
  invitePath() {
    return (
      "/pages/onboarding/index?shareCode=" +
      encodeURIComponent(this.data.shareCode)
    );
  },
  onShareAppMessage() {
    return this.data.isAgent && this.data.user && this.data.shareCode
      ? { title: "邀你加入清欣简妍肌", path: this.invitePath() }
      : { title: "清欣简妍肌 · 东方肌肤管理美学", path: "/pages/home/index" };
  },
  copyInvite() {
    if (
      !this.data.shareCode ||
      !this.data.isAgent ||
      !this.data.user ||
      !wx.getStorageSync("jyj_token") ||
      this.data.busy
    )
      return;
    wx.setClipboardData({
      data: this.invitePath(),
      success: () => wx.showToast({ title: "入驻路径已复制", icon: "success" }),
      fail: () => this.setData({ error: "复制失败，请重试" }),
    });
  },
  toMall() {
    if (this.data.navigating) return;
    this.setData({ navigating: true });
    wx.switchTab({
      url: "/pages/mall/index",
      fail: () => this.setData({ error: "商城暂时无法打开，请稍后重试" }),
      complete: () => this.setData({ navigating: false }),
    });
  },
});
