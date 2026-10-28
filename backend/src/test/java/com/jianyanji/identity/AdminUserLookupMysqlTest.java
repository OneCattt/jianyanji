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

/** 本机隔离MySQL验证地址分页、用户归属、逻辑删除和最小昵称查询，不清理业务表。 */
@EnabledIfEnvironmentVariable(named="USER_OPTIONS_TEST_MYSQL_URL",matches=".+")
class AdminUserLookupMysqlTest {
    @Test
    void paginatedAddressesAndNamesStayWithinTheirOwners() {
        String url = System.getenv("USER_OPTIONS_TEST_MYSQL_URL");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/java_rewards_test_user_options_[A-Za-z0-9_]+(?:\\?.*)?"));
        DriverManagerDataSource source = new DriverManagerDataSource(url,"root","");
        Flyway.configure().dataSource(source).load().migrate();
        Db db = new Db(new JdbcTemplate(source),new DataSourceTransactionManager(source));
        String stamp = "可读验收" + id();
        long first=db.insert("INSERT INTO users(openid,nickname) VALUES(?,?)",stamp,"本地客户");
        long other=db.insert("INSERT INTO users(openid,nickname) VALUES(?,?)",stamp+"other","其他客户");
        long deleted=db.insert("INSERT INTO users(openid,nickname,is_deleted) VALUES(?,?,1)",stamp+"deleted","已删除客户");
        for(int i=0;i<23;i++) db.insert("INSERT INTO addresses(user_id,recipient,phone,province,city,district,detail,is_default) VALUES(?,?,'13800000000','广东省','广州市','测试区',?,?)",first,"收货人"+i,"本地测试地址"+i,i==0?1:0);
        db.insert("INSERT INTO addresses(user_id,recipient,phone,province,city,district,detail) VALUES(?,'别人的地址','13900000000','广东省','广州市','测试区','不可跨用户')",other);
        db.insert("INSERT INTO addresses(user_id,recipient,phone,province,city,district,detail,is_deleted) VALUES(?,'已删除地址','13900000000','广东省','广州市','测试区','不可选择',1)",first);
        AdminUserLookupController controller = new AdminUserLookupController(db,mock(Access.class));
        ObjectNode result=(ObjectNode)controller.addresses(first,Collections.emptyMap());
        assertEquals(23,l(result,"total")); assertEquals(20,result.path("items").size());
        assertEquals("收货人0",s(result.path("items").get(0),"recipient"));
        assertFalse(result.path("items").get(0).has("user_id"));
        result=(ObjectNode)controller.addresses(first,Collections.singletonMap("page","2"));
        assertEquals(3,result.path("items").size());
        result=(ObjectNode)controller.addresses(first,Collections.singletonMap("q","别人的地址"));
        assertEquals(0,l(result,"total"));
        result=(ObjectNode)controller.addresses(first,Collections.singletonMap("q","已删除地址"));
        assertEquals(0,l(result,"total"));
        Map<Long,String> names = new UserNames(db).find(Arrays.asList(first,other,deleted));
        assertEquals(2,names.size()); assertEquals("本地客户",names.get(first)); assertFalse(names.containsKey(deleted));
    }
}
