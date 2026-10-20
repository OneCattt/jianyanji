package com.jianyanji.orders;

import static com.jianyanji.common.J.l;

import com.fasterxml.jackson.databind.JsonNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Input;
import org.springframework.web.bind.annotation.*;

/** 发货管理、物流纠正与本人逐包收货接口。 */
@RestController
@RequestMapping("/api")
public class FulfillmentController {
    /** 分包履约服务。 */
    private final FulfillmentService fulfillment;
    /** 用户会话与订单管理权限。 */
    private final Access access;

    public FulfillmentController(FulfillmentService fulfillment, Access access) {
        this.fulfillment = fulfillment;
        this.access = access;
    }

    @GetMapping("/admin/orders/{id}/fulfillment")
    public Object fulfillment(@PathVariable long id) {
        access.staffAny("orders:manage", "finance:manage");
        return fulfillment.fulfillment(Input.id(id));
    }

    @PostMapping("/admin/orders/{id}/shipments")
    public Object ship(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("orders:manage"), "id");
        return fulfillment.ship(Input.id(id), body, actor);
    }

    @PutMapping("/admin/shipments/{id}")
    public Object correct(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("orders:manage"), "id");
        return fulfillment.correct(Input.id(id), body, actor);
    }

    @PostMapping("/orders/{id}/shipments/{shipmentId}/receive")
    public Object receive(@PathVariable long id, @PathVariable long shipmentId) {
        long userId = l(access.user(), "id");
        return fulfillment.receive(Input.id(id), Input.id(shipmentId), userId);
    }
}
