const { request } = require("../../utils/api");
const { money } = require("../../utils/catalog");
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember } = require("../../utils/state");
const amount = (cents, method) =>
  method === "points"
    ? (cents / 100).toFixed(2) + " 积分"
    : method
      ? money(cents)
      : (cents / 100).toFixed(2) + "（退款方式以原订单为准）";
Page({
  data: {
    ...listData(),
    lines: [],
    order: null,
    reason: "",
    service: "",
    error: "",
    busy: false,
    loading: true,
    submitted: false,
    amountText: "0.00",
  },
  onLoad(options) {
    this.orderId = Number(options.orderId) || 0;
  },
  async onShow() {
    if (!isFresh(this)) await this.refresh();
  },
  async refresh() {
    if (this._refreshing) return;
    this._refreshing = true;
    this.setData({ loading: true, error: "" });
    try {
      const [r, c, order] = await Promise.all([
        this.load(),
        request("/public/content"),
        this.orderId
          ? request("/orders/" + this.orderId)
          : Promise.resolve(null),
      ]);
      const snapshotLines =
        order && order.snapshot ? order.snapshot.lines || [] : [];
      this.setData({
        items: this.data.items.map((i) => ({
          ...i,
          amountText: amount(i.total_cents, order && order.payment_method),
        })),
        service: c.content ? c.content.customerService : "",
        order,
        lines: order
          ? order.lines.map((line) => {
              const snapshot =
                snapshotLines.find((item) => item.skuId === line.sku_id) || {};
              return {
                ...line,
                title: snapshot.productName || snapshot.name || "订单商品",
                skuName: snapshot.skuName || "",
                available: Math.max(
                  0,
                  line.quantity - line.refunded_qty - line.refund_held,
                ),
                selectedQty: 0,
                unitText: amount(line.unit_cents, order.payment_method),
              };
            })
          : [],
        amountText: "0.00",
      });
      if (r) remember(this);
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this._refreshing = false;
      this.setData({ loading: false });
    }
  },
  async load(reset = true) {
    const names = {
      requested: "待审核",
      approved: "已通过，待退货核验或退款",
      processing: "退款处理中",
      unknown: "退款结果待核查",
      failed: "退款失败待处理",
      rejected: "已驳回",
      success: "已实际退款",
    };
    return loadPage(this, {
      reset,
      url: "/mini/refunds" + (this.orderId ? "?orderId=" + this.orderId : ""),
      map: (i) => ({
        ...i,
        statusName: names[i.status] || "待核查",
        amountText: amount(
          i.total_cents,
          this.data.order && this.data.order.payment_method,
        ),
      }),
    });
  },
  onReachBottom() {
    return this.load(false);
  },
  async onPullDownRefresh() {
    try {
      await this.refresh();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  quantity(e) {
    if (this.data.busy) return;
    const index = Number(e.currentTarget.dataset.index),
      value = e.detail.value;
    const lines = this.data.lines.map((line, i) =>
      i === index ? { ...line, selectedQty: value } : line,
    );
    const total = lines.reduce(
      (sum, line) => sum + (Number(line.selectedQty) || 0) * line.unit_cents,
      0,
    );
    this.requestKey = null;
    this.setData({
      lines,
      amountText: amount(
        total,
        this.data.order && this.data.order.payment_method,
      ),
      submitted: false,
    });
  },
  reason(e) {
    this.requestKey = null;
    this.setData({ reason: e.detail.value, submitted: false });
  },
  async submit() {
    if (this.data.busy) return;
    const items = this.data.lines
      .filter((line) => Number(line.selectedQty) > 0)
      .map((line) => ({ lineId: line.id, quantity: Number(line.selectedQty) }));
    if (!items.length || !this.data.reason.trim())
      return this.setData({ error: "请选择商品数量并填写售后原因" });
    if (
      this.data.lines.some(
        (line) =>
          !Number.isInteger(Number(line.selectedQty)) ||
          Number(line.selectedQty) < 0 ||
          Number(line.selectedQty) > line.available,
      )
    )
      return this.setData({
        error: "申请数量须为整数，且不超过剩余可申请数量",
      });
    this.requestKey =
      this.requestKey ||
      "user-refund-" + Date.now() + "-" + Math.random().toString(36).slice(2);
    this.setData({ busy: true, error: "" });
    try {
      await request("/refunds", "POST", {
        orderId: this.orderId,
        items,
        reason: this.data.reason.trim(),
        requestKey: this.requestKey,
      });
      this.requestKey = null;
      this.setData({ reason: "", submitted: true });
      await this.refresh();
      wx.showToast({ title: "申请已受理，等待审核", icon: "none" });
    } catch (e) {
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
  chooseOrder() {
    wx.navigateTo({ url: "/pages/orders/index" });
  },
  copy() {
    if (this.data.service) wx.setClipboardData({ data: this.data.service });
  },
});
