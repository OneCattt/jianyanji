-- MySQL 8，以数据库管理员执行；首次执行前更换账号密码。
CREATE DATABASE IF NOT EXISTS jianyanji CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER IF NOT EXISTS 'jianyanji'@'127.0.0.1' IDENTIFIED BY '请替换成独立强密码';
-- Flyway 建表及结构升级；业务仅逻辑删除，不授予 DELETE、DROP 权限。
GRANT SELECT, INSERT, UPDATE, CREATE, ALTER, INDEX, REFERENCES ON jianyanji.* TO 'jianyanji'@'127.0.0.1';
