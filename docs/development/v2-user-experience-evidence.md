# V2 独立用户体验线交付记录

2026-10-20：28、30、31、32 代码落地，本切片的本地验证完成；主线程负责任务状态与最终集成。未提交或推送，未派生其他agent，原既有变更保留。

## 28 本人在线售后申请

- 独立 `POST /api/refunds`，从会话取得用户，`db.tx` 内先 `orders.owned(orderId,userId)`，随后复用 `AftersalesService.request` 的业务互斥行、原价核定、数量占用、原政策和幂等键。幂等重放也不能绕过本人权限。
- 用户仅传订单、商品行/数量、原因和请求键，禁止金额、用户编号、审核结果、身份结论及超期特批字段；运费按原政策由后台单独核实。小程序展示原支付单位，积分退款不显示为人民币。
- 小程序本人订单进入、选择剩余数量、填写原因，提交后显示受理/审核进度，网络重试保留原请求键，失败或未决不显示已退款。
- `AftersalesService.review` approve + package + revoke 已调用主线程的 `membership.assertRefundRevocable`。
- 实际退款前新增 `assertRefundRevocableIfNeeded`：显式撤销，或此前成功退款+本次金额达到原产品包总额，必须核验资格撤销和团队接管计划。即使误选retain也不能绕过全退校验。
- 入口：`offline`写退款凭证前、`channel`新发起微信退款/租约前、`finishRefund`退积分及修改本地退款数量前。原微信查询路径保留；渠道已实际成功但本地校验失败时仍保留unknown及原交易补偿入口。
- 不修改catalog/orders/rewards/membership领域实现，不新增售后表。

## 30 品牌图文与四栏导航

- 新建content模块与 `V6__brand_content.sql`。新表包含项目6项主键/审计字段，仅逻辑删除。
- 后台独立组件 `BrandContentOperations.vue`、独立 `content-client.ts`；权限content:manage在服务端校验。保存 → 草稿 → 审核 → 发布；修改内容重回草稿。
- 公开列表限制分页，详情过滤未发布/下架/删除记录。关联真实在售商品、原生朋友及朋友圈分享。
- 小程序四栏：首页、商城、种草空间、我的；首页独立内容推荐组件；profile索引3。重读确认主线程真实分享码及team入口保留。
- 图片封面复用现有ImageUploader，不修改用户正在实现的图片OSS服务及组件。

## 31 品牌短视频

- 与图文共用审核发布闭环、商品关联和分享；详情用微信原生video，手动播放、海报、错误与重试提示。
- 独立 `CONTENT_VIDEO_HOSTS` 精确主机白名单，只接受稳定HTTPS MP4，录入时长1至180秒。视频通过品牌方已部署资源地址维护，不经过仅支持图片的OSS上传签名。
- 媒体域名、微信登记、Range/206、编码和OSS/CDN防盗链/限流上线步骤见 `brand-content-and-courses.md`。API沿用现有Nginx `/api/` 限流，外部视频流量由媒体存储/CDN独立限制。
- 当前只验证输入、状态与源代码，未宣称真机或真实媒体资源已播放成功。

## 32 分身份课程权益与联系

- 独立 `CourseRightsService/Controller`、`CourseRightsOperations.vue`；复用settings.course_rights，无需V10。
- D/C/B分别维护介绍、成长权益、联系方法、报名指引及启用状态；公开对比只返回已启用配置，本人接口从服务端有效身份选择，失效主理人回到游客权益。
- 首页内容推荐区与“我的”均有入口，未登录可了解对比并登录查看本人权益；无配置显示真实空态。
- 不创建报名、付费课程、学习或进度系统。

## 本地验证

显式选择并验证 Corretto JDK `1.8.0_452`，Maven `3.9.9` 的Java版本亦为1.8。

| 测试类 | 执行数 | 结果 |
| --- | --- | --- |
| CustomerAftersalesServiceTest | 5 | 通过：本人权限、原3501分×2金额、输入越权、超量/占用、过期 |
| AftersalesRevocationReviewTest | 1 | 通过：缺接管计划不批准撤销审核 |
| AftersalesRefundPreflightTest | 4 | 通过：累计全退误选retain、微信/线下/积分均在资金或本地写入前阻断，部分显式revoke亦受控 |
| BrandContentServiceTest | 6 | 通过：公开过滤、审核前发布被拒、逻辑删除、编辑重审、视频主机/协议边界、禁止客户端直接设状态 |
| CourseRightsServiceTest | 2 | 通过：有效身份选择、失效资格、未配置、真实联系方式要求 |
| ContentPermissionTest | 1 | 通过：后台全部写及状态接口无权限403、本人课程无登录401 |
| AftersalesServiceTest | 11 | 原售后回归通过 |
| CustomerAftersalesMysqlTest | 1 | 真实MySQL通过：越权、并发同键只创建1单/占用2件，7002分，其他键超量回滚 |
| BrandContentMysqlTest | 1 | 真实MySQL通过：视频草稿/审核/发布/改稿/下架/逻辑删除直链隔离 |
| **合计** | **32** | **0失败、0错误、0跳过** |

MySQL使用主线程已经迁移的 `127.0.0.1:3308/java_rewards_test_v2_20261020` 专用库。本切片未执行Flyway，V6自创建至本次验证未修改。测试仅按自己的主键逻辑清理，未物理删除。

- `vue-tsc --noEmit -p tsconfig.web.json` 通过；未执行前端全构建。
- `node scripts/check-user-experience.cjs` 通过，微信官方wcc/wcsc定向检查6份WXML、5份WXSS、7份JS，四栏及注册页面文件校验通过。
- 新增独立文件Prettier检查通过；自己范围新Java文件用 `/tmp/jyj-google-java-format-1.7.jar --aosp` 格式化。
- 单元日志 `/tmp/jianyanji-user-experience-unit.log`、集成日志 `/tmp/jianyanji-user-experience-mysql.log`；逐类报告在 `backend/target/surefire-reports/`。
- 未执行真微信登录/支付/通知/退款、真实银行退款、微信真机分享/朋友圈分享/视频播放，也未提供浏览器视觉验收证据。

## 主线程可直接粘贴的任务评论

- 28：已实现本人在线售后申请与小程序进度；原金额/政策/数量占用和幂等复用原售后事务，新增本人/金额/并发测试通过。撤销审核及退款前接管校验已加入，全退误选retain亦拦截。外部真实退款待验收。
- 30：content模块、V6、独立内容后台、公开已发布列表/详情、关联商品、首页推荐、原生分享及四栏导航已实现；权限及真实MySQL发布边界通过，未发布与下架直链404。
- 31：品牌短视频URL维护、域名白名单、审核发布及原生video播放/重试/分享已实现。图片上传仅用于图片；视频需预先部署MP4。真实媒体域名、CDN配置及真机播放待上线验收。
- 32：独立课程权益后台与服务、D/C/B配置、有效身份本人权益、介绍/联系方式及首页/我的入口已实现，使用settings无需V10；类型/身份/权限测试通过。

## 文件清单

### Java 与数据库

- backend/src/main/java/com/jianyanji/aftersales/CustomerAftersalesController.java
- backend/src/main/java/com/jianyanji/aftersales/CustomerAftersalesService.java
- backend/src/main/java/com/jianyanji/aftersales/AftersalesService.java（仅新增审核/执行前接管校验）
- backend/src/main/java/com/jianyanji/content/BrandContentController.java
- backend/src/main/java/com/jianyanji/content/BrandContentService.java
- backend/src/main/java/com/jianyanji/content/CourseRightsController.java
- backend/src/main/java/com/jianyanji/content/CourseRightsService.java
- backend/src/main/resources/db/migration/V6__brand_content.sql
- backend/src/test/java/com/jianyanji/aftersales/CustomerAftersalesServiceTest.java
- backend/src/test/java/com/jianyanji/aftersales/CustomerAftersalesMysqlTest.java
- backend/src/test/java/com/jianyanji/aftersales/AftersalesRevocationReviewTest.java
- backend/src/test/java/com/jianyanji/aftersales/AftersalesRefundPreflightTest.java
- backend/src/test/java/com/jianyanji/content/BrandContentServiceTest.java
- backend/src/test/java/com/jianyanji/content/BrandContentMysqlTest.java
- backend/src/test/java/com/jianyanji/content/CourseRightsServiceTest.java
- backend/src/test/java/com/jianyanji/content/ContentPermissionTest.java

### 前端

- web/content-client.ts
- web/content-operations.css
- web/components/BrandContentOperations.vue
- web/components/CourseRightsOperations.vue
- web/components/AdminView.vue（最小增量挂载独立组件与导航）
- miniprogram/pages/refunds/index.js / index.wxml / index.wxss
- miniprogram/pages/seeds/index.js / index.json / index.wxml / index.wxss
- miniprogram/pages/post/index.js / index.json / index.wxml / index.wxss
- miniprogram/pages/courses/index.js / index.json / index.wxml / index.wxss
- miniprogram/components/content-recommendations/index.js / index.json / index.wxml / index.wxss
- miniprogram/assets/figma/tab-seed.png / tab-seed-active.png
- miniprogram/app.json（追加页面及种草导航，保留team）
- miniprogram/custom-tab-bar/index.js（追加种草栏）
- miniprogram/pages/home/index.json / index.wxml（只挂载独立推荐组件）
- miniprogram/pages/profile/index.js（courses入口及索引3，保留主线程分享码和team）

### 文档和定向检查

- scripts/check-user-experience.cjs
- docs/development/brand-content-and-courses.md
- docs/development/v2-user-experience-evidence.md
