-- 对齐首期 Node 既有输入边界；仅扩宽字段，保留已有数据、索引、可空性与默认值。
-- 收款账户、外部流水上限均为 200 字符，联合唯一索引继续覆盖完整字段。
ALTER TABLE `company_receipts`
  MODIFY COLUMN `account_ref` VARCHAR(200) NOT NULL COMMENT '公司收款账户标识，最长200字符',
  MODIFY COLUMN `external_transaction_id` VARCHAR(200) NOT NULL COMMENT '原公司收款流水号，最长200字符';

-- 资格快照包含入驻条件及售后政策等完整 JSON；退款身份撤销原因沿用 2000 字符上限。
ALTER TABLE `onboardings`
  MODIFY COLUMN `qualification_snapshot` LONGTEXT NULL COMMENT '身份条件核对快照 JSON，可空',
  MODIFY COLUMN `review_reason` VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '身份审核或退款撤销原因，最长2000字符';

-- Node 的 paidAt 为无长度上限字符串；成功结果另外校验 ISO UTC 时间，
-- 明确失败/驳回时保留工作人员提交的原始值，不新增与旧接口不一致的长度限制。
ALTER TABLE `cash_refunds`
  MODIFY COLUMN `paid_at` LONGTEXT NULL COMMENT '工作人员提交的实际付款时间；成功结果要求ISO UTC，可空';
