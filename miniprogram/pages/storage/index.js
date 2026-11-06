const { request } = require("../../utils/api");
const { listData, loadPage } = require("../../utils/list");
const { productLine, pickupView } = require("../../utils/storage-view");
const selectionKey = "jyj_pickup_selection";
const metaKey = "jyj_pickup_selection_meta";
/** 仅提交批次与数量，展示信息另存，避免把描述数据当作库存授权。 */
function storedItems(selection) {
  return Object.values(selection).map((item) => ({
    lotId: item.lotId,
    quantity: item.quantity,
  }));
}
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
    showSelected: false,
    orderId: 0,
    orderFilterText: "",
    confirmOpen: false,
    confirmAddress: null,
    confirmAddressText: "",
    confirmQuote: null,
    confirmBusy: false,
    confirmError: "",
    confirmFeeText: "—",
    confirmAvailableText: "—",
    confirmShortfallText: "",
    confirmShippingText: "",
    confirmReasonText: "",
    confirmExpired: false,
  },
  onLoad(options) {
    this.token = wx.getStorageSync("jyj_token");
    const selectMode = options.select === "1";
    const selected = selectMode ? wx.getStorageSync(selectionKey) || [] : [];
    const meta = selectMode ? wx.getStorageSync(metaKey) || {} : {};
    this.selection = Object.fromEntries(
      selected.map((item) => [
        item.lotId,
        { ...(meta[item.lotId] || {}), ...item, lotId: item.lotId },
      ]),
    );
    this._needsSelectionVerify = selected.some((item) => !item.name);
    const orderId = Number(options.orderId) || 0;
    this.setData({
      selectMode,
      orderId,
      orderFilterText: orderId ? "当前仅查看该订单产生的寄存" : "",
    });
    this.updateSelection();
  },
  async onShow() {
    const token = wx.getStorageSync("jyj_token");
    if (this.token !== token) {
      this.selection = {};
      this.token = token;
      this.setData({
        selectedCount: 0,
        selectedItems: [],
        confirmOpen: false,
        confirmQuote: null,
      });
      wx.removeStorageSync(selectionKey);
      wx.removeStorageSync(metaKey);
    }
    if (!token) {
      this.setData({ error: "请先登录后查看本人寄存" });
      return;
    }
    await this.load();
    if (this._needsSelectionVerify) await this.loadSelectionMeta();
    // 从地址页返回后恢复核对步骤并重新报价。
    if (this.data.confirmOpen && this.data.confirmAddress) {
      await this.quoteConfirm(this.data.confirmAddress);
    }
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
  /** 只为旧缓存中缺少展示信息的已选批次补读一次，最多100项。 */
  async loadSelectionMeta() {
    const ids = Object.values(this.selection)
      .filter((item) => !item.name)
      .map((item) => item.lotId)
      .slice(0, 100);
    if (!ids.length) {
      this._needsSelectionVerify = false;
      return;
    }
    try {
      const result = await request(
        "/mini/storage?ids=" + ids.join(",") + "&limit=100",
      );
      const rows = new Map((result.items || []).map((row) => [row.id, row]));
      for (const id of ids) {
        const row = rows.get(id);
        if (!row || !this.selection[id]) continue;
        this.selection[id] = {
          ...this.selection[id],
          name: row.name,
          skuName: row.sku_name,
          imageThumb: row.image,
          isGift: !!row.is_gift,
          orderId: row.order_id,
          orderNumber: row.order_number,
        };
      }
      this._needsSelectionVerify = false;
      this.updateSelection();
    } catch (_) {
      // 补读失败仍保留批次与数量，用户可在核对面板继续查看已选件数。
    }
  },
  async loadLots(reset) {
    await loadPage(this, {
      key: "lots",
      reset,
      url:
        "/mini/storage" +
        (this.data.orderId ? "?orderId=" + this.data.orderId : ""),
      map: (item) => {
        // 已选批次跨页保留数量；已加载行补齐旧缓存缺少的展示信息。
        const chosen = this.selection[item.id];
        if (chosen)
          this.selection[item.id] = {
            ...chosen,
            name: item.name,
            skuName: item.sku_name,
            imageThumb: item.imageThumb || item.image,
            isGift: !!item.is_gift,
            orderId: item.order_id,
            orderNumber: item.order_number,
          };
        return {
          ...productLine(item),
          selectedQuantity: chosen ? chosen.quantity : 0,
          is_gift: !!item.is_gift,
        };
      },
    });
    if (this.data.selectedCount) this.updateSelection();
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
    if (quantity) {
      if (!this.selection[id] && Object.keys(this.selection).length >= 100) {
        this.setData({ error: "一次提货最多选择100个寄存批次，请分次提交" });
        return;
      }
      this.selection[id] = {
        lotId: id,
        quantity,
        name: row.name,
        skuName: row.sku_name,
        imageThumb: row.imageThumb || row.image,
        isGift: !!row.is_gift,
        orderId: row.order_id,
        orderNumber: row.order_number,
      };
    } else delete this.selection[id];
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
    const selected = Object.values(this.selection)
      .filter((item) => item.quantity > 0)
      .sort((a, b) => a.lotId - b.lotId);
    this.setData({
      selectedItems: selected.map((item) => ({
        ...item,
        imageThumb: item.imageThumb || item.image,
      })),
      selectedCount: selected.reduce((sum, item) => sum + item.quantity, 0),
    });
  },
  toggleSelected() {
    this.setData({ showSelected: !this.data.showSelected });
  },
  removeSelected(e) {
    if (this.data.busy) return;
    const id = Number(e.currentTarget.dataset.id);
    delete this.selection[id];
    this.updateSelection();
    this.setData({
      lots: this.data.lots.map((item) =>
        item.id === id ? { ...item, selectedQuantity: 0 } : item,
      ),
    });
  },
  clearOrderFilter() {
    if (!this.data.orderId) return;
    this.setData({ orderId: 0, orderFilterText: "", lots: [] });
    return this.loadLots(true);
  },
  async submit() {
    if (this.data.busy) return;
    if (this.data.selectMode) {
      const items = storedItems(this.selection);
      wx.setStorageSync(selectionKey, items);
      const meta = {};
      for (const item of Object.values(this.selection))
        meta[item.lotId] = {
          name: item.name,
          skuName: item.skuName,
          imageThumb: item.imageThumb,
          isGift: !!item.isGift,
          orderId: item.orderId,
          orderNumber: item.orderNumber,
        };
      wx.setStorageSync(metaKey, meta);
      wx.navigateBack();
      return;
    }
    return this.openConfirm();
  },
  async openConfirm() {
    if (this.data.confirmBusy) return;
    this.setData({
      confirmOpen: true,
      confirmBusy: true,
      confirmError: "",
      confirmQuote: null,
      showSelected: true,
    });
    try {
      const selected = wx.getStorageSync("jyj_address");
      const result = await request(
        "/mini/address" + (selected ? "?id=" + selected : ""),
      );
      const address = result.address || null;
      this.setData({
        confirmAddress: address,
        confirmAddressText: this.addressText(address),
      });
      if (!address) {
        this.setData({
          confirmBusy: false,
          confirmError: "请先添加或选择收货地址，再核对运费并提交。",
        });
        return;
      }
      await this.quoteConfirm(address);
    } catch (error) {
      this.setData({ confirmBusy: false, confirmError: error.message });
    }
  },
  addressText(address) {
    return address
      ? [
          address.recipient,
          address.phone,
          [address.province, address.city, address.district, address.detail]
            .filter(Boolean)
            .join(""),
        ]
          .filter(Boolean)
          .join(" · ")
      : "";
  },
  async quoteConfirm(address) {
    const revision = (this.confirmRevision = (this.confirmRevision || 0) + 1);
    this.setData({
      confirmBusy: true,
      confirmError: "",
      confirmQuote: null,
      confirmAddress: address,
      confirmAddressText: this.addressText(address),
    });
    try {
      const quote = await request("/pickups/quote", "POST", {
        addressId: address.id,
        storedItems: storedItems(this.selection),
      });
      if (revision !== this.confirmRevision) return;
      const fee = Number(quote.shippingCents || 0);
      const available = Number(quote.availableCents || 0);
      this.setData({
        confirmQuote: quote,
        confirmBusy: false,
        confirmFeeText: (fee / 100).toFixed(2),
        confirmAvailableText: (available / 100).toFixed(2),
        confirmShortfallText: (Math.max(0, fee - available) / 100).toFixed(2),
        confirmShippingText:
          quote.shippingReason ||
          "运费按原寄存成交金额和提货身份由服务端计算。",
      });
    } catch (error) {
      if (revision === this.confirmRevision)
        this.setData({ confirmBusy: false, confirmError: error.message });
    }
  },
  closeConfirm() {
    if (this.data.confirmBusy) return;
    this.setData({ confirmOpen: false, confirmQuote: null, confirmError: "" });
  },
  stopConfirm() {},
  async confirmSubmit() {
    if (
      this.data.busy ||
      this.data.confirmBusy ||
      !this.data.confirmQuote ||
      !this.data.confirmAddress
    )
      return;
    if (Number(this.data.confirmShortfallText) > 0) {
      this.setData({
        confirmError:
          "积分不足，还差 " +
          this.data.confirmShortfallText +
          " 积分，请先联系总部充值。",
      });
      return;
    }
    this.setData({ busy: true, confirmError: "" });
    try {
      this.key =
        this.key ||
        "pickup-" + Date.now() + "-" + Math.random().toString(36).slice(2);
      const pickup = await request("/pickups", "POST", {
        addressId: this.data.confirmAddress.id,
        storedItems: storedItems(this.selection),
        expectedFeeCents: this.data.confirmQuote.shippingCents,
        requestKey: this.key,
      });
      this.key = null;
      this.selection = {};
      this.setData({
        selectedCount: 0,
        selectedItems: [],
        confirmOpen: false,
        confirmQuote: null,
        showSelected: false,
      });
      wx.removeStorageSync(selectionKey);
      wx.removeStorageSync(metaKey);
      wx.navigateTo({
        url: "/pages/pickup/index?id=" + pickup.id,
        fail: () =>
          this.setData({
            error: "提货申请已提交，页面跳转失败，可在提货记录中查看。",
          }),
      });
    } catch (error) {
      this.setData({ confirmError: error.message });
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
