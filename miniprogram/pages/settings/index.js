const { request, login } = require("../../utils/api");
const config = require("../../config");
const accounts = ["visitor", "store", "principal"];
Page({
  data: {
    user: null,
    nickname: "",
    phone: "",
    busy: false,
    error: "",
    devAuth: config.devAuth,
    accountIndex: 0,
    accounts: ["游客", "调肤店主", "调肤主理人"],
  },
  onLoad(options) {
    // 只接受首期已有页面作为登录后的去向。
    const next = options.next ? decodeURIComponent(options.next) : "";
    this.next =
      /^\/pages\/(orders|addresses|onboarding|wallet|rewards|refunds|cash-refunds)\/index$/.test(
        next,
      ) ||
      /^\/pages\/orders\/index\?tab=[1-4]$/.test(next) ||
      /^\/pages\/onboarding\/index\?inviterId=[1-9]\d*$/.test(next)
        ? next
        : "";
  },
  async onShow() {
    if (this.data.busy) return;
    this.setData({ error: "" });
    if (!wx.getStorageSync("jyj_token")) {
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
      nickname: user ? user.nickname : "",
      phone: user ? user.phone : "",
    });
  },
  onNickname(event) {
    this.setData({ nickname: event.detail.value });
  },
  onPhone(event) {
    this.setData({ phone: event.detail.value });
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
    const agentOnly = /\/pages\/(wallet|rewards|cash-refunds)\//.test(
      this.next,
    );
    if (agentOnly && !["B", "C"].includes(this.data.user.identity)) {
      this.setData({
        error:
          "该入口仅对调肤店主、调肤主理人开放；游客可在商城微信付款购买商品",
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
  async onSave() {
    if (this.data.busy || !this.data.user) return;
    const nickname = this.data.nickname.trim(),
      phone = this.data.phone.trim();
    if (!nickname) {
      this.setData({ error: "请填写昵称" });
      return;
    }
    if (!/^$|^1[3-9]\d{9}$/.test(phone)) {
      this.setData({ error: "请填写正确的联系电话" });
      return;
    }
    this.setData({ busy: true, error: "" });
    try {
      this.setUser(await request("/me", "PATCH", { nickname, phone }));
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
      wx.removeStorageSync("jyj_token");
      this.setUser(null);
      wx.showToast({ title: "已退出登录", icon: "success" });
    } catch (error) {
      this.setData({ error: error.message });
      if (error.status === 401) this.setUser(null);
    } finally {
      this.setData({ busy: false });
    }
  },
});
