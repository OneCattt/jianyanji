const { request } = require("../../utils/api");
const { money } = require("../../utils/catalog");
const { imageUrl } = require("../../utils/image");
Page({
  data: {
    quote: null,
    displayQuote: null,
    address: null,
    error: "",
    busy: false,
    quoting: false,
    sales: null,
    hasSalesCode: false,
    salesConfirmed: false,
    combinedItems: [],
    combinedCount: 0,
    showCombined: false,
    payNavigationFailed: false,
  },
  async onShow() {
    this.quoteRevision = (this.quoteRevision || 0) + 1;
    this.setData({ quote: null, displayQuote: null, quoting: false });
    this.items = wx.getStorageSync("jyj_checkout") || [];
    if (!this.items.length) {
      this.setData({ error: "请先选择商品" });
      return;
    }
    const combined = wx.getStorageSync("jyj_pickup_selection") || [];
    const combinedMeta = wx.getStorageSync("jyj_pickup_selection_meta") || {};
    // 发给报价接口的仍是批次与数量；展示信息只用于核对完整清单。
    this.combinedStored = combined.map((item) => ({
      lotId: item.lotId,
      quantity: item.quantity,
    }));
    this.setData({
      combinedItems: combined.map((item) => ({
        ...(combinedMeta[item.lotId] || {}),
        ...item,
      })),
      combinedCount: combined.reduce((sum, item) => sum + item.quantity, 0),
    });
    this.salesCode = wx.getStorageSync("jyj_sales_candidate") || "";
    this.setData({
      hasSalesCode: !!this.salesCode,
      sales: null,
      salesConfirmed: false,
      quote: null,
      displayQuote: null,
      quoting: false,
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
    const revision = (this.quoteRevision = (this.quoteRevision || 0) + 1);
    this.setData({ quote: null, quoting: true, error: "" });
    try {
      const q = await request("/checkout/quote", "POST", {
        addressId: this.data.address.id,
        items: this.items.map((item) => ({ ...item })),
        storedItems: this.combinedStored || [],
        salesCode: this.salesCode || "",
        salesConfirmed: this.data.salesConfirmed,
      });
      if (revision !== this.quoteRevision) return;
      this.requestKey =
        "order-" + Date.now() + "-" + Math.random().toString(36).slice(2);
      const display = {
        ...q,
        goodsText: money(q.goodsCents),
        shippingText: money(q.shippingCents),
        totalText: (q.totalCents / 100).toFixed(2) + " 积分",
        pieces: q.lines.reduce((total, line) => total + line.quantity, 0),
        lines: q.lines.map((l) => ({
          ...l,
          imageThumb: imageUrl(l.image, "thumb"),
          priceText: money(l.unitCents),
        })),
      };
      this.setData({
        quote: display,
        displayQuote: display,
        error: "",
      });
    } catch (error) {
      if (revision === this.quoteRevision)
        this.setData({ error: error.message });
    } finally {
      if (revision === this.quoteRevision) this.setData({ quoting: false });
    }
  },
  async confirmSales(e) {
    this.quoteRevision = (this.quoteRevision || 0) + 1;
    this.setData({ quoting: false });
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
    this.quoteRevision = (this.quoteRevision || 0) + 1;
    this.setData({ quoting: false });
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
  async shippingQuantity(e) {
    if (this.data.busy) return;
    const index = Number(e.currentTarget.dataset.index),
      value = Number(e.detail.value);
    if (
      !Number.isInteger(value) ||
      value < 0 ||
      value > this.items[index].quantity
    ) {
      this.quoteRevision = (this.quoteRevision || 0) + 1;
      this.setData({
        quote: null,
        quoting: false,
        error: "本次发货数量不能超出购买数量，请重新结算",
      });
      return;
    }
    this.items[index] = { ...this.items[index], shipQuantity: value };
    if (this.data.displayQuote)
      this.setData({
        ["displayQuote.lines[" + index + "].shipQuantity"]: value,
      });
    wx.setStorageSync("jyj_checkout", this.items);
    try {
      await this.quote();
    } catch (error) {
      this.setData({ error: error.message });
    }
  },
  chooseStored() {
    wx.navigateTo({ url: "/pages/storage/index?select=1" });
  },
  toggleCombined() {
    this.setData({ showCombined: !this.data.showCombined });
  },
  async retryQuote() {
    if (!this.data.address || this.data.busy || this.data.quoting) return;
    if (this.salesCode && !this.data.salesConfirmed) return;
    await this.quote();
  },
  addresses() {
    wx.navigateTo({ url: "/pages/addresses/index?select=1" });
  },
  async submit() {
    if (this.data.busy || this.data.quoting) return;
    // 建单成功但导航失败时只重试导航，不能再次 POST 生成第二单。
    if (this.createdOrderId) return this.goPay();
    if (!this.data.quote) return;
    this.setData({ busy: true, error: "", payNavigationFailed: false });
    try {
      const o = await request("/orders", "POST", {
        quoteId: this.data.quote.id,
        requestKey: this.requestKey,
      });
      this.createdOrderId = o.id;
      wx.removeStorageSync("jyj_checkout");
      wx.removeStorageSync("jyj_pickup_selection");
      wx.removeStorageSync("jyj_sales_candidate");
      this.goPay();
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  goPay() {
    if (!this.createdOrderId) return;
    wx.redirectTo({
      url: "/pages/wallet/index?orderId=" + this.createdOrderId,
      fail: () =>
        this.setData({
          payNavigationFailed: true,
          error: "订单已创建，尚未付款。可继续付款或查看本单。",
        }),
    });
  },
  continuePay() {
    this.setData({ busy: true, error: "" });
    this.goPay();
    this.setData({ busy: false });
  },
  viewCreatedOrder() {
    if (!this.createdOrderId) return;
    wx.navigateTo({ url: "/pages/order/index?id=" + this.createdOrderId });
  },
});
