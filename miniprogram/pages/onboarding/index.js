const { showDialog } = require("../../utils/dialog");
const { request, login: signIn } = require("../../utils/api");
const { money } = require("../../utils/catalog");
const { imageUrl } = require("../../utils/image");
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember } = require("../../utils/state");
const { shippingLines } = require("../../utils/fulfillment");
const key = () =>
  "onboard-" + Date.now() + "-" + Math.random().toString(36).slice(2);
const states = {
  submitted: "待积分支付开通",
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
    deliveryMode: "ship",
    selected: null,
    target: "C",
    transfer: null,
    user: null,
    address: null,
    applications: [],
    current: null,
    inviterId: "",
    shareCode: "",
    relation: null,
    ...listData("qualifications"),
    agreed: false,
    busy: false,
    error: "",
    showTransfer: false,
  },
  onLoad(options) {
    this.requestKey = key();
    if (options.inviterId) this.setData({ inviterId: options.inviterId });
    const code =
      options.shareCode || wx.getStorageSync("jyj_invitation_candidate");
    if (code) {
      wx.setStorageSync("jyj_invitation_candidate", code);
      this.setData({ shareCode: code });
    }
  },
  async onShow() {
    const chosenId = wx.getStorageSync("jyj_address");
    if (isFresh(this) && chosenId === this._chosenAddress) return;
    this.setData({ error: "" });
    try {
      const config = await request("/membership");
      const packages = config.packages.map((p) => ({
        ...p,
        rightsImageDisplay: imageUrl(p.rightsImage, "detail"),
        amountText: money(p.amountCents),
        valueText: money(p.valueCents),
        lines: p.lines.map((l) => ({ ...l, priceText: money(l.unitCents) })),
      }));
      this.setData({ packages, transfer: config.transfer });
      this.choose(this.data.target, true);
      if (wx.getStorageSync("jyj_token")) {
        const [user, addresses, apps, qualifications] = await Promise.all([
          request("/me"),
          request("/mini/address" + (chosenId ? "?id=" + chosenId : "")),
          this.loadApplications(),
          this.loadQualifications(),
        ]);
        const address = addresses.address;
        const applications = this.data.applications;
        this.setData({
          user,
          qualifications: this.data.qualifications,
          address,
          applications,
          current: applications[0] || null,
        });
        if (this.data.inviterId || this.data.shareCode)
          await this.resolveInvitation();
        this._chosenAddress = chosenId;
        if (apps && qualifications) remember(this);
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
  async loadApplications() {
    const result = await request("/mini/onboardings?limit=1&page=1");
    this.setData({
      applications: result.items.map((a) => ({
        ...a,
        statusText: states[a.status] || a.status,
        deliveryText: a.order
          ? orderStates[a.order.status] || a.order.status
          : "等待核款后安排交付",
        amountText: money(a.package_snapshot.amountCents),
      })),
    });
    return true;
  },
  loadQualifications(reset = true) {
    return loadPage(this, {
      key: "qualifications",
      reset,
      url: "/mini/qualifications",
    });
  },
  onReachBottom() {
    return this.loadQualifications(false);
  },
  choose(target, preserve = false) {
    const selected =
      this.data.packages.find((p) => p.targetIdentity === target) ||
      this.data.packages[0] ||
      null;
    if (selected)
      selected.lines = shippingLines(
        selected.lines,
        preserve &&
          this.data.selected?.targetIdentity === selected.targetIdentity
          ? this.data.selected.lines
          : [],
        this.data.deliveryMode,
      );
    this.setData({
      selected,
      target: selected ? selected.targetIdentity : target,
      agreed: false,
    });
  },
  changeTarget(e) {
    this.requestKey = key();
    this.choose(e.currentTarget.dataset.target);
    if (this.data.user && (this.data.inviterId || this.data.shareCode))
      this.resolveInvitation();
  },
  onInviter(e) {
    this.requestKey = key();
    this.setData({ inviterId: e.detail.value, shareCode: "", relation: null });
  },
  async resolveInvitation(target) {
    const targetIdentity =
      typeof target === "string" ? target : this.data.target;
    try {
      const rel = await request(
        "/invitations/candidate?targetIdentity=" +
          targetIdentity +
          (this.data.shareCode
            ? "&shareCode=" + encodeURIComponent(this.data.shareCode)
            : "&inviterId=" + encodeURIComponent(this.data.inviterId)),
      );
      this.setData({
        relation: rel,
        inviterId: String(rel.inviterId),
        error: "",
      });
      return rel;
    } catch (e) {
      this.setData({ relation: null, error: e.message });
      return null;
    }
  },
  agreement(e) {
    this.setData({ agreed: e.detail.value.length > 0 });
  },
  addresses() {
    this.requestKey = key();
    wx.navigateTo({ url: "/pages/addresses/index?select=1" });
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
  async reconfirm() {
    if (this.data.busy || !this.data.current) return;
    this.setData({ busy: true, error: "" });
    try {
      const relation = await this.resolveInvitation(
        this.data.current.target_identity,
      );
      if (!relation) return;
      const accepted = await showDialog(this, {
        title: "重新确认邀请归属",
        content:
          "邀请人：" +
          relation.inviterName +
          "；归属：" +
          relation.principalName,
        confirmText: "确认归属",
        cancelText: "返回核对",
      });
      if (!accepted.confirm) return;
      await request(
        "/onboardings/" + this.data.current.id + "/confirm",
        "POST",
        {
          inviterId: relation.inviterId,
          principalId: relation.principalId,
          relationshipsConfirmed: true,
          conditionsMet: true,
        },
      );
      await this.onShow();
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  chooseStored() {
    wx.navigateTo({ url: "/pages/storage/index?select=1" });
  },
  deliveryMode(e) {
    this.requestKey = key();
    const mode = e.detail.value;
    this.setData({
      deliveryMode: mode,
      selected: {
        ...this.data.selected,
        lines: this.data.selected.lines.map((line) => ({
          ...line,
          shipQuantity: mode === "store" ? 0 : line.quantity,
        })),
      },
    });
  },
  shipQuantity(e) {
    const index = Number(e.currentTarget.dataset.index),
      quantity = Number(e.detail.value);
    if (
      !Number.isInteger(quantity) ||
      quantity < 0 ||
      quantity > this.data.selected.lines[index].quantity
    ) {
      this.setData({ error: "发货数量超出固定产品包数量" });
      return;
    }
    this.requestKey = key();
    this.setData({ ["selected.lines[" + index + "].shipQuantity"]: quantity });
    this.setData({
      deliveryMode: this.data.selected.lines.some(
        (line) => line.shipQuantity > 0,
      )
        ? "ship"
        : "store",
    });
  },
  async purchaseWithPoints() {
    if (this.data.busy || !this.data.current) return;
    this.setData({ busy: true, error: "" });
    try {
      const order = await request(
        "/onboardings/" + this.data.current.id + "/order",
        "POST",
        {},
      );
      wx.navigateTo({ url: "/pages/wallet/index?orderId=" + order.id });
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
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
      inviterId === null ||
      !Number.isSafeInteger(inviterId) ||
      inviterId < 1
    ) {
      this.setData({ error: "请提供有效代理分享码或邀请人编号" });
      return;
    }
    this.setData({ busy: true, error: "" });
    try {
      const relation = await this.resolveInvitation();
      if (!relation) return;
      const confirmed = await showDialog(this, {
        title: "确认本次入驻资格与归属",
        content:
          "直接邀请人：" +
          relation.inviterName +
          "；归属主理人：" +
          relation.principalName +
          "。请确认符合所示入驻条件。",
        confirmText: "确认归属",
        cancelText: "返回核对",
      });
      if (!confirmed.confirm) return;
      await request("/onboardings", "POST", {
        targetIdentity: this.data.target,
        deliveryMode: this.data.deliveryMode,
        storedItems: wx.getStorageSync("jyj_pickup_selection") || [],
        shipQuantities: this.data.selected.lines.map(
          (line) => line.shipQuantity,
        ),
        addressId: this.data.address.id,
        inviterId: relation.inviterId,
        shareCode: this.data.shareCode,
        principalId: relation.principalId,
        relationshipsConfirmed: true,
        conditionsMet: true,
        agreementAccepted: true,
        requestKey: this.requestKey,
      });
      await this.onShow();
      this.setData({ showTransfer: true });
      wx.removeStorageSync("jyj_pickup_selection");
      wx.showToast({ title: "申请已提交，请扣积分购买权益包", icon: "none" });
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
});
