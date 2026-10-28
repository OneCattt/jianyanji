package com.jianyanji.identity;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/** 隔离 MySQL 验证用户远页搜索、手机号脱敏、有效身份和逻辑删除边界。 */
@EnabledIfEnvironmentVariable(named = "USER_OPTIONS_TEST_MYSQL_URL", matches = ".+")
class UserOptionsMysqlTest {
    @Test
    void searchAcrossPagesAndRestrictEffectiveIdentities() {
        String url = System.getenv("USER_OPTIONS_TEST_MYSQL_URL");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/java_rewards_test_user_options_[A-Za-z0-9_]+(?:\\?.*)?"));
        DriverManagerDataSource source = new DriverManagerDataSource(url, "root", "");
        Flyway.configure().dataSource(source).load().migrate();
        Db db = new Db(new JdbcTemplate(source), new DataSourceTransactionManager(source));
        UserOptionsController controller = new UserOptionsController(db, mock(Access.class));
        String run = "用户搜索" + id();
        String digits = String.valueOf(System.nanoTime());
        String phone = "138" + digits.substring(digits.length() - 8);
        long first = 0;
        for (int i = 0; i < 43; i++) {
            long user = db.insert("INSERT INTO users(openid,nickname,phone,identity) VALUES(?,?,?,'D')", run + i, run + i, phone);
            if (i == 0) first = user;
        }
        Map<String, String> q = new HashMap<>();
        q.put("q", run);
        ObjectNode page = (ObjectNode) controller.options(q);
        assertEquals(43, l(page, "total"));
        assertEquals(20, page.path("items").size());
        assertEquals("138****" + phone.substring(7), page.path("items").get(0).path("phone").asText());
        q.put("page", "3");
        assertEquals(3, ((ObjectNode) controller.options(q)).path("items").size());
        q.clear(); q.put("q", String.valueOf(first));
        assertEquals(first, ((ObjectNode) controller.options(q)).path("items").get(0).path("id").asLong());
        q.clear(); q.put("q", phone);
        assertEquals(43, l((ObjectNode) controller.options(q), "total"));
        long principal = db.insert("INSERT INTO users(openid,nickname,identity) VALUES(?,?,'B')", run + "B", run + "主理人");
        db.insert("INSERT INTO users(openid,nickname,identity,identity_valid_until) VALUES(?,?,'C','2099-01-01T00:00:00.000Z')", run + "C", run + "店主");
        long expired = db.insert("INSERT INTO users(openid,nickname,identity,identity_valid_until) VALUES(?,?,'B','2000-01-01T00:00:00.000Z')", run + "过期", run + "过期");
        q.clear(); q.put("q", run); q.put("identity", "B");
        page = (ObjectNode) controller.options(q);
        assertEquals(1, l(page, "total"));
        assertEquals(principal, page.path("items").get(0).path("id").asLong());
        q.put("identity", "agents");
        assertEquals(2, l((ObjectNode) controller.options(q), "total"));
        q.clear(); q.put("id", String.valueOf(expired));
        assertEquals("D", ((ObjectNode) controller.options(q)).path("items").get(0).path("identity").asText());
        db.update("UPDATE users SET is_deleted=1,update_user_id=0,update_time=CURRENT_TIMESTAMP(3) WHERE id=?", principal);
        q.clear(); q.put("id", String.valueOf(principal));
        assertEquals(0, l((ObjectNode) controller.options(q), "total"));
        q.clear(); q.put("q", run + "%_!");
        assertEquals(0, l((ObjectNode) controller.options(q), "total"));
    }
}
