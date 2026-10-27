package com.jianyanji.catalog;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/** 本机隔离 MySQL 验证分页、名称搜索、停用回显与原交易来源，测试数据只追加不物理删除。 */
@EnabledIfEnvironmentVariable(named = "PRODUCT_OPTIONS_TEST_MYSQL_URL", matches = ".+")
class ProductOptionsMysqlTest {
    @Test
    void searchAcrossPagesResolveInactiveAndKeepOriginalOwnershipAndSnapshots() {
        String url = System.getenv("PRODUCT_OPTIONS_TEST_MYSQL_URL");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/java_rewards_test_options_[A-Za-z0-9_]+(?:\\?.*)?"));
        DriverManagerDataSource source = new DriverManagerDataSource(url, "root", "");
        Flyway.configure().dataSource(source).load().migrate();
        Db db = new Db(new JdbcTemplate(source), new DataSourceTransactionManager(source));
        ProductOptionsService service = new ProductOptionsService(db);
        String run = "搜索测试" + id();
        long category = db.insert("INSERT INTO categories(name,active,sort) VALUES(?,1,0)", run);
        long oldSku = 0, oldProduct = 0;
        for (int i = 0; i < 43; i++) {
            long p = db.insert("INSERT INTO products(category_id,name,subtitle,description,image,detail_image,active,featured) VALUES(?,?,'说明','说明','/a.png','/a.png',1,0)", category, run + "商品" + i);
            long sku = db.insert("INSERT INTO skus(product_id,name,unit,retail_cents,stock,active,storage_enabled,storage_exclusion) VALUES(?,?,'瓶',1000,20,1,1,'none')", p, "规格" + i);
            if (i == 0) { oldSku = sku; oldProduct = p; }
        }
        Map<String, String> q = new HashMap<>(); q.put("q", run); q.put("kind", "sku");
        ObjectNode first = service.page(q);
        assertEquals(43, l(first, "total")); assertEquals(20, first.path("items").size());
        q.put("page", "3"); assertEquals(3, service.page(q).path("items").size());
        q.put("page", "1"); q.put("q", run + "商品0");
        assertEquals(oldSku, service.page(q).path("items").get(0).path("id").asLong());
        q.put("q", "规格42"); assertTrue(l(service.page(q), "total") >= 1);
        q.put("q", "绝不存在的商品%_!"); assertEquals(0, l(service.page(q), "total"));
        db.update("UPDATE skus SET active=0 WHERE id=?", oldSku);
        q.put("q", run + "商品0"); assertEquals(0, l(service.page(q), "total"));
        q.remove("q"); q.put("ids", String.valueOf(oldSku));
        ObjectNode inactive = service.page(q);
        assertEquals(1, l(inactive, "total")); assertFalse(inactive.path("items").get(0).path("active").asBoolean());
        assertTrue(inactive.path("items").get(0).path("label").asText().contains("已停用"));
        q.remove("ids"); q.put("includeInactive", "true"); q.put("q", run + "商品0"); assertEquals(1, l(service.page(q), "total"));
        q.put("kind", "product"); q.remove("includeInactive"); assertEquals(oldProduct, service.page(q).path("items").get(0).path("id").asLong());
        q.put("ids", "1 OR 1=1"); assertThrows(BusinessException.class, () -> service.page(q));
        q.put("ids", "9007199254740992"); assertThrows(BusinessException.class, () -> service.page(q));
        q.clear(); q.put("kind", "lot"); assertThrows(BusinessException.class, () -> service.page(q));
        long user = db.insert("INSERT INTO users(openid,nickname) VALUES(?,?)", run, "搜索客户");
        long other = db.insert("INSERT INTO users(openid,nickname) VALUES(?,?)", run + "other", "另一客户");
        String snapshot = stringify(obj("lines", arr().add(obj("skuId", oldSku, "name", "成交原商品", "skuName", "原规格", "unit", "盒", "isGift", true))));
        long order = db.insert("INSERT INTO orders(number,user_id,status,payment_state,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key) VALUES(?,?,'paid','paid',1000,1000,0,?,'2099-01-01T00:00:00.000Z',?)", run + "订单", user, snapshot, run);
        long line = db.insert("INSERT INTO order_lines(order_id,sku_id,quantity,unit_cents,is_gift) VALUES(?,?,5,0,1)", order, oldSku);
        q.put("kind", "orderLine"); q.put("q", "成交原商品");
        ObjectNode imported = service.page(q);
        assertEquals(line, imported.path("items").get(0).path("id").asLong());
        assertTrue(imported.path("items").get(0).path("label").asText().contains("成交原商品 · 原规格（盒） · 赠品"));
        assertEquals(5, imported.path("items").get(0).path("quantity").asInt());
        q.put("userId", String.valueOf(other)); assertEquals(0, l(service.page(q), "total"));
        long lot = db.insert("INSERT INTO storage_lots(user_id,order_id,line_id,sku_id,total_qty,remaining_qty,held_qty,refund_held_qty,shipped_qty,unit_cents,starts_on,expires_on) VALUES(?,?,?,?,5,4,1,1,1,0,'2026-01-01','2027-01-01')", user, order, line, oldSku);
        q.put("userId", String.valueOf(user)); assertEquals(0, l(service.page(q), "total"));
        q.put("kind", "lot");
        ObjectNode lots = service.page(q);
        assertEquals(lot, lots.path("items").get(0).path("id").asLong());
        assertEquals(2, lots.path("items").get(0).path("quantity").asInt());
        assertFalse(lots.path("items").get(0).has("snapshot"));
        q.put("userId", String.valueOf(other)); assertEquals(0, l(service.page(q), "total"));
        db.update("UPDATE products SET is_deleted=1 WHERE id=?", oldProduct);
        q.clear(); q.put("kind", "sku"); q.put("ids", String.valueOf(oldSku)); assertEquals(0, l(service.page(q), "total"));
    }
}
