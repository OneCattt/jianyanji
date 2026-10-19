# catalog / orders / fulfillment 输入长度与 V1 列宽核对

核对日期：2026-10-19。依据为 Node 原接口、当前 Java 校验、V1 DDL，以及本地 `127.0.0.1:3308/jianyanji_schema_check` 的 information_schema。该隔离库实测版本为 MySQL 8.0.45。

## 给 Bernoulli 的 V2 增补项

本域恰有两列需要扩容，均为商品维护接口合法输入（最长 1000 字符）：

| 请求字段 | 实际落库列 | Node / Java 上限 | V1 / 当前隔离库 | 建议 V2 |
| --- | --- | ---: | --- | --- |
| image | products.image | 1000 | VARCHAR(191) NOT NULL | VARCHAR(1000) NOT NULL |
| detailImage | products.detail_image | 1000 | VARCHAR(191) NOT NULL | VARCHAR(1000) NOT NULL |

影响 `POST /api/admin/products`、`PUT /api/admin/products/{id}`；列表/详情、购物车和报价快照也读取这两个值。品牌内容的 `heroImage` 存在 settings.value JSON 中，不是第三个待扩容列。

请由 V2 负责人将以下内容合并到尚在协调的增量迁移；本次没有修改 V1 或 V2 文件，也没有执行 ALTER：

```sql
ALTER TABLE `products`
  MODIFY COLUMN `image` VARCHAR(1000) NOT NULL COMMENT '列表图片地址，最长1000字符',
  MODIFY COLUMN `detail_image` VARCHAR(1000) NOT NULL COMMENT '详情图片地址，最长1000字符';
```

两列无索引，扩容不改变唯一键、默认值或可空性。应保留原接口 1000 字符限制，不能缩为 191 或截断地址。

## 完整输入对照

以下长度均指校验后的字符数；JSON 内容仍写入 LONGTEXT，不映射到额外 VARCHAR 列。

| 领域输入 / 生成值 | 上限 | 实际列 / 类型 | 结论 |
| --- | --- | --- | --- |
| content.brandName | 30 | settings.value LONGTEXT | 足够 |
| content.introduction | 2000 | 同上 | 足够 |
| content.heroTitle / heroSubtitle | 40 / 100 | 同上 | 足够 |
| content.heroImage | 1000 | 同上 | 足够 |
| content.customerService | 300 | 同上 | 足够 |
| content.purchaseNotes / transferNotes | 2000 / 2000 | 同上 | 足够 |
| content.companyName / bankName / bankAccount | 各100 | 同上 | 足够 |
| category.name | 40 | categories.name VARCHAR(191) | 足够 |
| product.name | 80 | products.name VARCHAR(191) | 足够 |
| product.subtitle | 160 | products.subtitle VARCHAR(191) | 足够 |
| product.description | 8000 | products.description LONGTEXT | 足够 |
| product.image / detailImage | 各1000 | products.image / detail_image VARCHAR(191) | 两列须扩容 |
| sku.name / unit | 60 / 10 | skus.name / unit 各VARCHAR(191) | 足够 |
| address.recipient / phone | 40 / 固定11 | addresses.recipient / phone 各VARCHAR(191) | 足够 |
| address.province / city / district | 各30 | 对应列各VARCHAR(191) | 足够 |
| address.detail | 200 | addresses.detail VARCHAR(2000) | 足够，无需扩为200 |
| shipping.provinces | 每项30，最多40项 | settings.value LONGTEXT | 足够 |
| shipping.instructions | 2000 | settings.value LONGTEXT | 足够 |
| 下单 requestKey | 100 | orders.request_key VARCHAR(191) | 足够 |
| 服务端订单编号 | 26（JY+8位日期+16位UUID片段） | orders.number VARCHAR(191) | 足够 |
| 订单及报价快照 | 按已校验商品、地址、政策生成 JSON | orders.snapshot / quotes.snapshot LONGTEXT | 足够 |
| shipment.carrier | 60 | shipments.carrier VARCHAR(191) | 足够 |
| shipment.tracking | 80 | shipments.tracking VARCHAR(191) | 足够 |
| shipment.requestKey | 100 | shipments.request_key VARCHAR(191) | 足够 |
| shipment.lines | 最多50项，编号和数量经校验的 JSON | shipments.lines LONGTEXT | 足够 |
| correction.old_carrier / carrier | 各60 | shipment_corrections 对应列各VARCHAR(191) | 足够 |
| correction.old_tracking / tracking | 各80 | 同上 | 足够 |
| correction.reason | 200 | shipment_corrections.reason VARCHAR(2000) | 足够 |
| 各维护 reason / 审计说明 | 客户端200；内置说明为固定短文本 | audit_events.reason VARCHAR(2000) | 足够 |
| correction / audit 的 request_id | ApiFilter 服务端生成32位 UUID | 对应 request_id VARCHAR(191) | 足够；不直接信任外部请求头 |
| expires_at / shipped_at / received_at | 服务端 ISO UTC，最长30 | 对应列VARCHAR(191) | 足够 |
| kind / status / payment_method / payment_state / 审计动作 | 服务端固定枚举和动作名 | 对应列VARCHAR(191) | 足够，不接受自由长文本 |

`cart_items` 与 `order_lines` 的业务输入为编号、数量和金额，没有遗漏的文本列。settings.key 为本域固定的 content / shipping，不承载用户输入。审计的 object_id 为数字编号或固定 brand / settings，均不会超过191。

## 真实 MySQL 证据

在隔离库事务内新建临时分类和商品，用合法 `https://example.com/` 前缀填充图片 URL：

- image、detail_image 各191字符：插入成功，CHAR_LENGTH 均为191。
- 分别将 image、detail_image 改为192字符：均得到 `ERROR 1406 (22001): Data too long for column ...`。
- 分别将 image、detail_image 改为1000字符：同样报1406。
- 最后 ROLLBACK；按临时主键查询，产品和分类剩余记录数均为0，没有物理删除或保留探针数据。

这是列宽问题，合法 URL 已通过现有字符串及 https 前缀约束。当前 Java `ApiErrors` 将 DataIntegrityViolationException 映射为409；实际问题仍是合法输入保存失败，必须扩容解决。

V3 扩容后验证：两个字段分别在192、1000字符时均能通过真实 CatalogService 新增/更新并完整读回，1001字符仍由原输入校验拒绝，且订单及报价快照不截断图片地址。
