-- 品牌种草内容；保存与修改回到草稿，审核后才能发布，正常公开查询仅返回已发布记录。
CREATE TABLE `brand_posts` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `type` VARCHAR(20) NOT NULL COMMENT '内容类型：article 图文，video 短视频',
  `title` VARCHAR(100) NOT NULL COMMENT '审核后的标题，1至100字',
  `summary` VARCHAR(300) NOT NULL COMMENT '内容摘要，1至300字',
  `body` TEXT NOT NULL COMMENT '纯文字正文，不执行HTML',
  `cover` VARCHAR(1000) NOT NULL COMMENT 'HTTPS封面图片地址',
  `image_urls` TEXT NOT NULL COMMENT '图文图片地址JSON数组，最多9张',
  `video_url` VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '已配置媒体域名的HTTPS MP4地址，图文为空',
  `duration_seconds` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '短视频时长，单位秒，1至180；图文为0',
  `product_ids` TEXT NOT NULL COMMENT '关联商品编号JSON数组，最多10个',
  `status` VARCHAR(20) NOT NULL DEFAULT 'draft' COMMENT 'draft草稿、reviewed已审核、published已发布',
  `reviewed_by` BIGINT UNSIGNED DEFAULT NULL COMMENT '审核工作人员ID，草稿为空',
  `reviewed_at` DATETIME(3) DEFAULT NULL COMMENT '审核时间，草稿为空',
  `published_at` DATETIME(3) DEFAULT NULL COMMENT '最近发布时间，尚未发布为空',
  `update_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '修改用户ID',
  `create_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '新增用户ID',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  `is_deleted` TINYINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '删除标记 0：未删除 1：删除',
  PRIMARY KEY (`id`),
  INDEX `brand_posts_public` (`is_deleted`,`status`,`published_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='品牌审核发布图文及短视频';
