-- 每个来源、类型、收益人分别登记；先建立候选唯一索引再移除旧限制。既有奖励及审计字段不改写。
ALTER TABLE rewards ADD UNIQUE INDEX reward_source_recipient_active(order_id,kind,user_id,_active_reward_source_active);
ALTER TABLE rewards DROP INDEX reward_source_active;
