# 后台图片直传 OSS

商品列表图、详情图、品牌首页图和入驻产品包权益图统一选择本地图片上传，支持 JPG、PNG、WebP，单张不超过 5MiB。浏览器自动压缩后直接向 OSS 发送文件；Java 只签发短期表单凭证，上传成功后将公开图片地址带入原有保存接口。上传失败保留原图，上传过程中不能保存或关闭编辑弹窗。

这些图片均用于公开展示。付款凭证和个人隐私附件不能使用此上传入口。

## 服务端配置

在部署服务器的 `/etc/jianyanji.env` 或本地根目录 `.env` 设置以下变量，重启 Java 服务生效。不要将真实密钥提交到仓库或放入前端构建变量。

```dotenv
OSS_REGION=cn-hangzhou
OSS_BUCKET=实际商品图片桶名
OSS_ACCESS_KEY_ID=专用RAM身份的AccessKeyId
OSS_ACCESS_KEY_SECRET=专用RAM身份的AccessKeySecret
# 可选：使用临时凭证时必须同时提供有效的安全令牌。
OSS_SECURITY_TOKEN=
# 可选：已绑定此 Bucket 的 HTTPS 图片域名，例如 https://images.example.com。
OSS_PUBLIC_BASE_URL=
```

`OSS_REGION` 使用地域 ID，例如 `cn-hangzhou`，不包含 `oss-`。上传域名由 Bucket 和地域生成，例如 `https://bucket-name.oss-cn-hangzhou.aliyuncs.com`。`OSS_PUBLIC_BASE_URL` 留空时，保存的图片地址也使用这个域名；使用自定义域名时需完成 OSS/CDN 绑定和 HTTPS 配置。

已有图片地址继续显示。未配置 OSS 时，上传会明确提示配置不可用，不会替换当前图片。

## Bucket 权限与跨域

1. 为专用 RAM 身份应用 [上传权限模板](../../deploy/oss/uploader-policy.json)，替换实际 Bucket 名。仅允许写入 `products/*`。
2. 按 [公开图片读取模板](../../deploy/oss/public-products-policy.json) 只开放 `products/*` 的匿名读取，保持匿名上传、列举和删除被拒绝。
3. 在 Bucket 跨域设置中应用 [跨域模板](../../deploy/oss/image-upload-cors.xml)，将来源替换为真实后台 Origin，例如 `https://admin.example.com`，不带路径或结尾斜杠。开启 POST、GET、HEAD，允许请求头 `Content-Type`，暴露 `x-oss-request-id` 和 `ETag`。OSS 会处理跨域 OPTIONS 预检。
4. 本地调试需要单独添加 `http://127.0.0.1:5173` 来源；`localhost` 与 `127.0.0.1` 是不同来源。生产桶只保留实际使用的来源。
5. 防盗链允许后台网站及实际小程序来源，具体沿用 [OSS 防盗链说明](basic-traffic-protection.md#oss公开商品图片与敏感凭证分开)。分别验证上传和图片读取。

表单上传不发送后台 Cookie 到 OSS。签名有效期为 5 分钟，约束指定 Bucket、完整对象键、图片 MIME 和文件大小；文件名由服务端随机生成，浏览器不能指定存储路径。对象键统一位于 `products/catalog/`、`products/content/` 或 `products/membership/`。取消编辑和替换图片不会自动删除 OSS 对象，避免删除已被其他业务引用的文件。

## 接口与生产定位

`POST /api/admin/image-uploads/policy` 接收：

```json
{ "purpose": "catalog", "contentType": "image/png", "size": 102400 }
```

用途 `catalog`、`content`、`membership` 分别要求商品管理、品牌内容管理和代理入驻权限。接口返回 `host`、`url`、`key`、`fields` 和 `requestId`。浏览器将 `fields` 逐项写入 `FormData`，最后添加 `file`，向 `host` 发起 POST；OSS 返回 204 后才更新图片地址。签名遵循 [OSS POST V4 规范](https://help.aliyun.com/zh/oss/developer-reference/signature-version-4-recommend)。

OSS 上传失败时，浏览器调用 `POST /api/admin/image-uploads/failure` 记录用途、对象键、HTTP 状态和 OSS 请求号。网络或 CORS 失败时状态为 0，页面保留签发请求号。服务端日志记录 requestId、工作人员编号和对象键，便于关联 OSS 请求；不记录密钥、签名或图片正文。日志上报失败不会触发自动重试，也不会将失败图片保存为成功。

## 上线核对

- 用有权限的工作人员上传小图，确认 OSS 对象生成、预览正常、保存后重新编辑仍显示该图片。
- 上传期间保存按钮不可用；超过 5MiB、非支持格式和损坏图片应被拒绝。
- 拒绝无权限账号签发凭证；上传失败后原图和其他表单内容仍保留。
- 篡改表单对象键、文件类型或超出大小约束，应由 OSS 拒绝。
- 在真实后台域名和微信真机验证图片读取；浏览器模拟上传不能替代真实 Bucket 权限、CORS 和防盗链验证。


## 图片压缩、缩略图与缓存

### 上传前压缩

后台先校验输入图片（JPG、PNG、WebP，单张不超过 5MiB），再在浏览器中以 WebP、质量 82% 编码。宽度不超过 1600px，不放大小图；长图保持比例，同时将画布限制在高度 16384px、总像素约 1600 万以内。压缩结果只有小于输入文件时才用于上传，防止小图反而变大；浏览器未提供 WebP 编码时，以实际编码类型为准。图片不经过 Java 服务，不增加后端图片处理或网络调用。

上传授权绑定的是最终上传文件的 MIME 和字节数。保存的地址指向这张展示用主图，不另存一份未经压缩的大原图；需要留存设计源文件时应由运营单独保管。取消或失败不会将新地址写入表单。

### 展示缩略图

固定三个档位，均等比缩放、不放大小图，质量 80%，输出 WebP：

| 档位 | 宽度上限 | 使用位置 |
| --- | --- | --- |
| thumb | 160px | 后台商品列表、购物袋、结算页、订单、内容关联商品 |
| card | 400px | 商城卡片、首页商品、内容列表、后台上传预览、内容分享封面 |
| detail | 1200px | 首页主视觉、商品详情、入驻权益图、内容正文及视频封面 |

数据库继续保存主图地址。小程序和后台仅在展示时附加 `x-oss-process=image/resize,w_400,m_lfit,limit_1/quality,q_80/format,webp` 等参数；正文点击大图预览仍使用未附加参数的主图地址。

只转换本项目直传生成的 `products/{catalog|content|membership}/{日期}/{UUID}.{jpg|png|webp}` HTTPS 地址，支持 OSS 默认域名及已接入 OSS 图片处理的自定义域名。包内素材、外部图片、带查询参数或片段的地址保持不变，避免破坏签名和已有处理规则。已有符合路径规则的图片发布前端后即可使用缩略图；外部或早期手工维护的其他路径需重新上传后才能纳入。

图片处理可能单独计费。自定义图片域名或未来的 CDN 必须将 `x-oss-process` 透传到 OSS，并将查询参数纳入缓存键；否则不同尺寸可能得到同一份缓存，或处理参数不生效。

### 公开图片缓存

新上传的对象在签名表单中绑定 `Cache-Control: public, max-age=31536000, immutable`。文件名使用随机 UUID、禁止覆盖，同地址内容不变，更换图片产生新地址。缓存时长一年，客户端实际缓存命中仍受微信、浏览器和设备缓存策略影响。

这项设置随 Java 服务部署后对新上传生效，**不会自动修改已有 OSS 对象的响应头**。已有公开商品图片需在 OSS 控制台的文件管理中，选择本项目 `products/` 下的公开图片，批量设置 HTTP 头 `Cache-Control` 为 `public, max-age=31536000, immutable`，保留 Content-Type 等其他元数据。不要对付款凭证、私有附件应用公开缓存配置。

### 发布验证

1. 发布 Java、管理后台与小程序；使用真实 Bucket 确认已启用并允许所需图片处理操作。
2. 在后台上传商品图，检查对象 MIME、实际字节数及缓存头；确认压缩后清晰度、透明背景和长图文字可接受。
3. 在网站及微信真机查看商城、购物袋、结算、订单、首页、内容与入驻页，确认图片请求携带对应的 160/400/1200 档位；正文大图预览使用主图地址。
4. 检查原地址和缩略图响应的 `Cache-Control`、Content-Type 与实际尺寸；再次访问检查缓存命中。跨域上传和防盗链规则继续按前文验证。
5. 若使用自定义图片域名，分别请求同图的 160px 和 1200px 版本，确认处理参数和缓存键生效，再检查 OSS 流量和图片处理账单。
