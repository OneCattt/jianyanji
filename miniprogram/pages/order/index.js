const { showDialog } = require("../../utils/dialog");
const { request } = require("../../utils/api");
const { decorate, decorateCombinedPickup } = require("./view");
Page({
  data: {
    order: null,
    payment: null,
    error: "",
    busy: false,
    transfer: null,
    transferConfigured: false,
    canUsePoints: false,
    hasCombined: false,
    combinedPickup: null,
    combinedLoading: false,
    combinedError: "",
  },
  onLoad(options) {
    this.orderId = Number(options.id);
  },
  onShow() {
    this.load();
  },
  async load() {
    if (!this.orderId) return;
    const revision = (this.loadRevision = (this.loadRevision || 0) + 1);
    this.setData({ canUsePoints: false });
    try {
      const [payment, content, user] = await Promise.all([
        request("/orders/" + this.orderId + "/payment"),
        request("/public/content"),
        request("/me"),
      ]);
      if (revision !== this.loadRevision) return;
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
        canUsePoints: order.canPay && ["goods", "package"].includes(order.kind),
        error: "",
        hasCombined: !!order.snapshot.storedItems?.length,
        combinedPickup: null,
        combinedError: "",
        combinedLoading: false,
      });
      await this.loadCombined(order, revision);
    } catch (e) {
      if (revision === this.loadRevision) this.setData({ error: e.message });
    }
  },
  async loadCombined(order, revision) {
    if (!order.snapshot.storedItems?.length) return;
    this.setData({ combinedLoading: true, combinedError: "" });
    try {
      // 后端按提货单ID升序返回；建单时生成的首发合并单先于后续分次提货。
      const first = order.pickups[0];
      if (!first) throw Error("暂未找到合并提货单，请在我的寄存查看提货记录");
      const pickup = await request("/pickups/" + first.id);
      if (Number(pickup.purchase_order_id) !== Number(order.id))
        throw Error("合并提货单与本订单不一致，请重新查询");
      if (revision === this.loadRevision)
        this.setData({ combinedPickup: decorateCombinedPickup(pickup) });
    } catch (e) {
      if (revision === this.loadRevision)
        this.setData({ combinedError: e.message });
    } finally {
      if (revision === this.loadRevision)
        this.setData({ combinedLoading: false });
    }
  },
  retryCombined() {
    if (this.data.combinedLoading || !this.data.order) return;
    return this.loadCombined(this.data.order, this.loadRevision);
  },
  back() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.redirectTo({ url: "/pages/orders/index" });
  },
  copy() {
    wx.setClipboardData({ data: this.data.order.number });
  },
  pickup(e) {
    wx.navigateTo({
      url: "/pages/pickup/index?id=" + e.currentTarget.dataset.id,
    });
  },
  storage() {
    wx.navigateTo({ url: "/pages/storage/index" });
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
        content: "取消后释放未付款订单的商品及寄存提货占用，不扣积分。",
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
