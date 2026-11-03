-- 手机号仅关联原微信账号，不新增手机号唯一键，不合并历史用户和资金。
ALTER TABLE `users`
  ADD COLUMN `phone_verified_at` DATETIME(3) DEFAULT NULL COMMENT '微信授权手机号验证记录时间，手填或未验证为空';

CREATE TABLE `wechat_exchange_logs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  `interface_name` VARCHAR(80) NOT NULL COMMENT '微信接口名称',
  `target_url` VARCHAR(255) NOT NULL COMMENT '微信接口地址，不含访问令牌或密钥',
  `business_key` VARCHAR(191) NOT NULL COMMENT '用户编号或本次登录凭证的不可逆摘要关联键',
  `request_id` VARCHAR(191) NOT NULL COMMENT '请求追踪编号',
  `request_body` LONGTEXT NOT NULL COMMENT '脱敏后的请求参数JSON，授权码和密钥不落库',
  `response_body` LONGTEXT DEFAULT NULL COMMENT '脱敏后的响应JSON，无响应时为空',
  `result` VARCHAR(24) NOT NULL COMMENT 'success成功 business_failure业务失败 exception异常 timeout超时',
  `http_status` INT DEFAULT NULL COMMENT 'HTTP状态码，未收到响应时为空',
  `elapsed_ms` BIGINT NOT NULL COMMENT '接口调用耗时，毫秒',
  `error_type` VARCHAR(191) DEFAULT NULL COMMENT '异常类型，无异常时为空',
  `error_message` VARCHAR(500) DEFAULT NULL COMMENT '脱敏错误说明，无异常时为空',
  `update_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '修改用户ID',
  `create_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '新增用户ID',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  `is_deleted` TINYINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '删除标记 0：未删除 1：删除',
  INDEX `wechat_exchange_request` (`request_id`, `is_deleted`),
  INDEX `wechat_exchange_business` (`business_key`, `create_time`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='微信身份及手机号接口请求响应日志';
