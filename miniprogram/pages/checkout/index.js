const { request } = require("../../utils/api");
const { money } = require("../../utils/catalog");
Page({
  data: { quote: null, address: null, error: "", busy: false },
  async onShow() {
    this.items = wx.getStorageSync("jyj_checkout") || [];
    if (!this.items.length) {
      this.setData({ error: "请先选择商品" });
      return;
    }
    try {
      const addresses = await request("/addresses"),
        selected = wx.getStorageSync("jyj_address");
      const address = addresses.find((a) => a.id === selected) || addresses[0];
      this.setData({ address, quote: null, error: "" });
      if (address) await this.quote();
    } catch (e) {
      this.setData({ error: e.message });
    }
  },
  async quote() {
    const q = await request("/checkout/quote", "POST", {
      addressId: this.data.address.id,
      items: this.items,
    });
    this.requestKey =
      "order-" + Date.now() + "-" + Math.random().toString(36).slice(2);
    this.setData({
      quote: {
        ...q,
        goodsText: money(q.goodsCents),
        shippingText: money(q.shippingCents),
        totalText: money(q.totalCents),
        lines: q.lines.map((l) => ({ ...l, priceText: money(l.unitCents) })),
      },
      error: "",
    });
  },
  addresses() {
    wx.navigateTo({ url: "/pages/addresses/index?select=1" });
  },
  async submit() {
    if (this.data.busy || !this.data.quote) return;
    this.setData({ busy: true, error: "" });
    try {
      const o = await request("/orders", "POST", {
        quoteId: this.data.quote.id,
        requestKey: this.requestKey,
      });
      wx.redirectTo({ url: "/pages/order/index?id=" + o.id });
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
});
