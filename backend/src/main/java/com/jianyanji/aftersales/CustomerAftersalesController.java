package com.jianyanji.aftersales;

import static com.jianyanji.common.J.l;

import com.fasterxml.jackson.databind.JsonNode;
import com.jianyanji.common.Access;
import org.springframework.web.bind.annotation.*;

/** 登录购买者提交本人订单的售后申请，提交成功仅代表受理。 */
@RestController
public class CustomerAftersalesController {
    /** 本人售后受理服务。 */
    private final CustomerAftersalesService service;
    /** 从服务端会话取得购买者编号。 */
    private final Access access;

    public CustomerAftersalesController(CustomerAftersalesService service, Access access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/api/refunds")
    public Object request(@RequestBody JsonNode body) {
        return service.request(body, l(access.user(), "id"));
    }
}
