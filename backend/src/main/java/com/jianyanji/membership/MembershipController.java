package com.jianyanji.membership;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Input;
import org.springframework.web.bind.annotation.*;

/** 入驻产品包公开查询、本人申请及后台配置、财务核款、独立审批接口。 */
@RestController
public class MembershipController {
    /** 当前请求权限校验。 */
    private final Access access;
    /** 入驻产品包领域服务。 */
    private final MembershipService membership;

    public MembershipController(Access access, MembershipService membership) {
        this.access = access;
        this.membership = membership;
    }

    @GetMapping("/api/membership")
    public Object publicView() {
        return membership.publicView();
    }

    @GetMapping("/api/admin/membership-settings")
    public Object settings() {
        access.staff("onboarding:manage");
        JsonNode value = membership.settings();
        return value == null || value.isNull() ? NullNode.getInstance() : value;
    }

    @PutMapping("/api/admin/membership-settings")
    public Object settings(@RequestBody JsonNode body) {
        long actor = l(access.staff("onboarding:manage"), "id");
        return membership.updateSettings(body, actor);
    }

    @PostMapping("/api/onboardings")
    public Object apply(@RequestBody JsonNode body) {
        ObjectNode user = access.user();
        return membership.apply(body, user);
    }

    @GetMapping("/api/onboardings")
    public Object list() {
        return membership.applications(l(access.user(), "id"));
    }

    @GetMapping("/api/onboardings/{id}")
    public Object get(@PathVariable long id) {
        long userId = l(access.user(), "id");
        return membership.applicationView(membership.application(Input.id(id), userId));
    }

    @GetMapping("/api/admin/onboardings")
    public Object adminList() {
        access.staff("onboarding:manage");
        return membership.applications(null);
    }

    @PostMapping("/api/admin/onboardings/{id}/receipt")
    public Object receipt(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("finance:manage"), "id");
        return membership.confirmReceipt(Input.id(id), body, actor);
    }

    @PostMapping("/api/admin/onboardings/{id}/review")
    public Object review(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("onboarding:manage"), "id");
        return membership.review(Input.id(id), body, actor);
    }
}
