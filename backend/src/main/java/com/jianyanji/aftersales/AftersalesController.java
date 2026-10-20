package com.jianyanji.aftersales;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Input;
import org.springframework.web.bind.annotation.*;

/** 售后政策、商品退款申请、审核、验收及三种原路退款的完整 HTTP 端点。 */
@RestController
public class AftersalesController {
    /** 售后领域服务。 */
    private final AftersalesService aftersales;
    /** 用户、后台权限及审计上下文。 */
    private final Access access;

    public AftersalesController(AftersalesService aftersales, Access access) {
        this.aftersales = aftersales;
        this.access = access;
    }

    @GetMapping("/api/admin/aftersales-settings")
    public Object settings() {
        access.staff("orders:manage");
        JsonNode result = aftersales.settings();
        return result == null ? NullNode.getInstance() : result;
    }

    @PutMapping("/api/admin/aftersales-settings")
    public Object updateSettings(@RequestBody JsonNode body) {
        access.staff("orders:manage");
        return aftersales.updateSettings(body);
    }

    @GetMapping("/api/admin/refunds")
    public Object adminRefunds(@RequestParam java.util.Map<String, String> query) {
        access.staffAny("orders:manage", "finance:manage");
        return query.containsKey("page") ? aftersales.adminPage(query) : aftersales.listRefunds(null);
    }

    @GetMapping("/api/refunds")
    public Object refunds() {
        long userId = l(access.user(), "id");
        return aftersales.listRefunds(userId);
    }

    @PostMapping("/api/admin/refunds")
    public Object request(@RequestBody JsonNode body) {
        return aftersales.request(body, access.staff("orders:manage"));
    }

    @PostMapping("/api/admin/refunds/{id}/review")
    public Object review(@PathVariable long id, @RequestBody JsonNode body) {
        access.staff("orders:manage");
        return aftersales.review(Input.id(id), body);
    }

    @PostMapping("/api/admin/refunds/{id}/inspect")
    public Object inspect(@PathVariable long id, @RequestBody JsonNode body) {
        access.staff("orders:manage");
        return aftersales.inspect(Input.id(id), body);
    }

    @PostMapping("/api/admin/refunds/{id}/points")
    public Object points(@PathVariable long id, @RequestBody JsonNode body) {
        access.staff("finance:manage");
        return aftersales.points(Input.id(id), body);
    }

    @PostMapping("/api/admin/refunds/{id}/offline")
    public Object offline(@PathVariable long id, @RequestBody JsonNode body) {
        access.staff("finance:manage");
        return aftersales.offline(Input.id(id), body);
    }

    @PostMapping("/api/admin/refunds/{id}/wechat")
    public Object wechat(@PathVariable long id, @RequestBody JsonNode body) {
        access.staff("finance:manage");
        return aftersales.channel(Input.id(id), body, false);
    }

    @PostMapping("/api/admin/refunds/{id}/query")
    public Object query(@PathVariable long id, @RequestBody JsonNode body) {
        access.staff("finance:manage");
        return aftersales.channel(Input.id(id), body, true);
    }
}
