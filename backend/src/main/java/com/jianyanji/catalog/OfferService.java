package com.jianyanji.catalog;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.jianyanji.common.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 固定活动/买赠套餐配置；明细和分摊由服务端展开，客户不能传价格或替换商品。 */
@RestController
public class OfferService {
    /** 设置、商品及成交来源访问。 */
    private final Db db;
    /** 后台权限及审计。 */
    private final Access access;

    public OfferService(Db db, Access access) {
        this.db = db;
        this.access = access;
    }

    @GetMapping("/api/offers")
    public Object offers() {
        ArrayNode result = arr();
        JsonNode config = db.setting("offers");
        if (config != null)
            for (JsonNode offer : config.path("offers"))
                if (b(offer, "active")) {
                    ObjectNode row = (ObjectNode) offer.deepCopy();
                    row.set("lines", items(l(offer, "id"), 1, "ship"));
                    result.add(row);
                }
        return obj("items", result);
    }

    @GetMapping("/api/admin/offers")
    public Object settings() {
        access.staff("catalog:manage");
        JsonNode v = db.setting("offers");
        return v == null ? obj("offers", arr()) : v;
    }

    @PutMapping("/api/admin/offers")
    public Object save(@RequestBody JsonNode body) {
        long actor = l(access.staff("catalog:manage"), "id");
        Input.strict(body, "offers", "reason");
        String reason = Input.text(body, "reason", 1, 2000);
        ArrayNode result = arr();
        Set<Long> ids = new HashSet<>();
        for (JsonNode offer : Input.array(body, "offers", 0, 100)) {
            Input.strict(offer, "id", "name", "amountCents", "active", "lines");
            long id = Input.integer(offer, "id", 1, 1000000),
                    amount = Input.integer(offer, "amountCents", 1, 100000000);
            Input.require(ids.add(id), "套餐编号不可重复");
            ArrayNode lines = arr();
            Set<String> skus = new HashSet<>();
            long sum = 0;
            for (JsonNode line : Input.array(offer, "lines", 1, 50)) {
                Input.strict(line, "skuId", "quantity", "unitCents", "isGift");
                long sku = Input.integer(line, "skuId", 1, 9007199254740991L),
                        qty = Input.integer(line, "quantity", 1, 999);
                boolean gift = Input.bool(line, "isGift");
                long price = Input.integer(line, "unitCents", gift ? 0 : 1, 100000000);
                Input.require(!gift || price == 0, "赠品单价必须为0");
                Input.require(skus.add(sku + ":" + gift), "同套餐同规格主品或赠品不可重复");
                if (db.one("SELECT id FROM skus WHERE id=? AND is_deleted=0", sku) == null)
                    throw conflict("套餐规格不存在");
                sum = Math.addExact(sum, Math.multiplyExact(qty, price));
                lines.add(obj("skuId", sku, "quantity", qty, "unitCents", price, "isGift", gift));
            }
            Input.require(sum == amount, "套餐明细货款分摊须等于固定套餐价");
            result.add(
                    obj(
                            "id",
                            id,
                            "name",
                            Input.text(offer, "name", 1, 80),
                            "amountCents",
                            amount,
                            "active",
                            Input.bool(offer, "active"),
                            "lines",
                            lines));
        }
        return db.tx(
                () -> {
                    ObjectNode value = obj("offers", result);
                    db.putSetting("offers", value, actor);
                    access.audit("offers.settings", "settings", "offers", reason);
                    return value;
                });
    }
    /** 仅后台保存的固定明细可进入结算；内部字段不可由普通结算接口传入。 */
    public ArrayNode items(long offerId, long count, String mode) {
        JsonNode found = null, config = db.setting("offers");
        if (config != null)
            for (JsonNode offer : config.path("offers"))
                if (l(offer, "id") == offerId && b(offer, "active")) found = offer;
        if (found == null) throw conflict("套餐已停售或不存在");
        Input.require(count >= 1 && count <= 99, "套餐购买数量须为1至99");
        Input.require(Arrays.asList("ship", "store").contains(mode), "套餐交付方式无效");
        ArrayNode items = arr();
        for (JsonNode line : found.path("lines")) {
            ObjectNode sku =
                    db.one(
                            "SELECT s.*,p.name AS product_name FROM skus s JOIN products p ON p.id=s.product_id AND p.is_deleted=0 WHERE s.id=? AND s.is_deleted=0",
                            l(line, "skuId"));
            if (sku == null) throw conflict("套餐原商品已不存在");
            long qty = Math.multiplyExact(count, l(line, "quantity"));
            Input.require(qty <= 999, "套餐单项数量超过999");
            items.add(
                    obj(
                            "skuId",
                            l(line, "skuId"),
                            "quantity",
                            qty,
                            "shipQuantity",
                            "store".equals(mode) ? 0 : qty,
                            "offerUnitCents",
                            l(line, "unitCents"),
                            "isGift",
                            b(line, "isGift"),
                            "lineKey",l(line,"skuId")+":"+b(line,"isGift"),
                            "bundleKey",
                            "offer:" + offerId,
                            "bundleName",
                            s(found, "name"),
                            "name",
                            s(sku, "product_name"),
                            "skuName",
                            s(sku, "name")));
        }
        return items;
    }
}
