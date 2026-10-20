const { showDialog } = require("../../utils/dialog");
const { request } = require("../../utils/api");
const { decorate } = require("../order/view");
const format = (n) =>
  (n / 100).toLocaleString("en-US", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember, session } = require("../../utils/state");
const kinds = {
  credit: "品牌发积分",
  spend: "订货支付",
  order_refund: "订单退款",
  cash_refund: "原款退现金",
  correction: "来源积分纠错",
};
Page({
  data: {
    wallet: null,
    ...listData("ledger"),
    filter: "all",
    filterName: "全部类型",
    user: null,
    error: "",
    busy: false,
    showTransfer: false,
    order: null,
    paid: false,
    canPay: false,
    insufficient: false,
  },
  onLoad(options) {
    this.orderId = Number(options.orderId) || 0;
  },
  onShow() {
    if (this.orderId || !isFresh(this)) return this.load();
  },
  async onPullDownRefresh() {
    try {
      await this.load();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  async load() {
    if (this._loading) return;
    this._loading = true;
    this.setData({ canPay: false });
    const identity = session();
    if (!identity) {
      this.setData({
        wallet: null,
        user: null,
        ledger: [],
        order: null,
        paid: false,
        error: "请先登录后查看本人积分钱包",
      });
      this._loading = false;
      return;
    }
    if (this._loadedSession !== undefined && this._loadedSession !== identity)
      this.setData({ wallet: null, user: null, ledger: [], order: null });
    try {
      const [wallet, user, order, ledgerOk] = await Promise.all([
        request("/mini/wallet"),
        request("/me"),
        this.orderId
          ? request("/orders/" + this.orderId)
          : Promise.resolve(null),
        this.loadLedger(),
      ]);
      if (session() !== identity) return;
      this.setData({
        user,
        wallet: {
          ...wallet,
          balanceText: format(wallet.balanceCents),
          availableText: format(wallet.availableCents),
          heldText: format(wallet.heldCents),
          creditedText: format(wallet.creditedCents),
          spentText: format(wallet.spentCents),
        },
      });
      if (order)
        this.setData({
          order: { ...order, totalText: format(order.total_cents) },
          paid:
            order.payment_method === "points" && order.payment_state === "paid",
          canPay:
            decorate(order).canPay &&
            order.kind === "goods" &&
            ["B", "C"].includes(user.identity),
          insufficient: wallet.availableCents < order.total_cents,
        });
      if (ledgerOk) remember(this);
    } catch (e) {
      if (session() === identity || e.status === 401)
        this.setData({
          error: e.message,
          ...(e.status === 401
            ? { user: null, wallet: null, ledger: [], order: null }
            : {}),
        });
    } finally {
      this._loading = false;
    }
  },
  async loadLedger(reset = true) {
    const ok = await loadPage(this, {
      key: "ledger",
      reset,
      url: "/mini/wallet/ledger?kind=" + this.data.filter,
      map: (e) => ({
        ...e,
        title: kinds[e.kind] || "积分变动",
        deltaText: (e.delta_cents > 0 ? "+" : "") + format(e.delta_cents),
        dateText: e.create_time.replace("T", " ").slice(0, 16),
        icon:
          e.kind === "credit"
            ? "credit"
            : e.kind === "order_refund"
              ? "refund"
              : "spend",
        source: e.order_number
          ? "订单 " + e.order_number
          : "原收款 " + e.external_transaction_id,
      }),
    });
    return ok;
  },
  onReachBottom() {
    return this.loadLedger(false);
  },
  async filter() {
    const options = [
      ["all", "全部类型"],
      ["credit", "品牌发放"],
      ["spend", "订货支付"],
      ["order_refund", "订单退款"],
      ["cash_refund", "原款退现金"],
      ["correction", "来源纠错"],
    ];
    const result = await showDialog(this, {
      mode: "select",
      title: "筛选积分明细",
      items: options.map((option) => option[1]),
      selectedIndex: options.findIndex(
        (option) => option[0] === this.data.filter,
      ),
    });
    if (!result.confirm || options[result.tapIndex][0] === this.data.filter)
      return;
    this.setData({
      filter: options[result.tapIndex][0],
      filterName: options[result.tapIndex][1],
    });
    this.setData({ ledger: [] });
    await this.loadLedger();
  },
  transfer() {
    this.setData({ showTransfer: !this.data.showTransfer });
  },
  closeTransfer() {
    this.setData({ showTransfer: false });
  },
  stop() {},
  back() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.switchTab({ url: "/pages/profile/index" });
  },
  login() {
    wx.switchTab({ url: "/pages/profile/index" });
  },
  cashRefunds() {
    wx.navigateTo({ url: "/pages/cash-refunds/index" });
  },
  order() {
    if (this.orderId)
      wx.navigateTo({ url: "/pages/order/index?id=" + this.orderId });
  },
  ledgerOrder(e) {
    const id = e.currentTarget.dataset.id;
    if (id) wx.navigateTo({ url: "/pages/order/index?id=" + id });
  },
  async pay() {
    if (this.data.busy || !this.data.canPay || !this.data.order) return;
    if (this.data.insufficient) {
      this.setData({
        error: "积分不足，请线下充值并核款，或返回订单整笔线下付款",
      });
      return;
    }
    this.setData({ busy: true, error: "" });
    try {
      const confirmed = await showDialog(this, {
        title: "确认整单积分付款",
        content:
          "本次扣减 " +
          this.data.order.totalText +
          " 积分。退款积分按原批次退回；积分不足可线下充值或整笔线下付款。",
        confirmText: "确认扣减",
        cancelText: "暂不付款",
      });
      if (!confirmed.confirm) return;
      const result = await request(
        "/orders/" + this.orderId + "/pay/points",
        "POST",
      );
      if (
        !result.order ||
        result.order.id !== this.orderId ||
        result.order.payment_method !== "points" ||
        result.order.payment_state !== "paid"
      )
        throw new Error("积分扣减结果待核实，请刷新订单后确认，勿重复操作");
      this.setData({
        paid: true,
        canPay: false,
        order: { ...result.order, totalText: format(result.order.total_cents) },
      });
      await this.load();
      wx.showToast({ title: "积分付款成功", icon: "success" });
    } catch (e) {
      this.setData({ error: e.message });
      await this.load();
      this.setData({ error: e.message });
    } finally {
      this.setData({ busy: false });
    }
  },
});
