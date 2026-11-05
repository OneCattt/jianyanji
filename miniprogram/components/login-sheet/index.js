const { request, authenticate, cancelledLogin } = require("../../utils/api");
const privacy = require("../../utils/privacy");
const config = require("../../config");

Component({
  data: {
    visible: false,
    stage: "consent",
    loading: false,
    busy: false,
    agreed: false,
    configured: false,
    needAuthorization: false,
    operator: "",
    contact: "",
    contractName: "《小程序用户隐私保护指引》",
    mode: "login",
    devAuth: config.devAuth,
    user: null,
    nickname: "",
    error: "",
  },
  lifetimes: {
    detached() {
      this.finish(null, cancelledLogin());
    },
  },
  pageLifetimes: {
    hide() {
      if (!this._viewingPolicy) this.finish(null, cancelledLogin());
    },
    show() {
      this._viewingPolicy = false;
    },
  },
  methods: {
    open(options = {}) {
      if (this._pending) return this._pending;
      this._account = options.account || "visitor";
      this._ticket = (this._ticket || 0) + 1;
      this._viewingPolicy = false;
      this._pending = new Promise((resolve, reject) => {
        this._resolve = resolve;
        this._reject = reject;
      });
      this.setData({
        visible: true,
        stage: "consent",
        agreed: false,
        loading: true,
        configured: false,
        busy: false,
        error: "",
        user: null,
        nickname: "",
        mode: options.mode || "login",
      });
      this.prepare();
      return this._pending;
    },
    async prepare() {
      const ticket = this._ticket;
      this.setData({ loading: true, configured: false, error: "" });
      try {
        const [result, platform] = await Promise.all([
          request("/public/content"),
          new Promise((resolve, reject) => {
            if (typeof wx.getPrivacySetting !== "function") {
              reject(new Error("请更新客户端后再登录"));
              return;
            }
            wx.getPrivacySetting({
              success: resolve,
              fail: () => reject(new Error("隐私授权状态暂时无法读取，请重试")),
            });
          }),
        ]);
        if (!this.active(ticket)) return;
        const content = result.content || {};
        const operator = (content.privacyOperator || "").trim();
        const contact = (content.privacyContact || "").trim();
        if (platform.needAuthorization && privacy.hasConsent())
          privacy.revokeConsent();
        this.setData({
          operator,
          contact,
          configured: !!(operator && contact),
          needAuthorization: !!platform.needAuthorization,
          contractName:
            platform.privacyContractName || "《小程序用户隐私保护指引》",
          stage:
            privacy.hasConsent() && !platform.needAuthorization
              ? "login"
              : "consent",
          error:
            operator && contact
              ? ""
              : "品牌方尚未配置隐私主体及联系方式，暂不能登录",
        });
      } catch (error) {
        if (this.active(ticket)) this.setData({ error: error.message });
      } finally {
        if (this.active(ticket)) this.setData({ loading: false });
      }
    },
    active(ticket) {
      return !!this._resolve && this._ticket === ticket;
    },
    toggleAgreement(event) {
      this.setData({
        agreed: event.detail.value.includes("privacy"),
        error: "",
      });
    },
    agree() {
      if (
        !this.data.agreed ||
        !this.data.configured ||
        this.data.loading ||
        this.data.busy
      )
        return;
      try {
        privacy.recordConsent(
          this.data.operator,
          this.data.contact,
          "login-privacy-agree",
        );
        this.setData({ stage: "login", needAuthorization: false, error: "" });
      } catch (error) {
        this.setData({ error: error.message });
      }
    },
    phoneLogin(event) {
      if (this.data.busy) return;
      if (!event.detail.code) {
        this.setData({ error: "手机号授权未完成，可重试或选择暂不绑定手机号" });
        return;
      }
      return this.submitLogin(event.detail.code);
    },
    plainLogin() {
      return this.submitLogin("");
    },
    async submitLogin(phoneCode) {
      if (
        this.data.busy ||
        this.data.loading ||
        this.data.stage !== "login" ||
        !this.data.configured ||
        !privacy.hasConsent()
      )
        return;
      const ticket = this._ticket;
      this.setData({ busy: true, error: "" });
      try {
        const user =
          this.data.mode === "phone"
            ? await request("/me/wechat-phone", "POST", { code: phoneCode })
            : await authenticate(this._account, phoneCode, () =>
                this.active(ticket),
              );
        if (!this.active(ticket)) return;
        if (
          this.data.mode === "phone" ||
          (user.nickname && user.nickname !== "微信用户" && user.phone)
        ) {
          this.finish(user);
        } else {
          this.setData({
            user,
            stage: "profile",
            nickname: user.nickname === "微信用户" ? "" : user.nickname || "",
          });
        }
      } catch (error) {
        if (this.active(ticket)) this.setData({ error: error.message });
      } finally {
        if (this.active(ticket)) this.setData({ busy: false });
      }
    },
    async saveProfile(event) {
      if (this.data.busy || !this.data.user) return;
      // 表单提交收集昵称组件最终值，避免绕过微信异步内容安全校验。
      const nickname = (event.detail.value.nickname || "").trim();
      if (!nickname || nickname.length > 30) {
        this.setData({ error: "请填写30字以内的昵称，或选择稍后完善" });
        return;
      }
      const ticket = this._ticket;
      this.setData({ busy: true, error: "" });
      try {
        const user = await request("/me", "PATCH", { nickname });
        if (this.active(ticket)) this.finish(user);
      } catch (error) {
        if (this.active(ticket))
          this.setData({ error: "已登录，昵称保存未完成：" + error.message });
      } finally {
        if (this.active(ticket)) this.setData({ busy: false });
      }
    },
    skipProfile() {
      if (!this.data.busy && this.data.user) this.finish(this.data.user);
    },
    cancel() {
      if (this.data.busy) return;
      if (this.data.user) this.finish(this.data.user);
      else this.finish(null, cancelledLogin());
    },
    finish(user, error) {
      const resolve = this._resolve,
        reject = this._reject;
      if (!resolve) return;
      this._resolve = this._reject = this._pending = null;
      this._ticket += 1;
      this.setData({ visible: false, busy: false });
      if (error) reject(error);
      else resolve(user);
    },
    openPolicy() {
      this._viewingPolicy = true;
      wx.navigateTo({
        url: "/pages/privacy/index",
        fail: () => {
          this._viewingPolicy = false;
          this.setData({ error: "隐私政策暂未打开，请重试" });
        },
      });
    },
    openContract() {
      this._viewingPolicy = true;
      wx.openPrivacyContract({
        fail: () => {
          this._viewingPolicy = false;
          this.setData({ error: "小程序隐私指引暂未打开，请重试" });
        },
      });
    },
    stop() {},
  },
});
