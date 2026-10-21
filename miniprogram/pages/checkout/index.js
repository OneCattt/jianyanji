const { request } = require("../../utils/api");
const { money } = require("../../utils/catalog");
const { imageUrl } = require("../../utils/image");
Page({
  data: {
    quote: null,
    address: null,
    error: "",
    busy: false,
    sales: null,
    hasSalesCode: false,
    salesConfirmed: false,
  },
  async onShow() {
    this.items = wx.getStorageSync("jyj_checkout") || [];
    if (!this.items.length) {
      this.setData({ error: "请先选择商品" });
      return;
    }
    this.salesCode = wx.getStorageSync("jyj_sales_candidate") || "";
    this.setData({
      hasSalesCode: !!this.salesCode,
      sales: null,
      salesConfirmed: false,
      quote: null,
      error: "",
    });
    try {
      const selected = wx.getStorageSync("jyj_address");
      const { address } = await request(
        "/mini/address" + (selected ? "?id=" + selected : ""),
      );
      this.setData({ address });
      if (this.salesCode) {
        const sales = await request(
          "/sales/candidate?shareCode=" + encodeURIComponent(this.salesCode),
        );
        this.setData({ sales, salesConfirmed: false, quote: null });
      }
      if (address && !this.salesCode) await this.quote();
    } catch (e) {
      this.setData({ error: e.message });
    }
  },
  async quote() {
    const q = await request("/checkout/quote", "POST", {
      addressId: this.data.address.id,
      items: this.items,
      salesCode: this.salesCode || "",
      salesConfirmed: this.data.salesConfirmed,
    });
    this.requestKey =
      "order-" + Date.now() + "-" + Math.random().toString(36).slice(2);
    this.setData({
      quote: {
        ...q,
        goodsText: money(q.goodsCents),
        shippingText: money(q.shippingCents),
        totalText: money(q.totalCents),
        lines: q.lines.map((l) => ({
          ...l,
          imageThumb: imageUrl(l.image, "thumb"),
          priceText: money(l.unitCents),
        })),
      },
      error: "",
    });
  },
  async confirmSales(e) {
    this.setData({ salesConfirmed: e.detail.value.length > 0, quote: null });
    if (this.data.salesConfirmed && this.data.address) {
      try {
        await this.quote();
      } catch (e) {
        this.setData({ error: e.message });
      }
    }
  },
  async removeSales() {
    wx.removeStorageSync("jyj_sales_candidate");
    this.salesCode = "";
    this.setData({
      sales: null,
      hasSalesCode: false,
      salesConfirmed: false,
      quote: null,
      error: "",
    });
    if (this.data.address) {
      try {
        await this.quote();
      } catch (e) {
        this.setData({ error: e.message });
      }
    }
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
      wx.removeStorageSync("jyj_checkout");
      wx.removeStorageSync("jyj_sales_candidate");
      wx.redirectTo({ url: "/pages/order/index?id=" + o.id });
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
});
