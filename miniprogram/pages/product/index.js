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
    identityReady: false,
    identityLoading: false,
    identityError: "",
    canSalesShare: false,
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
    if (this._shareSession !== session())
      this.setData({ ownSalesCode: "", canSalesShare: false });
    await Promise.all([
      this.loadProduct(),
      loadBadge(this, "cart"),
      this.loadShareRole(),
    ]);
  },
  /** 销售分享资格只看服务端当前身份；匿名不请求私有接口，读取失败不阻塞商品浏览。 */
  async loadShareRole() {
    const identity = session();
    if (!identity) {
      this._roleSession = undefined;
      this.setData({
        identityReady: true,
        identityLoading: false,
        identityError: "",
        canSalesShare: false,
        ownSalesCode: "",
      });
      return;
    }
    this.setData({
      identityLoading: true,
      identityError: "",
      canSalesShare: false,
    });
    try {
      const me = await request("/me");
      if (session() !== identity) return;
      this._roleSession = identity;
      const allowed = ["B", "C"].includes(me.identity);
      this.setData({
        identityReady: true,
        identityLoading: false,
        canSalesShare: allowed,
        ...(allowed ? {} : { ownSalesCode: "" }),
      });
      if (!allowed) this._shareSession = undefined;
    } catch (error) {
      if (session() !== identity) return;
      this._roleSession = undefined;
      this._shareSession = undefined;
      this.setData({
        identityReady: false,
        identityLoading: false,
        identityError: error.message || "销售身份暂不可用",
        canSalesShare: false,
        ownSalesCode: "",
      });
    }
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
    if (this.data.shareBusy || !this.data.canSalesShare) return;
    this.setData({ shareBusy: true, error: "" });
    try {
      if (!wx.getStorageSync("jyj_token")) await login();
      const share = await request("/invitations/mine");
      if (!this.data.canSalesShare)
        throw new Error("当前身份已变化，暂不可销售分享");
      this._shareSession = session();
      this.setData({ ownSalesCode: share.shareCode, error: "" });
      wx.showToast({ title: "请点击分享给顾客", icon: "none" });
    } catch (e) {
      this._shareSession = undefined;
      this.setData({ ownSalesCode: "", error: e.message });
    } finally {
      this.setData({ shareBusy: false });
    }
  },
  onShareAppMessage() {
    const salesCode =
      this.data.canSalesShare &&
      this._shareSession === session() &&
      this._roleSession === session()
        ? this.data.ownSalesCode
        : "";
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
        if (!this.data.product || !this.data.sku || !this.data.sku.available)
          return;
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
