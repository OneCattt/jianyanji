# 首期基础防刷、费用告警与人工止损

适用部署：杭州单台 ECS，Nginx 直接接收公网请求，Java 8 仅监听 `127.0.0.1:3100`，MySQL 不对公网开放。公开商品图片可由 OSS 提供。费用预算按正常业务使用估算，以下措施降低风险，不构成费用封顶或大流量 DDoS 防护。

## 项目内已有的防护

`deploy/nginx.conf` 及 `deploy/nginx/` 必须一起安装，配置位于 Nginx 的 `http` 内。

| 入口            | 每个来源地址的持续速率 / 突发余量                   | 其他约束                                                                                              |
| --------------- | --------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| 普通 API        | 10 次/秒，额外突发 20 次                            | 全站 50 次/秒、额外突发 100 次；每来源最多 20 个并发请求                                              |
| 普通 API 写入   | 在普通 API 限制上，再限制为 2 次/秒、额外突发 10 次 | GET、HEAD、OPTIONS 不占写入额度                                                                       |
| 后台登录        | 5 次/分钟，额外突发 5 次                            | 全站 2 次/秒、额外突发 5 次；Java 保留每来源 15 分钟 20 次的登录限制                                  |
| 微信登录        | 30 次/分钟，额外突发 15 次                          | 全站 5 次/秒、额外突发 20 次；Java 登录限制仍生效                                                     |
| 本地 `/assets/` | 30 次/秒，额外突发 60 次                            | 每来源最多 40 个并发请求；不限制 OSS 直连访问                                                         |
| 微信支付通知    | 30 次/秒，额外突发 60 次                            | 全站独立额度 20 次/秒、额外突发 100 次；不受普通 API 写入限制和暂停开关影响，仍由 Java 验签并幂等入账 |

超过限额返回 429、中文 JSON 和 `Retry-After`；前端不要自动循环重试。阈值是首期起点，同一 Wi-Fi 下多人共享来源地址，上线后需观察误拦情况再调整。

入口还限制 128KB 请求体、慢请求超时，拒绝未知 Host，关闭生产开发登录入口。Nginx 覆盖客户端提供的转发头；Tomcat 只信任本机代理，登录限流使用恢复后的真实客户端地址。不要开放 3100，也不要通过 `set_real_ip_from 0.0.0.0/0` 信任任意来源。

若以后增加 CDN、负载均衡或第二层代理，必须重新配置可信代理范围并验证来源地址，不能直接沿用本机直连方案。

## 安装与验证

先替换 `deploy/nginx.conf` 中的域名和证书路径。若已有其他默认站点，只保留一个 `listen 80 default_server`，防止冲突。以下命令从仓库根目录执行：

```bash
sudo install -d -m 0755 /etc/nginx/jianyanji
sudo install -m 0644 deploy/nginx/api-guard.conf /etc/nginx/jianyanji/api-guard.conf
# 首次安装复制封禁文件；以后升级必须保留服务器上已维护的名单。
sudo test -f /etc/nginx/jianyanji/blocked-ips.conf || sudo install -m 0644 deploy/nginx/blocked-ips.conf /etc/nginx/jianyanji/blocked-ips.conf
sudo install -m 0644 deploy/nginx.conf /etc/nginx/conf.d/jianyanji.conf
sudo install -m 0755 deploy/traffic-guard.sh /usr/local/sbin/jyj-traffic-guard
sudo install -m 0644 deploy/logrotate-jianyanji /etc/logrotate.d/jianyanji
sudo nginx -t
sudo systemctl reload nginx
```

安全组仅向业务用户开放 80/443；SSH 的 22 端口只放行运维来源。3100、3306 不对公网开放。还需部署包含本次 `application.yml` 更新的 Java JAR，使可信代理设置生效。

访问日志位于 `/var/log/nginx/jianyanji-access.log`，记录来源、方法、规范化路径、状态、流量、限流结果、耗时及 Nginx/Java 请求号，不记录查询串、Cookie、Authorization 或请求正文。Nginx 自身错误日志仍可能包含请求 URL，不得在 URL 中传密码、令牌或凭证。日志轮转需系统 logrotate 定时任务实际运行；`maxsize` 仅在轮转任务运行时检查，不是即时磁盘限额。

隔离验证命令（Python 3.9+、OpenSSL、支持 SSL 的 Nginx）：

```bash
python3 scripts/check-edge-security.py --nginx /usr/sbin/nginx
```

脚本会以临时自签名证书、随机本地端口和假后端加载实际部署模板，验证限流、转发头覆盖、Host 拒绝、请求体大小、IP 封禁和暂停/恢复。不访问正式业务库，不测试真实微信付款或云账单。

## OSS：公开商品图片与敏感凭证分开

当前商品编辑支持填写 HTTPS 图片地址，项目尚未接入 OSS SDK 上传和私有图片签名服务。不要把访问密钥放入小程序、网页、仓库或聊天。

1. **商品图片 Bucket**：杭州、标准存储，桶 ACL 保持私有；只存打算对消费者展示的图片，统一放在 `products/` 前缀。先在控制台上传经过压缩的图片，建议单图 100～300KB，不上传视频和大型原图。
2. 商品上线时，替换 `deploy/oss/public-products-policy.json` 中的 Bucket 名，使用 Bucket Policy 仅允许匿名 `GetObject` 读取 `products/*`。不授予匿名上传、列举或删除权限。如果“阻止公共访问”阻止了该授权，仅在确认这个 Bucket 专门用于公开商品图后调整该 Bucket 的设置，不关闭账户级防护或敏感 Bucket 的保护。
3. 在商品 Bucket 的防盗链中添加实际网站来源（例如 `https://example.com/*`）和小程序来源 `https://servicewechat.com/实际小程序APPID/*`，关闭允许空 Referer。不要填写 `*` 通配所有网站。项目已将跨站 Referrer 策略改为只带来源，避免浏览器图片因没有 Referer 被误拦。须分别用真实网页、开发者工具和微信真机验证，不能只凭 Referer 规则推断已生效。
4. **付款凭证、用户隐私附件**使用另一个私有 Bucket，保持阻止公共访问；不配置任何匿名读取策略，不给用户保存永久公开链接。当前尚无 OSS 私有凭证上传入口，暂不将凭证文件放入商品桶。
5. 后续接入上传服务时，给专用 RAM 身份使用 `deploy/oss/uploader-policy.json`，替换 Bucket 名后只允许写入 `products/*`。该模板不允许读私有凭证、管理 Bucket 或修改访问策略，也不是现阶段必须创建的长期密钥。

先验证：正常小程序/网页可看商品图；无 Referer 或无关网站来源的匿名请求被拒绝；匿名上传、列举被拒绝；私有凭证没有公开读取路径。Referer 可以伪造，仅用于降低盗链，不能保证防刷或限制账单。被拒绝的请求也可能存在计费项，不能把拒绝请求等同于零费用。

## 阿里云侧告警：上线前人工启用

本仓库不能替你启用云端规则；这些步骤需在实际阿里云账户完成并验证通知送达。

| 位置                  | 首期建议                                              | 如何验收                                                                                       |
| --------------------- | ----------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| 费用与成本 → 预算管理 | 为后续按量费用设置每月 50 元预算，50%、80%、100% 提醒 | 核对统计范围，避免将已支付的 ECS 年费算成异常按量支出；OSS、ECS 流量按控制台支持的维度分别查看 |
| 费用中心的余额提醒    | 可用余额低于 100 元时提醒                             | 联系人及通知渠道已绑定，能收到通知                                                             |
| 云监控 → OSS 监控     | 外网出方向带宽持续 5 分钟超过 1Mbps 时提醒            | 选择实际杭州 Bucket，并核对界面指标单位后换算阈值                                              |
| 云监控 → ECS 监控     | 公网出方向带宽持续 5 分钟超过 2.5Mbps 时提醒          | 选择本次 ECS，核对指标单位和统计周期                                                           |

启用规则前确认相应监控和通知产品是否收费，优先使用已有基础能力。预算和账单有统计延迟，告警不是硬限额，不会自动暂停业务。余额耗尽也不是可靠的止损方式。

## 异常时人工止损

先看监控：ECS 流量异常和 OSS 流量异常是不同入口。保存异常时间、来源、被访问路径、流量指标及请求号，优先封禁明确来源；不要直接修改订单支付或退款状态。

**明确异常 IP**：编辑 `/etc/nginx/jianyanji/blocked-ips.conf`，添加 `deny 实际异常IP;`，执行 `sudo nginx -t && sudo systemctl reload nginx`。封禁共享出口可能影响正常用户，不要随意封禁微信回调来源。

**商城 API 被刷**：

```bash
sudo jyj-traffic-guard pause-api
sudo jyj-traffic-guard status
# 查明并处理异常后恢复
sudo jyj-traffic-guard resume-api
```

开关对新请求立即生效，不需要重载；停止普通 API 和登录入口，支付通知仍到达 Java 验签处理。已经到达后端的请求不会被取消。

**本地素材被刷**：使用 `pause-assets` / `resume-assets`。`/assets/` 同时包含网页脚本和样式，暂停后刷新后台也可能无法加载，恢复操作应通过 SSH 执行。

**OSS 图片被刷**：服务器开关无效。进入对应商品 Bucket 的权限控制，暂时撤销 `public-products-policy.json` 对应的匿名读取授权，同时确认对象没有独立 `public-read` ACL。如果以后接入 CDN，缓存中的图片仍可能继续被下载，需在 CDN 另行暂停相关域名。暂停商品图会影响展示，处理后再恢复。不要修改私有凭证桶的权限。

以上是人工止损，没有自动按金额断流。若确需硬限制预算，应另行设计配额/分发方案，并接受额度耗尽后正常用户也会看不到图片的影响。
