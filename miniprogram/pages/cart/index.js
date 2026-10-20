const { request } = require("../../utils/api");
const { money } = require("../../utils/catalog");
const { imageUrl } = require("../../utils/image");
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember } = require("../../utils/state");
Page({
  data: {
    ...listData(),
    totalText: "¥0.00",
    kinds: 0,
    pieces: 0,
    error: "",
    busy: false,
  },
  async onShow() {
    if (!isFresh(this) && !this.data.itemsLoading) await this.load();
  },
  async load(reset = true) {
    if (
      await loadPage(this, {
        reset,
        url: "/mini/cart",
        map: (i) => ({
          ...i,
          imageThumb: imageUrl(i.image, "thumb"),
          priceText: money(i.priceCents),
        }),
        extra: (r) => ({
          kinds: r.kinds,
          pieces: r.pieces,
          totalText: money(r.totalCents),
        }),
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
  async change(e) {
    if (this.data.busy) return;
    const item = this.data.items.find(
        (i) => i.skuId === Number(e.currentTarget.dataset.id),
      ),
      action = e.currentTarget.dataset.action;
    this.setData({ busy: true, error: "" });
    try {
      if (action === "remove")
        await request("/cart/" + item.skuId + "/remove", "POST");
      else
        await request("/cart/" + item.skuId, "PUT", {
          quantity:
            action === "toggle"
              ? item.quantity
              : item.quantity + Number(e.currentTarget.dataset.delta),
          selected: action === "toggle" ? !item.selected : item.selected,
        });
      await this.load();
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  async checkout() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: "" });
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
