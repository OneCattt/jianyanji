const { request } = require("../../utils/api");
const { showDialog } = require("../../utils/dialog");
const { shippingLines } = require("../../utils/fulfillment");
const { imageUrl } = require("../../utils/image");
Page({
  data: {
    offers: [],
    selectedOfferId: 0,
    storedItems: [],
    delivery: "ship",
    address: null,
    error: "",
    busy: false,
    loading: true,
  },
  onLoad(options = {}) {
    const offerId = Number(options.offerId);
    if (Number.isInteger(offerId) && offerId > 0) {
      this.setData({ selectedOfferId: offerId });
      wx.setNavigationBarTitle({ title: "确认套餐" });
    }
  },
  selectOffer(e) {
    const offerId = Number(e.currentTarget.dataset.id);
    if (!this.data.offers.some((offer) => offer.id === offerId)) return;
    wx.navigateTo({ url: "/pages/offers/index?offerId=" + offerId });
  },
  async onShow() {
    this.setData({ loading: true });
    try {
      const offers = await request("/offers");
      const selected = wx.getStorageSync("jyj_address");
      let address = null;
      if (this.data.selectedOfferId && wx.getStorageSync("jyj_token"))
        address = (
          await request("/mini/address" + (selected ? "?id=" + selected : ""))
        ).address;
      const items = offers.items.filter(
        (offer) =>
          !this.data.selectedOfferId || offer.id === this.data.selectedOfferId,
      );
      this.setData({
        storedItems: wx.getStorageSync("jyj_pickup_selection") || [],
        offers: items.map((offer) => {
          const lines = shippingLines(
            offer.lines,
            (this.data.offers.find((old) => old.id === offer.id) || {}).lines,
            this.data.delivery,
          ).map((line) => ({
            ...line,
            imageCard: imageUrl(line.image, "card"),
          }));
          const giftCount = lines.reduce(
            (sum, line) => sum + (line.isGift ? line.quantity : 0),
            0,
          );
          return {
            ...offer,
            amountText: (offer.amountCents / 100).toFixed(2),
            lines,
            previewLines: lines.slice(0, 3),
            extraKinds: Math.max(0, lines.length - 3),
            giftCount,
            mainCount: lines.reduce(
              (sum, line) => sum + (line.isGift ? 0 : line.quantity),
              0,
            ),
          };
        }),
        address,
        error:
          this.data.selectedOfferId && !items.length
            ? "该套餐已下架，请返回选择其他套餐"
            : "",
      });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ loading: false });
    }
  },
  mode(e) {
    const mode = e.detail.value;
    this.setData({
      delivery: mode,
      offers: this.data.offers.map((offer) => ({
        ...offer,
        lines: offer.lines.map((line) => ({
          ...line,
          shipQuantity: mode === "store" ? 0 : line.quantity,
        })),
      })),
    });
  },
  quantity(e) {
    const offer = this.data.offers.find(
        (item) => item.id === Number(e.currentTarget.dataset.offer),
      ),
      index = Number(e.currentTarget.dataset.index),
      quantity = Number(e.detail.value);
    if (
      !Number.isInteger(quantity) ||
      quantity < 0 ||
      quantity > offer.lines[index].quantity
    ) {
      this.setData({ error: "本次发货数量超出套餐数量" });
      return;
    }
    offer.lines[index].shipQuantity = quantity;
    this.setData({
      offers: this.data.offers,
      delivery: this.data.offers.some((item) =>
        item.lines.some((line) => line.shipQuantity > 0),
      )
        ? "ship"
        : "store",
    });
  },
  chooseStored() {
    wx.navigateTo({ url: "/pages/storage/index?select=1" });
  },
  addresses() {
    wx.navigateTo({ url: "/pages/addresses/index?select=1" });
  },
  async buy(e) {
    if (this.data.busy || this.data.loading || !this.data.selectedOfferId)
      return;
    const offerId = Number(e.currentTarget.dataset.id);
    if (
      offerId !== this.data.selectedOfferId ||
      !this.data.offers.some((item) => item.id === offerId)
    )
      return;
    if (!this.data.address) {
      this.setData({ error: "请先登录并选择收货地址" });
      return;
    }
    this.setData({ busy: true, error: "" });
    try {
      const quote = await request("/checkout/quote", "POST", {
        addressId: this.data.address.id,
        offerId,
        offerQuantity: 1,
        offerDelivery: this.data.delivery,
        offerShipQuantities: this.data.offers
          .find((item) => item.id === offerId)
          .lines.map((line) => line.shipQuantity),
        storedItems: this.data.storedItems,
      });
      const confirm = await showDialog(this, {
        title: "确认固定套餐",
        content:
          "本套餐固定货品不可调换，需支付" +
          (quote.totalCents / 100).toFixed(2) +
          "积分。" +
          "本次新购发货" +
          quote.lines.reduce((n, line) => n + line.shipQuantity, 0) +
          "件，寄存" +
          quote.lines.reduce(
            (n, line) => n + line.quantity - line.shipQuantity,
            0,
          ) +
          "件。",
        confirmText: "创建订单",
        cancelText: "返回",
      });
      if (!confirm.confirm) return;
      const order = await request("/orders", "POST", {
        quoteId: quote.id,
        requestKey:
          "offer-" + Date.now() + "-" + Math.random().toString(36).slice(2),
      });
      wx.removeStorageSync("jyj_pickup_selection");
      wx.navigateTo({ url: "/pages/wallet/index?orderId=" + order.id });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ busy: false });
    }
  },
});
