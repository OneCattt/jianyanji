package com.jianyanji.catalog;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Db;
import com.jianyanji.common.Input;
import com.jianyanji.common.PageQuery;
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

    /** 后台按页查询全部有效记录，筛选和计数在数据库执行。 */
    public ObjectNode adminPage(java.util.Map<String, String> query) {
        return new PageQuery(query, "FROM products p WHERE p.is_deleted=0")
                .eq("active", "p.active")
                .eq("categoryId", "p.category_id")
                .keyword(
                        "p.name",
                        "p.subtitle",
                        "(SELECT c.name FROM categories c WHERE c.id=p.category_id AND c.is_deleted=0)",
                        "(SELECT GROUP_CONCAT(s.name) FROM skus s WHERE s.product_id=p.id AND s.is_deleted=0)")
                .select(db, "p.*", "p.id DESC", row -> productView(row, null, true));
    }

    public CatalogService(Db db, Access access) {
        this.db = db;
        this.access = access;
    }

    /** 使用独立销售分享口径核验候选，不以入驻邀请人替代销售代理。 */
    public ObjectNode salesCandidate(String code) {
        return new SalesAttributionService(db).candidate(code);
    }

    /** 按有效身份计算单价（分）；普通商品独立三档价格，福利品各身份同价，配置无效返回 null。 */
    public Long rolePrice(JsonNode sku, JsonNode user) {
        return priceForIdentity(sku, user == null ? "D" : effectiveIdentity(user));
    }

    /** 服务端唯一价格依据；直接读取规格身份价，单位为分，微信与积分共用。 */
    public Long priceForIdentity(JsonNode sku, String identity) {
        String type = productType(sku);
        if (!("regular".equals(type) || "welfare".equals(type))) return null;
        String field =
                "welfare".equals(type)
                        ? "welfare_cents"
                        : "B".equals(identity)
                                ? "principal_cents"
                                : "C".equals(identity) ? "store_cents" : "retail_cents";
        JsonNode value = sku.get(field);
        return value != null
                        && value.isIntegralNumber()
                        && value.canConvertToLong()
                        && value.longValue() > 0
                        && value.longValue() <= 100000000
                ? value.longValue()
                : null;
    }

    /** 未标记的既有 SKU 视为普通商品；历史订单不调用现价计算。 */
    public String productType(JsonNode sku) {
        return has(sku, "product_type") ? s(sku, "product_type") : "regular";
    }

    /** 输出商品与规格；管理端显示实际库存、占用量及完整档价，客户端只显示可用量。 */
    public ObjectNode productView(ObjectNode product, JsonNode user, boolean admin) {
        return productView(
                product,
                user,
                admin,
                db.list(
                        "SELECT s.*,(SELECT COALESCE(SUM(remaining_qty),0) FROM storage_lots l WHERE l.sku_id=s.id AND l.is_deleted=0) AS storage_locked FROM skus s WHERE product_id=? AND s.is_deleted=0 ORDER BY s.id",
                        l(product, "id")));
    }

    /** 使用调用方批量读取的规格，避免商品列表为每个商品重复查询。 */
    public ObjectNode productView(
            ObjectNode product, JsonNode user, boolean admin, List<ObjectNode> skuRows) {
        ArrayNode skus = arr();
        for (ObjectNode sku : skuRows) {
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
                            "productType",
                            productType(sku),
                            "welfareCents",
                            sku.get("welfare_cents"),
                            "available",
                            b(product, "active")
                                    && b(sku, "active")
                                    && availableStock > 0
                                    && price != null);
            view.put(
                    "storageEnabled",
                    b(sku, "storage_enabled") && "none".equals(s(sku, "storage_exclusion")));
            view.put("storageExclusion", s(sku, "storage_exclusion"));
            if (admin) {
                view.set("storeCents", json(priceForIdentity(sku, "C")));
                view.set("principalCents", json(priceForIdentity(sku, "B")));
                view.set("stock", sku.get("stock"));
                view.set("reserved", sku.get("reserved"));
                view.put("storageLocked", l(sku, "storage_locked"));
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

    /** 保存品牌资料；隐私主体全称限 0~100 字，联络方式限 0~300 字，成对填写或清空，省略时保留。 */
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
                "privacyOperator",
                "privacyContact",
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
        boolean bank = s(value, "bankName").isEmpty(), account = s(value, "bankAccount").isEmpty();
        Input.require(bank == account, "银行名称和账号须成对填写或全部留空");
        Input.require(bank || !s(value, "companyName").isEmpty(), "配置银行账户时须填写公司名称");
        // 必须区分字段省略与显式空值，单字段请求不得误当作旧请求保留配置。
        Input.require(
                body.has("privacyOperator") == body.has("privacyContact"), "运营主体全称与隐私联络方式需一并提交");
        if (body.has("privacyOperator")) {
            String operator = Input.text(body, "privacyOperator", 0, 100);
            String contact = Input.text(body, "privacyContact", 0, 300);
            Input.require(operator.isEmpty() == contact.isEmpty(), "运营主体全称与隐私联络方式需完整填写或全部留空");
            value.put("privacyOperator", operator);
            value.put("privacyContact", contact);
        }
        return db.tx(
                () -> {
                    // 旧请求省略隐私字段时，在写事务取得锁后读取最新配置，避免覆盖其他管理员的更新。
                    if (!body.has("privacyOperator") && !body.has("privacyContact")) {
                        JsonNode current = db.setting("content");
                        value.put("privacyOperator", s(current, "privacyOperator"));
                        value.put("privacyContact", s(current, "privacyContact"));
                    }
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
                                    "SELECT * FROM skus WHERE product_id=? AND is_deleted=0",
                                    saved);
                    Set<Long> retained = new HashSet<>();
                    for (JsonNode sku : value.withArray("skus")) {
                        Long skuId = has(sku, "id") ? l(sku, "id") : null;
                        Long retail = nullablePrice(sku, "retailCents"),
                                welfare = nullablePrice(sku, "welfareCents");
                        ObjectNode originalSku =
                                skuId == null
                                        ? null
                                        : existing.stream()
                                                .filter(row -> l(row, "id") == skuId)
                                                .findFirst()
                                                .orElse(null);
                        Long store =
                                sku.has("storeCents")
                                        ? nullablePrice(sku, "storeCents")
                                        : originalSku == null
                                                ? null
                                                : (has(originalSku, "store_cents")
                                                        ? l(originalSku, "store_cents")
                                                        : null);
                        Long principal =
                                sku.has("principalCents")
                                        ? nullablePrice(sku, "principalCents")
                                        : originalSku == null
                                                ? null
                                                : (has(originalSku, "principal_cents")
                                                        ? l(originalSku, "principal_cents")
                                                        : null);
                        if (store != null && principal != null && principal > store)
                            throw conflict("19800档价格不能高于2980档价格，否则订货价差为负");
                        long savedSku;
                        if (skuId != null) {
                            ObjectNode old =
                                    existing.stream()
                                            .filter(row -> l(row, "id") == skuId)
                                            .findFirst()
                                            .orElse(null);
                            if (old == null) throw conflict("规格不属于当前商品");
                            if (has(sku, "stock")) {
                                if (!has(sku, "expectedStock")
                                        || l(sku, "expectedStock") != l(old, "stock"))
                                    throw conflict("STOCK_CHANGED", "库存已变化或缺少原库存信息，请刷新商品后重新调整");
                                if (l(sku, "stock") < l(old, "reserved"))
                                    throw conflict("库存不能低于待付款占用数量");
                            }
                            retained.add(skuId);
                            savedSku = skuId;
                            db.update(
                                    "UPDATE skus SET name=?,unit=?,retail_cents=?,store_cents=?,principal_cents=?,product_type=?,welfare_cents=?,stock=COALESCE(?,stock),active=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND product_id=? AND is_deleted=0",
                                    s(sku, "name"),
                                    s(sku, "unit"),
                                    retail,
                                    store,
                                    principal,
                                    s(sku, "productType"),
                                    welfare,
                                    has(sku, "stock") ? l(sku, "stock") : null,
                                    b(sku, "active") ? 1 : 0,
                                    actor,
                                    skuId,
                                    saved);
                        } else
                            savedSku =
                                    db.insert(
                                            "INSERT INTO skus(product_id,name,unit,retail_cents,store_cents,principal_cents,product_type,welfare_cents,stock,active,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                                            saved,
                                            s(sku, "name"),
                                            s(sku, "unit"),
                                            retail,
                                            store,
                                            principal,
                                            s(sku, "productType"),
                                            welfare,
                                            l(sku, "stock"),
                                            b(sku, "active") ? 1 : 0,
                                            actor,
                                            actor);
                        if (sku.has("storageEnabled"))
                            db.update(
                                    "UPDATE skus SET storage_enabled=?,storage_exclusion=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    b(sku, "storageEnabled") ? 1 : 0,
                                    s(sku, "storageExclusion"),
                                    actor,
                                    savedSku);
                    }
                    for (ObjectNode old : existing) {
                        if (retained.contains(l(old, "id"))) continue;
                        if (db.one(
                                        "SELECT id FROM storage_lots WHERE sku_id=? AND remaining_qty>0 AND is_deleted=0 LIMIT 1",
                                        l(old, "id"))
                                != null) throw conflict("有寄存货品的规格不能移除，请先停售");
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
                                    "SELECT l.id FROM storage_lots l JOIN skus s ON s.id=l.sku_id AND s.is_deleted=0 WHERE s.product_id=? AND l.remaining_qty>0 AND l.is_deleted=0 LIMIT 1",
                                    id)
                            != null) throw conflict("商品仍有用户寄存库存，请先停售，不得移除");

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

    /**
     * 严格校验商品和规格，保留 null 价格。已有规格可省略 stock 以保留当前库存； 主动调整须携带 expectedStock 原总库存，数量按规格 unit 计量且为 0 至
     * 10000000 的整数。
     */
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
                    "productType",
                    "welfareCents",
                    "storeCents",
                    "principalCents",
                    "storageEnabled",
                    "storageExclusion",
                    "stock",
                    "expectedStock",
                    "active");
            String type = Input.text(row, "productType", 1, 20);
            Input.require("regular".equals(type) || "welfare".equals(type), "商品类型无效");
            Long retail = nullablePrice(row, "retailCents"),
                    welfare = nullablePrice(row, "welfareCents");
            ObjectNode sku =
                    obj(
                            "name",
                            Input.text(row, "name", 1, 60),
                            "unit",
                            Input.text(row, "unit", 1, 10),
                            "retailCents",
                            retail,
                            "productType",
                            type,
                            "welfareCents",
                            welfare,
                            "active",
                            Input.bool(row, "active"));
            for (String field : new String[] {"storeCents", "principalCents"})
                if (row.has(field)) sku.set(field, json(nullablePrice(row, field)));
            if (row.has("storageEnabled")) {
                String exclusion =
                        row.has("storageExclusion")
                                ? Input.text(row, "storageExclusion", 1, 20)
                                : "none";
                Input.require(
                        java.util.Arrays.asList("none", "flash", "sample", "near_expiry")
                                .contains(exclusion),
                        "寄存禁用分类无效");
                boolean enabled = Input.bool(row, "storageEnabled");
                Input.require(!enabled || "none".equals(exclusion), "秒杀、小样、临期商品不能寄存");
                sku.put("storageEnabled", enabled);
                sku.put("storageExclusion", exclusion);
            }
            if (!row.has("id") || row.has("stock"))
                sku.put("stock", Input.integer(row, "stock", 0, 10000000));
            if (row.has("expectedStock")) {
                Input.require(row.has("id") && row.has("stock"), "原库存仅用于调整已有规格库存");
                sku.put("expectedStock", Input.integer(row, "expectedStock", 0, 10000000));
            }
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
