import { permissionNames, type Audit, type Permission } from "./client";

// 审计编码保持原样保存，页面统一使用业务名称解释历史和新增记录。
export const auditActionNames: Record<string, string> = {
  "user.login": "登录小程序",
  "user.logout": "退出小程序",
  "profile.update": "修改个人资料",
  "profile.denied": "尝试访问他人资料",
  "staff.login": "登录管理后台",
  "staff.logout": "退出管理后台",
  "staff.create": "新增工作人员",
  "staff.update": "修改工作人员权限或状态",
  "staff.remove": "移除工作人员",
  "staff.seed": "初始化演示账号",
  "staff.bootstrap": "初始化管理员",
  "permission.denied": "尝试使用未授权功能",
  "request.denied": "提交不符合要求的操作",
  "content.update": "修改品牌与经营说明",
  "course-rights.update": "修改课程成长权益",
  "brand-content.save": "保存品牌种草内容",
  "brand-content.review": "审核品牌种草内容",
  "brand-content.publish": "发布品牌种草内容",
  "brand-content.unpublish": "撤下品牌种草内容",
  "brand-content.remove": "移除品牌种草内容",
  "category.save": "保存商品分类",
  "product.save": "保存商品与规格",
  "product.remove": "移除商品",
  "shipping.settings": "修改配送政策",
  "membership.settings": "修改产品包与入驻设置",
  "aftersales.settings": "修改售后政策",
  "reward.settings": "修改奖励规则",
  "address.save": "保存收货地址",
  "order.create": "提交订单",
  "order.cancel": "取消未支付订单",
  "order.pay_points": "使用积分支付订单",
  "shipment.create": "登记订单发货",
  "shipment.correct": "更正物流信息",
  "shipment.receive": "确认商品收货",
  "payment.start": "发起微信支付",
  "payment.prepay": "申请微信预支付",
  "payment.query": "查询微信支付结果",
  "payment.close.request": "申请关闭微信支付",
  "payment.notify": "处理微信支付通知",
  "payment.verified": "核实微信支付到账",
  "payment.closed": "关闭微信支付交易",
  "payment.expired-absent": "释放到期未付款订单占用",
  "payment.reconcile": "核对微信交易状态",
  "receipt.confirm": "确认公司收款并发放积分",
  "wallet.correct": "更正收款积分批次",
  "cash_refund.reserve": "申请未用积分退现金",
  "cash_refund.success": "登记积分余额现金退款成功",
  "cash_refund.failed": "登记积分余额现金退款失败",
  "cash_refund.rejected": "驳回积分余额现金退款",
  "cash_refund.unknown": "登记现金退款结果待核实",
  "refund.request": "提交商品售后申请",
  "refund.review": "审核商品售后申请",
  "refund.inspect": "登记退货验收结果",
  "refund.points": "办理订单积分退款",
  "refund.offline": "登记订单线下退款",
  "refund.unknown": "登记订单退款结果待核实",
  "refund.channel": "处理微信原路退款",
  "onboarding.initial": "登记首批代理入驻",
  "onboarding.apply": "提交代理入驻申请",
  "onboarding.reconfirm": "重新确认入驻邀请与归属",
  "onboarding.receipt": "核实入驻产品包到账",
  "qualification.bootstrap": "核验开通首批代理资格",
  "qualification.activate": "激活代理身份",
  "relation.adjust": "调整代理邀请与归属关系",
  "team.handover.prepare": "指定团队接管主理人",
  "team.handover.apply": "办理团队交接",
  "reward.register": "登记代理奖励",
  "reward.reconcile": "核对代理奖励",
  "reward.reverse": "冲回退款对应奖励",
  "reward.settlement.plan": "生成奖励月结单",
  "reward.settlement.paid": "登记奖励结算付款",
  "reward.settlement.failed": "登记奖励结算付款失败",
  "reward.settlement.unknown": "登记奖励结算结果待核实",
  "reward.settlement.cancelled": "取消奖励月结单",
  "reward.recovery.received": "登记奖励追回到账",
};

export const auditObjectNames: Record<string, string> = {
  user: "用户",
  staff: "工作人员",
  permission: "功能权限",
  request: "操作请求",
  content: "品牌与经营说明",
  settings: "业务设置",
  "brand-post": "品牌种草内容",
  category: "商品分类",
  product: "商品",
  shipping: "配送政策",
  membership: "产品包与入驻设置",
  aftersales: "售后政策",
  reward_settings: "奖励规则",
  address: "收货地址",
  order: "订单",
  shipment: "发货记录",
  refund: "售后退款",
  receipt: "公司收款",
  wallet_lot: "收款积分批次",
  cash_refund: "积分余额现金退款",
  onboarding: "代理入驻申请",
  reward: "代理奖励",
  reward_reversal: "奖励冲回记录",
  reward_settlement: "奖励月结单",
  reward_recovery: "奖励追回记录",
};

export function auditAction(event: Audit): string {
  return auditActionNames[event.action] || "其他操作（查看详情）";
}

export function auditObject(event: Audit): string {
  const type = auditObjectNames[event.object_type] || "业务对象";
  const id = event.object_id;
  if (event.object_type === "permission")
    return permissionNames[id as Permission] || "功能权限（查看详情）";
  if (event.object_type === "settings" && id === "course_rights")
    return "课程成长权益";
  if (
    [
      "content",
      "shipping",
      "membership",
      "aftersales",
      "reward_settings",
    ].includes(event.object_type)
  )
    return type;
  if (id === "wechat") return "微信登录";
  if (id === "notification") return "微信支付通知";
  return /^\d+$/.test(id) ? `${type} #${id}` : type;
}

export function auditActor(event: Audit): string {
  if (event.actor_kind === "system") return "系统自动处理";
  if (event.actor_name) return event.actor_name;
  const kind = event.actor_kind === "staff" ? "工作人员" : "用户";
  return event.actor_id ? `${kind} #${event.actor_id}` : `未登录${kind}`;
}

export function auditReason(event: Audit): string {
  const reason = event.reason.trim();
  if (!reason) return "未填写操作说明";
  if (/^\d+$/.test(reason)) return `未说明具体内容（原始填写：${reason}）`;
  return reason.replace(
    /\b(?:staff|users|audit|content|catalog|orders|onboarding|finance|rewards):(?:manage|read)\b/g,
    (permission) => permissionNames[permission as Permission] || permission,
  );
}
