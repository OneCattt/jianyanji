const { request } = require("../../utils/api");
const privacy = require("../../utils/privacy");
const policy = require("../../utils/privacy-policy");

Page({
  data: {
    policy,
    consentMode: false,
    agreed: false,
    operator: "",
    contact: "",
    loading: false,
    configured: false,
    platformReady: false,
    needAuthorization: false,
    contractName: "《小程序用户隐私保护指引》",
    busy: false,
    error: "",
  },
  onLoad(options) {
    this.setData({ consentMode: options.consent === "1" });
    this.section = options.section === "rights" ? "rights" : "";
    return this.load();
  },
  onReady() {
    if (this.section)
      wx.pageScrollTo({ selector: "#" + this.section, duration: 0 });
  },
  onUnload() {
    this._unloaded = true;
    if (!this.data.consentMode) return;
    if (this._accepted) privacy.finishConsent();
    else privacy.rejectConsent();
  },
  async load() {
    if (this.data.loading) return;
    this.setData({
      loading: true,
      error: "",
      configured: false,
      platformReady: false,
    });
    try {
      const [result, platform] = await Promise.all([
        request("/public/content"),
        this.data.consentMode ? this.platformSetting() : Promise.resolve(null),
      ]);
      if (this._unloaded) return;
      const content = result.content || {};
      const operator = (content.privacyOperator || "").trim();
      const contact = (content.privacyContact || "").trim();
      this.setData({
        operator,
        contact,
        configured: !!(operator && contact),
        platformReady: !!platform,
        needAuthorization: !!(platform && platform.needAuthorization),
        contractName:
          (platform && platform.privacyContractName) || this.data.contractName,
      });
    } catch (error) {
      if (!this._unloaded) this.setData({ error: error.message });
    } finally {
      if (!this._unloaded) this.setData({ loading: false });
    }
  },
  platformSetting() {
    return new Promise((resolve, reject) => {
      if (typeof wx.getPrivacySetting !== "function") {
        reject(new Error("当前微信版本暂不支持隐私授权，请升级微信后重试"));
        return;
      }
      wx.getPrivacySetting({
        success: resolve,
        fail: () => reject(new Error("微信隐私授权状态暂不可读取，请重试")),
      });
    });
  },
  toggleAgreement(event) {
    this.setData({ agreed: event.detail.value.includes("privacy"), error: "" });
  },
  openContract() {
    if (typeof wx.openPrivacyContract !== "function") {
      this.setData({
        error: "当前微信版本暂不支持查看平台隐私指引，请升级微信后重试",
      });
      return;
    }
    wx.openPrivacyContract({
      fail: () =>
        this.setData({ error: "微信隐私指引暂未打开，请重试或联系品牌客服" }),
    });
  },
  agree() {
    if (
      this.data.busy ||
      !this.data.consentMode ||
      !this.data.agreed ||
      !this.data.configured ||
      !this.data.platformReady
    )
      return;
    this.setData({ busy: true, error: "" });
    try {
      privacy.recordConsent(this.data.operator, this.data.contact);
      this._accepted = true;
      wx.navigateBack({
        success: () => privacy.finishConsent(),
        fail: () =>
          this.setData({
            busy: false,
            error: "已同意，请使用左上角返回后继续",
          }),
      });
    } catch (error) {
      this.setData({ busy: false, error: error.message });
    }
  },
  decline() {
    if (this.data.busy) return;
    if (this._accepted) privacy.revokeConsent();
    else privacy.rejectConsent();
    this._accepted = false;
    wx.navigateBack({ fail: () => wx.reLaunch({ url: "/pages/home/index" }) });
  },
  copyContact() {
    if (!this.data.contact) return;
    wx.setClipboardData({
      data: this.data.contact,
      fail: () => this.setData({ error: "复制未完成，可长按上方联系方式复制" }),
    });
  },
  contactRights() {
    wx.pageScrollTo({ selector: "#rights", duration: 200 });
  },
});
