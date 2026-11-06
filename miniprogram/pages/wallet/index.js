const { showDialog } = require("../../utils/dialog");
const { request, login: signIn } = require("../../utils/api");
const { decorate, dateText } = require("../order/view");
const format = (n) =>
  (n / 100).toLocaleString("en-US", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember, session } = require("../../utils/state");
const identityText = (identity) =>
  identity === "B" ? "19800店主" : identity === "C" ? "2980店主" : "";
const addressText = (address) =>
  address
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
function paymentCopy(data) {
  const { paid, canPay, expired, isPackage, activated, paymentLoading, order } =
    data;
  if (paid) {
    if (!isPackage)
      return {
        paymentStatusText: "支付成功",
        paymentCaptionText: "本单积分已扣减，可查看订单与配送进度",
        paymentHelpText:
          "付款后按本次配送与寄存安排履约，退款积分按原批次退回。",
      };
    if (activated)
      return {
        paymentStatusText: "支付成功，身份已开通",
        paymentCaptionText: "身份已开通，商品按本次配送与寄存安排交付",
        paymentHelpText: "退款积分按原批次退回；身份撤销按原规则办理。",
      };
    return {
      paymentStatusText: "付款成功，身份状态待核实",
      paymentCaptionText: "付款已成功，身份状态待核实，请重新查询",
      paymentHelpText: "付款结果已保留，请勿重复支付；可刷新查询资格状态。",
    };
  }
  if (expired)
    return {
      paymentStatusText: "本次购买已关闭，未开通",
      paymentCaptionText:
        "原待付款订单已过期，请返回入驻页刷新后重新办理；已充值积分保留",
      paymentHelpText: "过期后按原关闭释放流程重新办理，不延长原订单有效期。",
    };
  if (order?.status === "closed")
    return {
      paymentStatusText: isPackage ? "本次购买已关闭，未开通" : "订单已关闭",
      paymentCaptionText: isPackage
        ? "请返回入驻页刷新实际状态后重新办理"
        : order.hint,
      paymentHelpText: isPackage
        ? "原订单已关闭并释放占用，未开通身份；已充值积分保留。"
        : order.hint,
    };
  if (canPay) {
    if (isPackage)
      return {
        paymentStatusText: "待付款",
        paymentCaptionText:
          "支付成功后开通对应身份，商品按本次配送与寄存安排交付",
        paymentHelpText:
          "先由总部核款增加积分，再回来手动支付本单；充值本身不开通身份。",
      };
    return {
      paymentStatusText: "待付款",
      paymentCaptionText: "确认后从积分余额一次扣减",
      paymentHelpText: "付款后按本次配送与寄存安排履约，退款积分按原批次退回。",
    };
  }
  if (paymentLoading)
    return {
      paymentStatusText: "正在核对订单",
      paymentCaptionText: "请稍候，核对完成后可继续操作",
      paymentHelpText: "",
    };
  return {
    paymentStatusText: order?.receiptText || "",
    paymentCaptionText: order?.hint || "",
    paymentHelpText: "付款后按本次配送与寄存安排履约，退款积分按原批次退回。",
  };
}
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
    isPackage: false,
    activated: false,
    activationPending: false,
    expired: false,
    paymentStatusText: "",
    paymentCaptionText: "",
    paymentHelpText: "",
    navigationFailed: false,
  },
  onLoad(options) {
    this.orderId = Number(options.orderId) || 0;
    this._navigated = false;
    this.setData({ paymentMode: Boolean(this.orderId) });
    wx.setNavigationBarTitle({
      title: this.orderId ? "积分支付" : "积分明细",
    });
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
        const isPackage = order.kind === "package";
        const snapshot = order.snapshot || {};
        const expiresAt = order.expires_at ? new Date(order.expires_at) : null;
        const expired = Boolean(
          expiresAt &&
          expiresAt.getTime() <= Date.now() &&
          order.status === "pending" &&
          !["paid", "refunded"].includes(order.payment_state),
        );
        const activated = isPackage
          ? snapshot.onboardingApproved === true ||
            (paid && this.data.activated === true)
          : false;
        const next = {
          order: {
            ...view,
            totalText: format(order.total_cents),
            goodsPointsText: format(order.goods_cents),
            shippingPointsText: format(order.shipping_cents),
            identityText: identityText(snapshot.identity),
            deliveryText:
              snapshot.deliveryMode === "store" ? "全部寄存" : "按本次数量发货",
            addressText: addressText(snapshot.address),
            expiresText: expiresAt ? dateText(order.expires_at) : "",
          },
          isPackage,
          paid,
          activated,
          activationPending: isPackage && paid && !activated,
          expired,
          canPay:
            view.canPay &&
            ["goods", "package"].includes(order.kind) &&
            !expired,
          insufficient: wallet.availableCents < order.total_cents,
          shortfallText: format(
            Math.max(0, order.total_cents - wallet.availableCents),
          ),
          afterPayText: format(
            Math.max(0, wallet.availableCents - order.total_cents),
          ),
        };
        next.paymentLoading = false;
        Object.assign(next, paymentCopy(next));
        this.setData(next);
        wx.setNavigationBarTitle({
          title: paid
            ? isPackage && activated
              ? "付款成功，身份已开通"
              : "付款成功"
            : expired
              ? "订单已过期"
              : "积分支付",
        });
        // 已付款或已关闭等终态不再停留在可付款页，直接进入真实订单详情。
        if (paid || ["closed", "refunded"].includes(order.status))
          this.navigateToOrder(!!(this._payAttempted && paid));
      }
      if (ledgerOk) remember(this);
    } catch (e) {
      if (session() === identity || e.status === 401)
        this.setData({
          error:
            this.data.paid && e.status !== 401
              ? "支付已成功，最新资料刷新失败，请重新查询：" + e.message
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
    if (this.orderId) this.navigateToOrder(false, true);
  },
  /** 成功确认后最多导航一次；失败只保留已付款事实并提供重试。 */
  navigateToOrder(justPaid, stack) {
    if (!this.orderId || this._navigated) return;
    this._navigated = true;
    this.setData({ navigationFailed: false });
    const url =
      "/pages/order/index?id=" + this.orderId + (justPaid ? "&justPaid=1" : "");
    const done = () => {
      if (stack) this._navigated = false;
    };
    wx.redirectTo({
      url,
      fail: () => {
        this._navigated = false;
        this.setData({ navigationFailed: true });
      },
      complete: done,
    });
  },
  retryNavigate() {
    this.navigateToOrder(this.data.paid);
  },
  myOrders() {
    if (this.orderId) wx.redirectTo({ url: "/pages/orders/index" });
    else wx.navigateTo({ url: "/pages/orders/index" });
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
      const isPackage = this.data.isPackage;
      const confirmed = await showDialog(this, {
        title: isPackage ? "确认积分购买权益包" : "确认整单积分付款",
        content:
          "本次扣减 " +
          this.data.order.totalText +
          " 积分。" +
          (isPackage
            ? "支付成功后开通对应身份，商品按本次配送与寄存安排交付。"
            : "") +
          "退款积分按原批次退回；积分不足请联系总部微信充值，足额后一次支付。",
        confirmText: "确认扣减",
        cancelText: "暂不付款",
      });
      if (!confirmed.confirm) return;
      // 记录本次确曾发起扣减，响应未知后查询到已付时才强调成功结果。
      this._payAttempted = true;
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
      let activated = !isPackage;
      let activationPending = false;
      if (isPackage) {
        const snapshot = result.order.snapshot || {};
        activated = snapshot.onboardingApproved === true;
        if (!activated) {
          const onboardingId =
            snapshot.onboardingId || this.data.order.snapshot?.onboardingId;
          if (onboardingId) {
            try {
              const application = await request("/onboardings/" + onboardingId);
              activated =
                application.status === "approved" ||
                application.qualification_snapshot != null;
              activationPending = !activated;
            } catch (_) {
              activationPending = true;
            }
          } else activationPending = true;
        }
      }
      const next = {
        isPackage,
        paid: true,
        canPay: false,
        wallet: null,
        activated,
        activationPending,
        order: {
          ...this.data.order,
          ...result.order,
          totalText: format(result.order.total_cents),
        },
      };
      Object.assign(next, paymentCopy(next));
      this.setData(next);
      wx.setNavigationBarTitle({
        title: isPackage && activated ? "付款成功，身份已开通" : "付款成功",
      });
      // 服务端已确认扣减，立即进入本单详情；余额流水读取失败不得阻塞主流程。
      this.navigateToOrder(true);
      wx.showToast({
        title: isPackage && activated ? "支付成功，身份已开通" : "积分付款成功",
        icon: "success",
      });
    } catch (e) {
      const unknown = !e.status;
      this.setData({
        error: unknown
          ? "付款结果待确认，请重新查询原订单核实，请勿换单或重复付款"
          : e.message,
      });
      await this.load();
      if (!this.data.paid)
        this.setData({
          error: unknown
            ? "付款结果待确认，请重新查询原订单核实，请勿换单或重复付款"
            : e.message,
        });
    } finally {
      this.setData({ busy: false });
    }
  },
});
