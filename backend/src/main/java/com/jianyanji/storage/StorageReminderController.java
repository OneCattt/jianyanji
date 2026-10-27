package com.jianyanji.storage;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.jianyanji.common.*;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** 到期站内提醒及已授权微信订阅发送；外部请求不占用业务事务，逐条记录实际结果。 */
@RestController
public class StorageReminderController {
    /** 仅记录业务键和安全错误，不记录密钥、令牌或完整请求地址。 */
    private static final Logger LOG = LoggerFactory.getLogger(StorageReminderController.class);
    /** 数据与事务。 */
    private final Db db;
    /** 会话、权限与审计。 */
    private final Access access;
    /** 微信应用标识。 */
    private final String appId;
    /** 微信应用密钥，不进入响应和日志。 */
    private final String secret;
    /** 缓存服务端凭证，仅驻留进程内存。 */
    private String token = "";
    /** 缓存凭证有效截止毫秒。 */
    private long tokenUntil;

    public StorageReminderController(
            Db db,
            Access access,
            @Value("${jianyanji.wechat-app-id:}") String appId,
            @Value("${jianyanji.wechat-app-secret:}") String secret) {
        this.db = db;
        this.access = access;
        this.appId = appId;
        this.secret = secret;
    }

    private JsonNode settings() {
        JsonNode value = db.setting("storage-reminders");
        return value == null
                ? obj(
                        "enabled",
                        true,
                        "days",
                        json(Arrays.asList(30, 7)),
                        "expiryTemplateId",
                        "",
                        "rejectedTemplateId",
                        "",
                        "expiryFields",
                        obj(),
                        "rejectedFields",
                        obj())
                : value;
    }

    @GetMapping("/api/admin/storage-reminder-settings")
    public Object config() {
        access.staff("orders:manage");
        return settings();
    }

    @PutMapping("/api/admin/storage-reminder-settings")
    public Object save(@RequestBody JsonNode body) {
        long actor = l(access.staff("orders:manage"), "id");
        Input.strict(
                body,
                "enabled",
                "days",
                "expiryTemplateId",
                "rejectedTemplateId",
                "expiryFields",
                "rejectedFields",
                "reason");
        ObjectNode value = obj("enabled", Input.bool(body, "enabled"));
        ArrayNode days = Input.array(body, "days", 1, 4);
        Set<Integer> seen = new HashSet<>();
        for (JsonNode day : days) {
            Input.require(
                    day.isInt() && day.asInt() > 0 && day.asInt() <= 365 && seen.add(day.asInt()),
                    "提醒天数须为1至365的不重复整数");
        }
        value.set("days", days);
        for (String key : new String[] {"expiryTemplateId", "rejectedTemplateId"})
            value.put(key, Input.text(body, key, 0, 191));
        for (String key : new String[] {"expiryFields", "rejectedFields"}) {
            JsonNode fields = body.path(key);
            Input.require(fields.isObject() && fields.size() <= 10, "模板字段配置无效");
            fields.fields()
                    .forEachRemaining(
                            e ->
                                    Input.require(
                                            e.getKey().matches("[a-z]+[0-9]+")
                                                    && Arrays.asList("title", "body", "expiresOn")
                                                            .contains(e.getValue().asText()),
                                            "模板字段映射仅支持title/body/expiresOn"));
            value.set(key, fields);
        }
        String reason = Input.text(body, "reason", 1, 2000);
        return db.tx(
                () -> {
                    db.putSetting("storage-reminders", value, actor);
                    access.audit(
                            "storage.reminder.settings", "settings", "storage-reminders", reason);
                    return value;
                });
    }

    @GetMapping("/api/storage-subscription-templates")
    public Object templates() {
        access.user();
        JsonNode config = settings();
        return obj(
                "expiryTemplateId",
                s(config, "expiryTemplateId"),
                "rejectedTemplateId",
                s(config, "rejectedTemplateId"));
    }

    @PostMapping("/api/storage-subscriptions")
    public Object subscribe(@RequestBody JsonNode body) {
        long uid = l(access.user(), "id");
        Input.strict(body, "templateId", "accepted");
        String template = Input.text(body, "templateId", 1, 191);
        JsonNode config = settings();
        Input.require(
                template.equals(s(config, "expiryTemplateId"))
                        || template.equals(s(config, "rejectedTemplateId")),
                "模板不是当前寄存提醒模板");
        boolean accept = Input.bool(body, "accepted");
        return db.tx(
                () -> {
                    db.update(
                            "INSERT INTO storage_subscriptions(user_id,template_id,accepted,create_user_id,update_user_id) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE accepted=VALUES(accepted),update_user_id=VALUES(update_user_id),update_time=CURRENT_TIMESTAMP(3)",
                            uid,
                            template,
                            accept ? 1 : 0,
                            uid,
                            uid);
                    return obj("ok", true);
                });
    }

    @GetMapping("/api/admin/storage-messages")
    public Object messages(@RequestParam Map<String, String> query) {
        access.staff("orders:manage");
        return new PageQuery(query, "FROM storage_messages WHERE is_deleted=0")
                .eq("state", "delivery_state")
                .eq("userId", "user_id")
                .select(db, "*", "id DESC", r -> r);
    }

    @PostMapping("/api/admin/storage/{id}/remind")
    public Object remind(@PathVariable long id) {
        long actor = l(access.staff("orders:manage"), "id");
        return db.tx(
                () -> {
                    ObjectNode lot =
                            db.one(
                                    "SELECT * FROM storage_lots WHERE id=? AND is_deleted=0",
                                    Input.id(id));
                    if (lot == null) throw BusinessException.notFound("寄存批次不存在");
                    new StorageService(db, access)
                            .message(
                                    l(lot, "user_id"),
                                    id,
                                    "expiry",
                                    "寄存到期提醒",
                                    "寄存批次#" + id + "到期日为" + s(lot, "expires_on") + "，请及时安排提货。",
                                    "manual:" + id + ":" + LocalDate.now(StorageService.ZONE),
                                    actor);
                    access.audit("storage.remind", "storage", id, "人工发送寄存到期提醒");
                    return obj("ok", true);
                });
    }

    @PostMapping("/api/admin/storage-messages/{id}/retry")
    public Object retry(@PathVariable long id) {
        long actor = l(access.staff("orders:manage"), "id");
        return db.tx(
                () -> {
                    if (db.update(
                                    "UPDATE storage_messages SET delivery_state='pending',last_error='',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND delivery_state IN ('failed','unavailable','unknown') AND is_deleted=0",
                                    actor,
                                    Input.id(id))
                            != 1) throw BusinessException.conflict("仅失败、未授权或结果未明的消息可重试");
                    access.audit(
                            "storage.reminder.retry", "storage_message", id, "人工确认重试，实际送达以微信返回为准");
                    return obj("ok", true);
                });
    }
    /** 每分钟扫描小批次待发送任务；没有配置或授权也保留可查询站内消息。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 15000)
    public void tick() {
        try {
            JsonNode config = settings();
            if (b(config, "enabled")) scan(config);
            db.tx(
                    () ->
                            db.update(
                                    "UPDATE storage_messages SET delivery_state='unknown',last_error='发送进程中断，结果未明，需人工核实',update_time=CURRENT_TIMESTAMP(3) WHERE delivery_state='sending' AND update_time<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 5 MINUTE) AND is_deleted=0"));
            for (ObjectNode message :
                    db.list(
                            "SELECT m.*,u.openid FROM storage_messages m JOIN users u ON u.id=m.user_id AND u.is_deleted=0 WHERE m.delivery_state='pending' AND m.is_deleted=0 ORDER BY m.id LIMIT 30"))
                send(message, config);
        } catch (Exception error) {
            LOG.error(
                    "寄存提醒批处理失败 requestId=storage-reminder 原因类型={}",
                    error.getClass().getSimpleName());
        }
    }

    private void scan(JsonNode config) {
        LocalDate today = LocalDate.now(StorageService.ZONE);
        for (JsonNode days : config.path("days")) {
            String date = today.plusDays(days.asInt()).toString();
            for (ObjectNode lot :
                    db.list(
                            "SELECT id,user_id,expires_on FROM storage_lots WHERE expires_on=? AND remaining_qty>0 AND is_deleted=0",
                            date))
                db.tx(
                        () ->
                                new StorageService(db, access)
                                        .message(
                                                l(lot, "user_id"),
                                                l(lot, "id"),
                                                "expiry",
                                                "寄存到期提醒",
                                                "寄存货品将于" + date + "到期，请安排提货。",
                                                "expiry:"
                                                        + l(lot, "id")
                                                        + ":"
                                                        + date
                                                        + ":"
                                                        + days.asInt(),
                                                0));
        }
    }

    private RestTemplate http() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(5000);
        return new RestTemplate(f);
    }

    private synchronized String token() {
        if (tokenUntil > System.currentTimeMillis()) return token;
        String url =
                UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/cgi-bin/token")
                        .queryParam("grant_type", "client_credential")
                        .queryParam("appid", appId)
                        .queryParam("secret", secret)
                        .build()
                        .encode()
                        .toUriString();
        JsonNode response = parse(http().getForObject(url, String.class));
        if (!has(response, "access_token")) throw new IllegalStateException("微信访问凭证获取失败");
        token = s(response, "access_token");
        tokenUntil =
                System.currentTimeMillis() + Math.max(60, l(response, "expires_in") - 120) * 1000;
        return token;
    }

    private void send(ObjectNode message, JsonNode config) {
        long id = l(message, "id"), uid = l(message, "user_id");
        boolean expiry = "expiry".equals(s(message, "kind"));
        String template = s(config, expiry ? "expiryTemplateId" : "rejectedTemplateId");
        if (template.isEmpty()
                || appId.isEmpty()
                || secret.isEmpty()
                || db.one(
                                "SELECT id FROM storage_subscriptions WHERE user_id=? AND template_id=? AND accepted=1 AND is_deleted=0",
                                uid,
                                template)
                        == null) {
            state(id, "unavailable", "缺少有效订阅授权、模板或微信配置；站内消息可查看");
            return;
        }
        String request = "storage-message:" + id + ":" + id();
        boolean claimed =
                db.tx(
                        () ->
                                db.update(
                                                "UPDATE storage_messages SET delivery_state='sending',attempts=attempts+1,request_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND delivery_state='pending' AND is_deleted=0",
                                                request,
                                                id)
                                        == 1);
        if (!claimed) return;
        try {
            ObjectNode data = obj();
            String expires = "";
            if (has(message, "lot_id")) {
                ObjectNode lot =
                        db.one(
                                "SELECT expires_on FROM storage_lots WHERE id=? AND is_deleted=0",
                                l(message, "lot_id"));
                expires = s(lot, "expires_on");
            }
            final String expiryDate = expires;
            config.path(expiry ? "expiryFields" : "rejectedFields")
                    .fields()
                    .forEachRemaining(
                            e -> {
                                String text =
                                        "expiresOn".equals(e.getValue().asText())
                                                ? expiryDate
                                                : s(message, e.getValue().asText());
                                data.set(
                                        e.getKey(),
                                        obj(
                                                "value",
                                                text.length() > 20 ? text.substring(0, 20) : text));
                            });
            Input.require(data.size() > 0, "订阅模板字段映射尚未配置");
            String response =
                    http().postForObject(
                                    "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token="
                                            + token(),
                                    obj(
                                            "touser",
                                            s(message, "openid"),
                                            "template_id",
                                            template,
                                            "page",
                                            "pages/storage/index",
                                            "data",
                                            data),
                                    String.class);
            JsonNode result = parse(response);
            if (result.has("errcode") && l(result, "errcode") == 0) {
                state(id, "sent", "");
                db.tx(
                        () ->
                                db.update(
                                        "UPDATE storage_subscriptions SET accepted=0,update_time=CURRENT_TIMESTAMP(3) WHERE user_id=? AND template_id=? AND is_deleted=0",
                                        uid,
                                        template));
            } else {
                if (l(result, "errcode") == 40001 || l(result, "errcode") == 42001) tokenUntil = 0;
                state(id, "failed", "微信返回" + l(result, "errcode") + "：" + s(result, "errmsg"));
                LOG.warn(
                        "寄存消息发送失败 requestId={} messageId={} userId={} errcode={}",
                        request,
                        id,
                        uid,
                        l(result, "errcode"));
            }
        } catch (Exception error) {
            state(id, "unknown", "发送异常或结果未明，需人工核实；原因类型=" + error.getClass().getSimpleName());
            LOG.warn(
                    "寄存消息结果未明 requestId={} messageId={} userId={} 原因类型={}",
                    request,
                    id,
                    uid,
                    error.getClass().getSimpleName());
        }
    }

    private void state(long id, String state, String error) {
        db.tx(
                () ->
                        db.update(
                                "UPDATE storage_messages SET delivery_state=?,last_error=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                state,
                                error.length() > 1900 ? error.substring(0, 1900) : error,
                                id));
    }
}
