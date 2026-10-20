const { request } = require("../../utils/api");
const { money } = require("../../utils/catalog");
const { imageUrl } = require("../../utils/image");
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember } = require("../../utils/state");

function summary(result) {
  return {
    kinds: result.kinds,
    pieces: result.pieces,
    totalKinds: result.totalKinds,
    allSelected: result.allSelected,
    totalText: money(result.totalCents),
  };
}

Page({
  data: {
    ...listData(),
    totalText: "¥0.00",
    kinds: 0,
    pieces: 0,
    totalKinds: 0,
    allSelected: false,
    swipeSkuId: 0,
    error: "",
    busy: false,
  },
  async onShow() {
    if (!isFresh(this) && !this.data.itemsLoading) await this.load();
  },
  async load(reset = true) {
    if (this.data.busy) return;
    this.setData({ swipeSkuId: 0 });
    if (
      await loadPage(this, {
        reset,
        url: "/mini/cart",
        map: (i) => ({
          ...i,
          imageThumb: imageUrl(i.image, "thumb"),
          priceText: money(i.priceCents),
        }),
        extra: summary,
      })
    )
      remember(this);
  },
  onReachBottom() {
    return this.load(false);
  },
  async onPullDownRefresh() {
    try {
      await this.load();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  // 写操作直接采用服务端整袋汇总，保留已加载的分页和滚动位置。
  async mutate(url, method, body, patch) {
    if (this.data.busy || this.data.itemsLoading) return false;
    this.setData({ busy: true, error: "", swipeSkuId: 0 });
    try {
      const result = await request(url, method, body);
      this.setData({ ...patch(), ...summary(result) });
      remember(this);
      return true;
    } catch (e) {
      this.setData({ error: e.message });
      return false;
    } finally {
      this.setData({ busy: false });
    }
  },
  async change(e) {
    if (!(await this.settleQuantity())) return;
    const { id, action, delta } = e.currentTarget.dataset;
    const item = this.data.items.find((i) => i.skuId === Number(id));
    if (!item) return;
    if (action === "remove") {
      const removed = await this.mutate(
        "/cart/" + item.skuId + "/remove",
        "POST",
        undefined,
        () => ({
          items: this.data.items.filter((i) => i.skuId !== item.skuId),
        }),
      );
      // 删除会前移后续行，从已加载末尾所在页补齐，避免跳过下一页首项。
      if (removed) {
        const hasMore = this.data.items.length < this.data.totalKinds;
        this.setData({
          itemsNextPage: hasMore
            ? Math.floor(this.data.items.length / 20) + 1
            : null,
          itemsHasMore: hasMore,
        });
      }
    } else if (action === "toggle") {
      return this.mutate(
        "/cart/selection",
        "PUT",
        { skuId: item.skuId, selected: !item.selected },
        () => ({
          items: this.data.items.map((i) =>
            i.skuId === item.skuId ? { ...i, selected: !item.selected } : i,
          ),
        }),
      );
    } else {
      return this.saveQuantity(item, item.quantity + Number(delta));
    }
  },
  async toggleAll() {
    if (!(await this.settleQuantity())) return;
    if (!this.data.totalKinds) return;
    const selected = !this.data.allSelected;
    return this.mutate("/cart/selection", "PUT", { selected }, () => ({
      items: this.data.items.map((item) => ({ ...item, selected })),
    }));
  },
  startQuantityEdit(e) {
    const skuId = Number(e.currentTarget.dataset.id);
    const item = this.data.items.find((i) => i.skuId === skuId);
    if (!item) return;
    this._quantityEdit = { skuId, value: String(item.quantity) };
    this._quantityCommit = null;
  },
  editQuantity(e) {
    if (this._quantityEdit) this._quantityEdit.value = e.detail.value;
  },
  commitQuantity(e) {
    const edit = this._quantityEdit;
    if (!edit) return this._quantityCommit || Promise.resolve(true);
    this._quantityEdit = null;
    const item = this.data.items.find((i) => i.skuId === edit.skuId);
    if (!item) return Promise.resolve(false);
    const value = String(e.detail.value).trim();
    const limit = Math.min(999, item.stock);
    if (!/^[1-9]\d{0,2}$/.test(value) || Number(value) > limit) {
      this.setData({
        ["items[" + this.data.items.indexOf(item) + "].quantity"]:
          item.quantity,
      });
      wx.showToast({ title: "请输入1–" + limit + "之间的整数", icon: "none" });
      this._quantityCommit = Promise.resolve(false);
    } else {
      this._quantityCommit = this.saveQuantity(item, Number(value)).then(
        (ok) => {
          if (!ok)
            this.setData({
              ["items[" + this.data.items.indexOf(item) + "].quantity"]:
                item.quantity,
            });
          return ok;
        },
      );
    }
    const pending = this._quantityCommit;
    pending.then(() => {
      if (this._quantityCommit === pending) this._quantityCommit = null;
    });
    return pending;
  },
  async settleQuantity() {
    if (this._quantityEdit)
      this.commitQuantity({ detail: { value: this._quantityEdit.value } });
    const pending = this._quantityCommit;
    if (!pending) return true;
    const ok = await pending;
    if (this._quantityCommit === pending) this._quantityCommit = null;
    return ok;
  },
  confirmQuantity(e) {
    const result = this.commitQuantity(e);
    wx.hideKeyboard();
    return result;
  },
  saveQuantity(item, quantity) {
    if (quantity === item.quantity) return Promise.resolve(true);
    if (
      !Number.isInteger(quantity) ||
      quantity < 1 ||
      quantity > Math.min(999, item.stock)
    )
      return Promise.resolve(false);
    return this.mutate(
      "/cart/" + item.skuId,
      "PUT",
      { quantity, selected: item.selected },
      () => ({
        items: this.data.items.map((i) =>
          i.skuId === item.skuId ? { ...i, quantity } : i,
        ),
      }),
    );
  },
  touchStart(e) {
    if (this.data.busy || this.data.itemsLoading) return;
    const point = e.touches[0];
    this._touch = {
      skuId: Number(e.currentTarget.dataset.id),
      x: point.clientX,
      y: point.clientY,
    };
    this._touchAxis = "";
  },
  touchMove(e) {
    if (!this._touch) return;
    const point = e.touches[0];
    const dx = point.clientX - this._touch.x;
    const dy = point.clientY - this._touch.y;
    if (!this._touchAxis && Math.max(Math.abs(dx), Math.abs(dy)) > 8)
      this._touchAxis = Math.abs(dx) > Math.abs(dy) ? "x" : "y";
    this._touch.dx = dx;
  },
  touchEnd() {
    if (this._touch && this._touchAxis === "x" && !this.data.busy) {
      if (this._touch.dx < -32) this.setData({ swipeSkuId: this._touch.skuId });
      else if (this._touch.dx > 24) this.closeSwipe();
    }
    this._touch = null;
  },
  touchCancel() {
    this._touch = null;
  },
  closeSwipe() {
    if (this.data.swipeSkuId) this.setData({ swipeSkuId: 0 });
  },
  showPricingInfo() {
    wx.showModal({
      title: "结算核价说明",
      content:
        "普通商品按当前身份核价：主理人、店主享对应等级订货价，游客按零售价。福利品统一价。结算时重新核验价格与库存，以确认订单页为准。",
      showCancel: false,
      confirmText: "我知道了",
      confirmColor: "#7A1C1C",
    });
  },
  async checkout() {
    if (!(await this.settleQuantity())) return;
    if (this.data.busy || this.data.itemsLoading) return;
    this.setData({ busy: true, error: "", swipeSkuId: 0 });
    try {
      const result = await request("/mini/cart/selection");
      wx.setStorageSync("jyj_checkout", result.items);
      wx.navigateTo({ url: "/pages/checkout/index" });
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
});
