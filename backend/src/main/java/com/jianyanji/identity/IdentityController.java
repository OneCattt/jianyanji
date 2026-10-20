package com.jianyanji.identity;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** 微信身份、工作人员账号与审计接口，保持前端原有接口及权限边界。 */
@RestController
@RequestMapping("/api")
public class IdentityController {
    /** 会话和账号存储。 */
    private final Db db;
    /** 当前请求身份及审计。 */
    private final Access access;
    /** 生产模式，启用 Secure Cookie 并禁止本地身份模拟。 */
    private final boolean production;
    /** 仅本地显式启用的开发登录开关。 */
    private final boolean devAuth;
    /** 微信小程序标识。 */
    private final String appId;
    /** 微信服务端密钥，不进入响应和日志。 */
    private final String appSecret;
    /** 按可信入口解析后的客户端地址计算的十五分钟登录限流窗口。 */
    private final Map<String, Window> attempts = new ConcurrentHashMap<>();
    /** 不存在账号时仍执行同样的密码推导，避免直接暴露账号存在性。 */
    private final String dummyPassword = Passwords.hash(id());

    public IdentityController(
            Db db,
            Access access,
            @Value("${jianyanji.production}") boolean production,
            @Value("${jianyanji.dev-auth}") boolean devAuth,
            @Value("${jianyanji.wechat-app-id}") String appId,
            @Value("${jianyanji.wechat-app-secret}") String appSecret,
            org.springframework.core.env.Environment env) {
        this.db = db;
        this.access = access;
        this.production = production || "production".equals(env.getProperty("NODE_ENV"));
        this.devAuth = devAuth;
        this.appId = appId;
        this.appSecret = appSecret;
        if (this.production && devAuth) throw new IllegalStateException("生产环境禁止开启调试登录");
    }

    @GetMapping("/public/context")
    public Object context() {
        return obj(
                "brandName",
                "清欣简妍肌",
                "devAuth",
                devAuth,
                "wechatConfigured",
                !appId.isEmpty() && !appSecret.isEmpty());
    }

    @PostMapping("/auth/dev")
    public Object dev(@RequestBody JsonNode body) {
        limit();
        if (!devAuth) throw notFound("接口不存在");
        Input.strict(body, "account");
        String account = Input.text(body, "account", 3, 40);
        Input.require(account.matches("[a-z0-9_-]{3,40}"), "开发账号格式不正确");
        return loginUser("dev:" + account);
    }

    @PostMapping("/auth/wechat")
    public Object wechat(@RequestBody JsonNode body) {
        limit();
        Input.strict(body, "code");
        String code = Input.text(body, "code", 1, 256);
        if (appId.isEmpty() || appSecret.isEmpty())
            throw new BusinessException(502, "WECHAT_LOGIN_FAILED", "微信登录尚未配置，请联系品牌方");
        String openid;
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(8000);
            factory.setReadTimeout(8000);
            String raw =
                    new RestTemplate(factory)
                            .getForObject(
                                    UriComponentsBuilder.fromHttpUrl(
                                                    "https://api.weixin.qq.com/sns/jscode2session")
                                            .queryParam("appid", appId)
                                            .queryParam("secret", appSecret)
                                            .queryParam("js_code", code)
                                            .queryParam("grant_type", "authorization_code")
                                            .build()
                                            .encode()
                                            .toUri(),
                                    String.class);
            JsonNode result = parse(raw);
            openid = s(result, "openid");
            if (l(result, "errcode") != 0 || openid.isEmpty())
                throw new IllegalArgumentException("微信凭证无效");
        } catch (Exception e) {
            access.audit("user.login", "user", "wechat", "微信登录失败，请重新登录", "failure");
            throw new BusinessException(502, "WECHAT_LOGIN_FAILED", "微信登录失败，请重新登录");
        }
        return loginUser(openid);
    }

    private Object loginUser(String openid) {
        return db.tx(
                () -> {
                    ObjectNode user =
                            db.one("SELECT * FROM users WHERE openid=? AND is_deleted=0", openid);
                    if (user == null) {
                        long id = db.insert("INSERT INTO users(openid) VALUES(?)", openid);
                        user = db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", id);
                    }
                    access.setIdentity("user", user);
                    String token = access.issueSession("user", l(user, "id"));
                    access.audit("user.login", "user", l(user, "id"), "用户登录");
                    return obj("token", token, "user", publicUser(user));
                });
    }

    @GetMapping({"/me", "/users/{id}"})
    public Object me(@PathVariable(required = false) Long id) {
        ObjectNode user = access.user();
        own(user, id);
        return publicUser(user);
    }

    @PatchMapping({"/me", "/users/{id}"})
    public Object profile(@PathVariable(required = false) Long id, @RequestBody JsonNode body) {
        ObjectNode user = access.user();
        own(user, id);
        Input.strict(body, "nickname", "phone");
        String name = Input.text(body, "nickname", 1, 30), phone = Input.text(body, "phone", 0, 11);
        Input.require(phone.matches("|1[3-9]\\d{9}"), "请填写正确的联系电话");
        long uid = l(user, "id");
        return db.tx(
                () -> {
                    db.update(
                            "UPDATE users SET nickname=?,phone=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            name,
                            phone,
                            uid,
                            uid);
                    access.audit("profile.update", "user", uid, "本人更新昵称及联系电话");
                    return publicUser(
                            db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", uid));
                });
    }

    private void own(ObjectNode user, Long id) {
        if (id != null && Input.id(id) != l(user, "id")) {
            access.audit("profile.denied", "user", id, "不能访问他人的个人资料", "denied");
            throw notFound("资料不存在或无权访问");
        }
    }

    @PostMapping("/auth/logout")
    public Object logout() {
        ObjectNode user = access.user();
        db.tx(
                () -> {
                    revokeToken("user", l(user, "id"));
                    access.audit("user.logout", "user", l(user, "id"), "用户退出登录");
                });
        return obj("ok", true);
    }

    @PostMapping("/admin/login")
    public Object adminLogin(@RequestBody JsonNode body) {
        limit();
        Input.strict(body, "username", "password");
        String name = Input.text(body, "username", 1, 32);
        String password = rawPassword(body, 1);
        ObjectNode staff = db.one("SELECT * FROM staff WHERE username=? AND is_deleted=0", name);
        boolean valid =
                Passwords.check(
                        password, staff == null ? dummyPassword : s(staff, "password_hash"));
        if (staff == null || !valid || !b(staff, "enabled")) {
            access.audit(
                    "staff.login",
                    "staff",
                    staff == null ? "unknown" : l(staff, "id"),
                    "账号或密码错误，或账号已停用",
                    "failure");
            throw new BusinessException(401, "INVALID_CREDENTIALS", "账号或密码错误，或账号已停用");
        }
        access.setIdentity("staff", staff);
        return db.tx(
                () -> {
                    String token = access.issueSession("staff", l(staff, "id"));
                    cookie(token, 43200);
                    access.audit("staff.login", "staff", l(staff, "id"), "工作人员登录");
                    return publicStaff(staff);
                });
    }

    @GetMapping("/admin/me")
    public Object adminMe() {
        return publicStaff(access.staff(""));
    }

    @PostMapping("/admin/logout")
    public Object adminLogout() {
        ObjectNode staff = access.staff("");
        db.tx(
                () -> {
                    revokeToken("staff", l(staff, "id"));
                    access.audit("staff.logout", "staff", l(staff, "id"), "工作人员退出登录");
                });
        cookie("", 0);
        return obj("ok", true);
    }

    private void revokeToken(String kind, long actor) {
        db.update(
                "UPDATE sessions SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE token_hash=? AND is_deleted=0",
                actor,
                Access.hash(access.token(kind)));
    }

    private void cookie(String token, int age) {
        access.response()
                .addHeader(
                        "Set-Cookie",
                        "jyj_admin="
                                + token
                                + "; Path=/api/admin; Max-Age="
                                + age
                                + "; HttpOnly; SameSite=Strict"
                                + (production ? "; Secure" : ""));
    }

    @GetMapping("/admin/permissions")
    public Object permissions() {
        access.staff("staff:manage");
        return Arrays.asList(Access.PERMISSIONS);
    }

    @GetMapping("/admin/staff")
    public Object staff() {
        access.staff("staff:manage");
        return db.list("SELECT * FROM staff WHERE is_deleted=0 ORDER BY id DESC").stream()
                .map(J::publicStaff)
                .collect(Collectors.toList());
    }

    @PostMapping("/admin/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public Object createStaff(@RequestBody JsonNode body) {
        access.staff("staff:manage");
        Input.strict(body, "username", "displayName", "password", "permissions", "reason");
        String name = Input.text(body, "username", 3, 32);
        Input.require(name.matches("[a-zA-Z][a-zA-Z0-9_-]{2,31}"), "账号格式不正确");
        String display = Input.text(body, "displayName", 1, 30),
                password = rawPassword(body, 12),
                reason = Input.reason(body);
        JsonNode grants = grants(body);
        return db.tx(
                () -> {
                    if (db.one("SELECT id FROM staff WHERE username=? AND is_deleted=0", name)
                            != null) throw conflict("USERNAME_EXISTS", "账号已存在");
                    long id =
                            db.insert(
                                    "INSERT INTO staff(username,display_name,password_hash,permissions,create_user_id,update_user_id) VALUES(?,?,?,?,?,?)",
                                    name,
                                    display,
                                    Passwords.hash(password),
                                    stringify(grants),
                                    access.actor(),
                                    access.actor());
                    access.audit("staff.create", "staff", id, reason);
                    return publicStaff(findStaff(id));
                });
    }

    @PatchMapping("/admin/staff/{id}")
    public Object editStaff(@PathVariable long id, @RequestBody JsonNode body) {
        access.staff("staff:manage");
        Input.id(id);
        Input.strict(body, "displayName", "permissions", "enabled", "reason");
        String display = Input.text(body, "displayName", 1, 30), reason = Input.reason(body);
        JsonNode grants = grants(body);
        boolean enabled = Input.bool(body, "enabled");
        return db.tx(
                () -> {
                    ObjectNode staff = findStaff(id);
                    protectManager(staff, !enabled || !contains(grants, "staff:manage"));
                    db.update(
                            "UPDATE staff SET display_name=?,permissions=?,enabled=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            display,
                            stringify(grants),
                            enabled ? 1 : 0,
                            access.actor(),
                            id);
                    if (!enabled) revokeStaff(id);
                    access.audit(
                            "staff.update",
                            "staff",
                            id,
                            reason + "；权限：" + stringify(grants) + "；" + (enabled ? "启用" : "停用"));
                    return publicStaff(findStaff(id));
                });
    }

    @PostMapping("/admin/staff/{id}/remove")
    public Object removeStaff(@PathVariable long id, @RequestBody JsonNode body) {
        access.staff("staff:manage");
        Input.id(id);
        Input.strict(body, "reason");
        String reason = Input.reason(body);
        db.tx(
                () -> {
                    ObjectNode staff = findStaff(id);
                    protectManager(staff, true);
                    db.update(
                            "UPDATE staff SET is_deleted=1,enabled=0,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            access.actor(),
                            id);
                    revokeStaff(id);
                    access.audit("staff.remove", "staff", id, reason);
                });
        return obj("ok", true);
    }

    private void revokeStaff(long id) {
        db.update(
                "UPDATE sessions SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE kind='staff' AND subject_id=? AND is_deleted=0",
                access.actor(),
                id);
    }

    private ObjectNode findStaff(long id) {
        ObjectNode row = db.one("SELECT * FROM staff WHERE id=? AND is_deleted=0", id);
        if (row == null) throw notFound("工作人员不存在");
        return row;
    }

    private void protectManager(ObjectNode row, boolean losing) {
        if (losing
                && b(row, "enabled")
                && contains(parse(s(row, "permissions")), "staff:manage")
                && l(
                                db.one(
                                        "SELECT count(*) AS n FROM staff WHERE is_deleted=0 AND enabled=1 AND JSON_CONTAINS(permissions,?)",
                                        "\"staff:manage\""),
                                "n")
                        <= 1) throw conflict("LAST_MANAGER", "至少保留一个启用的账号管理员");
    }

    private JsonNode grants(JsonNode body) {
        JsonNode value = Input.array(body, "permissions", 0, Access.PERMISSIONS.length);
        Set<String> found = new HashSet<>();
        for (JsonNode n : value)
            Input.require(
                    n.isTextual()
                            && Arrays.asList(Access.PERMISSIONS).contains(n.asText())
                            && found.add(n.asText()),
                    "权限不存在或重复");
        return value;
    }

    private boolean contains(JsonNode values, String value) {
        return list(values).stream().anyMatch(v -> value.equals(v.asText()));
    }

    private String rawPassword(JsonNode body, int min) {
        Input.require(has(body, "password") && body.get("password").isTextual(), "请填写密码");
        String password = s(body, "password");
        Input.require(password.length() >= min && password.length() <= 128, "密码长度不符合要求");
        return password;
    }

    @GetMapping("/admin/users")
    public Object users(@RequestParam(defaultValue = "1") int page) {
        access.staff("users:read");
        Input.require(page >= 1 && page <= 100000, "页码不合法");
        List<ObjectNode> items =
                db
                        .list(
                                "SELECT * FROM users WHERE is_deleted=0 ORDER BY id DESC LIMIT 30 OFFSET ?",
                                (page - 1) * 30)
                        .stream()
                        .map(J::publicUser)
                        .collect(Collectors.toList());
        for (ObjectNode u : items) {
            String p = s(u, "phone");
            if (!p.isEmpty())
                u.put("phone", p.substring(0, 3) + "****" + p.substring(p.length() - 4));
        }
        return obj(
                "items",
                items,
                "page",
                page,
                "total",
                l(db.one("SELECT count(*) AS total FROM users WHERE is_deleted=0"), "total"));
    }

    @GetMapping("/admin/audit")
    public Object audit(@RequestParam Map<String, String> query) {
        access.staff("audit:read");
        Input.strict(json(query), "page", "objectType", "objectId");
        int page = page(query.get("page"));
        String type = query.get("objectType"), id = query.get("objectId");
        if (type != null)
            Input.require(
                    Arrays.asList(
                                    "user", "staff", "permission", "request", "content", "settings",
                                    "brand-post", "category", "product", "shipping", "membership",
                                    "aftersales", "reward_settings", "address", "order", "shipment",
                                    "refund", "receipt", "wallet_lot", "cash_refund", "onboarding",
                                    "reward", "reward_reversal", "reward_settlement", "reward_recovery")
                            .contains(type),
                    "业务对象不正确");
        if (id != null) Input.require(id.length() <= 40, "对象编号过长");
        StringBuilder where = new StringBuilder("a.is_deleted=0");
        List<Object> args = new ArrayList<>();
        if (type != null) {
            where.append(" AND a.object_type=?");
            args.add(type);
        }
        if (id != null) {
            where.append(" AND a.object_id=?");
            args.add(id);
        }
        long total =
                l(
                        db.one(
                                "SELECT count(*) AS total FROM audit_events a WHERE " + where,
                                args.toArray()),
                        "total");
        args.add((page - 1) * 30);
        return obj(
                "items",
                db.list(
                        // 只对本页记录关联姓名，不逐条查询账号；已移除账号保留原审计编号。
                        "SELECT a.*,CASE WHEN a.actor_kind='staff' THEN s.display_name "
                                + "WHEN a.actor_kind='user' THEN u.nickname ELSE NULL END AS actor_name "
                                + "FROM (SELECT a.id,a.actor_kind,a.actor_id,a.action,a.object_type,a.object_id,"
                                + "a.result,a.reason,a.request_id,a.create_time FROM audit_events a WHERE "
                                + where
                                + " ORDER BY a.id DESC LIMIT 30 OFFSET ?) a "
                                + "LEFT JOIN staff s ON a.actor_kind='staff' AND s.id=a.actor_id AND s.is_deleted=0 "
                                + "LEFT JOIN users u ON a.actor_kind='user' AND u.id=a.actor_id AND u.is_deleted=0 "
                                + "ORDER BY a.id DESC",
                        args.toArray()),
                "page",
                page,
                "total",
                total);
    }

    private int page(String value) {
        try {
            int p = value == null ? 1 : Integer.parseInt(value);
            Input.require(p >= 1 && p <= 100000, "页码不合法");
            return p;
        } catch (NumberFormatException e) {
            throw invalid("页码不合法");
        }
    }

    private void limit() {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> e.getValue().until <= now);
        String key = access.request().getRemoteAddr();
        Window w = attempts.computeIfAbsent(key, k -> new Window(now + 900000));
        synchronized (w) {
            if (w.count >= 20)
                throw new BusinessException(429, "TOO_MANY_ATTEMPTS", "登录尝试过多，请稍后再试");
            w.count++;
        }
    }
    /** 单个实际来源地址的登录尝试窗口。 */
    private static final class Window {
        /** 当前窗口到期的 Unix 毫秒。 */
        private final long until;
        /** 当前窗口内尝试次数，最大20次。 */
        private int count;

        private Window(long until) {
            this.until = until;
        }
    }
}
