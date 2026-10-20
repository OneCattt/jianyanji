package com.jianyanji.media;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** OSS V4 签名、单对象授权范围及配置边界的独立验证，不调用真实 OSS。 */
class OssUploadSignerTest {
    /** 跨本地日期边界的固定时刻，用于确认路径、凭据与过期时间均使用 UTC。 */
    private static final Instant NOW = Instant.parse("2026-10-19T23:59:30Z");
    /** 刻意使用东八区时钟，签名器仍必须输出 UTC 日期。 */
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"));

    static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("OSS_BUCKET", "examplebucket")
                .withProperty("OSS_ACCESS_KEY_ID", "test-access-id")
                .withProperty("OSS_ACCESS_KEY_SECRET", "test-secret-not-real");
    }

    @Test
    void exactObjectMimeLengthAndEveryFormFieldAreSignedForFiveMinutes() {
        ObjectNode result =
                new OssUploadSigner(environment(), CLOCK).sign("catalog", "image/jpeg", 1024);
        String key = s(result, "key");
        assertTrue(
                key.matches(
                        "products/catalog/20261019/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.jpg"));
        assertEquals("https://examplebucket.oss-cn-hangzhou.aliyuncs.com", s(result, "host"));
        assertEquals(s(result, "host") + "/" + key, s(result, "url"));
        ObjectNode fields = (ObjectNode) result.get("fields");
        assertEquals(
                obj(
                        "key", key,
                        "Content-Type", "image/jpeg",
                        "x-oss-content-type", "image/jpeg",
                        "success_action_status", "204",
                        "x-oss-forbid-overwrite", "true",
                        "x-oss-signature-version", "OSS4-HMAC-SHA256",
                        "x-oss-credential",
                                "test-access-id/20261019/cn-hangzhou/oss/aliyun_v4_request",
                        "x-oss-date", "20261019T235930Z",
                        "policy", s(fields, "policy"),
                        "x-oss-signature", s(fields, "x-oss-signature")),
                fields);
        JsonNode policy = decoded(fields);
        assertEquals("2026-10-20T00:04:30.000Z", s(policy, "expiration"));
        assertEquals(NOW.plusSeconds(300), Instant.parse(s(policy, "expiration")));
        ArrayNode conditions = (ArrayNode) policy.get("conditions");
        Set<JsonNode> expected = new HashSet<>();
        expected.add(obj("bucket", "examplebucket"));
        fields.fields()
                .forEachRemaining(
                        field -> {
                            if (!"policy".equals(field.getKey())
                                    && !"x-oss-signature".equals(field.getKey()))
                                expected.add(obj(field.getKey(), field.getValue()));
                        });
        expected.add(arr("content-length-range", 1024, 1024));
        Set<JsonNode> actual = new HashSet<>();
        conditions.forEach(actual::add);
        assertEquals(expected.size(), conditions.size());
        assertEquals(expected, actual);
        assertFalse(stringify(policy).contains("starts-with"));
        assertFalse(stringify(result).contains("test-secret-not-real"));
        assertEquals(
                referenceSignature(s(fields, "policy"), "20261019", "cn-hangzhou"),
                s(fields, "x-oss-signature"));
    }

    @Test
    void matchesFrozenPythonHmacReferenceVector() {
        // 按官方 Python 示例使用标准库 hmac/hashlib 离线计算；固定输入和期望值不复用 Java 实现。
        String encoded =
                "eyJleHBpcmF0aW9uIjoiMjAyNi0xMC0yMFQwMDowNDozMFoiLCJjb25kaXRpb25zIjpbeyJidWNrZXQiOiJleGFtcGxlYnVja2V0In0seyJrZXkiOiJwcm9kdWN0cy9jYXRhbG9nLzIwMjYxMDE5LzEyM2U0NTY3LWU4OWItNDJkMy1hNDU2LTQyNjYxNDE3NDAwMC5qcGcifSx7IkNvbnRlbnQtVHlwZSI6ImltYWdlL2pwZWcifSxbImNvbnRlbnQtbGVuZ3RoLXJhbmdlIiwxMDI0LDEwMjRdXX0=";
        assertEquals(
                "76dab601feea0cf52c71fd534cd8e991bbe0667eef2b2dddf92a29a6f6935476",
                OssUploadSigner.signature(
                        "test-secret-not-real", "20261019", "cn-hangzhou", encoded));
    }

    @Test
    void stsRegionAndPublicPathAreBoundWithoutChangingUploadHost() {
        MockEnvironment env =
                environment()
                        .withProperty("OSS_REGION", "cn-shanghai")
                        .withProperty(
                                "OSS_PUBLIC_BASE_URL", "https://images.example.com/assets/brand///")
                        .withProperty("OSS_SECURITY_TOKEN", "test-sts-token");
        ObjectNode result = new OssUploadSigner(env, CLOCK).sign("membership", "image/png", 1);
        ObjectNode fields = (ObjectNode) result.get("fields");
        assertEquals("https://examplebucket.oss-cn-shanghai.aliyuncs.com", s(result, "host"));
        assertEquals(
                "https://images.example.com/assets/brand/" + s(result, "key"), s(result, "url"));
        assertEquals("test-sts-token", s(fields, "x-oss-security-token"));
        assertTrue(
                decoded(fields)
                        .get("conditions")
                        .toString()
                        .contains("\"x-oss-security-token\":\"test-sts-token\""));
        assertEquals(
                "test-access-id/20261019/cn-shanghai/oss/aliyun_v4_request",
                s(fields, "x-oss-credential"));
        assertEquals(
                referenceSignature(s(fields, "policy"), "20261019", "cn-shanghai"),
                s(fields, "x-oss-signature"));
    }

    @Test
    void repeatedPoliciesUseDifferentRandomKeysAndAllPurposesAndMimeExtensionsWork() {
        OssUploadSigner signer = new OssUploadSigner(environment(), CLOCK);
        Set<String> keys = new HashSet<>();
        String[] purposes = {"catalog", "content", "membership"};
        String[] mimes = {"image/jpeg", "image/png", "image/webp"};
        String[] extensions = {"jpg", "png", "webp"};
        for (String purpose : purposes) {
            for (int index = 0; index < mimes.length; index++) {
                ObjectNode result = signer.sign(purpose, mimes[index], 5242880);
                assertTrue(s(result, "key").startsWith("products/" + purpose + "/20261019/"));
                assertTrue(s(result, "key").endsWith("." + extensions[index]));
                assertTrue(keys.add(s(result, "key")));
                assertEquals(mimes[index], s(result.get("fields"), "x-oss-content-type"));
            }
        }
        assertNotEquals(
                s(signer.sign("catalog", "image/jpeg", 1), "key"),
                s(signer.sign("catalog", "image/jpeg", 1), "key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OSS_BUCKET", "OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET"})
    void absentRequiredConfigurationReturnsServiceUnavailable(String name) {
        MockEnvironment env = environment();
        env.getPropertySources().remove("mockProperties");
        assertUnavailable(env);
        assertUnavailable(environment().withProperty(name, "  "));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://images.example.com", "https://user:password@images.example.com",
                "https://images.example.com?q=secret", "https://images.example.com/#fragment",
                "https:///missing-host", "not a url"
            })
    void malformedOrNonHttpsPublicBaseIsUnavailable(String base) {
        assertUnavailable(environment().withProperty("OSS_PUBLIC_BASE_URL", base));
    }

    @Test
    void invalidBucketAndRegionAreUnavailable() {
        assertUnavailable(environment().withProperty("OSS_BUCKET", "bad/bucket"));
        assertUnavailable(environment().withProperty("OSS_REGION", "cn-hangzhou/invalid"));
    }

    private static void assertUnavailable(MockEnvironment env) {
        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () -> new OssUploadSigner(env, CLOCK).sign("catalog", "image/jpeg", 1));
        assertEquals(503, error.getStatus());
        assertEquals("OSS_NOT_CONFIGURED", error.getCode());
        assertTrue(error.getMessage().contains("未配置"));
    }

    private static JsonNode decoded(ObjectNode fields) {
        return parse(
                new String(
                        Base64.getDecoder().decode(s(fields, "policy")), StandardCharsets.UTF_8));
    }

    /** 使用独立 BouncyCastle HMAC 实现验证实际响应，避免生产签名函数自证。 */
    private static String referenceSignature(String policy, String date, String region) {
        byte[] key = "aliyun_v4test-secret-not-real".getBytes(StandardCharsets.UTF_8);
        for (String input : new String[] {date, region, "oss", "aliyun_v4_request", policy}) {
            HMac mac = new HMac(new SHA256Digest());
            mac.init(new KeyParameter(key));
            byte[] data = input.getBytes(StandardCharsets.UTF_8);
            mac.update(data, 0, data.length);
            key = new byte[mac.getMacSize()];
            mac.doFinal(key, 0);
        }
        return Hex.toHexString(key);
    }
}
