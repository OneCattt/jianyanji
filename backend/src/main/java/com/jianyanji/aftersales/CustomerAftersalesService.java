package com.jianyanji.aftersales;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Db;
import com.jianyanji.common.Input;
import com.jianyanji.orders.OrderService;
import org.springframework.stereotype.Service;

/** 本人在线售后受理；复用原售后事务、数量占用及原支付金额核定，不授予后台特批权限。 */
@Service
public class CustomerAftersalesService {
    /** 嵌套调用共用业务互斥行和同一事务。 */
    private final Db db;
    /** 本人订单归属校验。 */
    private final OrderService orders;
    /** 原售后核价及数量占用服务。 */
    private final AftersalesService aftersales;

    public CustomerAftersalesService(Db db, OrderService orders, AftersalesService aftersales) {
        this.db = db;
        this.orders = orders;
        this.aftersales = aftersales;
    }

    /** 用户仅提交商品数量及原因；不允许自填金额、审核结果或超期特批原因。 */
    public ObjectNode request(JsonNode body, long userId) {
        Input.strict(body, "orderId", "items", "reason", "requestKey");
        long orderId = Input.integer(body, "orderId", 1, 9007199254740991L);
        Input.array(body, "items", 1, 50);
        ObjectNode command = ((ObjectNode) body).deepCopy();
        command.put("shippingCents", 0);
        return db.tx(
                () -> {
                    // 必须先检查本人归属，幂等键重放也不得绕过权限。
                    orders.owned(orderId, userId);
                    return aftersales.request(command, obj("id", userId, "permissions", "[]"));
                });
    }
}
