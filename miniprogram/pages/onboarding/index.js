const { request } = require("../../utils/api");
const { money } = require("../../utils/catalog");
const key = () =>
  "onboard-" + Date.now() + "-" + Math.random().toString(36).slice(2);
const states = {
  submitted: "等待核款 / 身份审批",
  approved: "身份已启用",
  rejected: "申请未通过",
  revoked: "本次身份已撤销",
};
const orderStates = {
  paid: "待安排发货",
  shipped: "商品已发出",
  completed: "已确认收货",
  refunded: "商品已退款",
};
Page({
  data: {
    packages: [],
    selected: null,
    target: "C",
    transfer: null,
    user: null,
    address: null,
    applications: [],
    current: null,
    inviterId: "",
    agreed: false,
    busy: false,
    error: "",
    showTransfer: false,
  },
  onLoad(options) {
    this.requestKey = key();
    if (options.inviterId) this.setData({ inviterId: options.inviterId });
  },
  async onShow() {
    this.setData({ error: "" });
    try {
      const config = await request("/membership");
      const packages = config.packages.map((p) => ({
        ...p,
        amountText: money(p.amountCents),
        valueText: money(p.valueCents),
        lines: p.lines.map((l) => ({ ...l, priceText: money(l.unitCents) })),
      }));
      this.setData({ packages, transfer: config.transfer });
      this.choose(this.data.target);
      if (wx.getStorageSync("jyj_token")) {
        const [user, addresses, apps] = await Promise.all([
          request("/me"),
          request("/addresses"),
          request("/onboardings"),
        ]);
        const chosenId = wx.getStorageSync("jyj_address");
        const address =
          addresses.find((a) => a.id === chosenId) || addresses[0] || null;
        const applications = apps.map((a) => ({
          ...a,
          statusText: states[a.status] || a.status,
          deliveryText: a.order
            ? orderStates[a.order.status] || a.order.status
            : "等待核款后安排交付",
          amountText: money(a.package_snapshot.amountCents),
        }));
        this.setData({
          user,
          address,
          applications,
          current: applications[0] || null,
        });
      } else
        this.setData({
          user: null,
          address: null,
          applications: [],
          current: null,
        });
    } catch (e) {
      this.setData({ error: e.message });
    }
  },
  choose(target) {
    const selected =
      this.data.packages.find((p) => p.targetIdentity === target) ||
      this.data.packages[0] ||
      null;
    this.setData({
      selected,
      target: selected ? selected.targetIdentity : target,
      agreed: false,
    });
  },
  changeTarget(e) {
    this.requestKey = key();
    this.choose(e.currentTarget.dataset.target);
  },
  onInviter(e) {
    this.requestKey = key();
    this.setData({ inviterId: e.detail.value });
  },
  agreement(e) {
    this.setData({ agreed: e.detail.value.length > 0 });
  },
  addresses() {
    this.requestKey = key();
    wx.navigateTo({ url: "/pages/addresses/index?select=1" });
  },
  login() {
    const next =
      "/pages/onboarding/index" +
      (this.data.inviterId ? "?inviterId=" + this.data.inviterId : "");
    wx.navigateTo({
      url: "/pages/settings/index?next=" + encodeURIComponent(next),
    });
  },
  close() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.switchTab({ url: "/pages/home/index" });
  },
  transfer() {
    this.setData({ showTransfer: !this.data.showTransfer });
  },
  order() {
    if (this.data.current && this.data.current.order)
      wx.navigateTo({
        url: "/pages/order/index?id=" + this.data.current.order.id,
      });
  },
  async submit() {
    if (this.data.busy || !this.data.selected) return;
    if (!this.data.user) {
      this.login();
      return;
    }
    if (!this.data.agreed) {
      this.setData({ error: "请先阅读并同意代理入驻协议" });
      return;
    }
    if (!this.data.address) {
      this.setData({ error: "请先选择产品包收货地址" });
      return;
    }
    const inviterId = this.data.inviterId ? Number(this.data.inviterId) : null;
    if (
      inviterId !== null &&
      (!Number.isSafeInteger(inviterId) || inviterId < 1)
    ) {
      this.setData({ error: "请输入有效的直接邀请人编号，未受邀请可留空" });
      return;
    }
    this.setData({ busy: true, error: "" });
    try {
      await request("/onboardings", "POST", {
        targetIdentity: this.data.target,
        addressId: this.data.address.id,
        inviterId,
        agreementAccepted: true,
        requestKey: this.requestKey,
      });
      await this.onShow();
      this.setData({ showTransfer: true });
      wx.showToast({ title: "申请已提交，待核款审批", icon: "none" });
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
});
