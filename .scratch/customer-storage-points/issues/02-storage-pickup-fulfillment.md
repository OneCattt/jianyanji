Status: ready-for-human

# storage-pickup-fulfillment

依照 ../spec.md 和本会话确认方案实施，验证结果追加 Comments。

## Comments
- 2026-10-27：开始实施。
- 2026-10-27 收尾：寄存批次、跨单合并提货、运费、审核发货、部分签收、到期延期与老单人工转寄存已实现；独立MySQL覆盖并发/占用/回滚，HTTP链路核对3件寄存提2剩1。真实仓储物流待验收。
- 交付记录：[迁移与验收清单](../../../docs/development/customer-storage-points-delivery.md)。本地实施结束，目标环境和业务验收待执行。
- 2026-10-27 19:01 发布：后端及管理前端已按 `2b6e504` 部署，V12～V14成功、原业务记录数/资金汇总一致；小程序未上传，真实业务验收继续待办。详见 [发布证据](../verification/deployment-result.json)。
