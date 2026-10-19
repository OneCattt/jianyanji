const { request } = require("../../utils/api");
const { money } = require("../../utils/catalog");
Page({
  data: { items: [], service: "", error: "" },
  async onShow() {
    try {
      const [r, c] = await Promise.all([
        request("/refunds"),
        request("/public/content"),
      ]);
      const names = {
        requested: "待审核",
        approved: "已通过，待退款",
        processing: "退款处理中",
        unknown: "退款结果待核查",
        failed: "退款失败待处理",
        rejected: "已驳回",
        success: "已实际退款",
      };
      this.setData({
        items: r.map((i) => ({
          ...i,
          statusName: names[i.status],
          amountText: money(i.total_cents),
        })),
        service: c.content ? c.content.customerService : "",
        error: "",
      });
    } catch (e) {
      this.setData({ error: e.message });
    }
  },
  copy() {
    if (this.data.service) wx.setClipboardData({ data: this.data.service });
  },
});
