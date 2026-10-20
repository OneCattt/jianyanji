# V2 品牌种草、视频和课程权益

## 数据与入口

运行服务为 Java 8 Spring Boot。`V6__brand_content.sql` 新建 `brand_posts`；课程使用现有 `settings.course_rights`，无需 V10。新表包含项目主键、创建/修改用户、毫秒创建/修改时间与逻辑删除字段。内容移除仅设置 `is_deleted=1`，不重写既有交易。

后台“品牌种草内容”由 `content:manage` 授权，按草稿保存 → 品牌审核 → 发布操作。修改已审核/发布内容一律回到草稿。下架和逻辑删除立即使公开详情返回404；客户端文字按纯文字展示，不执行HTML。关联商品须真实存在；发布时检查商品和分类仍上架，公开详情只关联当前可售商品。

小程序四栏：首页、商城、种草空间、我的。种草列表采用编号游标、每页12条，单次最大30条。首页独立组件推荐最近3篇；种草详情支持图文、短视频、当前关联商品、原生分享与朋友圈分享。课程从首页推荐区和“我的”进入，公开展示启用的身份权益对比，登录后由服务端有效身份选中本人权益，课程系统仅介绍与联系方式。

## HTTP 接口

| 方法 | 路径（前缀 /api） | 权限 / 含义 |
| --- | --- | --- |
| POST | /refunds | 登录本人，orderId/items(lineId,quantity)/reason/requestKey；不接受金额、特批或审核结果 |
| GET | /refunds | 复用既有本人售后进度 |
| GET | /public/posts?before=&limit=12 | 公开，仅已发布未删除的列表 |
| GET | /public/posts/{id} | 公开，仅已发布未删除的详情与在售关联商品 |
| GET、POST | /admin/posts | content:manage；后台列表、新建草稿 |
| PUT | /admin/posts/{id} | content:manage；保存后重置审核状态 |
| POST | /admin/posts/{id}/review、publish、unpublish、remove | content:manage；JSON reason；审核、发布、下架、逻辑移除 |
| GET | /public/course-rights | 公开，只返回启用的实际权益 |
| GET | /course-rights | 已登录，只按当前有效身份选取本人权益 |
| GET、PUT | /admin/course-rights | content:manage；分D/C/B维护enabled/title/introduction/benefits/contact/enrollmentGuide |

## 真实视频媒体配置

现有 OSS image upload 仅支持图片。本切片复用封面图片上传组件，不修改其实现；视频通过品牌方预先部署、审核后的稳定 HTTPS MP4 地址维护，不经过图片签名接口。

后端环境配置：

```dotenv
# 只填主机名，可逗号分隔；留空时拒绝视频录入/发布。
CONTENT_VIDEO_HOSTS=media.品牌域名.cn
```

后台视频要求1至180秒，MP4地址长度不超过1000，不包含用户密码、片段或临时查询签名。域名必须精确匹配配置（不允许相似子域绕过）。时长由品牌人员录入，并非服务端转码检测值；上线前核实真实文件时长与编码。视频托管为独立媒体存储/CDN，不经API上传大文件。

上线配置及验证：

1. 将真实审核后视频部署到自有媒体存储/CDN；核对 MIME `video/mp4`、HTTPS证书、H.264/AAC等真机兼容编码以及 Range/206 分段响应。
2. 微信公众平台按实际访问方式登记 HTTPS 媒体域名，真机验证。浏览器管理预览如跨域使用，须按实际后台域名配置媒体CORS。
3. `/api/public/posts` 等新接口继续进入既有 Nginx `/api/` 的 `api-guard.conf`，沿用入口限流及暂停开关。视频直接走媒体域名，需在对应OSS/CDN配置防盗链、访问限速和带宽/费用告警；现有API限流不约束外部媒体流量。
4. 实际AppID与真机验证从列表进入、横竖屏播放、暂停继续、弱网/失败提示、关联商品、朋友分享和朋友圈分享，之后才确认外部媒体可用。

未配置视频域名时图文内容仍正常使用；课程未配置显示真实空态，不植入演示权益或未确认的奖励承诺。

## 验证边界

- 普通单元测试覆盖金额、本人权限、内容审核状态、未发布直链、逻辑删除、媒体白名单和有效身份选择。
- `CustomerAftersalesMysqlTest` 和 `BrandContentMysqlTest` 由主线程统一迁移后执行，使用 `PAYMENTS_TEST_MYSQL_URL` 指向本机 `java_rewards_test_` 专用库，测试本身不执行Flyway。
- 本地源码检查不等于真微信登录、真实退款或视频真机播放验收。暂未执行上述外部验收。
