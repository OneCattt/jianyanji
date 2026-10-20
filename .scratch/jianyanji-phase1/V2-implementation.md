# V2 增量实施索引

> 2026-10-20 后续范围调整：当前基线为 V2.1，首期资金收付均线下办理。任务 24 改为 `wontfix`，任务 36 已完成代码收口，状态为 `ready-for-human`；任务 35 记录新范围本地验证，保留外部验收。当前结果见 [线下交易实施说明](../../docs/development/v2-offline-transactions.md)，下方既有结果保留为调整前历史证据。

用户于2026-10-20确认开始实施；总并发最多3个agent。原01～18任务及父规格保留。

|编号|任务|前置|
|---|---|---|
|19|[普通商品折扣、福利价与订货收益](issues/19-role-pricing-and-welfare.md)|无|
|20|[资格来源留存与首批代理核验](issues/20-qualification-sources-and-bootstrap.md)|无|
|21|[邀请确认、分享码与归属调整](issues/21-confirmed-invitation-and-relations.md)|20|
|22|[资格撤销与主理人团队交接](issues/22-qualification-revocation-and-handover.md)|20|
|23|[公司转款产品包核款后激活](issues/23-transfer-package-activation.md)|21、22|
|24|[微信购买产品包与原路退款，首期不做](issues/24-wechat-package-payment-and-refund.md)|wontfix|
|25|[店主全额购包升级及退款恢复](issues/25-full-package-upgrade-and-restore.md)|23、36|
|26|[多收益人入驻奖励及结算追回](issues/26-multi-recipient-onboarding-rewards.md)|23|
|27|[逐单销售归属与零售佣金](issues/27-retail-attribution-and-commission.md)|19、26|
|28|[用户在线提交售后](issues/28-customer-aftersales-submission.md)|无|
|29|[主理人团队与限定订单查询](issues/29-principal-team-and-orders.md)|19、21、22|
|30|[品牌图文种草与四栏导航](issues/30-brand-content-and-navigation.md)|无|
|31|[种草短视频发布与播放](issues/31-brand-short-video.md)|30|
|32|[身份权益、课程介绍与联系入口](issues/32-course-rights-and-contact.md)|无|
|33|[财务业务授权导出](issues/33-finance-authorized-export.md)|23、26、27、36|
|34|[运营业务授权导出](issues/34-operations-authorized-export.md)|25、28、29|
|35|[V2联合验收与上线交付](issues/35-v2-acceptance-and-delivery.md)|31、32、33、34、36|
|36|[首期线下交易收口](issues/36-offline-transactions.md)|既有订单、核款和售后模块；不依赖24|

## 执行记录

- 首批并行：主线程20起资格与入驻链路；agent A执行19价格链路；agent B执行28售后入口，再执行30～32内容权益。
- 已有未提交的OSS上传、后台反馈与配置改动保留，实际验证证据按各任务记录。

## 调整前 V2.0 实施结果

19～34已完成代码及本地联合验证，均转为 `ready-for-human` 等待评审和相应外部验收；31的实际播放分享、32的品牌政策审核保持未勾选。35已完成本地资金／权限回归，外部渠道、正式告警与上线交接保持待办。状态词沿项目五标签，不另造完成标签。

- 主线程：20～25、29、34及35集成；agent A：19、26、27、33；agent B：28、30～32及两项资格边界复核。总并发最多3，两个agent现均已结束。
- Java8全量187项通过，0失败、0错误、0跳过，含39项真实MySQL；保留既有钱包／退款／结算回归。
- Vue类型检查与构建、原生22页编译通过；后台13类导出均200，无浏览器脚本错误。
- 本机隔离库V1～V10迁移通过，备份恢复到全新隔离库，38表行数和校验值全部一致；正式库未操作。
- 入口：[落地与验收报告](../../docs/development/V2落地与验收.md)、[页面与关键流程](../../docs/design/V2页面与业务流程.md)。验收收尾时尚未提交或推送。

- 后续用户授权提交推送：本轮代码提交为 `3b4aea1`，任务、规格和验证资料单独提交，远端同步状态见Git记录。


## V2.1 线下交易结果

任务 36 已完成应用改造，任务 35 按线下范围重新记录验收，两者为 `ready-for-human`。主线程与两位子 agent 总并发不超过 3，子 agent 已关闭。最终 JDK 8 全量 193 项通过（53 项真实 MySQL），V1～V11 隔离迁移、16 项 Node 行为、22 页原生检查、后台类型及实际财务权限浏览器核款／退款通过。本轮未提交或推送，默认业务库及原服务保留。正式迁移、真实资金、微信设备及材料交接仍待实际完成，见 [最新实施说明](../../docs/development/v2-offline-transactions.md)。
