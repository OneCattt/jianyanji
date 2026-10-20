const { request } = require("../../utils/api");
const { decorate } = require("../order/view");
const format = (n) =>
  (n / 100).toLocaleString("en-US", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
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
    ledger: [],
    visible: [],
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
    this.load();
  },
  async onPullDownRefresh() {
    try {
      await this.load();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
  async load() {
    this.setData({ canPay: false });
    if (!wx.getStorageSync("jyj_token")) {
      this.setData({
        wallet: null,
        user: null,
        ledger: [],
        visible: [],
        order: null,
        paid: false,
        error: "请先登录后查看本人积分钱包",
      });
      return;
    }
    try {
      const [wallet, user] = await Promise.all([
        request("/wallet"),
        request("/me"),
      ]);
      const ledger = wallet.ledger.map((e) => ({
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
      }));
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
        ledger,
        error: "",
      });
      this.applyFilter();
      if (this.orderId) {
        const order = await request("/orders/" + this.orderId);
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
      }
    } catch (e) {
      this.setData({
        error: e.message,
        ...(e.status === 401
          ? { user: null, wallet: null, ledger: [], visible: [], order: null }
          : {}),
      });
    }
  },
  applyFilter() {
    this.setData({
      visible:
        this.data.filter === "all"
          ? this.data.ledger
          : this.data.ledger.filter((e) => e.kind === this.data.filter),
    });
  },
  filter() {
    const options = [
      ["all", "全部类型"],
      ["credit", "品牌发放"],
      ["spend", "订货支付"],
      ["order_refund", "订单退款"],
      ["cash_refund", "原款退现金"],
      ["correction", "来源纠错"],
    ];
    wx.showActionSheet({
      itemList: options.map((o) => o[1]),
      success: (r) => {
        this.setData({
          filter: options[r.tapIndex][0],
          filterName: options[r.tapIndex][1],
        });
        this.applyFilter();
      },
    });
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
      const confirmed = await new Promise((resolve) =>
        wx.showModal({
          title: "确认整单积分付款",
          content:
            "本次扣减 " +
            this.data.order.totalText +
            " 积分。退款积分按原批次退回；积分不足可线下充值或整笔线下付款。",
          confirmText: "确认扣减",
          confirmColor: "#8d1724",
          success: (r) => resolve(r.confirm),
          fail: () => resolve(false),
        }),
      );
      if (!confirmed) return;
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
