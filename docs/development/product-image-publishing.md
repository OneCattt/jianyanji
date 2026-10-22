# 商品图片上传与发布

## 操作流程

- 后台「商品与分类」→ 新增或编辑商品 →「列表图片」选图。
- 在正方形画布中拖动或用滑块调整商品大小、左右及上下位置，同时核对首页竖框、商城方框预览。
- 默认居中裁剪。封面最大 1200×1200px；可用区域不足 600px 时提示，允许继续；不放大小图。封面只做一次 WebP 编码，质量 0.9。
- 「详情图片」选图后先预览完整海报及原尺寸，确认后沿用已有等比压缩。
- 确认上传只更新商品编辑表单，点击「保存商品」才生效。取消或失败保留旧图，失败时保留裁剪画面，可重试或取消。裁剪、编码或上传期间禁止保存、关闭商品表单。
- 历史商品无需迁移。需要调整旧封面时，重新选择原图、裁剪并保存。裁剪只能取舍画面，不能自动擦除覆盖商品的文字。

继续使用 `image`、`detailImage` 两个字段，保存接口和库存原值校验不变。此次只发布管理前端，无后端源码、数据库迁移和配置变更，小程序发布独立进行。

## 验证

```sh
npm run typecheck
node --test scripts/check-image-crop.test.cjs scripts/check-image-delivery.test.cjs scripts/check-product-stock.test.cjs
npm run build
# 用静态服务器提供 dist，避免 Vite preview 继承开发环境 /assets 代理：
python3 -m http.server 4174 --bind 127.0.0.1 --directory dist
# 另一个终端，使用本机已有的 Playwright：
PLAYWRIGHT_MODULE=/本机/playwright ADMIN_TEST_BASE=http://127.0.0.1:4174 node scripts/check-product-images.browser.cjs
```

已通过类型检查、10 项针对性回归及生产构建的浏览器场景：拖动、键盘缩放、实际预览变化、低像素提示、窄屏、取消不上传、损坏图片拒绝、失败重试、实际编码后的图片尺寸、详情比例、上传与保存分离、保存失败保留表单、资料编辑不提交库存。

浏览器使用本地模拟的上传策略、OSS 和商品保存响应，检查实际编码并发送的图片。真实 OSS 上传及真机视觉效果需发布后使用业务账号验收；未在生产创建测试商品或改动真实商品。

## 发布与回退

发布提交及构建哈希写入服务器 `/opt/jianyanji/release.json`，本次备份目录在发布记录中保留。先同步带哈希的静态文件，再原子替换 `index.html`；保留旧静态资源供已有页面使用，后端 JAR 保持原版本。

回退时先恢复备份的资源，最后恢复旧版 `index.html` 及 `release.json`。没有数据库变更，代码回退不需要恢复业务数据；发布后已由运营保存的图片仍属于商品数据，不随页面回退撤销。

### 2026-10-22 发布结果

- 北京时间 14:23 发布管理前端 `88d23d469dfdc58d7fdc219a76b3b124346aa785`；后端保留 `f76f5ca320d357e15dbcd7be3a7447738a608bc4`，JAR SHA-256 仍为 `236af1754e8b4371208e34f4694edfe39b4948dd35b60b7935672baa62df4d28`。
- 备份：`/var/backups/jianyanji/20261022T142328+0800-admin-88d23d4`；发布产物与清单：`/opt/jianyanji/releases/admin-88d23d4`。
- 内部 HTTPS 的管理入口、JS 和 CSS 响应哈希与构建一致，Java、Nginx 均 active；未登录管理接口 401，`devAuth=false`。
- 首次校验脚本遇到服务器 Python 3.6 参数兼容问题，旧入口和清单自动恢复；修正脚本后重新发布成功。
- 当前检测网络访问公网域名 HTTPS 仍连接重置，服务器内 HTTPS 校验成功；不能据此声称公网全线路可用。真实 OSS 上传和微信端业务图片显示仍需运营账号及设备验收。
- 完整哈希与校验结果见 `.scratch/product-image-publishing/verification/deployment.json`。
