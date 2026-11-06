const { request, login } = require("../../utils/api");
const { isFresh, remember, session } = require("../../utils/state");
const { loadBadge } = require("../../utils/badges");
const destinations = {
  offers: "/pages/offers/index",
  storage: "/pages/storage/index",
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

/** 只保留尚未收完的权益包；全部收货后不再占用个人中心版面。 */
function pendingPackages(items) {
  return (items || []).filter((item) => {
    const consumed = Number(item.consumed_qty);
    const total = Number(item.total_qty);
    return (
      Number.isFinite(consumed) && Number.isFinite(total) && consumed < total
    );
  });
}

/** 单个权益包展示具体进度，多个时只汇总数量；全部收货后返回空。 */
function pendingPackageText(items) {
  const pending = pendingPackages(items);
  if (!pending.length) return "";
  if (pending.length > 1) return pending.length + " 个权益包待收货";
  const item = pending[0];
  return (
    (item.target_identity === "B" ? "19800" : "2980") +
    "权益包 " +
    item.consumed_qty +
    "/" +
    item.total_qty +
    " 件待收货"
  );
}

Page({
  data: {
    user: null,
    packagePendingText: "",
    avatarText: "妍",
    shareCode: "",
    isAgent: false,
    busy: false,
    navigating: false,
    error: "",
    walletError: "",
    availablePoints: "—",
    walletLoaded: false,
    transferConfigured: false,
    orderCounts: null,
    orderCountsError: "",
    headerTop: 62,
    headerHeight: 250,
    orderShortcuts: [
      { tab: 1, name: "待付款", icon: "pending" },
      { tab: 2, name: "待发货", icon: "paid" },
      { tab: 3, name: "运输中", icon: "shipped" },
      { tab: 4, name: "已完成", icon: "completed" },
    ],
    menuItems: [
      {
        key: "offers",
        title: "活动套餐",
        hint: "固定套餐与买赠优惠",
        icon: "gift",
      },
      {
        key: "storage",
        title: "我的寄存",
        hint: "寄存货品、提货申请与记录",
        icon: "storage",
      },
      {
        key: "wallet",
        title: "积分钱包",
        hint: "微信充值到账后积分购物",
        icon: "wallet",
      },
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
    if (tabBar) tabBar.setData({ selected: 2 });
    const profile = !isFresh(this) ? this.refresh() : Promise.resolve();
    return Promise.all([profile, loadBadge(this, "orders")]);
  },
  loadOrderCounts() {
    return loadBadge(this, "orders", true);
  },
  async onPullDownRefresh() {
    try {
      await Promise.all([this.refresh(), loadBadge(this, "orders", true)]);
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  setUser(user) {
    const changed = !user || !this.data.user || user.id !== this.data.user.id;
    const upgrade = !!user && user.identity === "C";
    this.setData({
      user,
      ...(!user
        ? {
            orderCounts: null,
            packagePendingText: "",
          }
        : {}),
      avatarText: Array.from(((user && user.nickname) || "").trim())[0] || "妍",
      isAgent: !!user && ["B", "C"].includes(user.identity),
      menuItems: this.data.menuItems.map((item) =>
        item.key === "onboarding"
          ? {
              ...item,
              title: upgrade ? "升级19800店主" : "代理入驻",
              hint: upgrade ? "另购完整19800权益包" : "身份权益与申请",
            }
          : item,
      ),
      ...(changed
        ? {
            availablePoints: "—",
            walletLoaded: false,
            transferConfigured: false,
            walletError: "",
            shareCode: "",
            packagePendingText: "",
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
      request("/mini/package-consumption?limit=100")
        .then((result) => {
          if (session() !== identity) return;
          this.setData({
            packagePendingText: pendingPackageText(
              Array.isArray(result.items) ? result.items : [],
            ),
          });
        })
        .catch(() => {});
      // 身份已确认即可操作入口，邀请和积分独立加载。
      this.setData({ busy: false });
      if (!this.data.isAgent) {
        this.setData({ shareCode: "" });
        try {
          const wallet = await request("/mini/wallet");
          if (session() !== identity) return;
          this.setData({
            availablePoints: (wallet.availableCents / 100).toFixed(2),
            walletLoaded: true,
            transferConfigured: !!(
              wallet.transfer && wallet.transfer.configured
            ),
            walletError: "",
          });
        } catch (error) {
          if (session() === identity)
            this.setData({ walletError: error.message });
        }
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
  async onNavigate(event) {
    if (this.data.navigating) return;
    const key = event.currentTarget.dataset.key;
    if (this.data.busy && !["settings", "info", "courses"].includes(key))
      return;
    let url = destinations[key];
    if (!url) return;
    const tab = Number(event.currentTarget.dataset.tab);
    if (key === "orders" && tab >= 1 && tab <= 4) url += "?tab=" + tab;
    if (
      !["info", "courses"].includes(key) &&
      (!this.data.user || !wx.getStorageSync("jyj_token"))
    ) {
      this.setData({ navigating: true, error: "" });
      try {
        this.setUser(await login());
        await this.refresh();
      } catch (error) {
        if (error.code !== "LOGIN_CANCELLED")
          this.setData({ error: error.message });
        return;
      } finally {
        this.setData({ navigating: false });
      }
      if (key === "settings") return;
    }
    if (key === "rewards" && !this.data.isAgent) return;
    if (key === "onboarding" && this.data.user?.identity === "C")
      url += "?target=B";
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
      ? {
          title: "邀你加入清欣简妍肌",
          path: this.invitePath(),
          imageUrl: "/assets/brand/invitation-cover-v1.jpg",
        }
      : {
          title: "清欣简妍肌 · 东方肌肤管理美学",
          path: "/pages/home/index",
          imageUrl: "/assets/brand/app-icon.png",
        };
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
