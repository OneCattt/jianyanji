const { request } = require("../../utils/api");
const { money } = require("../../utils/catalog");
Page({
  data: {
    items: [],
    totalText: "¥0.00",
    kinds: 0,
    pieces: 0,
    error: "",
    busy: false,
  },
  async onShow() {
    await this.load();
  },
  async load() {
    try {
      const r = await request("/cart");
      const selected = r.items.filter((i) => i.selected);
      this.setData({
        items: r.items.map((i) => ({ ...i, priceText: money(i.priceCents) })),
        kinds: selected.length,
        pieces: selected.reduce((n, i) => n + i.quantity, 0),
        totalText: money(
          selected.reduce((n, i) => n + (i.priceCents || 0) * i.quantity, 0),
        ),
        error: "",
      });
    } catch (e) {
      this.setData({ error: e.message });
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
  checkout() {
    const selected = this.data.items.filter((i) => i.selected);
    if (!selected.length || selected.some((i) => !i.available)) {
      this.setData({ error: "请选择可购买商品后结算" });
      return;
    }
    wx.setStorageSync(
      "jyj_checkout",
      selected.map((i) => ({ skuId: i.skuId, quantity: i.quantity })),
    );
    wx.navigateTo({ url: "/pages/checkout/index" });
  },
});
