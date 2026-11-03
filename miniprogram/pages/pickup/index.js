const { request } = require("../../utils/api");
const { showDialog } = require("../../utils/dialog");
const { pickupView } = require("../../utils/storage-view");
Page({
  data: { pickup: null, error: "", busy: false, loading: true },
  onLoad(options) {
    this.id = Number(options.id);
  },
  onShow() {
    return this.load();
  },
  async load() {
    this.setData({ loading: true });
    try {
      const pickup = await request("/pickups/" + this.id);
      this.setData({
        pickup: {
          ...pickupView(pickup),
          cancelText:
            (pickup.storedGoodsQuantity > 0 ? "原寄存恢复可提，" : "") +
            "本次已扣运费积分退回。" +
            (pickup.newGoodsQuantity > 0
              ? "新购商品继续按原购买订单处理，不自动转寄存；未发部分由总部联系安排配送，请查看原购买订单进度。"
              : "原购买商品货款不退款。"),
        },
        error: "",
      });
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  async action(e) {
    if (this.data.busy) return;
    const action = e.currentTarget.dataset.action;
    const confirmed = await showDialog(this, {
      title: action === "receive" ? "确认实际收货" : "取消未发货提货",
      content:
        action === "receive"
          ? "本次包裹所有来源商品将一起确认收货。"
          : this.data.pickup.cancelText,
      confirmText: "确认",
      cancelText: "暂不操作",
    });
    if (!confirmed.confirm) return;
    this.setData({ busy: true, error: "" });
    try {
      await request("/pickups/" + this.id + "/" + action, "POST", {});
      await this.load();
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  order(e) {
    wx.navigateTo({
      url: "/pages/order/index?id=" + e.currentTarget.dataset.id,
    });
  },
  copy() {
    wx.setClipboardData({ data: this.data.pickup.tracking });
  },
});
