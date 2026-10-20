# 简妍肌域名 HTTPS 与自动续期

## 当前部署

- 配置日期：2026-10-20。
- 域名：`api.jianyanji.cn`，A 记录指向 `121.199.29.242`。
- 后台：`https://api.jianyanji.cn/admin`。
- 小程序接口基址：`https://api.jianyanji.cn/api`。
- 证书：Let's Encrypt，通过 Certbot webroot 验证签发，私钥仅存放于服务器。
- 域名 Nginx 配置：`/etc/nginx/conf.d/zz-jianyanji-cn.conf`。该文件在 `jianyanji.conf` 后加载，复用其中的限流区域和日志格式。
- HTTP 验证根目录：`/var/www/letsencrypt`。保留 `/.well-known/acme-challenge/` 的 HTTP 访问，其余域名 HTTP 请求跳转 HTTPS。
- 证书文件：`/etc/letsencrypt/live/api.jianyanji.cn/fullchain.pem`。
- 私钥文件：`/etc/letsencrypt/live/api.jianyanji.cn/privkey.pem`。

本次首次签发的证书到期日为 2027-01-18；后续以服务器实际证书为准。

## 自动续期

已启用系统自带的 `certbot-renew.timer`，开机自动启动，每天安排两次检查并随机错峰。Certbot 在证书进入续期窗口后更新证书。

续期成功后执行 `/etc/letsencrypt/renewal-hooks/deploy/20-jianyanji-nginx`：先运行 `nginx -t`，通过后重载 Nginx。配置检查失败时不会重载。

在服务器检查：

```sh
systemctl status certbot-renew.timer --no-pager
systemctl list-timers certbot-renew.timer --all --no-pager
journalctl -u certbot-renew.service -n 50 --no-pager
journalctl -t jianyanji-tls -n 20 --no-pager
openssl x509 -in /etc/letsencrypt/live/api.jianyanji.cn/cert.pem -noout -issuer -dates
```

需要排查时可模拟续期（不会替换当前正式证书）：

```sh
certbot renew --cert-name api.jianyanji.cn --dry-run --non-interactive --no-random-sleep-on-renew
```

保留域名 A 记录、公网 80 端口及验证路径；续期还需要服务器能访问 Let's Encrypt。修改域名入口时同步核对这些条件。

## 已完成验证

- 公网 DNS 解析和 HTTP 验证路径可达。
- HTTPS 公共接口返回正常，客户端默认信任链验证通过。
- HTTPS `/admin` 返回 200，HTTP `/admin` 跳转同域名 HTTPS。
- 模拟续期成功；部署钩子的配置检查和 Nginx 重载成功。
- 自动续期定时器已启用。

### 域名切换记录

2026-10-20 17:05（北京时间）按用户要求改用 `api.jianyanji.cn`。新域名证书签发、客户端默认信任链、公网接口、后台页面、HTTP 跳转及模拟续期均已验证通过；不据此推断 ICP 备案已完成，备案状态以阿里云控制台为准。

17:13 左右复查时，`api.jianyanji.cn` 在当前公网检测线路也出现备案拦截：HTTP 返回 `403`、`Server: Beaver` 和 `Non-compliance ICP Filing` 页面，HTTPS 握手连接重置。服务器内部通过该域名的 HTTPS 接口仍正常。需要完成备案或备案接入并解除拦截，再验证公网访问、模拟续期与真实微信登录；不能将首次公网验证通过视为后续持续可用。

旧域名 `api.qingxinjianyangji.com` 曾在部分公网访问线路出现备案拦截：HTTP 返回 `403`、响应头 `Server: Beaver`、页面标题 `Non-compliance ICP Filing`，同时有其他来源成功访问的记录。该现象不等于所有访问线路均不可达。

切换前 Nginx 和续期配置备份：`/var/backups/jianyanji/20261020T090456Z-jianyanji-cn`。旧域名入口及续期配置归档到该目录的 `retired-domain.conf`、`retired-renewal.conf`，原 IP 入口保留。

## 小程序接入

完成域名 ICP 备案，并在微信公众平台的 request 合法域名中添加 `https://api.jianyanji.cn`。小程序接口基址使用上述 HTTPS 地址。

本地 `miniprogram/config.js` 的接口基址已切换到 `https://api.jianyanji.cn/api`，语法检查通过；需在微信开发者工具中重新编译，上传体验版或发布版后才会影响相应已安装版本。本次未发布业务代码。

随后按用户手机预览调试需求，接口基址临时改回 `http://121.199.29.242/api`。本机开发者工具私有配置中 `urlCheck=false`，手机端仍需打开调试；`devAuth=false`，继续使用真实微信登录。备案拦截解除后，正式发布前切回上述 HTTPS 域名并恢复域名校验。

用户处理欠费后要求改回域名，本地接口基址已再次切回 `https://api.jianyanji.cn/api`。本次从当前检测线路访问 HTTPS 公共接口仍出现连接重置，尚未确认公网连通恢复；需要在微信开发者工具重新编译后测试。

2026-10-20 已将用户提供的微信登录凭证配置到服务器 `/etc/jianyanji.env` 的 `WECHAT_APP_ID`、`WECHAT_APP_SECRET`，配置文件权限为仅 root 可读写，后端重启后返回 `wechatConfigured=true`、`devAuth=false`。微信 stable_token 接口已接受该组凭证，校验过程中未回显密钥或访问令牌；这不等于真实 `wx.login` 流程已通过，仍需在小程序中实际登录验证。密钥不得放入前端、文档或 Git。
