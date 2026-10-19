package com.jianyanji.orders;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.BusinessException;
import org.junit.jupiter.api.Test;

/** 结算、物流和地址的恶意输入及原有严格校验回归。 */
class OrderInputTest {
    @Test
    void duplicateSkuAndClientSuppliedPriceCannotEnterCheckout() {
        ObjectNode line = obj("skuId", 1, "quantity", 2);
        assertThrows(
                BusinessException.class,
                () -> OrderInput.checkout(obj("addressId", 1, "items", arr(line, line))));
        line.put("unitCents", 1);
        assertThrows(
                BusinessException.class,
                () -> OrderInput.checkout(obj("addressId", 1, "items", arr(line))));
        assertThrows(
                BusinessException.class,
                () ->
                        OrderInput.checkout(
                                obj(
                                        "addressId",
                                        1,
                                        "items",
                                        arr(obj("skuId", 1, "quantity", "2")))));
    }

    @Test
    void quantitiesAndIdsRespectOriginalBoundaries() {
        assertEquals(1, OrderInput.entityId(json("1")));
        assertThrows(BusinessException.class, () -> OrderInput.entityId(json("9007199254740992")));
        assertThrows(BusinessException.class, () -> OrderInput.entityId(json("1.5")));
        assertThrows(
                BusinessException.class,
                () ->
                        OrderInput.checkout(
                                obj(
                                        "addressId",
                                        1,
                                        "items",
                                        arr(obj("skuId", 1, "quantity", 1000)))));
    }

    @Test
    void duplicateShipmentLineAndInvalidTrackingAreRejected() {
        ObjectNode line = obj("lineId", 1, "quantity", 1);
        ObjectNode body =
                obj(
                        "carrier",
                        "顺丰",
                        "tracking",
                        "SF1234",
                        "requestKey",
                        "ship-request-2026",
                        "reason",
                        "出库",
                        "lines",
                        arr(line, line));
        assertThrows(BusinessException.class, () -> OrderInput.logistics(body, true));
        body.set("lines", arr(line));
        body.put("tracking", "abc/123");
        assertThrows(BusinessException.class, () -> OrderInput.logistics(body, true));
    }

    @Test
    void mobileNumberIsNotSilentlyTrimmedAndDefaultsAreRequiredBooleans() {
        ObjectNode address =
                obj(
                        "recipient",
                        "收货人",
                        "phone",
                        " 13800000000",
                        "province",
                        "浙江省",
                        "city",
                        "杭州市",
                        "district",
                        "西湖区",
                        "detail",
                        "1号",
                        "isDefault",
                        true);
        assertThrows(BusinessException.class, () -> OrderInput.address(address));
        address.put("phone", "13800000000");
        address.put("isDefault", 1);
        assertThrows(BusinessException.class, () -> OrderInput.address(address));
    }
}
