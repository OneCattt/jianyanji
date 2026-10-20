package com.jianyanji.media;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.BusinessException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** OSS V4 表单直传签名器；只生成五分钟单对象授权，不访问 OSS 或数据库。 */
@Component
public class OssUploadSigner {
    /** UTC 日期格式，同时用于凭据范围和对象目录。 */
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    /** OSS V4 要求的 UTC 请求时间，精确到秒。 */
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    /** 策略过期时间沿用 OSS 官方示例的 UTC 毫秒格式。 */
    private static final DateTimeFormatter EXPIRATION =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    /** OSS_REGION，缺省为 cn-hangzhou，不包含 oss- 前缀。 */
    private final String region;
    /** OSS_BUCKET，仅允许标准 Bucket 名称。 */
    private final String bucket;
    /** OSS_ACCESS_KEY_ID，仅作为 V4 凭据范围返回，不写入日志。 */
    private final String accessKeyId;
    /** OSS_ACCESS_KEY_SECRET，仅用于服务端 HMAC，不返回或写入日志。 */
    private final String accessKeySecret;
    /** 可选 STS 令牌；配置后必须同时绑定表单和策略，不写入日志。 */
    private final String securityToken;
    /** 官方 HTTPS 上传地址，不接受客户端指定。 */
    private final String host;
    /** 图片公开访问前缀，可配置为 HTTPS 域名及路径，末尾无斜杠。 */
    private final String publicBase;
    /** 必需配置及公开访问地址是否有效；无效时接口返回 503。 */
    private final boolean configured;
    /** 签名时钟，生产使用 UTC，测试可注入固定时刻。 */
    private final Clock clock;

    @Autowired
    public OssUploadSigner(Environment environment) {
        this(environment, Clock.systemUTC());
    }

    OssUploadSigner(Environment environment, Clock clock) {
        region = environment.getProperty("OSS_REGION", "cn-hangzhou").trim();
        bucket = value(environment, "OSS_BUCKET");
        accessKeyId = value(environment, "OSS_ACCESS_KEY_ID");
        accessKeySecret = value(environment, "OSS_ACCESS_KEY_SECRET");
        securityToken = value(environment, "OSS_SECURITY_TOKEN");
        host = "https://" + bucket + ".oss-" + region + ".aliyuncs.com";
        String base = value(environment, "OSS_PUBLIC_BASE_URL");
        publicBase = (base.isEmpty() ? host : base).replaceAll("/+$", "");
        configured =
                bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
                        && region.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)+")
                        && !accessKeyId.isEmpty()
                        && !accessKeySecret.isEmpty()
                        && validPublicBase(publicBase);
        this.clock = clock;
    }

    ObjectNode sign(String purpose, String contentType, long size) {
        if (!configured) throw new BusinessException(503, "OSS_NOT_CONFIGURED", "图片上传服务未配置，请联系管理员");
        Instant now = clock.instant();
        String date = DATE.format(now);
        String key =
                "products/"
                        + purpose
                        + "/"
                        + date
                        + "/"
                        + UUID.randomUUID()
                        + "."
                        + ("image/jpeg".equals(contentType) ? "jpg" : contentType.substring(6));
        ObjectNode fields =
                obj(
                        "key",
                        key,
                        "Content-Type",
                        contentType,
                        "x-oss-content-type",
                        contentType,
                        "success_action_status",
                        "204",
                        "x-oss-forbid-overwrite",
                        "true",
                        "x-oss-signature-version",
                        "OSS4-HMAC-SHA256",
                        "x-oss-credential",
                        accessKeyId + "/" + date + "/" + region + "/oss/aliyun_v4_request",
                        "x-oss-date",
                        TIME.format(now));
        if (!securityToken.isEmpty()) fields.put("x-oss-security-token", securityToken);
        ArrayNode conditions = arr(obj("bucket", bucket));
        // 每个表单字段均精确匹配；同时锁定最高优先级的 x-oss-content-type，避免 MIME 被覆盖。
        fields.fields()
                .forEachRemaining(field -> conditions.add(obj(field.getKey(), field.getValue())));
        // 文件字节数必须等于申请值；HTTP multipart 边界及其他表单字段不计入该范围。
        conditions.add(arr("content-length-range", size, size));
        ObjectNode policy =
                obj(
                        "expiration",
                        EXPIRATION.format(now.plusSeconds(300)),
                        "conditions",
                        conditions);
        String encoded =
                Base64.getEncoder()
                        .encodeToString(stringify(policy).getBytes(StandardCharsets.UTF_8));
        fields.put("policy", encoded);
        fields.put("x-oss-signature", signature(accessKeySecret, date, region, encoded));
        return obj("host", host, "url", publicBase + "/" + key, "key", key, "fields", fields);
    }

    /** 按阿里云官方 POST V4 算法依次派生日期、地域、服务和请求密钥，再签名 Base64 策略。 */
    static String signature(String secret, String date, String region, String encodedPolicy) {
        // 官方参考：https://www.alibabacloud.com/help/en/oss/developer-reference/signature-version-4-recommend
        try {
            byte[] key = hmac(("aliyun_v4" + secret).getBytes(StandardCharsets.UTF_8), date);
            key = hmac(key, region);
            key = hmac(key, "oss");
            key = hmac(key, "aliyun_v4_request");
            byte[] result = hmac(key, encodedPolicy);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : result) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (GeneralSecurityException e) {
            // 不附加底层异常或配置值，避免统一异常处理器输出凭据。
            throw new BusinessException(503, "OSS_SIGNING_FAILED", "图片上传授权生成失败，请稍后重试");
        }
    }

    private static byte[] hmac(byte[] key, String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String value(Environment environment, String name) {
        return environment.getProperty(name, "").trim();
    }

    private static boolean validPublicBase(String base) {
        try {
            URI uri = URI.create(base);
            return "https".equals(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
