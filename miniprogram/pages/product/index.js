const { request, login } = require("../../utils/api");
const { decorate } = require("../../utils/catalog");
const { loadBadge } = require("../../utils/badges");
const { session } = require("../../utils/state");
Page({
  data: {
    product: null,
    sku: null,
    quantity: 1,
    error: "",
    ownSalesCode: "",
    cartCount: null,
    navTop: 48,
    pendingAction: "",
    shareBusy: false,
  },
  onLoad(q) {
    this.productId = q.id;
    if (q.salesCode) wx.setStorageSync("jyj_sales_candidate", q.salesCode);
    const windowInfo = wx.getWindowInfo();
    const capsule = wx.getMenuButtonBoundingClientRect();
    this.setData({
      navTop: Math.max(
        windowInfo.statusBarHeight,
        capsule.top + capsule.height / 2 - 22,
      ),
    });
  },
  async onShow() {
    if (this._shareSession !== session()) this.setData({ ownSalesCode: "" });
    await Promise.all([this.loadProduct(), loadBadge(this, "cart")]);
  },
  async loadProduct() {
    try {
      const p = decorate(await request("/products/" + this.productId));
      const sku =
        p.skus.find((s) => s.id === this.data.sku?.id && s.available) ||
        p.skus.find((s) => s.available) ||
        p.skus[0];
      this.setData({
        product: p,
        sku,
        quantity: Math.max(1, Math.min(this.data.quantity, sku.stock, 999)),
        error: "",
      });
    } catch (e) {
      this.setData({ error: e.message, product: null });
    }
  },
  async prepareSalesShare() {
    if (this.data.shareBusy) return;
    this.setData({ shareBusy: true, error: "" });
    try {
      if (!wx.getStorageSync("jyj_token")) await login();
      const share = await request("/invitations/mine");
      this._shareSession = session();
      this.setData({ ownSalesCode: share.shareCode, error: "" });
      wx.showToast({ title: "请点击分享给顾客", icon: "none" });
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ shareBusy: false });
    }
  },
  onShareAppMessage() {
    const salesCode =
      this._shareSession === session() ? this.data.ownSalesCode : "";
    return {
      title: this.data.product ? this.data.product.name : "品牌商品",
      path:
        "/pages/product/index?id=" +
        this.productId +
        (salesCode ? "&salesCode=" + encodeURIComponent(salesCode) : ""),
    };
  },
  select(e) {
    if (this.data.pendingAction) return;
    this.setData({
      sku: this.data.product.skus.find(
        (s) => s.id === Number(e.currentTarget.dataset.id),
      ),
      quantity: 1,
    });
  },
  change(e) {
    if (this.data.pendingAction) return;
    const quantity = this.data.quantity + Number(e.currentTarget.dataset.delta);
    if (quantity > 0 && quantity <= Math.min(this.data.sku.stock, 999))
      this.setData({ quantity });
  },
  async add() {
    if (!this.data.sku.available || this.data.pendingAction) return;
    this.setData({ pendingAction: "add", error: "" });
    try {
      if (!wx.getStorageSync("jyj_token")) await login();
      await request("/cart/" + this.data.sku.id, "PUT", {
        quantity: this.data.quantity,
        selected: true,
      });
      wx.showToast({ title: "已加入购物袋", icon: "success" });
      await loadBadge(this, "cart", true);
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ pendingAction: "" });
    }
  },
  async buy() {
    if (!this.data.sku.available || this.data.pendingAction) return;
    this.setData({ pendingAction: "buy", error: "" });
    try {
      if (!wx.getStorageSync("jyj_token")) {
        await login();
        await this.onShow();
        if (!this.data.product || !this.data.sku || !this.data.sku.available) return;
      }
      wx.setStorageSync("jyj_checkout", [
        { skuId: this.data.sku.id, quantity: this.data.quantity },
      ]);
      wx.navigateTo({ url: "/pages/checkout/index" });
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ pendingAction: "" });
    }
  },
  previewImage() {
    wx.previewImage({
      current: this.data.product.image,
      urls: [this.data.product.image],
    });
  },
  previewDetailImage() {
    wx.previewImage({
      current: this.data.product.detailImage,
      urls: [this.data.product.detailImage],
    });
  },
  back() {
    wx.navigateBack({ fail: () => this.home() });
  },
  home() {
    wx.switchTab({ url: "/pages/home/index" });
  },
  cart() {
    wx.navigateTo({ url: "/pages/cart/index" });
  },
});
