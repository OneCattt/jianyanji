package com.jianyanji.orders;

import static com.jianyanji.common.J.l;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Input;
import org.springframework.web.bind.annotation.*;

/** 配送配置、地址、购物车、报价和订单 HTTP 接口，入口先验证用户或后台权限。 */
@RestController
@RequestMapping("/api")
public class OrderController {
    /** 订单领域服务。 */
    private final OrderService orders;
    /** 用户会话与后台权限。 */
    private final Access access;

    public OrderController(OrderService orders, Access access) {
        this.orders = orders;
        this.access = access;
    }

    @GetMapping("/admin/shipping-settings")
    public Object shipping() {
        access.staff("orders:manage");
        JsonNode value = orders.shipping();
        return value == null || value.isNull() ? NullNode.getInstance() : value;
    }

    @PutMapping("/admin/shipping-settings")
    public Object saveShipping(@RequestBody JsonNode body) {
        return orders.saveShipping(body, l(access.staff("orders:manage"), "id"));
    }

    @GetMapping("/addresses")
    public Object addresses() {
        return orders.addresses(l(access.user(), "id"));
    }

    @PostMapping("/addresses")
    public Object createAddress(@RequestBody JsonNode body) {
        return orders.saveAddress(null, body, l(access.user(), "id"));
    }

    @PutMapping("/addresses/{id}")
    public Object updateAddress(@PathVariable long id, @RequestBody JsonNode body) {
        long userId = l(access.user(), "id");
        return orders.saveAddress(Input.id(id), body, userId);
    }

    @PostMapping("/addresses/{id}/remove")
    public Object removeAddress(@PathVariable long id) {
        long userId = l(access.user(), "id");
        return orders.removeAddress(Input.id(id), userId);
    }

    @GetMapping("/cart")
    public Object cart() {
        return orders.cart(access.user());
    }

    @PutMapping("/cart/{id}")
    public Object saveCart(@PathVariable long id, @RequestBody JsonNode body) {
        ObjectNode user = access.user();
        return orders.saveCart(Input.id(id), body, user);
    }

    @PostMapping("/cart/{id}/remove")
    public Object removeCart(@PathVariable long id) {
        long userId = l(access.user(), "id");
        return orders.removeCart(Input.id(id), userId);
    }

    @PostMapping("/checkout/quote")
    public Object quote(@RequestBody JsonNode body) {
        return orders.quote(body, access.user());
    }

    @PostMapping("/orders")
    public Object create(@RequestBody JsonNode body) {
        return orders.create(body, access.user());
    }

    @GetMapping("/orders")
    public Object orders() {
        return orders.orders(l(access.user(), "id"));
    }

    @GetMapping("/orders/{id}")
    public Object detail(@PathVariable long id) {
        long userId = l(access.user(), "id");
        return orders.detail(Input.id(id), userId);
    }

    @GetMapping("/admin/orders")
    public Object adminOrders(@RequestParam java.util.Map<String, String> query) {
        access.staffAny("orders:manage", "finance:manage");
        return query.containsKey("page") ? orders.adminPage(query) : orders.orders(null);
    }

    @PostMapping("/orders/{id}/cancel")
    public Object cancel(@PathVariable long id) {
        long userId = l(access.user(), "id");
        return orders.cancel(Input.id(id), userId);
    }
}
