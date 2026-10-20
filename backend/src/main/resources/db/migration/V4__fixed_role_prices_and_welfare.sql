-- V2 新单价格：普通 SKU 固定身份折扣，福利 SKU 统一福利价。
-- 不改写既有订单、退款或奖励快照；历史行 product_type/price_rule 为空时沿用原价差依据。
ALTER TABLE skus
  ADD COLUMN product_type VARCHAR(20) NOT NULL DEFAULT 'regular' COMMENT '商品类型 regular：普通商品 welfare：福利品',
  ADD COLUMN welfare_cents BIGINT NULL COMMENT '统一福利单价，单位分；空表示福利价未配置',
  ADD CONSTRAINT chk_sku_product_type CHECK (product_type IN ('regular','welfare')),
  ADD CONSTRAINT chk_sku_welfare_price CHECK (welfare_cents IS NULL OR (welfare_cents > 0 AND welfare_cents <= 100000000));

ALTER TABLE order_lines
  ADD COLUMN product_type VARCHAR(20) NULL COMMENT '下单商品类型快照，regular/welfare；空为迁移前原订单',
  ADD COLUMN retail_cents BIGINT NULL COMMENT '下单零售价快照，单位分，可空；历史订单不回填现价',
  ADD COLUMN price_rule VARCHAR(30) NULL COMMENT '价格规则版本 v2-fixed-role；空为迁移前原订单';
