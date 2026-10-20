package com.jianyanji.mobile;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import org.springframework.web.bind.annotation.*;

/** 小程序按需加载接口；本人列表的用户编号只从已认证会话取得。 */
@RestController
@RequestMapping("/api/mini")
public class MiniListController {
    /** 分页读取服务。 */
    private final MiniListService lists;
    /** 当前会话及权限。 */
    private final Access access;

    public MiniListController(MiniListService lists, Access access) {
        this.lists = lists;
        this.access = access;
    }

    @GetMapping("/products")
    public Object products(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int limit,
            @RequestParam(required=false) Long categoryId,
            @RequestParam(defaultValue="false") boolean featured) {
        return lists.products(access.optionalUser(), page, limit, categoryId, featured);
    }

    @GetMapping({"/orders", "/refunds", "/cash-refunds", "/addresses", "/cart",
            "/onboardings", "/qualifications"})
    public Object mine(javax.servlet.http.HttpServletRequest request,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="0") int tab,
            @RequestParam(required=false) Long orderId) {
        String path = request.getRequestURI();
        return lists.mine(path.substring(path.lastIndexOf('/') + 1), access.user(), page, limit, tab, orderId);
    }

    @GetMapping("/address")
    public Object address(@RequestParam(required=false) Long id) {
        return lists.address(l(access.user(), "id"), id);
    }

    @GetMapping("/cart/selection")
    public Object selection() {
        return lists.selection(access.user());
    }

    @GetMapping("/wallet")
    public Object wallet() {
        return lists.wallet(l(access.user(), "id"));
    }

    @GetMapping("/wallet/ledger")
    public Object ledger(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="all") String kind) {
        return lists.ledger(l(access.user(), "id"), page, limit, kind);
    }

    @GetMapping("/rewards")
    public Object rewards(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="0") int tab) {
        return lists.rewards(l(access.user(), "id"), page, limit, tab);
    }

    @GetMapping("/team/{kind:stores|principals|orders}")
    public Object team(@PathVariable String kind, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int limit) {
        ObjectNode user = access.user();
        if (!"B".equals(effectiveIdentity(user)))
            throw new com.jianyanji.common.BusinessException(403,"FORBIDDEN","仅有效主理人可查看本人团队");
        return lists.team(l(user, "id"), kind, page, limit);
    }

    @GetMapping("/rewards/{id}")
    public Object rewardDetail(@PathVariable long id,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="reversals,settlements,debts,offsets,recoveries") String parts) {
        return lists.rewardDetail(com.jianyanji.common.Input.id(id), l(access.user(),"id"), page, limit, parts);
    }
}
