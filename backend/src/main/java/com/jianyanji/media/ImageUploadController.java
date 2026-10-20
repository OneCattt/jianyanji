package com.jianyanji.media;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Input;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/** 后台图片直传授权和失败关联接口；按业务用途鉴权，仅记录脱敏上下文。 */
@RestController
@RequestMapping("/api/admin/image-uploads")
public class ImageUploadController {
    /** 上传排障日志，禁止包含凭据、策略或签名。 */
    private static final Logger LOG = LoggerFactory.getLogger(ImageUploadController.class);
    /** 签发对象路径：业务用途、真实 UTC 日期、随机 UUID v4 文件名和允许的扩展名。 */
    private static final Pattern KEY =
            Pattern.compile(
                    "products/(catalog|content|membership)/([0-9]{8})/"
                            + "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(jpg|png|webp)");
    /** 会话身份及业务权限访问器。 */
    private final Access access;
    /** OSS V4 单对象签名器。 */
    private final OssUploadSigner signer;

    public ImageUploadController(Access access, OssUploadSigner signer) {
        this.access = access;
        this.signer = signer;
    }

    @PostMapping("/policy")
    public ObjectNode policy(@RequestBody JsonNode body) {
        String requestId = safeRequestId(access.requestId());
        String purpose = "-";
        long actor = 0;
        try {
            Input.strict(body, "purpose", "contentType", "size");
            purpose = purpose(body);
            actor = l(access.staff(permission(purpose)), "id");
            String mime = exactText(body, "contentType", 1, 32);
            Input.require(
                    "image/jpeg".equals(mime)
                            || "image/png".equals(mime)
                            || "image/webp".equals(mime),
                    "仅支持 JPEG、PNG 或 WebP 图片");
            long size = Input.integer(body, "size", 1, 5242880);
            ObjectNode result = signer.sign(purpose, mime, size);
            result.put("requestId", requestId);
            LOG.info(
                    "图片直传授权已生成 requestId={} staff={} purpose={} key={}",
                    requestId,
                    actor,
                    purpose,
                    s(result, "key"));
            return result;
        } catch (BusinessException e) {
            if (actor == 0) actor = access.actor();
            LOG.warn(
                    "图片直传授权失败 requestId={} staff={} purpose={} key=- code={}",
                    requestId,
                    actor,
                    purpose,
                    e.getCode());
            throw e;
        }
    }

    @PostMapping("/failure")
    public ObjectNode failure(@RequestBody JsonNode body) {
        String requestId = safeRequestId(access.requestId());
        String purpose = "-";
        String key = "-";
        long actor = 0;
        try {
            Input.strict(body, "purpose", "key", "ossRequestId", "status");
            purpose = purpose(body);
            actor = l(access.staff(permission(purpose)), "id");
            String submittedKey = exactText(body, "key", 1, 200);
            Matcher match = KEY.matcher(submittedKey);
            Input.require(match.matches() && purpose.equals(match.group(1)), "图片路径与上传用途不匹配");
            try {
                LocalDate.parse(match.group(2), DateTimeFormatter.BASIC_ISO_DATE);
            } catch (DateTimeParseException e) {
                throw BusinessException.invalid("图片路径中的日期无效");
            }
            key = submittedKey;
            long status = Input.integer(body, "status", 0, 599);
            Input.require(status == 0 || (status >= 100 && status != 204), "请提供失败状态码，网络失败使用 0");
            String ossRequestId = exactText(body, "ossRequestId", 0, 128);
            Input.require(ossRequestId.matches("[A-Za-z0-9._-]*"), "OSS 请求编号格式有误");
            LOG.warn(
                    "图片直传失败上报 requestId={} staff={} purpose={} key={} status={} ossRequestId={}",
                    requestId,
                    actor,
                    purpose,
                    key,
                    status,
                    ossRequestId);
            return obj("ok", true, "requestId", requestId);
        } catch (BusinessException e) {
            if (actor == 0) actor = access.actor();
            LOG.warn(
                    "图片直传失败上报被拒绝 requestId={} staff={} purpose={} key={} code={}",
                    requestId,
                    actor,
                    purpose,
                    key,
                    e.getCode());
            throw e;
        }
    }

    private static String purpose(JsonNode body) {
        String purpose = exactText(body, "purpose", 1, 16);
        permission(purpose);
        return purpose;
    }

    private static String permission(String purpose) {
        switch (purpose) {
            case "catalog":
                return "catalog:manage";
            case "content":
                return "content:manage";
            case "membership":
                return "onboarding:manage";
            default:
                throw BusinessException.invalid("上传用途必须为 catalog、content 或 membership");
        }
    }

    private static String exactText(JsonNode body, String field, int min, int max) {
        String text = Input.text(body, field, min, max);
        Input.require(text.equals(body.get(field).asText()), field + " 不允许首尾空白");
        return text;
    }

    private static String safeRequestId(String requestId) {
        if (requestId == null) return "system";
        String safe = requestId.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.substring(0, Math.min(safe.length(), 128));
    }
}
