package com.jianyanji;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 清欣简妍肌单体服务入口，统一提供小程序接口和品牌管理后台。 */
@SpringBootApplication
@EnableScheduling
public class Application {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(Application.class);
        if ("true".equals(System.getenv("MIGRATE_ONLY"))
                || !System.getenv().getOrDefault("LEGACY_IMPORT_FILE", "").isEmpty())
            application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        org.springframework.context.ConfigurableApplicationContext context = application.run(args);
        if (context.getEnvironment().getProperty("jianyanji.bootstrap-admin", Boolean.class, false)
                || context.getEnvironment().getProperty("jianyanji.seed-demo", Boolean.class, false)
                || context.getEnvironment().getProperty("MIGRATE_ONLY", Boolean.class, false)
                || !context.getEnvironment().getProperty("LEGACY_IMPORT_FILE", "").isEmpty())
            context.close();
    }
}
