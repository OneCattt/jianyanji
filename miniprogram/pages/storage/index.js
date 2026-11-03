const { request } = require("../../utils/api");
const { showDialog } = require("../../utils/dialog");
const { listData, loadPage } = require("../../utils/list");
const { productLine, pickupView } = require("../../utils/storage-view");
Page({
  data: {
    ...listData("lots"),
    tab: "lots",
    pickups: [],
    messages: [],
    progress: [],
    error: "",
    busy: false,
    selectedCount: 0,
    selectMode: false,
    selectedItems: [],
    showFreight: false,
  },
  onLoad(options) {
    this.token = wx.getStorageSync("jyj_token");
    const selected =
      options.select === "1"
        ? wx.getStorageSync("jyj_pickup_selection") || []
        : [];
    this.selection = Object.fromEntries(
      selected.map((item) => [item.lotId, item]),
    );
    this.setData({ selectMode: options.select === "1" });
    this.updateSelection();
  },
  async onShow() {
    const token = wx.getStorageSync("jyj_token");
    if (this.token !== token) {
      this.selection = {};
      this.token = token;
      this.setData({ selectedCount: 0, selectedItems: [] });
    }
    if (!token) {
      this.setData({ error: "请先登录后查看本人寄存" });
      return;
    }
    await this.load();
  },
  async load() {
    this.setData({ error: "" });
    if (this.data.tab === "lots") {
      await this.loadLots(true);
      try {
        const result = await request("/mini/package-consumption?limit=100");
        this.setData({ progress: result.items });
      } catch (error) {
        this.setData({ error: error.message });
      }
    } else {
      try {
        const result = await request(
          "/mini/" +
            (this.data.tab === "pickups" ? "pickups" : "storage-messages") +
            "?limit=20",
        );
        this.setData(
          this.data.tab === "pickups"
            ? { pickups: result.items.map(this.decoratePickup) }
            : { messages: result.items },
        );
        this.otherPage = 1;
        this.otherMore = result.total > result.items.length;
      } catch (error) {
        this.setData({ error: error.message });
      }
    }
  },
  decoratePickup(item) {
    return pickupView(item);
  },
  async loadLots(reset) {
    await loadPage(this, {
      key: "lots",
      reset,
      url: "/mini/storage",
      map: (item) => ({
        ...productLine(item),
        selectedQuantity: (this.selection[item.id] || {}).quantity || 0,
        is_gift: !!item.is_gift,
      }),
    });
  },
  async onReachBottom() {
    if (this.data.tab === "lots") return this.loadLots(false);
    if (!this.otherMore || this.otherLoading) return;
    this.otherLoading = true;
    try {
      const page = (this.otherPage || 1) + 1;
      const result = await request(
        "/mini/" +
          (this.data.tab === "pickups" ? "pickups" : "storage-messages") +
          "?limit=20&page=" +
          page,
      );
      const key = this.data.tab === "pickups" ? "pickups" : "messages";
      this.setData({
        [key]: this.data[key].concat(
          key === "pickups"
            ? result.items.map(this.decoratePickup)
            : result.items,
        ),
      });
      this.otherPage = page;
      this.otherMore = page * 20 < result.total;
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.otherLoading = false;
    }
  },
  switchTab(e) {
    this.setData({ tab: e.currentTarget.dataset.tab, error: "" });
    return this.load();
  },
  toggleFreight() {
    this.setData({ showFreight: !this.data.showFreight });
  },
  toMall() {
    wx.switchTab({ url: "/pages/mall/index" });
  },
  quantity(e) {
    if (this.data.busy) return;
    const id = Number(e.currentTarget.dataset.id),
      row = this.data.lots.find((item) => item.id === id),
      quantity = Number(e.detail.value);
    if (
      !Number.isInteger(quantity) ||
      quantity < 0 ||
      !row ||
      row.expired ||
      quantity > row.available_qty
    ) {
      this.setData({ error: "提货数量须为不超过可提数量的整数" });
      return;
    }
    if (quantity) this.selection[id] = { lotId: id, quantity };
    else delete this.selection[id];
    this.updateSelection();
    this.setData({
      lots: this.data.lots.map((item) =>
        item.id === id ? { ...item, selectedQuantity: quantity } : item,
      ),
      error: "",
    });
  },
  stepQuantity(e) {
    const id = Number(e.currentTarget.dataset.id);
    const row = this.data.lots.find((item) => item.id === id);
    if (!row || this.data.busy || row.expired) return;
    const quantity =
      (row.selectedQuantity || 0) + Number(e.currentTarget.dataset.step);
    if (quantity < 0 || quantity > row.available_qty) return;
    this.quantity({
      currentTarget: { dataset: { id } },
      detail: { value: quantity },
    });
  },
  sourceOrder(e) {
    wx.navigateTo({
      url: "/pages/order/index?id=" + e.currentTarget.dataset.id,
    });
  },
  updateSelection() {
    const selected = Object.values(this.selection);
    this.setData({
      selectedItems: selected,
      selectedCount: selected.reduce((sum, item) => sum + item.quantity, 0),
    });
  },
  async submit() {
    if (this.data.busy || (!this.data.selectMode && !this.data.selectedCount))
      return;
    if (this.data.selectMode) {
      wx.setStorageSync("jyj_pickup_selection", Object.values(this.selection));
      wx.navigateBack();
      return;
    }
    this.setData({ busy: true, error: "" });
    try {
      const selected = wx.getStorageSync("jyj_address");
      const result = await request(
        "/mini/address" + (selected ? "?id=" + selected : ""),
      );
      if (!result.address) {
        wx.navigateTo({ url: "/pages/addresses/index?select=1" });
        return;
      }
      const body = {
        addressId: result.address.id,
        storedItems: Object.values(this.selection),
      };
      const quote = await request("/pickups/quote", "POST", body);
      const resultDialog = await showDialog(this, {
        title: "确认本次提货",
        content:
          "收货：" +
          result.address.recipient +
          " " +
          result.address.phone +
          "\n" +
          result.address.province +
          result.address.city +
          result.address.district +
          result.address.detail +
          "\n" +
          "本次提取" +
          this.data.selectedCount +
          "件，运费" +
          (quote.shippingCents / 100).toFixed(2) +
          "积分，可用" +
          (quote.availableCents / 100).toFixed(2) +
          "积分。商品货款不再扣除。",
        confirmText: "提交审核",
        cancelText: "返回核对",
      });
      if (!resultDialog.confirm) return;
      this.key =
        this.key ||
        "pickup-" + Date.now() + "-" + Math.random().toString(36).slice(2);
      const pickup = await request("/pickups", "POST", {
        ...body,
        expectedFeeCents: quote.shippingCents,
        requestKey: this.key,
      });
      this.key = null;
      this.selection = {};
      this.setData({ selectedCount: 0, selectedItems: [] });
      wx.navigateTo({ url: "/pages/pickup/index?id=" + pickup.id });
    } catch (error) {
      this.setData({ error: error.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  addresses() {
    wx.navigateTo({ url: "/pages/addresses/index?select=1" });
  },
  wallet() {
    wx.navigateTo({ url: "/pages/wallet/index" });
  },
  detail(e) {
    wx.navigateTo({
      url: "/pages/pickup/index?id=" + e.currentTarget.dataset.id,
    });
  },
  async subscribe() {
    try {
      const config = await request("/storage-subscription-templates");
      const ids = [config.expiryTemplateId, config.rejectedTemplateId].filter(
        Boolean,
      );
      if (!ids.length) {
        this.setData({
          error: "总部尚未配置微信提醒模板，到期消息可在本页查看",
        });
        return;
      }
      const result = await new Promise((resolve, reject) =>
        wx.requestSubscribeMessage({
          tmplIds: ids,
          success: resolve,
          fail: reject,
        }),
      );
      for (const id of ids)
        await request("/storage-subscriptions", "POST", {
          templateId: id,
          accepted: result[id] === "accept",
        });
    } catch (error) {
      this.setData({ error: error.message || error.errMsg || "订阅未完成" });
    }
  },
});
