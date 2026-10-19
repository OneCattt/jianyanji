package com.jianyanji.common;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.Base64;
import javax.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 请求身份与审计边界，用户令牌和工作人员 Cookie 相互隔离，每次请求核对有效授权。 */
@Component
public class Access {
    /** 会话及审计持久化访问。 */
    private final Db db;
    /** 全部可授予的首期后台权限。 */
    public static final String[] PERMISSIONS = {
        "staff:manage",
        "users:read",
        "audit:read",
        "content:manage",
        "catalog:manage",
        "orders:manage",
        "onboarding:manage",
        "finance:manage",
        "rewards:manage"
    };

    public Access(Db db) {
        this.db = db;
    }

    public HttpServletRequest request() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getRequest();
    }

    public HttpServletResponse response() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getResponse();
    }

    public String requestId() {
        Object v = request().getAttribute("requestId");
        return v == null ? "system" : v.toString();
    }

    public String token(String kind) {
        if ("user".equals(kind)) {
            String h = request().getHeader("Authorization");
            return h != null && h.startsWith("Bearer ") ? h.substring(7) : null;
        }
        Cookie[] cookies = request().getCookies();
        if (cookies != null)
            for (Cookie c : cookies) if ("jyj_admin".equals(c.getName())) return c.getValue();
        return null;
    }

    public ObjectNode user() {
        return authenticate("user");
    }

    public ObjectNode optionalUser() {
        return request().getHeader("Authorization") == null ? null : user();
    }

    public ObjectNode staff(String permission) {
        ObjectNode person = authenticate("staff");
        if (permission != null
                && !permission.isEmpty()
                && !list(parse(s(person, "permissions"))).stream()
                        .anyMatch(n -> permission.equals(n.asText()))) {
            audit("permission.denied", "permission", permission, "当前账号没有该功能权限", "denied");
            throw new BusinessException(403, "FORBIDDEN", "当前账号没有该功能权限");
        }
        return person;
    }

    public long actor() {
        ObjectNode staff = (ObjectNode) request().getAttribute("staff"),
                user = (ObjectNode) request().getAttribute("user");
        return staff != null ? l(staff, "id") : user != null ? l(user, "id") : 0;
    }

    public void setIdentity(String kind, ObjectNode row) {
        request().setAttribute(kind, row);
    }

    private ObjectNode authenticate(String kind) {
        Object cached = request().getAttribute(kind);
        if (cached != null) return (ObjectNode) cached;
        String token = token(kind);
        if (token == null || token.isEmpty())
            throw new BusinessException(401, "LOGIN_REQUIRED", "请先登录");
        ObjectNode session =
                db.one(
                        "SELECT subject_id FROM sessions WHERE token_hash=? AND kind=? AND is_deleted=0 AND expires_at>?",
                        hash(token),
                        kind,
                        now());
        if (session == null) throw new BusinessException(401, "SESSION_EXPIRED", "登录已失效，请重新登录");
        ObjectNode row =
                db.one(
                        "SELECT * FROM "
                                + ("user".equals(kind) ? "users" : "staff")
                                + " WHERE id=? AND is_deleted=0",
                        l(session, "subject_id"));
        if (row == null || ("staff".equals(kind) && !b(row, "enabled")))
            throw new BusinessException(401, "ACCOUNT_DISABLED", "账号已停用，请联系管理员");
        setIdentity(kind, row);
        return row;
    }

    public String issueSession(String kind, long id) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.insert(
                "INSERT INTO sessions(token_hash,kind,subject_id,expires_at,create_user_id,update_user_id) VALUES(?,?,?,?,?,?)",
                hash(token),
                kind,
                id,
                Instant.now().plusSeconds("staff".equals(kind) ? 43200 : 604800).toString(),
                id,
                id);
        return token;
    }

    public void audit(String action, String type, Object id, String reason) {
        audit(action, type, id, reason, "success");
    }

    public void audit(String action, String type, Object id, String reason, String result) {
        String kind =
                request().getAttribute("staff") != null
                        ? "staff"
                        : request().getAttribute("user") != null ? "user" : "system";
        auditSystem(kind, actor(), action, type, id, reason, result, requestId());
        request().setAttribute("auditRecorded", true);
    }

    public void auditSystem(
            String kind,
            long actor,
            String action,
            String type,
            Object id,
            String reason,
            String result,
            String requestId) {
        db.insert(
                "INSERT INTO audit_events(actor_kind,actor_id,action,object_type,object_id,result,reason,request_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                kind,
                actor,
                action,
                type,
                String.valueOf(id),
                result,
                reason,
                requestId,
                actor,
                actor);
    }

    public static String hash(String value) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : bytes) s.append(String.format("%02x", b));
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
