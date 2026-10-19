const { request, login } = require("../../utils/api");
const config = require("../../config");
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
  async onShow() {
    if (!wx.getStorageSync("jyj_token")) {
      this.setData({ user: null });
      return;
    }
    try {
      this.setUser(await request("/me"));
    } catch (error) {
      this.setData({
        error: error.message,
        ...(error.status === 401 ? { user: null } : {}),
      });
    }
  },
  setUser(user) {
    this.setData({
      user,
      nickname: user.nickname,
      phone: user.phone,
      error: "",
    });
  },
  async onLogin() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: "" });
    try {
      this.setUser(
        await login(["visitor", "store", "principal"][this.data.accountIndex]),
      );
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  onNickname(e) {
    this.setData({ nickname: e.detail.value });
  },
  onPhone(e) {
    this.setData({ phone: e.detail.value });
  },
  onAccount(e) {
    this.setData({ accountIndex: Number(e.detail.value) });
  },
  async onSave() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: "" });
    try {
      this.setUser(
        await request("/me", "PATCH", {
          nickname: this.data.nickname,
          phone: this.data.phone,
        }),
      );
      wx.showToast({ title: "个人资料已保存", icon: "success" });
    } catch (error) {
      this.setData({ error: error.message });
      if (error.status === 401) this.setData({ user: null });
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
      this.setData({ user: null });
    } catch (error) {
      this.setData({ error: error.message });
      if (error.status === 401) this.setData({ user: null });
    } finally {
      this.setData({ busy: false });
    }
  },
});
