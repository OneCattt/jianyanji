ALTER TABLE users ADD COLUMN share_code VARCHAR(64) DEFAULT NULL COMMENT '真实随机分享码，可空表示尚未生成', ADD COLUMN relation_version BIGINT NOT NULL DEFAULT 1 COMMENT '当前关系版本';
CREATE UNIQUE INDEX user_share_code ON users(share_code);
ALTER TABLE onboardings ADD COLUMN relation_confirmed TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '申请关系是否已由本人确认', ADD COLUMN relation_version BIGINT NOT NULL DEFAULT 1 COMMENT '申请时本人关系版本';
