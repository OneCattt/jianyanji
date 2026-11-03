const { showDialog } = require("../../utils/dialog");
const { request, login: signIn } = require("../../utils/api");
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
    paymentMode: false,
    paymentLoading: false,
    shortfallText: "",
    afterPayText: "",
  },
  onLoad(options) {
    this.orderId = Number(options.orderId) || 0;
    this.setData({ paymentMode: Boolean(this.orderId) });
    wx.setNavigationBarTitle({ title: this.orderId ? "订单付款" : "积分明细" });
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
    this.setData({ canPay: false, paymentLoading: true, error: "" });
    const identity = session();
    if (!identity) {
      this.setData({
        wallet: null,
        user: null,
        ledger: [],
        order: null,
        paid: false,
        error: this.orderId
          ? "请先登录后支付订单"
          : "请先登录后查看本人积分钱包",
        paymentLoading: false,
      });
      this._loading = false;
      return;
    }
    if (this._loadedSession !== undefined && this._loadedSession !== identity)
      this.setData({
        wallet: null,
        user: null,
        ledger: [],
        order: null,
        paid: false,
      });
    try {
      const [wallet, user, order, ledgerOk] = await Promise.all([
        request("/mini/wallet"),
        request("/me"),
        this.orderId
          ? request("/orders/" + this.orderId)
          : Promise.resolve(null),
        this.orderId ? Promise.resolve(true) : this.loadLedger(),
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
      if (order) {
        const view = decorate(order);
        const paid =
          order.payment_method === "points" && order.payment_state === "paid";
        this.setData({
          order: {
            ...view,
            totalText: format(order.total_cents),
            goodsPointsText: format(order.goods_cents),
            shippingPointsText: format(order.shipping_cents),
          },
          paid,
          canPay: view.canPay && ["goods", "package"].includes(order.kind),
          insufficient: wallet.availableCents < order.total_cents,
          shortfallText: format(
            Math.max(0, order.total_cents - wallet.availableCents),
          ),
          afterPayText: format(
            Math.max(0, wallet.availableCents - order.total_cents),
          ),
        });
        wx.setNavigationBarTitle({ title: paid ? "付款成功" : "订单付款" });
      }
      if (ledgerOk) remember(this);
    } catch (e) {
      if (session() === identity || e.status === 401)
        this.setData({
          error:
            this.data.paid && e.status !== 401
              ? "付款已成功，最新余额或订单刷新失败，请重新查询：" + e.message
              : e.message,
          ...(e.status === 401
            ? { user: null, wallet: null, ledger: [], order: null }
            : {}),
        });
    } finally {
      this._loading = false;
      this.setData({ paymentLoading: false });
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
    if (this.orderId) return;
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
  async login() {
    try {
      await signIn();
      await this.load();
    } catch (error) {
      if (error.code !== "LOGIN_CANCELLED")
        this.setData({ error: error.message });
    }
  },
  completeProfile() {
    wx.navigateTo({ url: "/pages/settings/index" });
  },
  cashRefunds() {
    wx.navigateTo({ url: "/pages/cash-refunds/index" });
  },
  walletDetails() {
    wx.navigateTo({ url: "/pages/wallet/index" });
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
        error: "积分不足，请联系总部微信充值，积分到账后再支付",
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
          " 积分。退款积分按原批次退回；积分不足请联系总部微信充值，足额后一次支付。",
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
        wallet: null,
        order: {
          ...this.data.order,
          ...result.order,
          totalText: format(result.order.total_cents),
        },
      });
      wx.setNavigationBarTitle({ title: "付款成功" });
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
