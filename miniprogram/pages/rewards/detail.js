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
Page({
  data: { item: null, error: "", loading: true },
  onLoad(q) {
    this.rewardId = q.id;
    this.load();
  },
  async load() {
    this.setData({ loading: true, error: "" });
    try {
      const r = await request("/rewards/" + this.rewardId);
      this.setData({
        item: {
          ...r,
          title: r.kind === "onboarding" ? "产品包入驻奖励" : "店主订货收益",
          statusName: statusNames[r.status],
          amount: amount(r.amountCents),
          remaining: amount(r.remainingCents),
          reversed: amount(r.reversedCents),
          settled: amount(r.settledCents),
          reversals: r.reversals.map((v) => ({
            ...v,
            amount: amount(v.amount_cents),
            debt: amount(v.debt_cents),
          })),
          debts: r.debts.map((v) => ({
            ...v,
            original: amount(v.debt_cents),
            offset: amount(v.offset_cents),
            recovered: amount(v.recovered_cents),
            remaining: amount(v.remainingCents),
          })),
          settlements: r.settlements.map((v) => ({
            ...v,
            statusName: statusNames[v.status],
            gross: amount(v.gross_cents),
            offset: amount(v.offset_cents),
            net: amount(v.net_cents),
          })),
          offsets: r.offsets.map((v) => ({
            ...v,
            amount: amount(v.amount_cents),
          })),
          recoveries: r.recoveries.map((v) => ({
            ...v,
            amount: amount(v.amount_cents),
          })),
        },
        loading: false,
      });
    } catch (e) {
      this.setData({ item: null, error: e.message, loading: false });
    }
  },
});
