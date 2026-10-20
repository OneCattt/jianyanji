const { showDialog } = require("../../utils/dialog");
const { request } = require("../../utils/api");
const { decorate } = require("./view");
Page({
  data: {
    order: null,
    payment: null,
    error: "",
    busy: false,
    transfer: null,
    transferConfigured: false,
    canUsePoints: false,
  },
  onLoad(options) {
    this.orderId = Number(options.id);
  },
  onShow() {
    this.load();
  },
  async load() {
    if (!this.orderId) return;
    this.setData({ canUsePoints: false });
    try {
      const [payment, content, user] = await Promise.all([
        request("/orders/" + this.orderId + "/payment"),
        request("/public/content"),
        request("/me"),
      ]);
      const order = decorate(payment.order);
      const transfer = content.content;
      this.setData({
        payment,
        order,
        transfer,
        transferConfigured: !!(
          transfer &&
          transfer.companyName &&
          transfer.bankName &&
          transfer.bankAccount
        ),
        canUsePoints:
          order.canPay &&
          order.kind === "goods" &&
          ["B", "C"].includes(user.identity),
        error: "",
      });
    } catch (e) {
      this.setData({ error: e.message });
    }
  },
  back() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.redirectTo({ url: "/pages/orders/index" });
  },
  copy() {
    wx.setClipboardData({ data: this.data.order.number });
  },
  logistics() {
    wx.navigateTo({ url: "/pages/order/logistics?id=" + this.orderId });
  },
  aftersales() {
    wx.navigateTo({ url: "/pages/refunds/index?orderId=" + this.orderId });
  },
  points() {
    if (this.data.busy || !this.data.canUsePoints) return;
    wx.navigateTo({ url: "/pages/wallet/index?orderId=" + this.orderId });
  },
  async cancel() {
    if (this.data.busy || !this.data.order || !this.data.order.canPay) return;
    this.setData({ busy: true, error: "" });
    try {
      const confirm = await showDialog(this, {
        title: "取消购买",
        content:
          "取消后将释放预占库存。如已线下转款，请先联系品牌方核实款项，避免重复付款。",
        confirmText: "取消订单",
        cancelText: "保留订单",
      });
      if (!confirm.confirm) return;
      await request("/orders/" + this.orderId + "/cancel", "POST", {});
      await this.load();
    } catch (e) {
      await this.load();
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  async contact() {
    const service = this.data.transfer && this.data.transfer.customerService;
    const result = await showDialog(this, {
      mode: "contact",
      title: "联系顾问",
      content: service || "暂未提供联系方式，请联系邀请您的代理协助处理。",
      showCancel: false,
      confirmText: service ? "复制联系方式" : "我知道了",
    });
    if (result.confirm && service) wx.setClipboardData({ data: service });
  },
});
