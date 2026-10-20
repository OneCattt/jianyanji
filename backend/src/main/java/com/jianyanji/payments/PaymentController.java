package com.jianyanji.payments;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Input;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 支付全部原始 HTTP 端点；用户归属和后台订单权限在控制器入口校验。 */
@RestController
public class PaymentController {
    /** 支付领域服务。 */
    private final PaymentService payments;
    /** 会话、权限与审计上下文。 */
    private final Access access;

    public PaymentController(PaymentService payments, Access access) {
        this.payments = payments;
        this.access = access;
    }

    @GetMapping("/api/payments/capabilities")
    public Object capabilities() {
        access.user();
        return payments.capabilities();
    }

    @PostMapping("/api/orders/{id}/pay/wechat")
    public Object start(@PathVariable long id) {
        return payments.start(Input.id(id), access.user());
    }

    @GetMapping("/api/orders/{id}/payment")
    public Object details(@PathVariable long id) {
        long userId = l(access.user(), "id");
        return payments.details(Input.id(id), userId, false);
    }

    @GetMapping("/api/admin/orders/{id}/payment")
    public Object adminDetails(@PathVariable long id) {
        access.staffAny("orders:manage", "finance:manage");
        return payments.details(Input.id(id), null, true);
    }

    /** 财务核实普通订单线下到账；金额与购买者均以原订单为准。 */
    @PostMapping("/api/admin/orders/{id}/receipt")
    public Object confirmReceipt(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("finance:manage"), "id");
        return payments.confirmOfflineReceipt(Input.id(id), body, actor);
    }

    @PostMapping("/api/orders/{id}/payment/query")
    public Object query(@PathVariable long id) {
        long userId = l(access.user(), "id");
        return payments.query(Input.id(id), userId);
    }

    @PostMapping("/api/admin/orders/{id}/payment/query")
    public Object adminQuery(@PathVariable long id) {
        access.staff("orders:manage");
        return payments.query(Input.id(id), null);
    }

    @PostMapping("/api/orders/{id}/payment/close")
    public Object close(@PathVariable long id) {
        long userId = l(access.user(), "id");
        return payments.close(Input.id(id), userId, "用户申请关闭原微信交易");
    }

    @PostMapping("/api/admin/orders/{id}/payment/close")
    public Object adminClose(@PathVariable long id, @RequestBody JsonNode body) {
        access.staff("orders:manage");
        Input.strict(body, "reason");
        return payments.close(Input.id(id), null, Input.text(body, "reason", 1, 200));
    }

    @PostMapping("/api/payments/wechat/notify")
    public Object notify(@RequestBody(required = false) byte[] raw, HttpServletRequest request) {
        return payments.notify(raw, request);
    }
}
