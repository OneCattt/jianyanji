-- 普通货款与产品包、积分充值分别登记，复用原公司流水唯一约束，避免一笔钱跨用途重复确认。
SET @receipt_purpose_check = (
    SELECT tc.CONSTRAINT_NAME
    FROM information_schema.TABLE_CONSTRAINTS tc
    JOIN information_schema.CHECK_CONSTRAINTS cc
      ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
    WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='company_receipts'
      AND tc.CONSTRAINT_TYPE='CHECK' AND cc.CHECK_CLAUSE LIKE '%purpose%'
    LIMIT 1
);
SET @receipt_purpose_ddl = CONCAT('ALTER TABLE company_receipts DROP CHECK `', @receipt_purpose_check, '`');
PREPARE receipt_purpose_stmt FROM @receipt_purpose_ddl;
EXECUTE receipt_purpose_stmt;
DEALLOCATE PREPARE receipt_purpose_stmt;

ALTER TABLE company_receipts
    MODIFY COLUMN purpose VARCHAR(191) NOT NULL COMMENT '收款用途 package：产品包 points：订货积分 order：普通订单货款',
    ADD COLUMN order_id BIGINT NULL COMMENT '普通货款对应订单编号，产品包及积分充值为空',
    ADD COLUMN _active_receipt_order_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN is_deleted=0 AND purpose='order' THEN order_id ELSE NULL END) STORED COMMENT '未删除普通货款的唯一订单编号',
    ADD UNIQUE INDEX receipts_order_active (_active_receipt_order_id),
    ADD CONSTRAINT fk_receipt_order FOREIGN KEY (order_id) REFERENCES orders(id),
    ADD CONSTRAINT chk_receipt_purpose CHECK (purpose IN ('package','points','order')),
    ADD CONSTRAINT chk_receipt_order CHECK (
        (purpose='order' AND order_id IS NOT NULL AND onboarding_id IS NULL)
        OR (purpose IN ('package','points') AND order_id IS NULL)
    );
