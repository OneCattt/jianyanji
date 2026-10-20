package com.jianyanji.content;

import static com.jianyanji.common.J.l;

import com.fasterxml.jackson.databind.JsonNode;
import com.jianyanji.common.*;
import org.springframework.web.bind.annotation.*;

/** 品牌种草公开读与后台审核发布接口；写操作独立校验内容权限。 */
@RestController
@RequestMapping("/api")
public class BrandContentController {
    /** 内容生命周期服务。 */
    private final BrandContentService service;
    /** 后台认证与权限上下文。 */
    private final Access access;

    public BrandContentController(BrandContentService service, Access access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/public/posts")
    public Object published(
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "20") int limit) {
        return service.published(before, limit);
    }

    @GetMapping("/public/posts/{id}")
    public Object detail(@PathVariable long id) {
        return service.detail(Input.id(id));
    }

    @GetMapping("/admin/posts")
    public Object list(@RequestParam java.util.Map<String, String> query) {
        access.staff("content:manage");
        return query.containsKey("page") ? service.adminPage(query) : service.adminList();
    }

    @PostMapping("/admin/posts")
    public Object create(@RequestBody JsonNode body) {
        return service.save(null, body, l(access.staff("content:manage"), "id"));
    }

    @PutMapping("/admin/posts/{id}")
    public Object update(@PathVariable long id, @RequestBody JsonNode body) {
        return service.save(Input.id(id), body, l(access.staff("content:manage"), "id"));
    }

    @PostMapping("/admin/posts/{id}/{action:review|publish|unpublish|remove}")
    public Object transition(
            @PathVariable long id, @PathVariable String action, @RequestBody JsonNode body) {
        return service.transition(
                Input.id(id), action, body, l(access.staff("content:manage"), "id"));
    }
}
