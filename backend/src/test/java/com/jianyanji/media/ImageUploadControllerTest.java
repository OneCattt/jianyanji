package com.jianyanji.media;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.ApiErrors;
import com.jianyanji.common.ApiFilter;
import com.jianyanji.common.BusinessException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 图片上传 HTTP 合同、权限顺序、严格输入与安全排障日志的定向回归。 */
class ImageUploadControllerTest {
    /** 合法的 UUID v4 对象路径，允许失败报告关联此前签发的对象。 */
    private static final String KEY =
            "products/catalog/20261020/123e4567-e89b-42d3-a456-426614174000.jpg";

    @ParameterizedTest
    @ValueSource(strings = {"catalog", "content", "membership"})
    void checksPurposePermissionBeforeSigningAndReturnsFormContract(String purpose)
            throws Exception {
        Access access = access();
        OssUploadSigner signer = spy(new OssUploadSigner(OssUploadSignerTest.environment()));
        MockMvc mvc = mvc(access, signer);
        MvcResult response =
                mvc.perform(request("policy", policy(purpose, "image/jpeg", 1024)))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.host")
                                        .value(
                                                "https://examplebucket.oss-cn-hangzhou.aliyuncs.com"))
                        .andExpect(jsonPath("$.fields.success_action_status").value("204"))
                        .andExpect(jsonPath("$.fields.x-oss-forbid-overwrite").value("true"))
                        .andExpect(
                                jsonPath("$.fields.x-oss-signature-version")
                                        .value("OSS4-HMAC-SHA256"))
                        .andExpect(jsonPath("$.fields.x-oss-signature").isString())
                        .andReturn();
        JsonNode body = parse(response.getResponse().getContentAsString());
        assertEquals(5, body.size());
        assertEquals(response.getResponse().getHeader("X-Request-ID"), s(body, "requestId"));
        assertEquals(s(body, "key"), s(body.get("fields"), "key"));
        assertTrue(s(body, "key").startsWith("products/" + purpose + "/"));
        org.mockito.InOrder order = inOrder(access, signer);
        order.verify(access).staff(permission(purpose));
        order.verify(signer).sign(purpose, "image/jpeg", 1024);
    }

    @ParameterizedTest
    @ValueSource(strings = {"catalog", "content", "membership"})
    void bothEndpointsRejectPermissionFailureBeforeSigningOrFailureProcessing(String purpose)
            throws Exception {
        for (int status : new int[] {401, 403}) {
            Access access = access();
            when(access.staff(permission(purpose)))
                    .thenThrow(
                            new BusinessException(
                                    status,
                                    status == 401 ? "LOGIN_REQUIRED" : "FORBIDDEN",
                                    "无操作权限"));
            OssUploadSigner signer = mock(OssUploadSigner.class);
            MockMvc mvc = mvc(access, signer);
            mvc.perform(request("policy", policy(purpose, "image/jpeg", 1)))
                    .andExpect(status().is(status));
            mvc.perform(request("failure", failure().put("purpose", purpose)))
                    .andExpect(status().is(status));
            verifyNoInteractions(signer);
        }
    }

    @Test
    void missingConfigurationReturnsChinese503WithRequestId() throws Exception {
        MockMvc mvc = mvc(access(), new OssUploadSigner(new MockEnvironment()));
        MvcResult response =
                mvc.perform(request("policy", policy("catalog", "image/jpeg", 1)))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("OSS_NOT_CONFIGURED"))
                        .andExpect(jsonPath("$.message").value("图片上传服务未配置，请联系管理员"))
                        .andReturn();
        assertEquals(
                response.getResponse().getHeader("X-Request-ID"),
                s(parse(response.getResponse().getContentAsString()), "requestId"));
    }

    @Test
    void realAccessRejectsStaffWhoseGrantedPermissionBelongsToAnotherPurpose() throws Exception {
        Access access = new Access(mock(com.jianyanji.common.Db.class));
        OssUploadSigner signer = new OssUploadSigner(OssUploadSignerTest.environment());
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new ImageUploadController(access, signer))
                        .setControllerAdvice(new ApiErrors(access))
                        .addFilters(new ApiFilter())
                        .build();
        ObjectNode staff = obj("id", 42, "permissions", "[\"catalog:manage\"]");
        mvc.perform(
                        request("policy", policy("catalog", "image/jpeg", 1))
                                .requestAttr("staff", staff))
                .andExpect(status().isOk());
        for (String purpose : new String[] {"content", "membership"}) {
            mvc.perform(
                            request("policy", policy(purpose, "image/jpeg", 1))
                                    .requestAttr("staff", staff))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(
                            request("failure", failure().put("purpose", purpose))
                                    .requestAttr("staff", staff))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(request("policy", policy("catalog", "image/jpeg", 1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    @Test
    void strictPolicyInputRejectsInvalidMimeSizePurposeAndExtraFields() throws Exception {
        OssUploadSigner signer = mock(OssUploadSigner.class);
        MockMvc mvc = mvc(access(), signer);
        for (ObjectNode body :
                Arrays.asList(
                        policy("unknown", "image/jpeg", 1),
                        policy("catalog\ninjected", "image/jpeg", 1),
                        policy("catalog", "image/gif", 1),
                        policy("catalog", "image/svg+xml", 1),
                        policy("catalog", "IMAGE/JPEG", 1),
                        policy("catalog", "image/jpeg ", 1),
                        policy("catalog", "image/jpeg", 0),
                        policy("catalog", "image/jpeg", -1),
                        policy("catalog", "image/jpeg", 5242881),
                        policy("catalog", "image/jpeg", 1).put("size", "1"),
                        policy("catalog", "image/jpeg", 1).put("size", 1.0),
                        policy("catalog", "image/jpeg", 1).put("size", true),
                        policy("catalog", "image/jpeg", 1).putNull("size"),
                        policy("catalog", "image/jpeg", 1).put("key", "client-chosen.jpg"))) {
            mvc.perform(request("policy", body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        ObjectNode missing = policy("catalog", "image/jpeg", 1);
        missing.remove("size");
        mvc.perform(request("policy", missing)).andExpect(status().isBadRequest());
        mvc.perform(request("policy", arr())).andExpect(status().isBadRequest());
        verifyNoInteractions(signer);
    }

    @Test
    void allSupportedMimesAndSizeBoundariesAreAcceptedOverHttp() throws Exception {
        MockMvc mvc = mvc(access(), new OssUploadSigner(OssUploadSignerTest.environment()));
        for (String mime : new String[] {"image/jpeg", "image/png", "image/webp"})
            for (int size : new int[] {1, 5242880})
                mvc.perform(request("policy", policy("content", mime, size)))
                        .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"catalog", "content", "membership"})
    void validFailureUsesPurposePermissionAndDoesNotSignOrPersist(String purpose) throws Exception {
        Access access = access();
        OssUploadSigner signer = mock(OssUploadSigner.class);
        String key = KEY.replace("/catalog/", "/" + purpose + "/");
        MockMvc mvc = mvc(access, signer);
        mvc.perform(request("failure", failure().put("purpose", purpose).put("key", key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.requestId").isString());
        verify(access).staff(permission(purpose));
        verify(access, never()).audit(anyString(), anyString(), any(), anyString());
        verifyNoInteractions(signer);
    }

    @Test
    void networkFailureAllowsEmptyOssRequestIdAndStatusZero() throws Exception {
        mvc(access(), mock(OssUploadSigner.class))
                .perform(request("failure", failure().put("status", 0).put("ossRequestId", "")))
                .andExpect(status().isOk());
    }

    @Test
    void strictFailureInputRejectsOtherPurposeTraversalFakeNamesAndLogInjection() throws Exception {
        OssUploadSigner signer = mock(OssUploadSigner.class);
        MockMvc mvc = mvc(access(), signer);
        for (ObjectNode body :
                Arrays.asList(
                        failure().put("key", KEY.replace("catalog", "content")),
                        failure().put("key", KEY.replace("20261020", "../20261020")),
                        failure().put("key", KEY.replace("20261020", "20260230")),
                        failure()
                                .put(
                                        "key",
                                        KEY.replace(
                                                "123e4567-e89b-42d3-a456-426614174000", "custom")),
                        failure().put("key", KEY.replace(".jpg", ".svg")),
                        failure().put("key", KEY + "\nforged=1"),
                        failure().put("status", -1),
                        failure().put("status", 99),
                        failure().put("status", 204),
                        failure().put("status", 600),
                        failure().put("status", "403"),
                        failure().put("status", 403.5),
                        failure().put("ossRequestId", "oss-id\nforged=1"),
                        failure().put("ossRequestId", "bad request"),
                        failure().putNull("ossRequestId"),
                        failure().put("extra", "not-supported"))) {
            mvc.perform(request("failure", body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        ObjectNode missing = failure();
        missing.remove("purpose");
        mvc.perform(request("failure", missing)).andExpect(status().isBadRequest());
        verifyNoInteractions(signer);
    }

    @Test
    void sanitizedLogsCorrelateSigningErrorsAndFailuresWithoutCredentialsPolicyOrSignature() {
        Logger logger = (Logger) LoggerFactory.getLogger(ImageUploadController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Access access = access();
            when(access.requestId()).thenReturn("request\nforged=1😀");
            ImageUploadController controller =
                    new ImageUploadController(
                            access,
                            new OssUploadSigner(
                                    OssUploadSignerTest.environment()
                                            .withProperty(
                                                    "OSS_SECURITY_TOKEN", "private-sts-token")));
            ObjectNode signed = controller.policy(policy("catalog", "image/jpeg", 1));
            controller.failure(failure().put("key", s(signed, "key")));
            assertThrows(
                    BusinessException.class,
                    () -> controller.failure(failure().put("ossRequestId", "bad\nforged=2")));
            assertThrows(
                    BusinessException.class,
                    () -> controller.policy(policy("bad\nforged=3", "image/jpeg", 1)));
            ImageUploadController unconfigured =
                    new ImageUploadController(access, new OssUploadSigner(new MockEnvironment()));
            assertThrows(
                    BusinessException.class,
                    () -> unconfigured.policy(policy("catalog", "image/jpeg", 1)));
            String logs =
                    appender.list.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(
                    logs.contains(
                            "图片直传授权已生成 requestId=request_forged_1_ staff=42 purpose=catalog key="
                                    + s(signed, "key")));
            assertTrue(
                    logs.contains(
                            "图片直传失败上报 requestId=request_forged_1_ staff=42 purpose=catalog key="
                                    + s(signed, "key")
                                    + " status=403 ossRequestId=oss-req-123"));
            assertTrue(logs.contains("code=OSS_NOT_CONFIGURED"));
            for (String forbidden :
                    new String[] {
                        "test-access-id",
                        "test-secret-not-real",
                        "private-sts-token",
                        s(signed.get("fields"), "policy"),
                        s(signed.get("fields"), "x-oss-signature"),
                        "bad\n",
                        "forged=2",
                        "forged=3"
                    }) assertFalse(logs.contains(forbidden), forbidden);
            for (ILoggingEvent event : appender.list) assertNull(event.getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static Access access() {
        Access access = mock(Access.class);
        when(access.staff(anyString())).thenReturn(obj("id", 42));
        when(access.requestId()).thenReturn("test-request");
        return access;
    }

    private static MockMvc mvc(Access access, OssUploadSigner signer) {
        when(access.requestId())
                .thenAnswer(
                        invocation ->
                                ((org.springframework.web.context.request.ServletRequestAttributes)
                                                org.springframework.web.context.request
                                                        .RequestContextHolder
                                                        .currentRequestAttributes())
                                        .getRequest()
                                        .getAttribute("requestId")
                                        .toString());
        return MockMvcBuilders.standaloneSetup(new ImageUploadController(access, signer))
                .setControllerAdvice(new ApiErrors(access))
                .addFilters(new ApiFilter())
                .build();
    }

    private static MockHttpServletRequestBuilder request(String endpoint, JsonNode body) {
        return post("/api/admin/image-uploads/" + endpoint)
                .header("Host", "admin.example.com")
                .header("Origin", "https://admin.example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .content(stringify(body));
    }

    private static ObjectNode policy(String purpose, String mime, long size) {
        return obj("purpose", purpose, "contentType", mime, "size", size);
    }

    private static ObjectNode failure() {
        return obj("purpose", "catalog", "key", KEY, "ossRequestId", "oss-req-123", "status", 403);
    }

    private static String permission(String purpose) {
        return "catalog".equals(purpose)
                ? "catalog:manage"
                : "content".equals(purpose) ? "content:manage" : "onboarding:manage";
    }
}
