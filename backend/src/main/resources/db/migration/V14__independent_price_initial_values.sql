-- 旧程序实际按零售价7折/3.5折运行，历史store/principal列可能是已停用的旧手工值。
-- 仅在首次升级时将旧程序的实际有效价格固化为新三档价初值；之后完全由后台独立维护。
-- 不更新任何订单、退款、积分或奖励历史快照，也不自动处理老订单寄存。
UPDATE skus
SET store_cents=CASE WHEN retail_cents>0 THEN NULLIF(FLOOR((retail_cents*70+50)/100),0) ELSE NULL END,
    principal_cents=CASE WHEN retail_cents>0 THEN NULLIF(FLOOR((retail_cents*35+50)/100),0) ELSE NULL END,
    update_user_id=0,update_time=CURRENT_TIMESTAMP(3)
WHERE product_type='regular' AND is_deleted=0;
