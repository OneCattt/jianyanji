# 身份与后台权限接口

所有接口以 `/api` 开头。错误格式为 `code`、中文 `message`、`requestId`；响应头同样有 `X-Request-ID`。认证相关响应禁止缓存。请求与响应均为 JSON，不支持客户端传入身份或权限作为认证依据。

## 小程序与购买端

| 方法与地址 | 登录要求 | 结果 |
| --- | --- | --- |
| GET `/public/context` | 无 | 品牌名称、本地调试开关、微信配置是否就绪，不返回密钥 |
| POST `/auth/wechat` | 无 | 只接受 `code`，服务端换取 openid，返回 `token` 与 `user` |
| POST `/auth/dev` | 无，仅本地显式开启 | 只接受 `account`；新账号固定建立为游客，已有代理保留身份 |
| GET `/me` | 用户 Bearer token | 本人编号、昵称、联系电话、有效身份及中文身份名 |
| PATCH `/me` | 用户 Bearer token | 只接受 `nickname` 和 `phone`，保存本人资料 |
| GET/PATCH `/users/:id` | 用户 Bearer token，编号必须本人 | 同本人资料接口；他人编号返回 404 |
| POST `/auth/logout` | 用户 Bearer token | 撤销当前用户会话 |

手机号可为空；填写时为大陆 11 位手机号，仅作为联系资料。昵称去首尾空白后 1～30 字。修改接口严格拒绝身份、openid、其他用户编号等额外字段。

用户会话有效期 7 天，令牌仅在客户端返回一次，数据库保存 SHA-256 摘要。已有代理的有效期过期后返回 D；登录不延长或重新授予身份。

## 品牌方后台

后台通过 `jyj_admin` HttpOnly cookie 认证，与用户 Bearer token 相互隔离。写入请求须携带同源 Origin，且不是跨站来源；不开放跨域 cookie 登录。会话有效期 12 小时，权限和账号状态每次请求核对。

| 方法与地址 | 所需权限 | 结果 |
| --- | --- | --- |
| POST `/admin/login` | 无，仍校验 Origin | `username`、`password` 登录，成功设置 cookie，响应不返回令牌或密码摘要 |
| GET `/admin/me` | 已登录工作人员 | 本人账号、姓名、权限、启用状态 |
| POST `/admin/logout` | 已登录工作人员 | 撤销当前后台会话并清 cookie |
| GET `/admin/permissions` | `staff:manage` | 当前可分配权限 |
| GET `/admin/staff` | `staff:manage` | 生效中的工作人员列表，不含密码摘要 |
| POST `/admin/staff` | `staff:manage` | 新增账号，要求账号、姓名、至少 12 位密码、权限和原因 |
| PATCH `/admin/staff/:id` | `staff:manage` | 修改 `displayName`、`permissions`、`enabled`，要求 `reason` |
| POST `/admin/staff/:id/remove` | `staff:manage` | 根据 `reason` 逻辑移除，不物理删除 |
| GET `/admin/users?page=1` | `users:read` | 用户列表，每页 30 条，联系电话脱敏 |
| GET `/admin/audit?page=1` | `audit:read` | 操作记录，每页 30 条，可按 `objectType`、`objectId` 筛选 |

权限：`staff:manage` 工作人员管理、`users:read` 用户资料查看、`audit:read` 操作记录查看。账号管理员有权分配这三类后台权限，不授予 B/C 购买身份。系统至少保留一个启用且具备工作人员管理权限的账号。

操作记录中 `objectType` 支持 user、staff、permission；操作者类型与编号共同识别用户或工作人员，不能只按数字跨体系识别。查询只返回操作摘要，不包含手机号、密码、微信凭证。

登录每来源地址在 15 分钟内至多 20 次尝试，超过返回 429。生产 Nginx 覆盖 `X-Real-IP`，Tomcat 只信任本机代理恢复来源地址；外部直连不能使用自报头绕过限制。入口另有限流及人工暂停开关，见 [基础防刷与费用告警](basic-traffic-protection.md)。

## 后续业务复用

后续任务使用相同的用户/后台会话和权限校验方式，按实际业务增加权限项；不把个人资料接口作为代理资格修改入口。关键业务操作使用既有审计写入能力，在成功事务中一起记录，并对需要查询的拒绝结果留痕。普通资料维护不额外建设失败重试平台。
