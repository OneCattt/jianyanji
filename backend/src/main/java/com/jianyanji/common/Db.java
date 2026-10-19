package com.jianyanji.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL 持久化及事务边界，业务写入按首期单体的顺序事务规则提交。 */
@Component
public class Db {
    /** JDBC 连接访问器，由连接池提供连接。 */
    private final JdbcTemplate jdbc;
    /** REQUIRED 事务模板，嵌套业务保持同一原子提交。 */
    private final TransactionTemplate transactions;

    public Db(
            JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public List<ObjectNode> list(String sql, Object... args) {
        return jdbc.query(sql, args, (rs, index) -> row(rs));
    }

    public ObjectNode one(String sql, Object... args) {
        List<ObjectNode> values = list(sql, args);
        return values.isEmpty() ? null : values.get(0);
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public long insert(String sql, Object... args) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement p =
                            connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
                    return p;
                },
                holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("数据库未返回主键");
        return key.longValue();
    }

    public <T> T tx(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return work.get();
        return transactions.execute(
                status -> {
                    jdbc.queryForObject(
                            "SELECT id FROM business_mutex WHERE id=1 FOR UPDATE", Long.class);
                    return work.get();
                });
    }

    public void tx(Runnable work) {
        tx(
                () -> {
                    work.run();
                    return null;
                });
    }

    public void assertTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("此业务操作必须处于事务内");
    }

    public JsonNode setting(String key) {
        ObjectNode row = one("SELECT value FROM settings WHERE `key`=? AND is_deleted=0", key);
        return row == null ? null : J.parse(J.s(row, "value"));
    }

    public void putSetting(String key, Object value, long actor) {
        update(
                "INSERT INTO settings(`key`,value,create_user_id,update_user_id) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE value=VALUES(value),version=version+1,update_user_id=VALUES(update_user_id),update_time=CURRENT_TIMESTAMP(3)",
                key,
                J.stringify(value),
                actor,
                actor);
    }

    private ObjectNode row(ResultSet rs) throws SQLException {
        ObjectNode value = J.obj();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            Object item = rs.getObject(i);
            if (item instanceof Timestamp) item = ((Timestamp) item).toInstant().toString();
            else if (item instanceof java.time.LocalDateTime)
                item = ((java.time.LocalDateTime) item).toInstant(ZoneOffset.UTC).toString();
            else if (item instanceof java.sql.Date) item = item.toString();
            if (!meta.getColumnLabel(i).startsWith("_active_"))
                value.set(meta.getColumnLabel(i), J.json(item));
        }
        return value;
    }
}
