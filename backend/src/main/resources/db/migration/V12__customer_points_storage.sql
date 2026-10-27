-- 全客户积分、独立档价、寄存与分次交付。历史订单/余额不重算。
ALTER TABLE skus
 ADD COLUMN storage_enabled TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否在寄存白名单 0否1是',
 ADD COLUMN storage_exclusion VARCHAR(20) NOT NULL DEFAULT 'none' COMMENT '寄存禁用分类 none/flash/sample/near_expiry',
 ADD CONSTRAINT chk_storage_exclusion CHECK(storage_exclusion IN ('none','flash','sample','near_expiry')),
 ADD CONSTRAINT chk_storage_enabled CHECK(storage_enabled=0 OR storage_exclusion='none');
ALTER TABLE order_lines
 ADD COLUMN storage_qty BIGINT NOT NULL DEFAULT 0 COMMENT '购买时选择寄存的数量，按SKU单位',
 ADD COLUMN is_gift TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '赠品标记0否1是',
 ADD COLUMN bundle_key VARCHAR(191) NULL COMMENT '成交套餐来源，可空';
ALTER TABLE shipments ADD COLUMN pickup_id BIGINT NULL COMMENT '合并提货单编号，可空', ADD INDEX shipments_pickup(pickup_id);
CREATE TABLE storage_lots (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
 user_id BIGINT NOT NULL COMMENT '所属用户ID',
 order_id BIGINT NOT NULL COMMENT '来源购买订单ID',
 line_id BIGINT NOT NULL COMMENT '来源订单明细ID',
 sku_id BIGINT NOT NULL COMMENT '原商品规格ID，不可调换',
 total_qty BIGINT NOT NULL COMMENT '累计寄存数量，含赠品，按SKU单位',
 remaining_qty BIGINT NOT NULL COMMENT '尚未出库寄存数量，含申请/退款占用',
 held_qty BIGINT NOT NULL DEFAULT 0 COMMENT '提货申请占用数量',
 refund_held_qty BIGINT NOT NULL DEFAULT 0 COMMENT '退款办理占用数量',
 shipped_qty BIGINT NOT NULL DEFAULT 0 COMMENT '寄存累计出库数量',
 refunded_qty BIGINT NOT NULL DEFAULT 0 COMMENT '未提货已退款取消数量',
 unit_cents BIGINT NOT NULL COMMENT '原成交分摊单价，分；赠品计费为0',
 starts_on DATE NOT NULL COMMENT '上海付款日期',
 expires_on DATE NOT NULL COMMENT '到期日期，当日仍有效',
   `update_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '修改用户ID',
  `create_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '新增用户ID',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  `is_deleted` TINYINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '删除标记 0：未删除 1：删除' ,
 UNIQUE KEY storage_line(line_id), INDEX storage_user_expiry(user_id,expires_on,id),
 CHECK(remaining_qty>=held_qty+refund_held_qty AND held_qty>=0 AND refund_held_qty>=0),
 CHECK(total_qty=remaining_qty+shipped_qty+refunded_qty),
 FOREIGN KEY(line_id) REFERENCES order_lines(id)
) ENGINE=InnoDB COMMENT='用户专属寄存批次';
CREATE TABLE pickup_orders (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
 number VARCHAR(191) NOT NULL UNIQUE COMMENT '提货单号',
 user_id BIGINT NOT NULL COMMENT '所属用户ID',
 purchase_order_id BIGINT NULL COMMENT '合并的新购订单ID；纯寄存可空',
 status VARCHAR(30) NOT NULL COMMENT 'awaiting_payment/review/approved/rejected/shipped/received/cancelled',
 identity_snapshot VARCHAR(1) NOT NULL COMMENT '运费判断身份D/C/B',
 new_goods_cents BIGINT NOT NULL DEFAULT 0 COMMENT '本次新购货款，分',
 stored_goods_cents BIGINT NOT NULL DEFAULT 0 COMMENT '原寄存货成交金额，分，不含赠品',
 fee_cents BIGINT NOT NULL DEFAULT 0 COMMENT '本次运费积分，百分之一积分',
 fee_refunded TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '运费是否已退0否1是',
 address_snapshot LONGTEXT NOT NULL COMMENT '本次收货地址JSON快照',
 request_key VARCHAR(191) NOT NULL COMMENT '用户幂等请求编号',
 request_snapshot LONGTEXT NOT NULL COMMENT '原申请JSON，用于重试内容核对',
 review_reason VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '审核或取消原因',
 carrier VARCHAR(60) NULL COMMENT '快递公司，可空',
 tracking VARCHAR(80) NULL COMMENT '快递单号，可空',
 received_at VARCHAR(191) NULL COMMENT '确认收货UTC时间，可空',
   `update_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '修改用户ID',
  `create_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '新增用户ID',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  `is_deleted` TINYINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '删除标记 0：未删除 1：删除' ,
 UNIQUE KEY pickup_request(user_id,request_key), INDEX pickup_user_status(user_id,status,id)
) ENGINE=InnoDB COMMENT='单次合并提货审核与物流';
CREATE TABLE pickup_lines (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
 pickup_id BIGINT NOT NULL COMMENT '提货单ID',
 lot_id BIGINT NULL COMMENT '原寄存批次ID；本次新购首发可空',
 line_id BIGINT NOT NULL COMMENT '原购买订单明细ID',
 quantity BIGINT NOT NULL COMMENT '申请数量，按SKU单位',
   `update_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '修改用户ID',
  `create_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '新增用户ID',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  `is_deleted` TINYINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '删除标记 0：未删除 1：删除' ,
 UNIQUE KEY pickup_line(pickup_id,line_id), FOREIGN KEY(pickup_id) REFERENCES pickup_orders(id)
) ENGINE=InnoDB COMMENT='提货单来源明细';
CREATE TABLE storage_events (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
 lot_id BIGINT NOT NULL COMMENT '寄存批次ID',
 kind VARCHAR(30) NOT NULL COMMENT 'create/hold/release/ship/refund/extend/import',
 quantity BIGINT NOT NULL DEFAULT 0 COMMENT '本次变动数量，延期为0',
 reference_key VARCHAR(191) NOT NULL COMMENT '原业务幂等编号',
 reason VARCHAR(2000) NOT NULL COMMENT '操作依据',
   `update_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '修改用户ID',
  `create_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '新增用户ID',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  `is_deleted` TINYINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '删除标记 0：未删除 1：删除' ,
 UNIQUE KEY storage_event(lot_id,kind,reference_key)
) ENGINE=InnoDB COMMENT='寄存数量变动与延期流水';
CREATE TABLE pickup_point_allocations (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
 pickup_id BIGINT NOT NULL COMMENT '提货单ID',
 lot_id BIGINT NOT NULL COMMENT '积分来源批次ID',
 receipt_id BIGINT NOT NULL COMMENT '原充值收款ID',
 spent_cents BIGINT NOT NULL COMMENT '扣除运费，百分之一积分',
 refunded_cents BIGINT NOT NULL DEFAULT 0 COMMENT '累计退回，百分之一积分',
   `update_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '修改用户ID',
  `create_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '新增用户ID',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  `is_deleted` TINYINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '删除标记 0：未删除 1：删除' ,
 UNIQUE KEY pickup_points(pickup_id,lot_id), CHECK(refunded_cents>=0 AND refunded_cents<=spent_cents)
) ENGINE=InnoDB COMMENT='提货运费积分来源';
CREATE TABLE storage_messages (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
 user_id BIGINT NOT NULL COMMENT '接收用户ID',
 lot_id BIGINT NULL COMMENT '寄存批次ID，可空',
 kind VARCHAR(30) NOT NULL COMMENT 'expiry/rejected',
 title VARCHAR(100) NOT NULL COMMENT '站内消息标题',
 body VARCHAR(2000) NOT NULL COMMENT '消息内容',
 dedup_key VARCHAR(191) NOT NULL UNIQUE COMMENT '提醒去重编号',
 delivery_state VARCHAR(30) NOT NULL DEFAULT 'pending' COMMENT 'pending/sending/sent/failed/unavailable',
 last_error VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '发送失败原因',
 attempts INT NOT NULL DEFAULT 0 COMMENT '发送尝试次数',
 request_id VARCHAR(191) NULL COMMENT '诊断请求编号，可空',
   `update_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '修改用户ID',
  `create_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '新增用户ID',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
  `is_deleted` TINYINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '删除标记 0：未删除 1：删除' ,
 INDEX messages_user(user_id,id)
) ENGINE=InnoDB COMMENT='站内消息及微信提醒发送状态';
ALTER TABLE onboardings ADD COLUMN consumption_confirmed TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '人工消耗完成标记，仅统计，不控制售价', ADD COLUMN consumption_note VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '人工消耗完成依据';
CREATE TABLE qualification_actions (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
 user_id BIGINT NOT NULL COMMENT '目标用户ID',
 request_key VARCHAR(191) NOT NULL COMMENT '幂等操作编号',
 snapshot LONGTEXT NOT NULL COMMENT '身份变更依据与原新身份JSON',
 update_user_id BIGINT UNSIGNED DEFAULT '0' COMMENT '修改用户ID',
 create_user_id BIGINT UNSIGNED DEFAULT '0' COMMENT '新增用户ID',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
 is_deleted TINYINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '删除标记 0：未删除 1：删除',
 UNIQUE KEY qualification_action(user_id,request_key)
) ENGINE=InnoDB COMMENT='独立身份变更及关闭记录';
CREATE TABLE storage_subscriptions (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
 user_id BIGINT NOT NULL COMMENT '已认证订阅用户ID',
 template_id VARCHAR(191) NOT NULL COMMENT '后台配置模板ID',
 accepted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '用户最近授权可尝试发送0否1是，实际以微信返回为准',
 update_user_id BIGINT UNSIGNED DEFAULT '0' COMMENT '修改用户ID',
 create_user_id BIGINT UNSIGNED DEFAULT '0' COMMENT '新增用户ID',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
 is_deleted TINYINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '删除标记 0：未删除 1：删除',
 UNIQUE KEY storage_subscription(user_id,template_id)
) ENGINE=InnoDB COMMENT='用户主动订阅提醒授权记录';
