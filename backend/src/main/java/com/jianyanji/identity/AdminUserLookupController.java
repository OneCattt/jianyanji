package com.jianyanji.identity;

import static com.jianyanji.common.J.*;

import com.jianyanji.common.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 后台只读用户名称及人工提货地址选项，不改变任何业务写入约束。 */
@RestController
public class AdminUserLookupController {
    /** 只读数据访问。 */
    private final Db db;
    /** 原业务权限检查。 */
    private final Access access;

    public AdminUserLookupController(Db db, Access access) { this.db = db; this.access = access; }

    /** 最多100个正整数用户编号，参数化批量查询最小昵称信息。 */
    @GetMapping("/api/admin/user-labels")
    public Object labels(@RequestParam String ids) {
        access.staffAny("users:read", "orders:manage", "onboarding:manage", "finance:manage", "rewards:manage", "audit:read");
        String[] values = ids.split(",", -1);
        Input.require(values.length >= 1 && values.length <= 100, "每批用户数量须为1至100");
        List<Long> keys = new ArrayList<>();
        for (String value : values) {
            Input.require(value.matches("[1-9][0-9]{0,15}"), "用户编号无效");
            keys.add(Input.id(Long.parseLong(value)));
        }
        List<Object> items = new ArrayList<>();
        new UserNames(db).find(keys).forEach((id, name) -> items.add(obj("id", id, "nickname", name)));
        return obj("items", items);
    }

    /** 人工提货只读取指定用户未删除地址，按默认地址优先分页，不返回无关用户地址。 */
    @GetMapping("/api/admin/users/{id}/address-options")
    public Object addresses(@PathVariable long id, @RequestParam Map<String, String> query) {
        access.staff("orders:manage");
        Input.id(id);
        Input.strict(json(query), "q", "page", "pageSize");
        Map<String, String> filters = new HashMap<>(query);
        filters.put("userId", String.valueOf(id));
        return new PageQuery(filters, "FROM addresses a WHERE a.is_deleted=0")
                .eq("userId", "a.user_id")
                .keyword("a.recipient", "a.phone", "CONCAT(a.province,a.city,a.district,a.detail)")
                .select(db, "a.id,a.recipient,a.phone,a.province,a.city,a.district,a.detail,a.is_default", "a.is_default DESC,a.id DESC", row -> row);
    }
}
