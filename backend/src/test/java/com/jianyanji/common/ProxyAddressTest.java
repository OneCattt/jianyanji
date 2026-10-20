package com.jianyanji.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.*;

/** 使用真实 Tomcat 验证生产配置的代理来源边界，不连接业务数据库。 */
@SpringBootTest(
        classes = ProxyAddressTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("proxy-address-test")
class ProxyAddressTest {
    /** 向隔离随机端口发起真实 HTTP 请求的客户端。 */
    @Autowired private TestRestTemplate client;
    /** 从 application.yml 加载的服务器配置。 */
    @Autowired private ServerProperties properties;

    @Test
    void localProxyRestoresClientAddressAndHttps() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Real-IP", "198.51.100.10");
        headers.set("X-Forwarded-For", "203.0.113.99");
        headers.set("X-Forwarded-Proto", "https");
        ResponseEntity<Map> result =
                client.exchange(
                        "/test/client-address", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(
                "strict-origin-when-cross-origin", result.getHeaders().getFirst("Referrer-Policy"));
        assertEquals("198.51.100.10", result.getBody().get("address"));
        assertEquals("https", result.getBody().get("scheme"));
    }

    @Test
    void requestWithoutProxyHeaderKeepsSocketAddress() {
        Map result = client.getForObject("/test/client-address", Map.class);
        assertEquals("127.0.0.1", result.get("address"));
        assertEquals("http", result.get("scheme"));
    }

    @Test
    void directExternalConnectionCannotForgeItsAddress() throws Exception {
        RemoteIpValve valve = new RemoteIpValve();
        valve.setInternalProxies(properties.getTomcat().getRemoteip().getInternalProxies());
        valve.setRemoteIpHeader(properties.getTomcat().getRemoteip().getRemoteIpHeader());
        valve.setProtocolHeader(properties.getTomcat().getRemoteip().getProtocolHeader());
        Request request = new Request(new Connector());
        org.apache.coyote.Request coyote = new org.apache.coyote.Request();
        coyote.scheme().setString("http");
        coyote.getMimeHeaders().addValue("X-Real-IP").setString("198.51.100.10");
        request.setCoyoteRequest(coyote);
        request.setRemoteAddr("203.0.113.20");
        Response response = new Response();
        Valve next = mock(Valve.class);
        valve.setNext(next);
        valve.invoke(request, response);
        assertEquals("203.0.113.20", request.getRemoteAddr());
        verify(next).invoke(request, response);
    }

    /** 只启动 Web 自动配置的隔离应用，不扫描业务控制器和数据库组件。 */
    @Configuration
    @Profile("proxy-address-test")
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({AddressController.class, ApiFilter.class})
    static class TestApplication {}

    /** 仅在测试进程存在的来源观测端点，不进入生产 JAR。 */
    @RestController
    @Profile("proxy-address-test")
    static class AddressController {
        @GetMapping("/test/client-address")
        Map<String, String> address(HttpServletRequest request) {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("address", request.getRemoteAddr());
            result.put("scheme", request.getScheme());
            return result;
        }
    }
}
