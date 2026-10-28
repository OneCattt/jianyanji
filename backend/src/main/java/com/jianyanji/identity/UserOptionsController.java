package com.jianyanji.identity;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 后台用户快捷搜索，只返回选人所需信息；业务写入仍由原接口核对身份和权限。 */
@RestController
public class UserOptionsController {
    /** 用户只读分页访问。 */
    private final Db db;
    /** 后台业务权限校验。 */
    private final Access access;

    public UserOptionsController(Db db, Access access) {
        this.db = db;
        this.access = access;
    }

    /** 昵称、手机号或编号搜索；identity为空表示全部，B为有效19800店主，agents为有效店主。 */
    @GetMapping("/api/admin/user-options")
    public Object options(@RequestParam Map<String, String> query) {
        access.staffAny("users:read", "orders:manage", "onboarding:manage", "finance:manage", "rewards:manage", "audit:read");
        Input.strict(json(query), "q", "page", "pageSize", "id", "identity");
        String identity = query.getOrDefault("identity", "");
        Input.require(Arrays.asList("", "B", "agents").contains(identity), "用户身份筛选无效");
        String from = "FROM users u WHERE u.is_deleted=0";
        if (!identity.isEmpty()) {
            from += " AND u.identity IN (" + ("B".equals(identity) ? "'B'" : "'B','C'") + ")";
            // 时间来自服务端，与现有身份有效期格式一致，不接收客户端SQL片段。
            from += " AND (u.identity_valid_until IS NULL OR u.identity_valid_until>'" + now() + "')";
        }
        if (query.containsKey("id")) {
            String id = query.get("id");
            Input.require(id.matches("[1-9][0-9]{0,15}"), "用户编号无效");
            Input.id(Long.parseLong(id));
        }
        String keyword = query.getOrDefault("q", "").trim();
        String order = "u.id DESC";
        if (keyword.matches("[1-9][0-9]{0,15}"))
            order = "CASE WHEN u.id=" + Long.parseLong(keyword) + " THEN 0 ELSE 1 END,u.id DESC";
        return new PageQuery(query, from)
                .eq("id", "u.id")
                .keyword("u.nickname", "u.phone", "CAST(u.id AS CHAR)")
                .select(db, "u.id,u.nickname,u.phone,u.identity,u.identity_valid_until", order, this::option);
    }

    /** 手机号仅展示脱敏值，响应不含账号标识、余额和关系等无关资料。 */
    private ObjectNode option(ObjectNode row) {
        ObjectNode user = publicUser(row);
        String phone = s(user, "phone");
        phone = phone.length() >= 7 ? phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4) : phone.isEmpty() ? "" : "****";
        String name = s(user, "nickname");
        user.put("phone", phone);
        user.put("label", (name.isEmpty() ? "未填写昵称" : name) + (phone.isEmpty() ? "" : " · " + phone) + " · #" + l(user, "id") + " · " + s(user, "identityName"));
        return user;
    }
}
