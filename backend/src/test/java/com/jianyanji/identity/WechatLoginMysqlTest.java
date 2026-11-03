package com.jianyanji.identity;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.*;

/** 本机隔离MySQL验证旧账号复用、资料更新隔离、失败回滚及微信报文真实落库。 */
@EnabledIfEnvironmentVariable(named = "JYJ_LOGIN_TEST_URL", matches = ".+")
class WechatLoginMysqlTest {
    @Test
    void preservesAccountAndCommitsLogsIndependentlyOfBusinessRollback() {
        String url = System.getenv("JYJ_LOGIN_TEST_URL");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/java_rewards_test_quick_login_[A-Za-z0-9_]+(?:\\?.*)?"));
        DriverManagerDataSource source = new DriverManagerDataSource(url, "root", "");
        Flyway.configure().dataSource(source).load().migrate();
        Db db = new Db(new JdbcTemplate(source),new DataSourceTransactionManager(source));
        String run = id(), openid = "login-test-" + run;
        long uid = db.insert("INSERT INTO users(openid,nickname,phone,identity) VALUES(?,'核款客户','13900139000','B')",openid);
        long usersBefore = count(db,"users"), sessionsBefore = count(db,"sessions");
        Access access = new Access(db);
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        session(server, openid);
        server.expect(requestTo(org.hamcrest.Matchers.containsString("stable_token")))
                .andRespond(withSuccess("{\"access_token\":\"test-token\",\"expires_in\":7200}",MediaType.APPLICATION_JSON));
        phone(server,"13800138000");
        phone(server,"13700137000");
        session(server,openid);
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getuserphonenumber")))
                .andRespond(withSuccess("{\"errcode\":40029,\"errmsg\":\"invalid code\"}",MediaType.APPLICATION_JSON));
        session(server,openid);
        phone(server,"13600136000");
        WechatIdentityClient client = new WechatIdentityClient(db,access,"test-app","test-secret",http);
        IdentityController controller = controller(db,access,client);
        try {
            request(run + "-success",null);
            ObjectNode logged = (ObjectNode) controller.wechat(obj("code","login-one","phoneCode","phone-one"));
            assertEquals(uid,l(logged.path("user"),"id"));
            assertEquals("核款客户",s(logged.path("user"),"nickname"));
            assertEquals("B",s(logged.path("user"),"identity"));
            assertEquals("13800138000",s(logged.path("user"),"phone"));
            assertTrue(b(logged.path("user"),"phoneVerified"));
            assertEquals(usersBefore,count(db,"users"));
            assertEquals(sessionsBefore + 1,count(db,"sessions"));
            assertEquals(3,l(db.one("SELECT count(*) AS n FROM wechat_exchange_logs WHERE request_id=? AND is_deleted=0",run+"-success"),"n"));

            ObjectNode staleUser = db.one("SELECT * FROM users WHERE id=? AND is_deleted=0",uid);
            request(run + "-bind",staleUser);
            controller.bindPhone(obj("code","phone-two"));
            // 模拟旧昵称表单与手机号绑定前读取到的身份共存；仅更新昵称不能覆盖刚绑定的新手机。
            controller.profile(null,obj("nickname","更新昵称"));
            ObjectNode current = db.one("SELECT * FROM users WHERE id=? AND is_deleted=0",uid);
            assertEquals("13700137000",s(current,"phone"));
            assertEquals("更新昵称",s(current,"nickname"));
            assertFalse(s(current,"phone_verified_at").isEmpty());

            request(run + "-failure",null);
            assertThrows(BusinessException.class,()->controller.wechat(obj("code","login-two","phoneCode","bad-code")));
            assertEquals(sessionsBefore+1,count(db,"sessions"));
            assertEquals("13700137000",s(db.one("SELECT * FROM users WHERE id=? AND is_deleted=0",uid),"phone"));
            assertEquals(1,l(db.one("SELECT count(*) AS n FROM wechat_exchange_logs WHERE request_id=? AND result='business_failure' AND is_deleted=0",run+"-failure"),"n"));

            request(run + "-rollback",null);
            Access failAudit = spy(new Access(db));
            doThrow(new IllegalStateException("模拟事务末尾审计失败")).when(failAudit)
                    .audit(eq("user.login"),eq("user"),any(),anyString());
            assertThrows(IllegalStateException.class,()->controller(db,failAudit,client)
                    .wechat(obj("code","login-three","phoneCode","phone-three")));
            assertEquals(sessionsBefore+1,count(db,"sessions"));
            assertEquals("13700137000",s(db.one("SELECT * FROM users WHERE id=? AND is_deleted=0",uid),"phone"));
            assertEquals(2,l(db.one("SELECT count(*) AS n FROM wechat_exchange_logs WHERE request_id=? AND is_deleted=0",run+"-rollback"),"n"));
            for (ObjectNode row : db.list("SELECT * FROM wechat_exchange_logs WHERE request_id LIKE ? AND is_deleted=0",run+"%")) {
                String saved = stringify(row);
                for (String secret : new String[]{"test-secret","test-token","login-one","phone-one","13800138000","13700137000","13600136000",openid})
                    assertFalse(saved.contains(secret));
            }
            server.verify();
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }

    private long count(Db db,String table) {
        return l(db.one("SELECT count(*) AS n FROM " + table + " WHERE is_deleted=0"),"n");
    }

    private void request(String trace,ObjectNode user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("requestId",trace);
        if(user!=null) request.setAttribute("user",user);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private IdentityController controller(Db db,Access access,WechatIdentityClient client) {
        return new IdentityController(db,access,false,false,client,new MockEnvironment());
    }

    private void session(MockRestServiceServer server,String openid) {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("jscode2session")))
                .andRespond(withSuccess(stringify(obj("openid",openid,"session_key","private-session")),MediaType.APPLICATION_JSON));
    }

    private void phone(MockRestServiceServer server,String phone) {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getuserphonenumber")))
                .andRespond(withSuccess(stringify(obj("errcode",0,"phone_info",obj("purePhoneNumber",phone,"phoneNumber",phone,"countryCode","86","watermark",obj("appid","test-app")))),MediaType.APPLICATION_JSON));
    }
}
