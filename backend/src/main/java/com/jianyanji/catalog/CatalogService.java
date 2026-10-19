package com.jianyanji.catalog;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Db;
import com.jianyanji.common.Input;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 品牌内容、分类、商品与身份档价服务；金额单位为分，商品删除仅作逻辑标记。 */
@Service
public class CatalogService {
    /** 数据访问与串行写事务。 */
    private final Db db;
    /** 当前操作人及审计上下文。 */
    private final Access access;

    public CatalogService(Db db, Access access) {
        this.db = db;
        this.access = access;
    }

    /** 按有效身份返回单价（分）；该身份未定价时返回 null，禁止跨档兜底。 */
    public Long rolePrice(JsonNode sku, JsonNode user) {
        String identity = user == null ? "D" : effectiveIdentity(user);
        String field =
                "B".equals(identity)
                        ? "principal_cents"
                        : "C".equals(identity) ? "store_cents" : "retail_cents";
        return has(sku, field) ? l(sku, field) : null;
    }

    /** 输出商品与规格；管理端显示实际库存、占用量及完整档价，客户端只显示可用量。 */
    public ObjectNode productView(ObjectNode product, JsonNode user, boolean admin) {
        ArrayNode skus = arr();
        for (ObjectNode sku :
                db.list(
                        "SELECT * FROM skus WHERE product_id=? AND is_deleted=0 ORDER BY id",
                        l(product, "id"))) {
            Long price = rolePrice(sku, user);
            long availableStock = l(sku, "stock") - l(sku, "reserved");
            ObjectNode view =
                    obj(
                            "id",
                            sku.get("id"),
                            "name",
                            sku.get("name"),
                            "unit",
                            sku.get("unit"),
                            "stock",
                            availableStock,
                            "active",
                            b(sku, "active"),
                            "priceCents",
                            price,
                            "retailCents",
                            sku.get("retail_cents"),
                            "available",
                            b(product, "active")
                                    && b(sku, "active")
                                    && availableStock > 0
                                    && price != null);
            if (admin) {
                view.set("storeCents", sku.get("store_cents"));
                view.set("principalCents", sku.get("principal_cents"));
                view.set("stock", sku.get("stock"));
                view.set("reserved", sku.get("reserved"));
            }
            skus.add(view);
        }
        return obj(
                "id",
                product.get("id"),
                "categoryId",
                product.get("category_id"),
                "name",
                product.get("name"),
                "subtitle",
                product.get("subtitle"),
                "description",
                product.get("description"),
                "image",
                product.get("image"),
                "detailImage",
                product.get("detail_image"),
                "active",
                b(product, "active"),
                "featured",
                b(product, "featured"),
                "version",
                product.get("version"),
                "skus",
                skus);
    }

    public JsonNode content() {
        return db.setting("content");
    }

    public ObjectNode publicContent() {
        JsonNode value = content();
        return obj("configured", value != null && !value.isNull(), "content", value);
    }

    public ObjectNode saveContent(JsonNode body, long actor) {
        Input.strict(
                body,
                "reason",
                "brandName",
                "introduction",
                "heroTitle",
                "heroSubtitle",
                "heroImage",
                "customerService",
                "purchaseNotes",
                "companyName",
                "bankName",
                "bankAccount",
                "transferNotes");
        String reason = Input.reason(body);
        ObjectNode value =
                obj(
                        "brandName",
                        Input.text(body, "brandName", 1, 30),
                        "introduction",
                        Input.text(body, "introduction", 1, 2000),
                        "heroTitle",
                        Input.text(body, "heroTitle", 1, 40),
                        "heroSubtitle",
                        Input.text(body, "heroSubtitle", 1, 100),
                        "heroImage",
                        image(body, "heroImage"),
                        "customerService",
                        Input.text(body, "customerService", 0, 300),
                        "purchaseNotes",
                        Input.text(body, "purchaseNotes", 0, 2000),
                        "companyName",
                        Input.text(body, "companyName", 0, 100),
                        "bankName",
                        Input.text(body, "bankName", 0, 100),
                        "bankAccount",
                        Input.text(body, "bankAccount", 0, 100),
                        "transferNotes",
                        Input.text(body, "transferNotes", 0, 2000));
        int filled =
                (s(value, "companyName").isEmpty() ? 0 : 1)
                        + (s(value, "bankName").isEmpty() ? 0 : 1)
                        + (s(value, "bankAccount").isEmpty() ? 0 : 1);
        Input.require(filled == 0 || filled == 3, "公司收款资料需完整填写或全部留空");
        return db.tx(
                () -> {
                    db.putSetting("content", value, actor);
                    access.audit("content.update", "content", "brand", reason);
                    return value;
                });
    }

    public List<ObjectNode> categories(boolean admin) {
        return db.list(
                "SELECT id,name,sort,active FROM categories WHERE is_deleted=0"
                        + (admin ? "" : " AND active=1")
                        + " ORDER BY sort,id");
    }

    public ObjectNode saveCategory(Long id, JsonNode body, long actor) {
        Input.strict(body, "name", "sort", "active", "reason");
        String name = Input.text(body, "name", 1, 40), reason = Input.reason(body);
        long sort = Input.integer(body, "sort", 0, 9999);
        boolean active = Input.bool(body, "active");
        return db.tx(
                () -> {
                    long saved;
                    if (id != null) {
                        if (db.one("SELECT id FROM categories WHERE id=? AND is_deleted=0", id)
                                == null) throw notFound("分类不存在");
                        db.update(
                                "UPDATE categories SET name=?,sort=?,active=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                name,
                                sort,
                                active ? 1 : 0,
                                actor,
                                id);
                        saved = id;
                    } else
                        saved =
                                db.insert(
                                        "INSERT INTO categories(name,sort,active,create_user_id,update_user_id) VALUES(?,?,?,?,?)",
                                        name,
                                        sort,
                                        active ? 1 : 0,
                                        actor,
                                        actor);
                    access.audit("category.save", "category", saved, reason);
                    return obj("id", saved);
                });
    }

    public ObjectNode products(Long category, JsonNode user) {
        List<ObjectNode> rows =
                category == null
                        ? db.list(
                                "SELECT p.* FROM products p JOIN categories c ON c.id=p.category_id AND c.is_deleted=0 AND c.active=1 WHERE p.is_deleted=0 AND p.active=1 ORDER BY p.id DESC LIMIT 200")
                        : db.list(
                                "SELECT p.* FROM products p JOIN categories c ON c.id=p.category_id AND c.is_deleted=0 AND c.active=1 WHERE p.is_deleted=0 AND p.active=1 AND p.category_id=? ORDER BY p.id DESC LIMIT 200",
                                category);
        ArrayNode items = arr();
        for (ObjectNode row : rows) items.add(productView(row, user, false));
        return obj("identity", user == null ? "D" : effectiveIdentity(user), "items", items);
    }

    public ObjectNode product(long id, JsonNode user) {
        ObjectNode row =
                db.one(
                        "SELECT p.* FROM products p JOIN categories c ON c.id=p.category_id AND c.is_deleted=0 AND c.active=1 WHERE p.id=? AND p.is_deleted=0 AND p.active=1",
                        id);
        if (row == null) throw notFound("商品已下架或不存在");
        return productView(row, user, false);
    }

    public ArrayNode adminProducts() {
        ArrayNode result = arr();
        for (ObjectNode row :
                db.list("SELECT * FROM products WHERE is_deleted=0 ORDER BY id DESC LIMIT 200"))
            result.add(productView(row, null, true));
        return result;
    }

    public ObjectNode saveProduct(Long id, JsonNode body, long actor) {
        ObjectNode value = productInput(body);
        return db.tx(
                () -> {
                    if (db.one(
                                    "SELECT id FROM categories WHERE id=? AND is_deleted=0",
                                    l(value, "categoryId"))
                            == null) throw conflict("分类不存在");
                    long saved;
                    if (id != null) {
                        if (db.one("SELECT id FROM products WHERE id=? AND is_deleted=0", id)
                                == null) throw notFound("商品不存在");
                        db.update(
                                "UPDATE products SET category_id=?,name=?,subtitle=?,description=?,image=?,detail_image=?,active=?,featured=?,version=version+1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                l(value, "categoryId"),
                                s(value, "name"),
                                s(value, "subtitle"),
                                s(value, "description"),
                                s(value, "image"),
                                s(value, "detailImage"),
                                b(value, "active") ? 1 : 0,
                                b(value, "featured") ? 1 : 0,
                                actor,
                                id);
                        saved = id;
                    } else
                        saved =
                                db.insert(
                                        "INSERT INTO products(category_id,name,subtitle,description,image,detail_image,active,featured,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                                        l(value, "categoryId"),
                                        s(value, "name"),
                                        s(value, "subtitle"),
                                        s(value, "description"),
                                        s(value, "image"),
                                        s(value, "detailImage"),
                                        b(value, "active") ? 1 : 0,
                                        b(value, "featured") ? 1 : 0,
                                        actor,
                                        actor);
                    List<ObjectNode> existing =
                            db.list(
                                    "SELECT id,reserved FROM skus WHERE product_id=? AND is_deleted=0",
                                    saved);
                    Set<Long> retained = new HashSet<>();
                    for (JsonNode sku : value.withArray("skus")) {
                        Long skuId = has(sku, "id") ? l(sku, "id") : null;
                        Long retail = nullablePrice(sku, "retailCents"),
                                store = nullablePrice(sku, "storeCents"),
                                principal = nullablePrice(sku, "principalCents");
                        if (skuId != null) {
                            ObjectNode old =
                                    existing.stream()
                                            .filter(row -> l(row, "id") == skuId)
                                            .findFirst()
                                            .orElse(null);
                            if (old == null) throw conflict("规格不属于当前商品");
                            if (l(sku, "stock") < l(old, "reserved"))
                                throw conflict("库存不能低于待付款占用数量");
                            retained.add(skuId);
                            db.update(
                                    "UPDATE skus SET name=?,unit=?,retail_cents=?,store_cents=?,principal_cents=?,stock=?,active=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND product_id=? AND is_deleted=0",
                                    s(sku, "name"),
                                    s(sku, "unit"),
                                    retail,
                                    store,
                                    principal,
                                    l(sku, "stock"),
                                    b(sku, "active") ? 1 : 0,
                                    actor,
                                    skuId,
                                    saved);
                        } else
                            db.insert(
                                    "INSERT INTO skus(product_id,name,unit,retail_cents,store_cents,principal_cents,stock,active,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                                    saved,
                                    s(sku, "name"),
                                    s(sku, "unit"),
                                    retail,
                                    store,
                                    principal,
                                    l(sku, "stock"),
                                    b(sku, "active") ? 1 : 0,
                                    actor,
                                    actor);
                    }
                    for (ObjectNode old : existing) {
                        if (retained.contains(l(old, "id"))) continue;
                        if (l(old, "reserved") > 0) throw conflict("待付款订单占用的规格不能移除，请先停售");
                        db.update(
                                "UPDATE skus SET is_deleted=1,active=0,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                actor,
                                l(old, "id"));
                    }
                    access.audit("product.save", "product", saved, s(value, "reason"));
                    return obj("id", saved);
                });
    }

    public ObjectNode removeProduct(long id, JsonNode body, long actor) {
        Input.strict(body, "reason");
        String reason = Input.reason(body);
        return db.tx(
                () -> {
                    if (db.one(
                                    "SELECT id FROM skus WHERE product_id=? AND reserved>0 AND is_deleted=0 LIMIT 1",
                                    id)
                            != null) throw conflict("待付款订单占用的商品请先下架");
                    if (db.update(
                                    "UPDATE products SET is_deleted=1,active=0,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    actor,
                                    id)
                            == 0) throw notFound("商品不存在");
                    access.audit("product.remove", "product", id, reason);
                    return obj("ok", true);
                });
    }

    /** 严格校验并规范化商品和规格，保留 null 价格与可选规格编号。 */
    private ObjectNode productInput(JsonNode body) {
        Input.strict(
                body,
                "categoryId",
                "name",
                "subtitle",
                "description",
                "image",
                "detailImage",
                "active",
                "featured",
                "skus",
                "reason");
        ObjectNode value =
                obj(
                        "categoryId",
                        entityId(body.get("categoryId")),
                        "name",
                        Input.text(body, "name", 1, 80),
                        "subtitle",
                        Input.text(body, "subtitle", 0, 160),
                        "description",
                        Input.text(body, "description", 1, 8000),
                        "image",
                        image(body, "image"),
                        "detailImage",
                        image(body, "detailImage"),
                        "active",
                        Input.bool(body, "active"),
                        "featured",
                        Input.bool(body, "featured"),
                        "reason",
                        Input.reason(body));
        ArrayNode rows = Input.array(body, "skus", 1, 30), skus = arr();
        Set<Long> ids = new HashSet<>();
        for (JsonNode row : rows) {
            Input.strict(
                    row,
                    "id",
                    "name",
                    "unit",
                    "retailCents",
                    "storeCents",
                    "principalCents",
                    "stock",
                    "active");
            Long retail = nullablePrice(row, "retailCents"),
                    store = nullablePrice(row, "storeCents"),
                    principal = nullablePrice(row, "principalCents");
            Input.require(principal == null || store == null || principal <= store, "主理人价不得高于店主价");
            ObjectNode sku =
                    obj(
                            "name",
                            Input.text(row, "name", 1, 60),
                            "unit",
                            Input.text(row, "unit", 1, 10),
                            "retailCents",
                            retail,
                            "storeCents",
                            store,
                            "principalCents",
                            principal,
                            "stock",
                            Input.integer(row, "stock", 0, 10000000),
                            "active",
                            Input.bool(row, "active"));
            if (row.has("id")) {
                long skuId = entityId(row.get("id"));
                Input.require(ids.add(skuId), "规格不可重复");
                sku.put("id", skuId);
            }
            skus.add(sku);
        }
        value.set("skus", skus);
        return value;
    }

    /** 图片不作 trim，保持 Node 校验语义。 */
    private String image(JsonNode body, String key) {
        JsonNode value = body.get(key);
        Input.require(value != null && value.isTextual(), "图片地址必须为字符串");
        String image = value.asText();
        Input.require(
                image.length() <= 1000
                        && (image.matches("^/assets/[a-zA-Z0-9_./-]+$")
                                || image.startsWith("https://")),
                "请使用本站素材或 HTTPS 图片地址");
        return image;
    }

    /** 必须提供的可空价格，非空时为 1 至 100000000 分的整数。 */
    private Long nullablePrice(JsonNode body, String key) {
        Input.require(body.has(key), key + "不能为空缺失");
        return body.get(key).isNull() ? null : Input.integer(body, key, 1, 100000000);
    }

    /** 对齐原 entityId 的数字字符串兼容，最终限制在 JavaScript 安全整数范围。 */
    private long entityId(JsonNode node) {
        if (node == null
                || node.isNull()
                || !(node.isNumber() || node.isTextual() || node.isBoolean()))
            throw invalid("编号无效");
        try {
            java.math.BigDecimal value =
                    new java.math.BigDecimal(
                            node.isBoolean()
                                    ? (node.asBoolean() ? "1" : "0")
                                    : node.asText().trim());
            return Input.id(value.longValueExact());
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid("编号无效");
        }
    }
}
