const { showDialog } = require("../../utils/dialog");
const { request, login: signIn } = require("../../utils/api");
const { money } = require("../../utils/catalog");
const { imageUrl } = require("../../utils/image");
const { listData, loadPage } = require("../../utils/list");
const { isFresh, remember, session } = require("../../utils/state");
const { shippingLines } = require("../../utils/fulfillment");
const key = () =>
  "onboard-" + Date.now() + "-" + Math.random().toString(36).slice(2);
const states = {
  submitted: "待付款，支付后开通",
  approved: "身份已开通",
  rejected: "申请未通过",
  revoked: "本次身份已撤销",
};
const orderStates = {
  pending: "待付款",
  paid: "待安排发货",
  shipped: "商品已发出",
  completed: "已确认收货",
  refunded: "商品已退款",
  closed: "本次购买已关闭",
};
const compactMoney = (value) => money(value).replace(/\.00$/, "");
const meaningful = (value) => !!value && value.trim() !== "/";
function decoratePackage(p) {
  return {
    ...p,
    amountText: compactMoney(p.amountCents),
    valueText: compactMoney(p.valueCents),
    pointsText: (p.amountCents / 100).toFixed(2).replace(/\.00$/, ""),
    rightsImageDisplay: imageUrl(p.rightsImage, "detail"),
    benefitsText: meaningful(p.benefits) ? p.benefits : "",
    conditionsText: meaningful(p.conditions) ? p.conditions : "",
    agreementText: meaningful(p.agreement) ? p.agreement : "",
    lines: (p.lines || []).map((l) => ({
      ...l,
      priceText: money(l.unitCents),
    })),
  };
}
function identityText(user) {
  if (user?.identity === "B") return "19800店主";
  if (user?.identity === "C") return "2980店主";
  return user ? "普通客户" : "未登录";
}
function primaryView(data) {
  const amountLabel = "所需积分";
  const selectedAmount = data.selected ? data.selected.pointsText : "待配置";
  const pending = data.current?.status === "submitted";
  if (data.busy)
    return {
      primaryActionName: "none",
      primaryText: data.busyText || "正在办理…",
      primaryHint: "请稍候，不要重复点击",
      primaryDisabled: true,
      primaryAmountLabel: pending ? "待付积分" : amountLabel,
      primaryAmountText: pending ? data.current.pointsText : selectedAmount,
    };
  if (data.loading)
    return {
      primaryActionName: "none",
      primaryText: "正在读取入驻资料",
      primaryHint: "请稍候",
      primaryDisabled: true,
      primaryAmountLabel: pending ? "待付积分" : amountLabel,
      primaryAmountText: pending ? data.current.pointsText : selectedAmount,
    };
  if (pending) {
    if (data.invitationLocked)
      return {
        primaryActionName: "pay",
        primaryText: "继续付款",
        primaryHint: "待付款，支付成功后开通身份",
        primaryDisabled: false,
        primaryAmountLabel: "待付积分",
        primaryAmountText: data.current.pointsText,
      };
    const confirmed = data.invitationStatus === "confirmed";
    return {
      primaryActionName: confirmed ? "reconfirm" : "guide",
      primaryText: confirmed ? "确认更新邀请归属" : "重新确认邀请关系",
      primaryHint: confirmed
        ? "确认归属后继续准备付款"
        : "请先核对并勾选确认邀请归属",
      primaryDisabled: false,
      primaryAmountLabel: "待付积分",
      primaryAmountText: data.current.pointsText,
    };
  }
  const upgradingToB =
    data.current?.status === "approved" &&
    data.current.target_identity === "C" &&
    data.target === "B" &&
    data.user?.identity === "C";
  if (data.current?.status === "approved" && !upgradingToB)
    return {
      primaryActionName: "order",
      primaryText: "查看商品交付订单",
      primaryHint: "身份已开通，商品按本次配送与寄存安排交付",
      primaryDisabled: !data.current.order,
      primaryAmountLabel: "本次已付积分",
      primaryAmountText: data.current.pointsText,
    };
  if (upgradingToB)
    return {
      primaryActionName: "submit",
      primaryText: "升级19800店主",
      primaryHint:
        data.applyHint ||
        "另购完整19800权益包，原2980权益包不抵扣；支付成功后开通",
      primaryDisabled: !data.canApply,
      primaryAmountLabel: "所需积分",
      primaryAmountText: selectedAmount,
    };
  if (
    data.current?.status === "rejected" &&
    data.current.order?.status === "closed"
  )
    return {
      primaryActionName: "submit",
      primaryText: "重新办理",
      primaryHint:
        data.applyHint || "本次购买已关闭，未开通；请确认资料后重新办理",
      primaryDisabled: !!data.applyHint,
      primaryAmountLabel: amountLabel,
      primaryAmountText: selectedAmount,
    };
  return {
    primaryActionName: "submit",
    primaryText: "确认并购买权益包",
    primaryHint: data.applyHint || "下一步确认积分付款，支付成功后开通身份",
    primaryDisabled: !data.canApply,
    primaryAmountLabel: amountLabel,
    primaryAmountText: selectedAmount,
  };
}
function applicationHint(data) {
  if (data.busy) return "正在办理，请勿重复操作";
  if (data.loading) return "正在读取入驻资料";
  if (data.loadError) return "资料读取失败，请重试";
  if (!data.selected) return "产品包尚未配置";
  if (data.current?.status === "submitted") return "已有待付款申请，请继续付款";
  if (!data.user) return "请先登录并验证邀请关系";
  if (
    data.user.identity === "B" ||
    (data.user.identity === "C" && data.target === "C")
  )
    return "已具备该身份，无需重复申请";
  if (data.invitationStatus === "verifying") return "正在验证邀请关系";
  if (
    !data.relation ||
    !["verified", "confirmed"].includes(data.invitationStatus)
  )
    return "请先验证邀请关系";
  if (data.invitationStatus !== "confirmed") return "请确认邀请人与归属";
  if (!data.agreed) return "请阅读并同意入驻协议";
  if (!data.address) return "请选择产品包收货地址";
  return "";
}
function decorateApplication(a) {
  const snapshot = a.package_snapshot || {};
  const orderClosed = a.order?.status === "closed";
  const address = snapshot.address;
  return {
    ...a,
    statusText:
      a.status === "rejected" && orderClosed
        ? "本次购买已关闭，未开通"
        : states[a.status] || a.status,
    deliveryText: a.order
      ? orderStates[a.order.status] || a.order.status
      : "购买产品包后安排交付",
    identityText:
      snapshot.identity === "B"
        ? "19800店主"
        : snapshot.identity === "C"
          ? "2980店主"
          : "—",
    deliveryModeText:
      snapshot.deliveryMode === "store" ? "全部寄存" : "按本次数量发货",
    addressText: address
      ? [
          address.recipient,
          address.phone,
          [address.province, address.city, address.district, address.detail]
            .filter(Boolean)
            .join(""),
        ]
          .filter(Boolean)
          .join(" · ")
      : "",
    amountText: compactMoney(snapshot.amountCents || 0),
    pointsText: ((snapshot.amountCents || 0) / 100)
      .toFixed(2)
      .replace(/\.00$/, ""),
  };
}

Page({
  data: {
    packages: [],
    selected: null,
    target: "C",
    deliveryMode: "ship",
    transfer: null,
    user: null,
    address: null,
    applications: [],
    current: null,
    inviterId: "",
    shareCode: "",
    relation: null,
    invitationStatus: "empty",
    invitationError: "",
    invitationLocked: false,
    ...listData("qualifications"),
    qualificationsError: "",
    agreed: false,
    busy: false,
    busyText: "",
    loading: false,
    loadError: "",
    error: "",
    notice: "",
    canApply: false,
    applyHint: "请先验证邀请关系",
    identityText: "未登录",
    primaryText: "确认并购买权益包",
    primaryHint: "请先验证邀请关系",
    primaryDisabled: true,
    primaryActionName: "submit",
    primaryAmountLabel: "所需积分",
    primaryAmountText: "待配置",
    showTransfer: false,
    showRightsImage: false,
    rightsImageFailed: false,
    sheetTop: 96,
    keyboardHeight: 0,
    scrollAnchor: "",
    inputFocus: false,
  },
  updateState(values = {}) {
    if (this._unloaded) return;
    const data = { ...this.data, ...values };
    const applyHint = applicationHint(data);
    data.applyHint = applyHint;
    data.canApply = !applyHint;
    data.identityText = identityText(data.user);
    this.setData({
      ...values,
      ...primaryView(data),
      applyHint,
      canApply: data.canApply,
      identityText: data.identityText,
    });
  },
  onLoad(options = {}) {
    this.requestKey = key();
    this.updateFrame();
    let source = options;
    if (!options.shareCode && !options.inviterId && options.scene) {
      try {
        source = {};
        for (const item of decodeURIComponent(options.scene).split("&")) {
          const [name, value] = item.split("=");
          if (["shareCode", "inviterId"].includes(name))
            source[name] = value || "";
        }
      } catch (_) {
        this.setData({ invitationError: "邀请参数无法识别，请填写邀请人编号" });
      }
    }
    const code =
      source.shareCode ||
      (source.inviterId ? "" : wx.getStorageSync("jyj_invitation_candidate")) ||
      "";
    if (source.inviterId && !source.shareCode)
      wx.removeStorageSync("jyj_invitation_candidate");
    if (code) wx.setStorageSync("jyj_invitation_candidate", code);
    this.updateState({
      inviterId: source.inviterId || "",
      shareCode: code,
      ...(["B", "C"].includes(source.target) ? { target: source.target } : {}),
    });
  },
  updateFrame() {
    if (
      typeof wx.getWindowInfo !== "function" ||
      typeof wx.getMenuButtonBoundingClientRect !== "function"
    )
      return;
    const info = wx.getWindowInfo(),
      capsule = wx.getMenuButtonBoundingClientRect();
    const safeTop = Math.max(info.statusBarHeight + 44, capsule.bottom) + 8;
    this.setData({
      sheetTop: Math.max(safeTop, Math.round(info.windowHeight * 0.1)),
    });
  },
  onResize() {
    this.updateFrame();
  },
  onUnload() {
    this._unloaded = true;
    this.invalidateInvitation();
    this._loadVersion = (this._loadVersion || 0) + 1;
  },
  onShow() {
    return this.load();
  },
  load(force = false) {
    if (this._loading && this._loadingSession === session())
      return this._loading;
    const chosenId = wx.getStorageSync("jyj_address");
    if (!force && isFresh(this) && chosenId === this._chosenAddress)
      return Promise.resolve(true);
    this._loadingSession = session();
    const loading = this.loadData(chosenId).finally(() => {
      if (this._loading === loading) this._loading = null;
    });
    this._loading = loading;
    return loading;
  },
  async loadData(chosenId) {
    const identity = session(),
      version = (this._loadVersion || 0) + 1;
    this._loadVersion = version;
    const active = () =>
      !this._unloaded &&
      this._loadVersion === version &&
      session() === identity;
    if (this._session !== identity) {
      this._session = identity;
      this.invalidateInvitation();
      this.updateState({
        user: null,
        current: null,
        applications: [],
        address: null,
        relation: null,
        invitationStatus: "empty",
        invitationLocked: false,
        agreed: false,
        ...listData("qualifications"),
      });
    }
    this.updateState({
      loading: true,
      loadError: "",
      error: "",
      qualificationsError: "",
    });
    try {
      const config = await request("/membership");
      if (!active()) return false;
      const packages = config.packages.map(decoratePackage);
      this.updateState({ packages, transfer: config.transfer });
      let current = null,
        qualificationsOk = true;
      if (identity) {
        const [user, addresses, applications, qualified] = await Promise.all([
          request("/me"),
          request("/mini/address" + (chosenId ? "?id=" + chosenId : "")),
          request("/mini/onboardings?limit=1&page=1"),
          this.loadQualifications(),
        ]);
        if (!active()) return false;
        const apps = applications.items.map(decorateApplication);
        current = apps[0] || null;
        qualificationsOk = qualified;
        this.updateState({
          user,
          address: addresses.address,
          applications: apps,
          current,
          qualificationsError: qualified ? "" : this.data.error,
          error: "",
        });
      } else
        this.updateState({
          user: null,
          address: null,
          current: null,
          applications: [],
        });
      const pending = current?.status === "submitted";
      if (pending) {
        const locked =
          current.relation_confirmed === true ||
          current.relation_confirmed === 1;
        const changed =
          this.data.inviterId !== String(current.inviter_id || "") ||
          this.data.target !== current.target_identity;
        if (changed) this.invalidateInvitation();
        if (!locked && this.data.invitationLocked)
          this.updateState({ relation: null, invitationStatus: "empty" });
        this.updateState({
          inviterId: String(current.inviter_id || ""),
          shareCode: "",
          invitationLocked: locked,
        });
        this.choose(current.target_identity, true);
        if (current.package_snapshot?.targetIdentity) {
          const selected = decoratePackage(current.package_snapshot);
          selected.lines = shippingLines(
            selected.lines,
            selected.lines,
            current.package_snapshot.deliveryMode || "ship",
          );
          this.updateState({
            selected,
            deliveryMode: current.package_snapshot.deliveryMode || "ship",
          });
          if (current.package_snapshot.address)
            this.updateState({ address: current.package_snapshot.address });
        }
        if (locked) {
          const known = this.data.relation;
          const same =
            known &&
            known.inviterId === current.inviter_id &&
            known.principalId === current.principal_id;
          this.updateState({
            relation: same
              ? known
              : {
                  inviterId: current.inviter_id,
                  inviterName: current.inviter_id
                    ? "用户 #" + current.inviter_id
                    : "品牌方首批核验",
                  principalId: current.principal_id,
                  principalName:
                    current.target_identity === "B"
                      ? "本人（开通后）"
                      : "用户 #" + current.principal_id,
                },
            invitationStatus: "confirmed",
            invitationError: "",
          });
        } else await this.resolveInvitation();
      } else {
        this.updateState({ invitationLocked: false });
        this.choose(this.data.target, true);
        if (this.data.user && (this.data.inviterId || this.data.shareCode))
          await this.resolveInvitation();
      }
      if (!active()) return false;
      this._chosenAddress = chosenId;
      if (qualificationsOk) remember(this);
      return true;
    } catch (error) {
      if (active()) this.updateState({ loadError: error.message });
      return false;
    } finally {
      if (!this._unloaded && this._loadVersion === version)
        this.updateState({ loading: false });
    }
  },
  loadQualifications(reset = true) {
    return loadPage(this, {
      key: "qualifications",
      reset,
      url: "/mini/qualifications",
    });
  },
  onReachBottom() {
    if (session()) return this.loadQualifications(false);
  },
  retryLoad() {
    return this.load(true);
  },
  choose(target, preserve = false) {
    const p =
      this.data.packages.find((p) => p.targetIdentity === target) ||
      this.data.packages[0] ||
      null;
    const same = p && this.data.selected?.targetIdentity === p.targetIdentity;
    const selected = p
      ? {
          ...p,
          lines: shippingLines(
            p.lines,
            preserve && same ? this.data.selected.lines : [],
            this.data.deliveryMode,
          ),
        }
      : null;
    this.updateState({
      selected,
      target: selected ? selected.targetIdentity : target,
      agreed:
        preserve && same && this.data.selected.agreement === selected.agreement
          ? this.data.agreed
          : false,
      ...(!same ? { showRightsImage: false, rightsImageFailed: false } : {}),
    });
  },
  async changeTarget(event) {
    const target = event.currentTarget.dataset.target;
    if (
      this.data.busy ||
      this.data.current?.status === "submitted" ||
      target === this.data.target
    )
      return;
    this.requestKey = key();
    this.invalidateInvitation();
    this.choose(target);
    this.updateState({
      relation: null,
      invitationStatus: "empty",
      invitationError: "",
    });
    if (this.data.user && (this.data.inviterId || this.data.shareCode))
      await this.resolveInvitation();
  },
  invalidateInvitation() {
    this._invitationVersion = (this._invitationVersion || 0) + 1;
    this._verification = null;
  },
  onInviter(event) {
    if (this.data.busy || this.data.invitationLocked) return;
    this.requestKey = key();
    this.invalidateInvitation();
    wx.removeStorageSync("jyj_invitation_candidate");
    this.updateState({
      inviterId: event.detail.value,
      shareCode: "",
      relation: null,
      invitationStatus: "empty",
      invitationError: "",
    });
  },
  editInviter() {
    if (this.data.busy || this.data.invitationLocked) return;
    this.onInviter({ detail: { value: this.data.inviterId } });
    this.setData({ inputFocus: true, scrollAnchor: "" });
  },
  invitationKey(target = this.data.target) {
    return [
      session(),
      this.data.user?.id,
      target,
      this.data.shareCode,
      this.data.inviterId,
    ].join("|");
  },
  resolveInvitation(target) {
    if (this._session !== session()) return this.load(true).then(() => null);
    const targetIdentity =
      typeof target === "string" ? target : this.data.target;
    if (this.data.invitationLocked) return Promise.resolve(this.data.relation);
    if (!this.data.user) {
      this.updateState({ invitationError: "请先登录，再验证邀请关系" });
      return Promise.resolve(null);
    }
    const number = this.data.inviterId.trim();
    if (
      !this.data.shareCode &&
      (!/^[1-9]\d*$/.test(number) || !Number.isSafeInteger(Number(number)))
    ) {
      this.updateState({
        relation: null,
        invitationStatus: number ? "failed" : "empty",
        invitationError: number ? "请填写有效的邀请人编号" : "请填写邀请人编号",
      });
      return Promise.resolve(null);
    }
    if (!this.data.shareCode && Number(number) === this.data.user.id) {
      this.updateState({
        relation: null,
        invitationStatus: "failed",
        invitationError: "不能填写自己的邀请人编号",
      });
      return Promise.resolve(null);
    }
    const signature = this.invitationKey(targetIdentity);
    if (this._verification?.signature === signature)
      return this._verification.promise;
    const previous = this.data.relation,
      confirmed = this.data.invitationStatus === "confirmed";
    const version = (this._invitationVersion || 0) + 1;
    this._invitationVersion = version;
    this.updateState({ invitationStatus: "verifying", invitationError: "" });
    if (typeof wx.hideKeyboard === "function") wx.hideKeyboard();
    const active = () =>
      !this._unloaded &&
      this._invitationVersion === version &&
      this.invitationKey(targetIdentity) === signature &&
      this.data.target === targetIdentity;
    const promise = request(
      "/invitations/candidate?targetIdentity=" +
        targetIdentity +
        (this.data.shareCode
          ? "&shareCode=" + encodeURIComponent(this.data.shareCode)
          : "&inviterId=" + encodeURIComponent(number)),
    )
      .then((rel) => {
        if (!active()) return null;
        if (rel.inviterId === this.data.user.id)
          throw Error("不能填写自己的邀请人编号");
        const unchanged =
          confirmed &&
          previous &&
          previous.inviterId === rel.inviterId &&
          previous.principalId === rel.principalId &&
          previous.inviterRelationVersion === rel.inviterRelationVersion;
        this.updateState({
          relation: rel,
          inviterId: String(rel.inviterId),
          invitationStatus: unchanged ? "confirmed" : "verified",
          invitationError: "",
          inputFocus: false,
        });
        return rel;
      })
      .catch((error) => {
        if (active())
          this.updateState({
            relation: null,
            invitationStatus: "failed",
            invitationError: error.message,
          });
        return null;
      })
      .finally(() => {
        if (this._verification?.version === version) this._verification = null;
      });
    this._verification = { signature, version, promise };
    return promise;
  },
  confirmInvitation(event) {
    if (this._session !== session()) return this.load(true);
    if (
      this.data.busy ||
      this.data.invitationLocked ||
      !this.data.relation ||
      !["verified", "confirmed"].includes(this.data.invitationStatus)
    )
      return;
    this.updateState({
      invitationStatus: event.detail.value.includes("confirm")
        ? "confirmed"
        : "verified",
    });
  },
  agreement(event) {
    if (!this.data.busy)
      this.updateState({ agreed: event.detail.value.includes("yes") });
  },
  addresses() {
    if (this.data.busy || this.data.current?.status === "submitted") return;
    this.requestKey = key();
    wx.navigateTo({ url: "/pages/addresses/index?select=1" });
  },
  async login() {
    if (this.data.busy) return;
    try {
      await signIn();
      await this.load(true);
    } catch (error) {
      if (error.code !== "LOGIN_CANCELLED")
        this.updateState({ error: error.message });
    }
  },
  close() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.switchTab({ url: "/pages/home/index" });
  },
  transfer() {
    this.setData({ showTransfer: !this.data.showTransfer });
  },
  toggleRights() {
    this.setData({ showRightsImage: !this.data.showRightsImage });
  },
  onRightsError() {
    this.setData({ rightsImageFailed: true });
  },
  previewRights() {
    if (this.data.selected?.rightsImage)
      wx.previewImage({
        current: this.data.selected.rightsImage,
        urls: [this.data.selected.rightsImage],
      });
  },
  order() {
    if (this.data.current?.order)
      wx.navigateTo({
        url: "/pages/order/index?id=" + this.data.current.order.id,
      });
  },
  async reconfirm() {
    if (
      this.data.busy ||
      this.data.invitationLocked ||
      this.data.current?.status !== "submitted" ||
      this.data.invitationStatus !== "confirmed"
    )
      return;
    const relation = this.data.relation;
    this.updateState({ busy: true, error: "" });
    let saved = false;
    try {
      const accepted = await showDialog(this, {
        title: "重新确认邀请归属",
        content:
          "邀请人：" +
          relation.inviterName +
          "；归属：" +
          relation.principalName,
        confirmText: "确认归属",
        cancelText: "返回核对",
      });
      if (!accepted.confirm) return;
      const current = await request(
        "/onboardings/" + this.data.current.id + "/confirm",
        "POST",
        {
          inviterId: relation.inviterId,
          principalId: relation.principalId,
          relationshipsConfirmed: true,
          conditionsMet: true,
        },
      );
      saved = true;
      this.updateState({
        current: decorateApplication(current),
        invitationLocked: true,
      });
      if (!(await this.load(true)))
        this.updateState({
          error: "邀请归属已确认，进度刷新失败，请刷新查看，不要重复提交",
        });
    } catch (error) {
      this.updateState({
        error: saved
          ? "邀请归属已确认，进度刷新失败，请刷新查看"
          : error.message,
      });
    } finally {
      this.updateState({ busy: false });
    }
  },
  chooseStored() {
    if (!this.data.busy && this.data.current?.status !== "submitted")
      wx.navigateTo({ url: "/pages/storage/index?select=1" });
  },
  deliveryMode(event) {
    if (
      this.data.busy ||
      !this.data.selected ||
      this.data.current?.status === "submitted"
    )
      return;
    this.requestKey = key();
    const mode = event.detail.value;
    this.setData({
      deliveryMode: mode,
      selected: {
        ...this.data.selected,
        lines: this.data.selected.lines.map((line) => ({
          ...line,
          shipQuantity: mode === "store" ? 0 : line.quantity,
        })),
      },
    });
  },
  shipQuantity(event) {
    if (
      this.data.busy ||
      !this.data.selected ||
      this.data.current?.status === "submitted"
    )
      return;
    const index = Number(event.currentTarget.dataset.index),
      quantity = Number(event.detail.value);
    if (
      !this.data.selected.lines[index] ||
      !Number.isInteger(quantity) ||
      quantity < 0 ||
      quantity > this.data.selected.lines[index].quantity
    ) {
      this.updateState({ error: "发货数量超出固定产品包数量" });
      return;
    }
    this.requestKey = key();
    this.setData({ ["selected.lines[" + index + "].shipQuantity"]: quantity });
    this.setData({
      deliveryMode: this.data.selected.lines.some(
        (line) => line.shipQuantity > 0,
      )
        ? "ship"
        : "store",
    });
  },
  focusField(event) {
    this._focusedAnchor = event.currentTarget.dataset.anchor;
    if (this.data.keyboardHeight) this.ensureFocusedVisible();
  },
  keyboardChange(event) {
    const height = Math.max(0, event.detail.height || 0);
    if (height === this.data.keyboardHeight) return;
    this.setData(
      {
        keyboardHeight: height,
        ...(!height ? { inputFocus: false, scrollAnchor: "" } : {}),
      },
      () => {
        if (height) this.ensureFocusedVisible();
      },
    );
  },
  ensureFocusedVisible() {
    const anchor = this._focusedAnchor;
    if (this._unloaded || !anchor || !this.data.keyboardHeight) return;
    const version = (this._keyboardMeasureVersion || 0) + 1;
    this._keyboardMeasureVersion = version;
    // 等键盘占位完成后量取实际可见范围，已经可见的输入框不触发滚动。
    wx.createSelectorQuery()
      .select(".ob-scroll")
      .boundingClientRect()
      .select("#" + anchor)
      .boundingClientRect()
      .exec(([viewport, field]) => {
        if (
          this._unloaded ||
          !this.data.keyboardHeight ||
          this._keyboardMeasureVersion !== version ||
          this._focusedAnchor !== anchor ||
          !viewport ||
          !field
        )
          return;
        if (field.top < viewport.top || field.bottom > viewport.bottom)
          this.setData({ scrollAnchor: "" }, () => {
            this.setData({ scrollAnchor: anchor });
          });
      });
  },
  primaryAction() {
    const action = this.data.primaryActionName;
    if (action === "pay") return this.purchaseWithPoints();
    if (action === "reconfirm") return this.reconfirm();
    if (action === "guide") return this.guideToInvitation();
    if (action === "order") return this.order();
    if (action === "submit") return this.submit();
  },
  guideToInvitation() {
    if (this.data.busy) return;
    this.updateState({
      error: "",
      notice: "请先核对邀请人与归属，勾选确认后再继续付款",
    });
    this.setData({ scrollAnchor: "" }, () => {
      this.setData({ scrollAnchor: "ob-invitation" });
    });
  },
  navigateToWallet(orderId) {
    if (this._navigating) return;
    this._navigating = true;
    const done = () => {
      this._navigating = false;
      if (!this._unloaded)
        this.updateState({ busy: false, busyText: "", preparing: false });
    };
    wx.navigateTo({
      url: "/pages/wallet/index?orderId=" + orderId,
      success: done,
      fail: (error) => {
        this._navigating = false;
        this.updateState({
          busy: false,
          busyText: "",
          preparing: false,
          error:
            "购买信息已保存，付款页打开失败，可点击继续付款：" +
            ((error && error.errMsg) || ""),
        });
      },
    });
  },
  async prepareAndPay(application) {
    const order = await request(
      "/onboardings/" + application.id + "/order",
      "POST",
      {},
    );
    if (this._unloaded) return;
    this.updateState({
      current: { ...decorateApplication(application), order },
      invitationLocked: true,
      error: "",
      notice: "",
    });
    this.navigateToWallet(order.id);
  },
  async purchaseWithPoints() {
    if (this.data.busy || this.data.current?.status !== "submitted") return;
    if (!this.data.invitationLocked) return this.guideToInvitation();
    this.updateState({
      busy: true,
      busyText: "正在准备付款…",
      preparing: true,
      error: "",
      notice: "",
    });
    try {
      await this.prepareAndPay(this.data.current);
    } catch (error) {
      this.updateState({
        error: "购买信息已保存，付款准备失败，可继续付款：" + error.message,
      });
    } finally {
      if (!this._navigating)
        this.updateState({ busy: false, busyText: "", preparing: false });
    }
  },
  async submit() {
    if (this._unloaded) return;
    if (this._session !== session()) {
      await this.load(true);
      return;
    }
    const blocked = applicationHint(this.data);
    if (blocked) {
      this.updateState();
      return;
    }
    if (["rejected", "revoked"].includes(this.data.current?.status))
      this.requestKey = key();
    const relation = this.data.relation;
    this.updateState({
      busy: true,
      busyText: "正在准备付款…",
      preparing: true,
      error: "",
      notice: "",
    });
    let saved = false;
    try {
      const result = await request("/onboardings", "POST", {
        targetIdentity: this.data.target,
        deliveryMode: this.data.deliveryMode,
        storedItems: wx.getStorageSync("jyj_pickup_selection") || [],
        shipQuantities: this.data.selected.lines.map(
          (line) => line.shipQuantity,
        ),
        addressId: this.data.address.id,
        inviterId: relation.inviterId,
        shareCode: this.data.shareCode,
        principalId: relation.principalId,
        relationshipsConfirmed: true,
        conditionsMet: true,
        agreementAccepted: true,
        requestKey: this.requestKey,
      });
      saved = true;
      wx.removeStorageSync("jyj_pickup_selection");
      if (this._unloaded) return;
      this.requestKey = key();
      this.updateState({
        current: decorateApplication(result),
        invitationLocked: true,
        error: "",
        notice: "",
      });
      await this.prepareAndPay(result);
    } catch (error) {
      this.updateState({
        error: saved
          ? "购买信息已保存，付款准备失败，可继续付款：" + error.message
          : error.message,
      });
    } finally {
      if (!this._navigating)
        this.updateState({ busy: false, busyText: "", preparing: false });
    }
  },
});
