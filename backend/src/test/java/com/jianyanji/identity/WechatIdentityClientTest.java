package com.jianyanji.identity;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.jianyanji.common.*;
import java.net.SocketTimeoutException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** 微信真实HTTP调用边界的成功、业务失败、异常及日志脱敏回归。 */
class WechatIdentityClientTest {
    @Test
    void sessionExchangeLogsRequestResponseAndTraceWithoutCredentials() {
        List<Object[]> logs = new ArrayList<>();
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session?appid=test-app&secret=test-secret&js_code=one-code&grant_type=authorization_code"))
                .andRespond(withSuccess("{\"openid\":\"original-openid\",\"session_key\":\"private-session\"}", MediaType.APPLICATION_JSON));
        assertEquals("original-openid", client(http, logs).openid("one-code"));
        assertEquals(1, logs.size());
        Object[] log = logs.get(0);
        assertEquals("trace-phone", log[4]);
        assertEquals("success", log[7]);
        assertTrue(log[5].toString().contains("js_code"));
        assertTrue(log[6].toString().contains("session_key"));
        String saved = Arrays.toString(log);
        for (String secret : Arrays.asList("test-secret", "one-code", "original-openid", "private-session"))
            assertFalse(saved.contains(secret), secret + "不应落库");
        server.verify();
    }

    @Test
    void phoneUsesServerCodeExchangeAndCachesStableToken() {
        List<Object[]> logs = new ArrayList<>();
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"secret-token\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));
        for (String code : Arrays.asList("phone-one", "phone-two"))
            server.expect(requestTo("https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=secret-token"))
                    .andExpect(content().json("{\"code\":\"" + code + "\"}"))
                    .andRespond(withSuccess("{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13800138000\",\"phoneNumber\":\"13800138000\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\"test-app\"}}}", MediaType.APPLICATION_JSON));
        WechatIdentityClient client = client(http, logs);
        assertEquals("13800138000", client.phone("phone-one", "user:7"));
        assertEquals("13800138000", client.phone("phone-two", "user:7"));
        assertEquals(3, logs.size());
        assertTrue(logs.stream().allMatch(row -> "success".equals(row[7])));
        String saved = logs.stream().map(Arrays::toString).reduce("", String::concat);
        for (String secret : Arrays.asList("secret-token", "phone-one", "phone-two", "13800138000", "test-secret"))
            assertFalse(saved.contains(secret));
        server.verify();
    }

    @Test
    void businessErrorHttpErrorMalformedResponseAndTimeoutAlwaysSaveLogsWithoutRetry() {
        for (String kind : Arrays.asList("business", "http", "malformed", "timeout")) {
            List<Object[]> logs = new ArrayList<>();
            RestTemplate http = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.createServer(http);
            org.springframework.test.web.client.ResponseActions expectation = server.expect(requestTo(org.hamcrest.Matchers.containsString("jscode2session")));
            if (kind.equals("business")) expectation.andRespond(withSuccess("{\"errcode\":40029,\"errmsg\":\"invalid code\"}", MediaType.APPLICATION_JSON));
            else if (kind.equals("http")) expectation.andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("{\"errcode\":-1}"));
            else if (kind.equals("malformed")) expectation.andRespond(withSuccess("invalid-json", MediaType.APPLICATION_JSON));
            else expectation.andRespond(request -> { throw new SocketTimeoutException("包含test-secret的原始异常"); });
            assertThrows(BusinessException.class, () -> client(http, logs).openid("sensitive-code"));
            assertEquals(1, logs.size());
            assertEquals(kind.equals("business") ? "business_failure" : kind.equals("timeout") ? "timeout" : "exception", logs.get(0)[7]);
            if (kind.equals("business")) assertTrue(logs.get(0)[6].toString().contains("40029"));
            if (kind.equals("timeout")) assertEquals("SocketTimeoutException", logs.get(0)[10]);
            assertFalse(Arrays.toString(logs.get(0)).contains("test-secret"));
            server.verify();
        }
    }

    @Test
    void phoneRejectsWrongApplicationWatermark() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo(org.hamcrest.Matchers.containsString("stable_token")))
                .andRespond(withSuccess("{\"access_token\":\"token\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getuserphonenumber")))
                .andRespond(withSuccess("{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13800138000\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\"other-app\"}}}", MediaType.APPLICATION_JSON));
        assertThrows(BusinessException.class, () -> client(http, new ArrayList<>()).phone("code", "user:7"));
        server.verify();
    }

    private WechatIdentityClient client(RestTemplate http, List<Object[]> logs) {
        Db db = mock(Db.class);
        doAnswer(call -> { logs.add(call.getArguments()); return 1L; })
                .when(db).insert(startsWith("INSERT INTO wechat_exchange_logs"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        Access access = mock(Access.class);
        when(access.requestId()).thenReturn("trace-phone");
        return new WechatIdentityClient(db, access, "test-app", "test-secret", http);
    }
}
