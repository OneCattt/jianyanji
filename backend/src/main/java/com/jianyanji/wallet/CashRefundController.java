package com.jianyanji.wallet;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Input;
import org.springframework.web.bind.annotation.*;

/** 积分余额退款查询及财务人工办理接口。 */
@RestController
public class CashRefundController {
    /** 每次请求从数据库确认会话及权限。 */
    private final Access access;
    /** 积分退现金服务。 */
    private final CashRefundService refunds;

    public CashRefundController(Access access, CashRefundService refunds) {
        this.access = access;
        this.refunds = refunds;
    }

    @GetMapping("/api/admin/cash-refunds")
    public Object adminList(@RequestParam java.util.Map<String, String> query) {
        access.staff("finance:manage");
        return query.containsKey("page") ? refunds.adminPage(query) : refunds.listRefunds(null);
    }

    @GetMapping("/api/cash-refunds")
    public Object userList() {
        return refunds.listRefunds(l(access.user(), "id"));
    }

    @PostMapping("/api/admin/cash-refunds")
    public Object reserve(@RequestBody JsonNode body) {
        long actor = l(access.staff("finance:manage"), "id");
        return refunds.reserve(body, actor, access.requestId());
    }

    @PostMapping("/api/admin/cash-refunds/{id}/resolve")
    public Object resolve(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("finance:manage"), "id");
        return refunds.resolve(Input.id(id), body, actor, access.requestId());
    }
}
