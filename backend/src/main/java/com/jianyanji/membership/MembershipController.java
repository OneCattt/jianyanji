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
    /** 本人全部资格记录。 */
    @GetMapping("/api/qualifications")
    public Object qualifications() {
        return membership.qualifications().list(l(access.user(), "id"));
    }

    /** 授权人员首批存量资格核验。 */
    @PostMapping("/api/admin/qualifications/bootstrap")
    public Object bootstrap(@RequestBody JsonNode body) {
        return membership
                .qualifications()
                .bootstrap(body, l(access.staff("onboarding:manage"), "id"));
    }

    /** 退款前准备有审计依据的有效主理人接管计划。 */
    @PostMapping("/api/admin/qualifications/handover")
    public Object handover(@RequestBody JsonNode body) {
        return membership
                .qualifications()
                .prepareHandover(body, l(access.staff("onboarding:manage"), "id"));
    }

    /** 本人真实分享码与当前关系。 */
    @GetMapping("/api/invitations/mine")
    public Object invitation() {
        return membership.invitations().mine(l(access.user(), "id"));
    }

    /** 本人付款前解析并核验候选邀请，只返回必要的展示资料。 */
    @GetMapping("/api/invitations/candidate")
    public Object candidate(
            @RequestParam(defaultValue = "") String shareCode,
            @RequestParam(required = false) Long inviterId,
            @RequestParam String targetIdentity) {
        return membership
                .invitations()
                .candidate(shareCode, inviterId, targetIdentity, l(access.user(), "id"));
    }

    /** 品牌方授权调整后续关系，保留历史来源。 */
    @PostMapping("/api/admin/relations")
    public Object relation(@RequestBody JsonNode body) {
        return membership.invitations().adjust(body, l(access.staff("onboarding:manage"), "id"));
    }

    /** 本人复用唯一产品包订单发起付款。 */
    @PostMapping("/api/onboardings/{id}/order")
    public Object order(@PathVariable long id) {
        return membership.preparePayment(Input.id(id), l(access.user(), "id"));
    }

    /** 关系变更后的本人明确重新确认。 */
    @PostMapping("/api/onboardings/{id}/confirm")
    public Object confirm(@PathVariable long id, @RequestBody JsonNode body) {
        return membership.reconfirm(Input.id(id), body, l(access.user(), "id"));
    }
    /** 首批新入驻仍关联真实后续付款，不直接开通资格。 */

    @PostMapping("/api/admin/qualifications/initial-application")
    public Object initialApplication(@RequestBody JsonNode body) {
        return membership.initialApplication(body, l(access.staff("onboarding:manage"), "id"));
    }
}
