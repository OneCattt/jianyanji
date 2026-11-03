const { request, login: signIn } = require("../../utils/api");
Page({
  data: {
    loading: true,
    rights: [],
    current: null,
    loggedIn: false,
    error: "",
    mineError: "",
  },
  async onShow() {
    this.setData({ loading: true, error: "", mineError: "", current: null });
    const loggedIn = !!wx.getStorageSync("jyj_token");
    this.setData({ loggedIn });
    try {
      const overview = await request("/public/course-rights");
      const names = { D: "游客", C: "调肤店主", B: "调肤主理人" };
      this.setData({
        rights: ["D", "C", "B"]
          .filter((id) => overview.rights[id])
          .map((id) => ({
            ...overview.rights[id],
            identity: id,
            identityName: names[id],
          })),
      });
      if (loggedIn) {
        try {
          this.setData({ current: await request("/course-rights") });
        } catch (e) {
          this.setData({
            mineError: e.message,
            ...(e.status === 401 ? { loggedIn: false } : {}),
          });
        }
      }
    } catch (e) {
      this.setData({ rights: [], error: e.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  copy(e) {
    const value = e.currentTarget.dataset.contact;
    if (value) wx.setClipboardData({ data: value });
  },
  async login() {
    try {
      await signIn();
      await this.onShow();
    } catch (error) {
      if (error.code !== "LOGIN_CANCELLED")
        this.setData({ error: error.message });
    }
  },
});
