const { request } = require("../../utils/api");
const { decorate } = require("./view");
Page({
  data: {
    order: null,
    payment: null,
    error: "",
    busy: false,
    capabilities: { wechatAvailable: false },
    contact: "",
  },
  onLoad(options) {
    this.orderId = Number(options.id);
  },
  onShow() {
    this.load();
  },
  async load() {
    if (!this.orderId) return;
    try {
      const [payment, capabilities] = await Promise.all([
        request("/orders/" + this.orderId + "/payment"),
        request("/payments/capabilities"),
      ]);
      this.setData({
        payment,
        order: decorate(payment.order),
        capabilities,
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
    wx.navigateTo({ url: "/pages/wallet/index?orderId=" + this.orderId });
  },
  async query() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: "" });
    try {
      await request("/orders/" + this.orderId + "/payment/query", "POST", {});
      await this.load();
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  async pay() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: "" });
    try {
      const result = await request(
        "/orders/" + this.orderId + "/pay/wechat",
        "POST",
        {},
      );
      if (result.parameters) {
        // 客户端回调只触发服务端查询，绝不据此显示到账成功。
        await new Promise((resolve) =>
          wx.requestPayment({
            ...result.parameters,
            success: resolve,
            fail: resolve,
          }),
        );
      }
      await request("/orders/" + this.orderId + "/payment/query", "POST", {});
      await this.load();
    } catch (e) {
      await this.load();
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  async cancel() {
    if (this.data.busy) return;
    const confirm = await new Promise((resolve) =>
      wx.showModal({
        title: "取消购买",
        content: "将核对并关闭原交易，确认关闭后释放商品库存。",
        success: resolve,
        fail: () => resolve({ confirm: false }),
      }),
    );
    if (!confirm.confirm) return;
    this.setData({ busy: true, error: "" });
    try {
      await request(
        "/orders/" +
          this.orderId +
          (this.data.order.locked ? "/payment/close" : "/cancel"),
        "POST",
        {},
      );
      await this.load();
    } catch (e) {
      await this.load();
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  async contact() {
    try {
      const result = await request("/public/content");
      const c = result.content;
      wx.showModal({
        title: "联系顾问",
        content: (c && c.customerService) || "品牌方尚未配置客服联系方式",
        showCancel: false,
      });
    } catch (e) {
      this.setData({ error: e.message });
    }
  },
});
