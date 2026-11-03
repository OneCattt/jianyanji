const { request, login } = require("../../utils/api");
const config = require("../../config");
const privacy = require("../../utils/privacy");
const { invalidate } = require("../../utils/state");
const { showDialog } = require("../../utils/dialog");
const accounts = ["visitor", "store", "principal"];
Page({
  data: {
    user: null,
    avatarText: "妍",
    nickname: "",
    phone: "",
    busy: false,
    error: "",
    privacyConsented: false,
    devAuth: config.devAuth,
    accountIndex: 0,
    accounts: ["游客", "调肤店主", "调肤主理人"],
  },
  onLoad(options) {
    // 只接受首期已有页面作为登录后的去向。
    const next = options.next ? decodeURIComponent(options.next) : "";
    this.next =
      /^\/pages\/(orders|addresses|onboarding|wallet|rewards|refunds|cash-refunds|storage|offers)\/index$/.test(
        next,
      ) ||
      /^\/pages\/orders\/index\?tab=[1-4]$/.test(next) ||
      /^\/pages\/onboarding\/index\?inviterId=[1-9]\d*$/.test(next)
        ? next
        : "";
  },
  async onShow() {
    this.setData({ privacyConsented: privacy.hasConsent() });
    if (this.data.busy) return;
    this.setData({ error: "" });
    if (!privacy.hasConsent() || !wx.getStorageSync("jyj_token")) {
      this.setUser(null);
      return;
    }
    this.setData({ busy: true });
    try {
      this.setUser(await request("/me"));
    } catch (error) {
      this.setData({ error: error.message });
      if (error.status === 401) this.setUser(null);
    } finally {
      this.setData({ busy: false });
    }
    if (this.data.user && this.next) this.continueAfterLogin();
  },
  setUser(user) {
    this.setData({
      user,
      avatarText: Array.from(((user && user.nickname) || "").trim())[0] || "妍",
      nickname: user ? user.nickname : "",
      phone: user ? user.phone : "",
    });
  },
  onNickname(event) {
    // 仅保留页面草稿；实际保存仍取微信昵称组件提交的最终值。
    this.setData({ nickname: event.detail.value });
  },
  onAccount(event) {
    if (!this.data.busy)
      this.setData({ accountIndex: Number(event.detail.value) });
  },
  async onLogin() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: "" });
    let loggedIn = false;
    try {
      this.setUser(await login(accounts[this.data.accountIndex]));
      loggedIn = true;
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ busy: false });
    }
    if (loggedIn && this.next) this.continueAfterLogin();
  },
  continueAfterLogin() {
    if (!this.next) return;
    const agentOnly = /\/pages\/(rewards)\//.test(this.next);
    if (agentOnly && !["B", "C"].includes(this.data.user.identity)) {
      this.setData({
        error: "奖励入口仅对有效店主开放；普通顾客也可充值积分购物和寄存提货",
      });
      this.next = "";
      return;
    }
    const url = this.next;
    this.next = "";
    wx.redirectTo({
      url,
      fail: () =>
        this.setData({ error: "登录成功，页面暂未打开，请返回我的页重试" }),
    });
  },
  async bindPhone() {
    if (this.data.busy || !this.data.user) return;
    const nickname = this.data.nickname;
    this.setData({ busy: true, error: "" });
    try {
      this.setUser(await login("visitor", { mode: "phone" }));
      this.setData({ nickname });
      wx.showToast({ title: "手机号已绑定", icon: "success" });
    } catch (error) {
      if (error.code !== "LOGIN_CANCELLED")
        this.setData({ error: error.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  async onSave(event) {
    if (this.data.busy || !this.data.user) return;
    const nickname = (event.detail.value.nickname || "").trim();
    if (!nickname) {
      this.setData({ error: "请填写昵称" });
      return;
    }
    this.setData({ busy: true, error: "" });
    try {
      this.setUser(await request("/me", "PATCH", { nickname }));
      wx.showToast({ title: "个人资料已保存", icon: "success" });
    } catch (error) {
      this.setData({ error: error.message });
      if (error.status === 401) this.setUser(null);
    } finally {
      this.setData({ busy: false });
    }
  },
  async onLogout() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: "" });
    try {
      await request("/auth/logout", "POST");
      wx.showToast({ title: "已退出登录", icon: "success" });
    } catch (error) {
      this.setData({
        error: "已退出本机登录；服务端退出未确认，旧会话将按有效期失效",
      });
    } finally {
      privacy.clearLocalSession();
      invalidate();
      this.setUser(null);
      this.setData({ busy: false });
    }
  },
  openPrivacy() {
    wx.navigateTo({ url: "/pages/privacy/index" });
  },
  privacyRights() {
    wx.navigateTo({ url: "/pages/privacy/index?section=rights" });
  },
  async withdrawPrivacy() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: "" });
    try {
      const result = await showDialog(this, {
        title: "撤回隐私同意",
        content:
          "撤回后将退出登录，清除本机登录和业务选择，并返回首页。你仍可匿名浏览；已有订单、积分和退款不会取消，需继续办理时可联系品牌方。再次使用个人服务需重新同意并登录。",
        confirmText: "撤回并退出",
        cancelText: "保留同意",
      });
      if (!result.confirm) return;
      // 先发出原会话退出请求；断网或在途失败不妨碍本机立即撤回。
      if (wx.getStorageSync("jyj_token"))
        request("/auth/logout", "POST").catch(() => {});
      privacy.revokeConsent();
      invalidate();
      this.setUser(null);
      this.setData({ privacyConsented: false });
      wx.reLaunch({
        url: "/pages/home/index",
        fail: () =>
          this.setData({
            error: "已撤回同意并清除本机登录，请关闭后重新打开小程序",
          }),
      });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ busy: false });
    }
  },
});
