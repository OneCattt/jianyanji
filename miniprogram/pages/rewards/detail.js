const { request } = require("../../utils/api");
const amount = (n) => (n / 100).toFixed(2);
const statusNames = {
  not_due: "未到结算期",
  eligible: "可结算",
  settled: "已结算",
  reversed: "已冲回",
  pending: "待付款",
  paid: "已核实结算",
  failed: "付款失败",
  unknown: "待核实",
  cancelled: "已取消",
};
const { listValues } = require("../../utils/list");
const histories = [
  "reversals",
  "settlements",
  "debts",
  "offsets",
  "recoveries",
];
function history(kind, value) {
  if (kind === "reversals")
    return {
      ...value,
      amount: amount(value.amount_cents),
      debt: amount(value.debt_cents),
    };
  if (kind === "debts")
    return {
      ...value,
      original: amount(value.debt_cents),
      offset: amount(value.offset_cents),
      recovered: amount(value.recovered_cents),
      remaining: amount(value.remainingCents),
    };
  if (kind === "settlements")
    return {
      ...value,
      statusName: statusNames[value.status],
      gross: amount(value.gross_cents),
      offset: amount(value.offset_cents),
      net: amount(value.net_cents),
    };
  return { ...value, amount: amount(value.amount_cents) };
}
Page({
  data: {
    item: null,
    error: "",
    loading: false,
    loaded: false,
    hasMore: true,
    reversals: [],
    settlements: [],
    debts: [],
    offsets: [],
    recoveries: [],
  },
  onLoad(q) {
    this.rewardId = q.id;
    this._nextPage = 1;
    this._nextParts = histories;
    return this.load();
  },
  async load(reset = true) {
    reset = reset !== false;
    if (this.data.loading || (!reset && !this.data.hasMore)) return;
    const page = reset ? 1 : this._nextPage;
    const parts = reset ? histories : this._nextParts;
    this.setData({ loading: true, error: "" });
    try {
      const result = await request(
        "/mini/rewards/" +
          this.rewardId +
          "?limit=20&page=" +
          page +
          "&parts=" +
          parts.join(","),
      );
      const values = {};
      const next = [];
      parts.forEach((kind) => {
        const part = result.pages[kind];
        Object.assign(
          values,
          listValues(
            this,
            kind,
            part.items.map((item) => history(kind, item)),
            reset,
          ),
        );
        if (part.hasMore) next.push(kind);
      });
      if (result.item) {
        const r = result.item;
        values.item = {
          ...r,
          title:
            r.kind === "onboarding"
              ? "产品包入驻奖励"
              : r.kind === "retail"
                ? "零售佣金"
                : "店主订货收益",
          statusName: statusNames[r.status],
          amount: amount(r.amountCents),
          remaining: amount(r.remainingCents),
          reversed: amount(r.reversedCents),
          settled: amount(r.settledCents),
        };
      }
      this._nextPage = page + 1;
      this._nextParts = next;
      this.setData({ ...values, hasMore: next.length > 0, loaded: true });
    } catch (e) {
      this.setData({
        error: e.message,
        ...(e.status === 401 || e.status === 404 ? { item: null } : {}),
      });
    } finally {
      this.setData({ loading: false });
    }
  },
  onReachBottom() {
    return this.load(false);
  },
  async onPullDownRefresh() {
    try {
      await this.load();
    } finally {
      wx.stopPullDownRefresh();
    }
  },
});
