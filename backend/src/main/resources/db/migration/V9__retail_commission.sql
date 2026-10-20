-- 保留原金额和来源，新普通零售订单使用独立 retail 类型；原约束由V1的匿名CHECK生成。
SET @reward_kind_check = (SELECT tc.CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='rewards' AND tc.CONSTRAINT_TYPE='CHECK' AND cc.CHECK_CLAUSE LIKE '%kind%' LIMIT 1);
SET @reward_drop_check = CONCAT('ALTER TABLE rewards DROP CHECK `', @reward_kind_check, '`');
PREPARE reward_check_statement FROM @reward_drop_check;
EXECUTE reward_check_statement;
DEALLOCATE PREPARE reward_check_statement;
ALTER TABLE rewards ADD CONSTRAINT chk_reward_kind CHECK (kind IN ('onboarding','margin','retail'));
