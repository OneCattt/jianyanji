package com.jianyanji.identity;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.net.SocketTimeoutException;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.util.UriComponentsBuilder;

/** 微信身份及手机号服务边界；一次性凭证不重试，所有调用均保存脱敏请求响应。 */
@Component
public class WechatIdentityClient {
    /** 请求响应日志持久化。 */
    private final Db db;
    /** 当前请求及业务身份。 */
    private final Access access;
    /** 已配置的小程序标识。 */
    private final String appId;
    /** 小程序密钥，仅用于向微信请求，不进入日志。 */
    private final String appSecret;
    /** 设置连接与读取超时的微信客户端。 */
    private final RestTemplate http;
    /** 仅在服务端内存缓存的访问令牌。 */
    private String token = "";
    /** 缓存到期时间，Unix毫秒；提前120秒失效。 */
    private volatile long tokenUntil;

    @Autowired
    public WechatIdentityClient(
            Db db, Access access,
            @Value("${jianyanji.wechat-app-id}") String appId,
            @Value("${jianyanji.wechat-app-secret}") String appSecret) {
        this(db, access, appId, appSecret, client());
    }

    WechatIdentityClient(Db db, Access access, String appId, String appSecret, RestTemplate http) {
        this.db = db;
        this.access = access;
        this.appId = appId;
        this.appSecret = appSecret;
        this.http = http;
    }

    private static RestTemplate client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    public boolean configured() {
        return !appId.isEmpty() && !appSecret.isEmpty();
    }

    public String openid(String code) {
        requireConfigured();
        String target = "https://api.weixin.qq.com/sns/jscode2session";
        String url = UriComponentsBuilder.fromHttpUrl(target)
                .queryParam("appid", appId).queryParam("secret", appSecret)
                .queryParam("js_code", code).queryParam("grant_type", "authorization_code")
                .build().encode().toUriString();
        JsonNode value = exchange("jscode2session", target, url, HttpMethod.GET, null,
                obj("appid", appId, "secret", "<REDACTED>", "js_code", "<REDACTED>",
                        "grant_type", "authorization_code"),
                "login:" + Access.hash(code).substring(0, 16), "openid");
        return s(value, "openid");
    }

    public String phone(String code, String businessKey) {
        requireConfigured();
        String target = "https://api.weixin.qq.com/wxa/business/getuserphonenumber";
        String url = UriComponentsBuilder.fromHttpUrl(target).queryParam("access_token", token())
                .build().encode().toUriString();
        JsonNode result = exchange("getuserphonenumber", target, url, HttpMethod.POST,
                obj("code", code), obj("code", "<REDACTED>"), businessKey, "phone_info");
        JsonNode info = result.path("phone_info");
        String phone = s(info, "purePhoneNumber");
        if (!"86".equals(s(info, "countryCode")) || !phone.matches("1[3-9]\\d{9}")
                || !appId.equals(s(info.path("watermark"), "appid"))) {
            access.audit("user.phone.denied", "user", businessKey,
                    "微信返回的手机号地区、格式或小程序标识不匹配", "failure");
            throw failure("暂仅支持中国大陆手机号，请重新授权或联系品牌方");
        }
        return phone;
    }

    private synchronized String token() {
        if (tokenUntil > System.currentTimeMillis()) return token;
        String target = "https://api.weixin.qq.com/cgi-bin/stable_token";
        JsonNode result = exchange("stable_token", target, target, HttpMethod.POST,
                obj("grant_type", "client_credential", "appid", appId,
                        "secret", appSecret, "force_refresh", false),
                obj("grant_type", "client_credential", "appid", appId,
                        "secret", "<REDACTED>", "force_refresh", false),
                "appid:" + appId, "access_token");
        token = s(result, "access_token");
        tokenUntil = System.currentTimeMillis() + Math.max(0, l(result, "expires_in") - 120) * 1000;
        return token;
    }

    private void requireConfigured() {
        if (!configured()) throw failure("微信登录尚未配置，请联系品牌方");
    }

    private JsonNode exchange(String name, String target, String url, HttpMethod method,
            JsonNode body, JsonNode safeRequest, String key, String required) {
        long started = System.nanoTime();
        Integer status = null;
        JsonNode response = null;
        String outcome = "exception", errorType = null, errorMessage = null;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> raw = http.exchange(url, method,
                    new HttpEntity<>(body == null ? null : stringify(body), headers), String.class);
            status = raw.getStatusCodeValue();
            response = safeParse(raw.getBody());
            if (response.path("invalidResponse").asBoolean(false))
                throw new IllegalArgumentException("微信返回非JSON响应");
            if (!response.isObject() || l(response, "errcode") != 0
                    || response.path(required).isMissingNode() || response.path(required).isNull()
                    || (response.path(required).isTextual() && s(response, required).isEmpty())) {
                outcome = "business_failure";
                if (Arrays.asList(40001L, 40014L, 42001L).contains(l(response, "errcode"))) {
                    // 不自动重试一次性授权码；使下一次用户主动授权重新取得服务端令牌。
                    tokenUntil = 0;
                }
                throw failure("微信授权未完成，请重新操作；仍失败时请联系品牌方");
            }
            outcome = "success";
            return response;
        } catch (HttpStatusCodeException e) {
            status = e.getRawStatusCode();
            response = safeParse(e.getResponseBodyAsString());
            errorType = e.getClass().getSimpleName();
            errorMessage = "微信接口HTTP响应异常：" + status;
            throw failure("微信服务暂时不可用，请稍后重试");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            outcome = cause instanceof SocketTimeoutException ? "timeout" : "exception";
            errorType = cause.getClass().getSimpleName();
            // 原始异常可能含带密钥的URL，不能直接保存异常消息。
            errorMessage = "timeout".equals(outcome) ? "微信接口连接或读取超时" : "微信接口请求或响应解析异常";
            throw failure("微信服务暂时不可用，请稍后重试");
        } finally {
            db.insert("INSERT INTO wechat_exchange_logs(interface_name,target_url,business_key,request_id,"
                            + "request_body,response_body,result,http_status,elapsed_ms,error_type,error_message,"
                            + "create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    name, target, key, access.requestId(), stringify(safeRequest),
                    response == null ? null : stringify(redact(response)), outcome, status,
                    (System.nanoTime() - started) / 1000000, errorType, errorMessage,
                    access.actor(), access.actor());
        }
    }

    private JsonNode safeParse(String raw) {
        try { return parse(raw); }
        catch (Exception ignored) { return obj("invalidResponse", true); }
    }

    private JsonNode redact(JsonNode value) {
        JsonNode copy = value.deepCopy();
        if (copy.isObject()) {
            List<String> fields = new ArrayList<>();
            copy.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                if (Arrays.asList("openid", "unionid", "session_key", "access_token", "code",
                        "secret", "phoneNumber", "purePhoneNumber").contains(field))
                    ((ObjectNode) copy).put(field, "<REDACTED>");
                else ((ObjectNode) copy).set(field, redact(copy.get(field)));
            }
        } else if (copy.isArray()) {
            for (int i = 0; i < copy.size(); i++)
                ((com.fasterxml.jackson.databind.node.ArrayNode) copy).set(i, redact(copy.get(i)));
        }
        return copy;
    }

    private BusinessException failure(String message) {
        return new BusinessException(502, "WECHAT_AUTH_FAILED", message);
    }
}
