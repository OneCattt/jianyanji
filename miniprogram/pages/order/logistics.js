const { request } = require("../../utils/api");
const { decorate, dateText } = require("./view");
Page({
  data: { order: null, shipments: [], error: "", busy: false },
  onLoad(options) {
    this.orderId = Number(options.id);
  },
  onShow() {
    this.load();
  },
  async load() {
    try {
      const payment = await request("/orders/" + this.orderId + "/payment");
      this.setData({
        order: decorate(payment.order),
        shipments: payment.order.shipments.map((s) => ({
          ...s,
          shippedText: dateText(s.shipped_at),
          receivedText: dateText(s.received_at),
          canReceive:
            !s.received_at &&
            ["paid", "partially_refunded"].includes(
              payment.order.payment_state,
            ),
        })),
        error: "",
      });
    } catch (e) {
      this.setData({ error: e.message });
    }
  },
  copy(e) {
    wx.setClipboardData({ data: e.currentTarget.dataset.tracking });
  },
  back() {
    wx.navigateBack();
  },
  async receive(e) {
    if (this.data.busy) return;
    const confirm = await new Promise((resolve) =>
      wx.showModal({
        title: "确认收货",
        content: "请确认已收到此包裹的剩余未退商品；已退款商品不重复计入收货。",
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
          "/shipments/" +
          e.currentTarget.dataset.id +
          "/receive",
        "POST",
        {},
      );
      await this.load();
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
});
