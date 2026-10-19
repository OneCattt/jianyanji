const { request } = require("../../utils/api");
const { money } = require("../../utils/catalog");
Page({
  data: { items: [], error: "" },
  async onShow() {
    try {
      const r = await request("/cash-refunds");
      const names = {
        processing: "办理中，积分已占用",
        unknown: "结果待核查，保留占用",
        failed: "未退款，已释放积分",
        rejected: "已驳回，已释放积分",
        success: "已实际退现金并扣回积分",
      };
      this.setData({
        items: r.map((i) => ({
          ...i,
          statusName: names[i.status],
          amountText: money(i.amount_cents),
        })),
        error: "",
      });
    } catch (e) {
      this.setData({ error: e.message });
    }
  },
});
