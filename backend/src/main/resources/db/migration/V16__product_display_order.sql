ALTER TABLE `products`
  ADD COLUMN `sort` BIGINT NOT NULL DEFAULT 0 COMMENT '商品显示顺序，越小越靠前；同值按新商品在前';

CREATE INDEX `idx_products_display_order` ON `products` (`is_deleted`, `active`, `sort`, `id` DESC);
CREATE INDEX `idx_products_category_display_order` ON `products` (`category_id`, `is_deleted`, `active`, `sort`, `id` DESC);
