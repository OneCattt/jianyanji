-- 分别保留资格来源；旧数据仅迁移真实已生效的记录，不补造收款或奖励。
CREATE TABLE qualifications (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
 user_id BIGINT NOT NULL COMMENT '资格所属用户ID',
 identity VARCHAR(1) NOT NULL COMMENT '资格身份 B：主理人 C：店主',
 source_kind VARCHAR(24) NOT NULL COMMENT '来源 package：真实产品包 existing：首批存量核验',
 onboarding_id BIGINT DEFAULT NULL COMMENT '产品包申请ID，存量核验可空',
 inviter_id BIGINT DEFAULT NULL COMMENT '该次资格直接邀请人ID，可空',
 principal_id BIGINT DEFAULT NULL COMMENT '该次资格归属主理人ID，可空',
 valid_until VARCHAR(191) DEFAULT NULL COMMENT '资格有效期，ISO UTC，可空表示长期',
 active TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '资格有效标记 0：撤销 1：有效',
 evidence VARCHAR(2000) NOT NULL COMMENT '真实核验依据',
 reason VARCHAR(2000) NOT NULL COMMENT '办理原因',
 update_user_id BIGINT UNSIGNED DEFAULT '0' COMMENT '修改用户ID',
 create_user_id BIGINT UNSIGNED DEFAULT '0' COMMENT '新增用户ID',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
 is_deleted TINYINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '删除标记 0：未删除 1：删除',
 _active_onboarding TINYINT GENERATED ALWAYS AS (CASE WHEN is_deleted=0 THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY qualification_source(onboarding_id,_active_onboarding),
 KEY qualification_user(user_id,active,is_deleted),
 CHECK(identity IN ('B','C')), CHECK(source_kind IN ('package','existing'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='各次资格及真实来源';
INSERT INTO qualifications(user_id,identity,source_kind,onboarding_id,inviter_id,principal_id,evidence,reason,create_user_id,update_user_id)
 SELECT user_id,target_identity,'package',id,inviter_id,principal_id,'原已通过入驻记录','V2保留原资格来源',create_user_id,update_user_id
 FROM onboardings WHERE status='approved' AND is_deleted=0 AND order_id IS NOT NULL;
CREATE TABLE team_handovers (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
 user_id BIGINT NOT NULL COMMENT '被撤销主理人用户ID',
 principal_id BIGINT NOT NULL COMMENT '指定接管有效主理人用户ID',
 state VARCHAR(16) NOT NULL DEFAULT 'prepared' COMMENT 'prepared：待资格撤销 applied：已交接',
 reason VARCHAR(2000) NOT NULL COMMENT '交接原因与依据',
 update_user_id BIGINT UNSIGNED DEFAULT '0' COMMENT '修改用户ID',
 create_user_id BIGINT UNSIGNED DEFAULT '0' COMMENT '新增用户ID',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
 is_deleted TINYINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '删除标记 0：未删除 1：删除',
 KEY handover_user(user_id,state,is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='品牌指定的主理人团队交接依据';
