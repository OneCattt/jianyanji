-- 对齐 Node 和 CatalogService 图片地址最长1000字符的既有输入约束。
-- 仅扩宽商品图片字段，保留已有数据、NOT NULL约束及中文注释。
ALTER TABLE `products`
  MODIFY COLUMN `image` VARCHAR(1000) NOT NULL COMMENT '列表图片地址',
  MODIFY COLUMN `detail_image` VARCHAR(1000) NOT NULL COMMENT '详情图片地址';
