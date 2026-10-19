const { request, login } = require("../../utils/api");
const { decorate } = require("../../utils/catalog");
Page({
  data: { product: null, sku: null, quantity: 1, error: "" },
  onLoad(q) {
    this.productId = q.id;
  },
  async onShow() {
    try {
      const p = decorate(await request("/products/" + this.productId));
      this.setData({
        product: p,
        sku: p.skus.find((s) => s.available) || p.skus[0],
        error: "",
      });
    } catch (e) {
      this.setData({ error: e.message, product: null });
    }
  },
  select(e) {
    this.setData({
      sku: this.data.product.skus.find(
        (s) => s.id === Number(e.currentTarget.dataset.id),
      ),
      quantity: 1,
    });
  },
  change(e) {
    const quantity = this.data.quantity + Number(e.currentTarget.dataset.delta);
    if (quantity > 0 && quantity <= Math.min(this.data.sku.stock, 999))
      this.setData({ quantity });
  },
  async add() {
    if (!this.data.sku.available) return;
    try {
      if (!wx.getStorageSync("jyj_token")) await login();
      await request("/cart/" + this.data.sku.id, "PUT", {
        quantity: this.data.quantity,
        selected: true,
      });
      wx.showToast({ title: "已加入购物袋", icon: "success" });
    } catch (e) {
      this.setData({ error: e.message });
    }
  },
  async buy() {
    if (!this.data.sku.available) return;
    try {
      if (!wx.getStorageSync("jyj_token")) {
        await login();
        await this.onShow();
        return;
      }
      wx.setStorageSync("jyj_checkout", [
        { skuId: this.data.sku.id, quantity: this.data.quantity },
      ]);
      wx.navigateTo({ url: "/pages/checkout/index" });
    } catch (e) {
      this.setData({ error: e.message });
    }
  },
  cart() {
    wx.navigateTo({ url: "/pages/cart/index" });
  },
});
