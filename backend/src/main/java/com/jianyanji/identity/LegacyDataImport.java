package com.jianyanji.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jianyanji.common.Db;
import com.jianyanji.common.Input;
import com.jianyanji.common.J;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 将只读导出的旧 SQLite 数据原样迁入空 MySQL 业务库，失败时整批回滚。 */
@Component
public class LegacyDataImport implements ApplicationRunner {
    /** 迁移计数日志，不输出账号摘要和业务内容。 */
    private static final Logger LOG = LoggerFactory.getLogger(LegacyDataImport.class);
    /** 按外键依赖排列的业务表白名单，不包含 Flyway 历史和事务锁。 */
    private static final List<String> TABLES =
            Arrays.asList(
                    "users",
                    "staff",
                    "sessions",
                    "audit_events",
                    "settings",
                    "categories",
                    "products",
                    "skus",
                    "addresses",
                    "cart_items",
                    "quotes",
                    "orders",
                    "order_lines",
                    "shipments",
                    "refunds",
                    "returned_inventory",
                    "payment_attempts",
                    "payments",
                    "payment_events",
                    "shipment_corrections",
                    "reward_settings",
                    "rewards",
                    "reward_reversals",
                    "reward_settlements",
                    "reward_offsets",
                    "reward_recoveries",
                    "company_receipts",
                    "onboardings",
                    "onboarding_identity_events",
                    "wallet_lots",
                    "wallet_spend_allocations",
                    "wallet_ledger",
                    "cash_refunds");
    /** 使用当前应用配置的目标库及原子事务。 */
    private final Db db;
    /** 导入文件路径和初始化模式开关。 */
    private final Environment env;

    public LegacyDataImport(Db db, Environment env) {
        this.db = db;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String path = env.getProperty("LEGACY_IMPORT_FILE", "");
        if (path.isEmpty()) return;
        Input.require(
                !env.getProperty("jianyanji.seed-demo", Boolean.class, false)
                        && !env.getProperty("jianyanji.bootstrap-admin", Boolean.class, false),
                "数据导入不能与账号初始化同时执行");
        JsonNode export =
                J.parse(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
        Input.require(
                J.l(export, "version") == 1 && export.path("tables").isObject(), "旧数据导出格式不正确");
        JsonNode tables = export.get("tables");
        Set<String> actual = new HashSet<>();
        tables.fieldNames().forEachRemaining(actual::add);
        Input.require(actual.equals(new HashSet<>(TABLES)), "导出业务表与当前版本不一致，拒绝不完整迁移");
        db.tx(
                () -> {
                    for (String table : TABLES) {
                        Input.require(
                                J.l(db.one("SELECT count(*) AS n FROM `" + table + "`"), "n") == 0,
                                "导入仅允许空业务库，目标表已有数据：" + table);
                    }
                    long total = 0;
                    for (String table : TABLES) {
                        Set<String> columns = new HashSet<>();
                        db.list("SHOW COLUMNS FROM `" + table + "`")
                                .forEach(c -> columns.add(J.s(c, "Field")));
                        Input.require(tables.get(table).isArray(), "导出数据必须为记录数组：" + table);
                        for (JsonNode row : tables.get(table)) {
                            Input.require(row.isObject(), "导出记录必须为对象：" + table);
                            List<String> names = new ArrayList<>();
                            List<Object> values = new ArrayList<>();
                            row.fields()
                                    .forEachRemaining(
                                            field -> {
                                                String name = field.getKey();
                                                Input.require(
                                                        columns.contains(name)
                                                                && name.matches("[a-z_]+"),
                                                        "导出字段不受支持：" + name);
                                                names.add("`" + name + "`");
                                                JsonNode value = field.getValue();
                                                if (value.isNull()) values.add(null);
                                                else if ("create_time".equals(name)
                                                        || "update_time".equals(name))
                                                    values.add(
                                                            Timestamp.from(
                                                                    Instant.parse(value.asText())));
                                                else if (value.isIntegralNumber())
                                                    values.add(value.longValue());
                                                else if (value.isTextual())
                                                    values.add(value.asText());
                                                else
                                                    throw new IllegalArgumentException(
                                                            "导出字段类型不受支持：" + name);
                                            });
                            db.update(
                                    "INSERT INTO `"
                                            + table
                                            + "`("
                                            + String.join(",", names)
                                            + ") VALUES("
                                            + String.join(
                                                    ",", Collections.nCopies(names.size(), "?"))
                                            + ")",
                                    values.toArray());
                            total++;
                        }
                    }
                    LOG.info("旧数据迁移完成，业务表数={}，记录数={}，账号及原权限保持导出内容", TABLES.size(), total);
                });
    }
}
