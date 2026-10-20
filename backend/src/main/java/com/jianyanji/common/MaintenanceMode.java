package com.jianyanji.common;

/** 一次性迁移、初始化与导入进程不启动交易定时任务，避免退出时访问已关闭连接池。 */
public final class MaintenanceMode {
    private MaintenanceMode() {}

    /** 与服务入口的一次性环境模式保持一致。 */
    public static boolean enabled() {
        return "true".equals(System.getenv("MIGRATE_ONLY"))
                || "true".equals(System.getenv("BOOTSTRAP_ADMIN"))
                || "true".equals(System.getenv("SEED_DEMO"))
                || !System.getenv().getOrDefault("LEGACY_IMPORT_FILE", "").isEmpty();
    }
}
