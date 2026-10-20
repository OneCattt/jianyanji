-- 仅依据原已撤销入驻与当前资格有效期补齐来源，既有资金和订单不重算。
INSERT INTO qualifications(user_id,identity,source_kind,onboarding_id,inviter_id,principal_id,active,evidence,reason,create_user_id,update_user_id)
 SELECT a.user_id,a.target_identity,'package',a.id,a.inviter_id,a.principal_id,0,'原已撤销入驻记录','V2保留已撤销资格历史',a.create_user_id,a.update_user_id
 FROM onboardings a
 WHERE a.status='revoked' AND a.order_id IS NOT NULL AND a.is_deleted=0
 AND NOT EXISTS(SELECT 1 FROM qualifications q WHERE q.onboarding_id=a.id AND q.is_deleted=0);
UPDATE qualifications q JOIN users u ON u.id=q.user_id AND u.onboarding_id=q.onboarding_id AND u.is_deleted=0
 SET q.valid_until=u.identity_valid_until,q.update_user_id=0,q.update_time=CURRENT_TIMESTAMP(3)
 WHERE q.is_deleted=0 AND q.valid_until IS NULL AND u.identity_valid_until IS NOT NULL;
