package com.jianyanji.catalog;

import static com.jianyanji.common.J.l;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Input;
import org.springframework.web.bind.annotation.*;

/** 品牌内容、分类和商品 HTTP 接口，保留原 /api 路径与权限。 */
@RestController
@RequestMapping("/api")
public class CatalogController {
    /** 商品领域服务。 */
    private final CatalogService catalog;
    /** 会话认证与权限检查。 */
    private final Access access;

    public CatalogController(CatalogService catalog, Access access) {
        this.catalog = catalog;
        this.access = access;
    }

    @GetMapping("/public/content")
    public Object publicContent() {
        return catalog.publicContent();
    }

    @GetMapping("/admin/content")
    public Object content() {
        access.staff("content:manage");
        JsonNode value = catalog.content();
        return value == null || value.isNull() ? NullNode.getInstance() : value;
    }

    @PutMapping("/admin/content")
    public Object saveContent(@RequestBody JsonNode body) {
        long actor = l(access.staff("content:manage"), "id");
        return catalog.saveContent(body, actor);
    }

    @GetMapping("/public/categories")
    public Object publicCategories() {
        return catalog.categories(false);
    }

    @GetMapping("/admin/categories")
    public Object categories() {
        access.staff("catalog:manage");
        return catalog.categories(true);
    }

    @PostMapping("/admin/categories")
    public Object createCategory(@RequestBody JsonNode body) {
        return catalog.saveCategory(null, body, l(access.staff("catalog:manage"), "id"));
    }

    @PutMapping("/admin/categories/{id}")
    public Object updateCategory(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("catalog:manage"), "id");
        return catalog.saveCategory(Input.id(id), body, actor);
    }

    @GetMapping("/sales/candidate")
    public Object salesCandidate(@RequestParam String shareCode) {
        return catalog.salesCandidate(shareCode);
    }

    @GetMapping("/products")
    public Object products(@RequestParam(required = false) Long categoryId) {
        ObjectNode user = access.optionalUser();
        return catalog.products(categoryId == null ? null : Input.id(categoryId), user);
    }

    @GetMapping("/products/{id}")
    public Object product(@PathVariable long id) {
        ObjectNode user = access.optionalUser();
        return catalog.product(Input.id(id), user);
    }

    @GetMapping("/admin/products")
    public Object products(@RequestParam java.util.Map<String, String> query) {
        access.staff("catalog:manage");
        return query.containsKey("page") ? catalog.adminPage(query) : catalog.adminProducts();
    }

    @PostMapping("/admin/products")
    public Object createProduct(@RequestBody JsonNode body) {
        return catalog.saveProduct(null, body, l(access.staff("catalog:manage"), "id"));
    }

    @PutMapping("/admin/products/{id}")
    public Object updateProduct(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("catalog:manage"), "id");
        return catalog.saveProduct(Input.id(id), body, actor);
    }

    @PostMapping("/admin/products/{id}/remove")
    public Object removeProduct(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("catalog:manage"), "id");
        return catalog.removeProduct(Input.id(id), body, actor);
    }
}
