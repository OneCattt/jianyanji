package com.jianyanji.orders;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.catalog.CatalogService;
import com.jianyanji.common.Access;
import com.jianyanji.common.Db;
import com.jianyanji.common.Input;
import com.jianyanji.common.PageQuery;
import com.jianyanji.payments.PaymentService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;

/** 地址、购物车、报价、订单及履约状态服务；写入全部经数据库互斥事务。 */
@Service
public class OrderService {
    /** 库存及履约异常日志，关联订单、规格与请求编号。 */
    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);
    /** 数据访问与事务。 */
    private final Db db;
    /** 操作人、请求编号和审计。 */
    private final Access access;
    /** 商品身份档价。 */
    private final CatalogService catalog;
    /** 支付未决交易检查，延迟注入避免订单支付相互依赖。 */
    private final PaymentService payments;

    /** 后台按页查询全部有效记录，筛选和计数在数据库执行。 */
    public ObjectNode adminPage(java.util.Map<String, String> query) {
        expire();
        return new PageQuery(
                        query,
                        "FROM orders WHERE is_deleted=0"
                                + ("true".equals(query.get("paidOnly"))
                                        ? " AND payment_state IN ('paid','partially_refunded')"
                                        : ""))
                .eq("status", "status")
                .eq("paymentState", "payment_state")
                .eq("kind", "kind")
                .keyword(
                        "number",
                        "CAST(id AS CHAR)",
                        "CAST(user_id AS CHAR)",
                        "JSON_UNQUOTE(JSON_EXTRACT(snapshot,'$.address.recipient'))",
                        "JSON_UNQUOTE(JSON_EXTRACT(snapshot,'$.address.phone'))",
                        "JSON_EXTRACT(snapshot,'$.lines[*].name')",
                        "JSON_EXTRACT(snapshot,'$.lines[*].skuName')")
                .select(db, "*", "id DESC", this::view);
    }

    public OrderService(
            Db db, Access access, CatalogService catalog, @Lazy PaymentService payments) {
        this.db = db;
        this.access = access;
        this.catalog = catalog;
        this.payments = payments;
    }

    /** 读取未删除订单；userId 非空时强制检查归属，未命中统一返回 404。 */
    public ObjectNode owned(long id, Long userId) {
        ObjectNode row =
                userId == null
                        ? db.one("SELECT * FROM orders WHERE id=? AND is_deleted=0", id)
                        : db.one(
                                "SELECT * FROM orders WHERE id=? AND is_deleted=0 AND user_id=?",
                                id,
                                userId);
        if (row == null) throw notFound("订单不存在");
        return row;
    }

    /** 普通订单付款前复核当前身份和店主归属；已付款历史订单始终保留原快照。 */
    public void assertRelationsCurrent(ObjectNode order) {
        if (!"goods".equals(s(order, "kind"))
                || !("unpaid".equals(s(order, "payment_state"))
                        || "failed".equals(s(order, "payment_state")))) return;
        ObjectNode buyer =
                db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", l(order, "user_id"));
        JsonNode snapshot = parse(s(order, "snapshot"));
        if (buyer == null
                || !effectiveIdentity(buyer).equals(s(snapshot, "identity"))
                || ("C".equals(s(snapshot, "identity"))
                        && l(buyer, "principal_id") != l(snapshot, "principalId"))) {
            throw conflict("ORDER_RELATIONS_CHANGED", "购买身份或归属主理人已变化，请重新确认订单");
        }
    }

    /** 输出原订单字段、冻结快照、明细及按编号排序的包裹。 */
    public ObjectNode view(ObjectNode order) {
        ObjectNode result = order.deepCopy();
        result.set("snapshot", parse(s(order, "snapshot")));
        result.set(
                "lines",
                json(
                        db.list(
                                "SELECT ol.*,(SELECT COALESCE(SUM(remaining_qty),0) FROM storage_lots l WHERE l.line_id=ol.id AND l.is_deleted=0) AS storage_remaining,(SELECT COALESCE(SUM(pl.quantity),0) FROM pickup_lines pl JOIN pickup_orders po ON po.id=pl.pickup_id AND po.is_deleted=0 WHERE pl.line_id=ol.id AND pl.lot_id IS NULL AND pl.is_deleted=0 AND po.status IN ('review','approved')) AS pickup_held FROM order_lines ol WHERE order_id=? AND ol.is_deleted=0 ORDER BY id",
                                l(order, "id"))));
        ArrayNode shipments = arr();
        for (ObjectNode shipment :
                db.list(
                        "SELECT id,carrier,tracking,`lines`,shipped_at,received_at,pickup_id FROM shipments WHERE order_id=? AND is_deleted=0 ORDER BY id",
                        l(order, "id"))) {
            shipment.set("lines", parse(s(shipment, "lines")));
            shipments.add(shipment);
        }
        result.set("shipments", shipments);
        result.set(
                "pickups",
                json(
                        db.list(
                                "SELECT DISTINCT p.id,p.number,p.status FROM pickup_orders p JOIN pickup_lines pl ON pl.pickup_id=p.id AND pl.is_deleted=0 JOIN order_lines ol ON ol.id=pl.line_id AND ol.is_deleted=0 WHERE ol.order_id=? AND p.is_deleted=0 ORDER BY p.id",
                                l(order, "id"))));
        return result;
    }

    /** 仅释放 pending 且 unpaid/failed 的订单；事务内重读防止重复释放与过期快照覆盖已支付状态。 */
    public boolean release(ObjectNode order) {
        return db.tx(
                () -> {
                    ObjectNode current = owned(l(order, "id"), null);
                    if (!"pending".equals(s(current, "status"))
                            || !("unpaid".equals(s(current, "payment_state"))
                                    || "failed".equals(s(current, "payment_state")))) return false;
                    long actor = currentActor();
                    for (ObjectNode line :
                            db.list(
                                    "SELECT sku_id,quantity FROM order_lines WHERE order_id=? AND is_deleted=0",
                                    l(current, "id"))) {
                        long quantity = l(line, "quantity"), skuId = l(line, "sku_id");
                        if (db.update(
                                        "UPDATE skus SET reserved=reserved-?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0 AND reserved>=?",
                                        quantity,
                                        actor,
                                        skuId,
                                        quantity)
                                != 1) {
                            LOG.error(
                                    "订单库存释放失败 requestId={} orderId={} skuId={} quantity={}",
                                    currentRequestId(),
                                    l(current, "id"),
                                    skuId,
                                    quantity);
                            throw conflict("STOCK_RESERVATION_MISMATCH", "库存占用记录不一致，请联系品牌方处理");
                        }
                    }
                    new com.jianyanji.storage.StorageService(db, access)
                            .releaseUnpaid(l(current, "id"), actor);
                    db.update(
                            "UPDATE orders SET status='closed',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0 AND status='pending'",
                            actor,
                            l(current, "id"));
                    if ("package".equals(s(current, "kind"))) {
                        db.update(
                                "UPDATE onboardings SET status='rejected',review_reason='原待付款订单已关闭，请重新申请',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE order_id=? AND status='submitted' AND is_deleted=0",
                                actor,
                                l(current, "id"));
                    }
                    return true;
                });
    }

    /** 过期释放仅覆盖未发起支付或支付已明确失败的订单，processing/unknown 交由支付查询。 */
    public void expire() {
        db.tx(
                () -> {
                    for (ObjectNode order :
                            db.list(
                                    "SELECT * FROM orders WHERE status='pending' AND payment_state IN ('unpaid','failed') AND expires_at<? AND is_deleted=0",
                                    now())) release(order);
                });
    }

    /** 本人列表仅清理本人的过期订单；无过期项时直接返回，不获取全局业务写锁。 */
    public void expireForUser(long userId) {
        String expiredAt = now();
        String condition =
                " FROM orders WHERE user_id=? AND status='pending'"
                        + " AND payment_state IN ('unpaid','failed') AND expires_at<? AND is_deleted=0";
        if (db.one("SELECT id" + condition + " LIMIT 1", userId, expiredAt) == null) return;
        db.tx(
                () -> {
                    // 获取写锁后重新查询，沿用 release 的状态复核及库存释放规则。
                    for (ObjectNode order : db.list("SELECT *" + condition, userId, expiredAt))
                        release(order);
                });
    }

    /** 与 Node 每三十秒清理行为一致，无微信商户配置时仍释放明确未支付的占用。 */
    @Scheduled(fixedDelayString = "${orders.expire-delay-ms:30000}")
    public void scheduledExpire() {
        if (com.jianyanji.common.MaintenanceMode.enabled()) return;
        try {
            expire();
        } catch (RuntimeException exception) {
            LOG.error("待付款订单过期释放失败 requestId=order-expire，事务已回滚，下一轮重试", exception);
        }
    }

    /** 定时或支付补偿任务没有 HTTP 会话时，审计操作人为系统 0。 */
    private long currentActor() {
        return RequestContextHolder.getRequestAttributes() == null ? 0 : access.actor();
    }

    /** 后台任务使用稳定来源标识，HTTP 调用保留原请求编号。 */
    private String currentRequestId() {
        return RequestContextHolder.getRequestAttributes() == null
                ? "order-system"
                : access.requestId();
    }

    public JsonNode shipping() {
        return db.setting("shipping");
    }

    public ObjectNode saveShipping(JsonNode body, long actor) {
        ObjectNode value = OrderInput.shipping(body);
        String reason = Input.reason(body);
        return db.tx(
                () -> {
                    db.putSetting("shipping", value, actor);
                    access.audit("shipping.settings", "shipping", "settings", reason);
                    return value;
                });
    }

    public List<ObjectNode> addresses(long userId) {
        return db.list(
                "SELECT * FROM addresses WHERE user_id=? AND is_deleted=0 ORDER BY is_default DESC,id DESC",
                userId);
    }

    public ObjectNode saveAddress(Long id, JsonNode body, long userId) {
        ObjectNode value = OrderInput.address(body);
        return db.tx(
                () -> {
                    if (id != null
                            && db.one(
                                            "SELECT id FROM addresses WHERE id=? AND user_id=? AND is_deleted=0",
                                            id,
                                            userId)
                                    == null) throw notFound("地址不存在");
                    if (b(value, "isDefault"))
                        db.update(
                                "UPDATE addresses SET is_default=0,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE user_id=? AND is_deleted=0",
                                userId,
                                userId);
                    long saved;
                    if (id != null) {
                        db.update(
                                "UPDATE addresses SET recipient=?,phone=?,province=?,city=?,district=?,detail=?,is_default=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=? AND is_deleted=0",
                                s(value, "recipient"),
                                s(value, "phone"),
                                s(value, "province"),
                                s(value, "city"),
                                s(value, "district"),
                                s(value, "detail"),
                                b(value, "isDefault") ? 1 : 0,
                                userId,
                                id,
                                userId);
                        saved = id;
                    } else
                        saved =
                                db.insert(
                                        "INSERT INTO addresses(user_id,recipient,phone,province,city,district,detail,is_default,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                                        userId,
                                        s(value, "recipient"),
                                        s(value, "phone"),
                                        s(value, "province"),
                                        s(value, "city"),
                                        s(value, "district"),
                                        s(value, "detail"),
                                        b(value, "isDefault") ? 1 : 0,
                                        userId,
                                        userId);
                    access.audit("address.save", "address", saved, "维护本人收货地址");
                    return obj("id", saved);
                });
    }

    public ObjectNode removeAddress(long id, long userId) {
        return db.tx(
                () -> {
                    if (db.update(
                                    "UPDATE addresses SET is_deleted=1,is_default=0,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=? AND is_deleted=0",
                                    userId,
                                    id,
                                    userId)
                            == 0) throw notFound("地址不存在");
                    return obj("ok", true);
                });
    }

    public ObjectNode cart(JsonNode user) {
        ArrayNode items = arr();
        // 购物车保留已下架或已逻辑删除商品，以 available=false 展示，不能过滤掉失效购物项。
        for (ObjectNode row :
                db.list(
                        "SELECT c.id,c.sku_id AS skuId,c.quantity,c.selected,p.name,p.image,s.name AS skuName,s.unit,s.stock-s.reserved AS stock,s.retail_cents,s.store_cents,s.principal_cents,s.product_type,s.welfare_cents,s.active,p.active AS product_active,p.is_deleted AS product_deleted,s.is_deleted AS sku_deleted,ct.active AS category_active,ct.is_deleted AS category_deleted FROM cart_items c JOIN skus s ON s.id=c.sku_id JOIN products p ON p.id=s.product_id JOIN categories ct ON ct.id=p.category_id WHERE c.user_id=? AND c.is_deleted=0 ORDER BY c.id",
                        l(user, "id"))) {
            Long price = catalog.rolePrice(row, user);
            items.add(
                    obj(
                            "id",
                            row.get("id"),
                            "skuId",
                            row.get("skuId"),
                            "quantity",
                            row.get("quantity"),
                            "selected",
                            b(row, "selected"),
                            "name",
                            row.get("name"),
                            "image",
                            row.get("image"),
                            "skuName",
                            row.get("skuName"),
                            "unit",
                            row.get("unit"),
                            "stock",
                            row.get("stock"),
                            "productType",
                            catalog.productType(row),
                            "priceCents",
                            price,
                            "available",
                            !b(row, "product_deleted")
                                    && !b(row, "sku_deleted")
                                    && !b(row, "category_deleted")
                                    && b(row, "active")
                                    && b(row, "product_active")
                                    && b(row, "category_active")
                                    && price != null
                                    && l(row, "stock") >= l(row, "quantity")));
        }
        return obj("items", items);
    }

    /** 整袋汇总包含未展示分页；totalKinds 为全部种类数，allSelected 仅在非空且全部勾选时为 true。 */
    public ObjectNode cartSummary(JsonNode user) {
        String identity = effectiveIdentity(user);
        String field =
                "B".equals(identity)
                        ? "s.principal_cents"
                        : "C".equals(identity) ? "s.store_cents" : "s.retail_cents";
        String price =
                "CASE WHEN s.product_type='welfare' THEN s.welfare_cents ELSE " + field + " END";
        ObjectNode result =
                db.one(
                        "SELECT COUNT(*) AS totalKinds,COALESCE(SUM(c.selected=1),0) AS kinds,"
                                + "COALESCE(SUM(CASE WHEN c.selected=1 THEN c.quantity ELSE 0 END),0) AS pieces,"
                                + "COALESCE(SUM(CASE WHEN c.selected=1 THEN ("
                                + price
                                + ")*c.quantity ELSE 0 END),0) AS totalCents"
                                + " FROM cart_items c JOIN skus s ON s.id=c.sku_id WHERE c.user_id=? AND c.is_deleted=0",
                        l(user, "id"));
        result.put(
                "allSelected",
                l(result, "totalKinds") > 0 && l(result, "totalKinds") == l(result, "kinds"));
        return result;
    }

    /** 修改本人购物项勾选；省略 skuId 时一次更新整袋，不受分页限制，失效商品也可取消勾选。 */
    public ObjectNode selectCart(JsonNode body, long userId) {
        Input.strict(body, "selected", "skuId");
        boolean selected = Input.bool(body, "selected");
        Long skuId = body.has("skuId") ? Input.integer(body, "skuId", 1, 9007199254740991L) : null;
        return db.tx(
                () -> {
                    String sql =
                            "UPDATE cart_items SET selected=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3)"
                                    + " WHERE user_id=? AND is_deleted=0";
                    if (skuId == null) {
                        db.update(sql, selected ? 1 : 0, userId, userId);
                    } else {
                        db.update(sql + " AND sku_id=?", selected ? 1 : 0, userId, userId, skuId);
                    }
                    return obj("ok", true);
                });
    }

    public ObjectNode saveCart(long skuId, JsonNode body, JsonNode user) {
        Input.strict(body, "quantity", "selected");
        long quantity = Input.integer(body, "quantity", 1, 999), userId = l(user, "id");
        boolean selected = Input.bool(body, "selected");
        return db.tx(
                () -> {
                    ObjectNode sku =
                            db.one(
                                    "SELECT s.*,p.active AS pa FROM skus s JOIN products p ON p.id=s.product_id AND p.is_deleted=0 WHERE s.id=? AND s.is_deleted=0",
                                    skuId);
                    if (sku == null
                            || !b(sku, "active")
                            || !b(sku, "pa")
                            || catalog.rolePrice(sku, user) == null) throw conflict("该商品暂不可购买");
                    if (l(sku, "stock") - l(sku, "reserved") < quantity) throw conflict("库存不足");
                    ObjectNode old =
                            db.one(
                                    "SELECT id FROM cart_items WHERE user_id=? AND sku_id=? AND is_deleted=0",
                                    userId,
                                    skuId);
                    if (old == null)
                        db.insert(
                                "INSERT INTO cart_items(user_id,sku_id,quantity,selected,create_user_id,update_user_id) VALUES(?,?,?,?,?,?)",
                                userId,
                                skuId,
                                quantity,
                                selected ? 1 : 0,
                                userId,
                                userId);
                    else
                        db.update(
                                "UPDATE cart_items SET quantity=?,selected=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                quantity,
                                selected ? 1 : 0,
                                userId,
                                l(old, "id"));
                    return obj("ok", true);
                });
    }

    public ObjectNode removeCart(long skuId, long userId) {
        return db.tx(
                () -> {
                    db.update(
                            "UPDATE cart_items SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE sku_id=? AND user_id=? AND is_deleted=0",
                            userId,
                            skuId,
                            userId);
                    return obj("ok", true);
                });
    }

    /** 服务端重建完整报价，冻结身份、归属、商品版本、主理人单价、地址、运费和售后政策。 */
    public ObjectNode buildQuote(JsonNode user, long addressId, JsonNode items) {
        JsonNode shipping = shipping();
        if (shipping == null || shipping.isNull()) throw conflict("配送和待付款有效期尚未配置");
        ObjectNode address =
                db.one(
                        "SELECT id,recipient,phone,province,city,district,detail FROM addresses WHERE id=? AND user_id=? AND is_deleted=0",
                        addressId,
                        l(user, "id"));
        if (address == null) throw conflict("请选择本人有效收货地址");
        boolean supported = false;
        for (JsonNode province : shipping.path("provinces"))
            if (province.asText().equals(s(address, "province"))) supported = true;
        if (!supported) throw conflict("该地区暂不支持配送");
        ObjectNode owner =
                db.one("SELECT principal_id FROM users WHERE id=? AND is_deleted=0", l(user, "id"));
        String identity = effectiveIdentity(user);
        JsonNode aftersales = db.setting("aftersales");
        if (aftersales == null || aftersales.isNull()) throw conflict("售后政策尚未配置，请联系品牌方");
        ArrayNode lines = arr();
        long goodsCents = 0;
        for (JsonNode item : items) {
            ObjectNode sku =
                    db.one(
                            "SELECT s.*,p.name AS product_name,p.image,p.version,p.active AS product_active,c.active AS category_active FROM skus s JOIN products p ON p.id=s.product_id AND p.is_deleted=0 JOIN categories c ON c.id=p.category_id AND c.is_deleted=0 WHERE s.id=? AND s.is_deleted=0",
                            l(item, "skuId"));
            if (sku == null
                    || !b(sku, "active")
                    || !b(sku, "product_active")
                    || !b(sku, "category_active")) throw conflict("商品或规格已停售，请重新选择");
            Long price =
                    item.has("offerUnitCents")
                            ? l(item, "offerUnitCents")
                            : catalog.rolePrice(sku, user);
            if (price == null) throw conflict("当前身份价格尚未配置");
            long quantity = l(item, "quantity");
            if (l(sku, "stock") - l(sku, "reserved") < quantity) throw conflict("商品库存不足");
            Long principalPrice = b(item, "isGift") ? 0 : catalog.priceForIdentity(sku, "B");
            if ("C".equals(identity) && principalPrice == null) throw conflict("商品主理人价格无效，暂不能订货");
            lines.add(
                    obj(
                            "skuId",
                            sku.get("id"),
                            "productId",
                            sku.get("product_id"),
                            "name",
                            sku.get("product_name"),
                            "skuName",
                            sku.get("name"),
                            "unit",
                            sku.get("unit"),
                            "image",
                            sku.get("image"),
                            "quantity",
                            quantity,
                            "shipQuantity",
                            item.has("shipQuantity") ? l(item, "shipQuantity") : quantity,
                            "storageEnabled",
                            b(sku, "storage_enabled") && "none".equals(s(sku, "storage_exclusion")),
                            "isGift",
                            b(item, "isGift"),
                            "bundleKey",
                            s(item, "bundleKey"),
                            "productType",
                            catalog.productType(sku),
                            "priceRule",
                            "v3-independent-points",
                            "retailCents",
                            sku.get("retail_cents"),
                            "welfareCents",
                            sku.get("welfare_cents"),
                            "unitCents",
                            price,
                            "principalCents",
                            principalPrice,
                            "version",
                            sku.get("version")));
            long shipQuantity = item.has("shipQuantity") ? l(item, "shipQuantity") : quantity;
            Input.require(shipQuantity >= 0 && shipQuantity <= quantity, "本次发货数量超出购买数量");
            if (shipQuantity < quantity
                    && !(b(sku, "storage_enabled") && "none".equals(s(sku, "storage_exclusion"))))
                throw conflict("该商品不支持寄存");
            goodsCents += price * quantity;
        }
        if (goodsCents > 100000000) throw conflict("订单金额超出允许范围");
        return obj(
                "bundleName",
                items.size() > 0 ? s(items.get(0), "bundleName") : "",
                "afterSalesDays",
                aftersales.get("days"),
                "afterSalesPolicy",
                aftersales.get("policy"),
                "identity",
                identity,
                "principalId",
                "C".equals(identity) && owner != null ? owner.get("principal_id") : null,
                "address",
                address,
                "lines",
                lines,
                "goodsCents",
                goodsCents,
                "shippingCents",
                0,
                "totalCents",
                goodsCents,
                "shippingInstructions",
                shipping.get("instructions"),
                "pendingMinutes",
                shipping.get("pendingMinutes"));
    }

    /** 只允许游客零售订单确认销售代理，确认结果写入本单快照。 */
    private void attachSales(ObjectNode snapshot, JsonNode input) {
        if (!has(input, "salesCode") || s(input, "salesCode").isEmpty()) return;
        Input.require(b(input, "salesConfirmed"), "请先确认本单销售代理");
        if (!"D".equals(s(snapshot, "identity"))) throw conflict("代理按身份价订货不另计本人零售佣金，请移除销售代理");
        snapshot.set("salesAgent", catalog.salesCandidate(s(input, "salesCode")));
        snapshot.put("salesConfirmed", true);
    }

    /** 套餐仅允许客户选择每项发货数量，不接受客户传商品或价格。 */
    private void applyOfferShipping(JsonNode items, JsonNode input) {
        if (!input.has("offerShipQuantities")) return;
        Input.require(input.has("offerId"), "只有活动套餐允许提供套餐发货数量");
        JsonNode quantities = input.get("offerShipQuantities");
        Input.require(quantities.isArray() && quantities.size() == items.size(), "套餐发货明细与原固定配置不一致");
        for (int i = 0; i < items.size(); i++) {
            JsonNode n = quantities.get(i);
            Input.require(
                    n.isIntegralNumber()
                            && n.canConvertToLong()
                            && n.asLong() >= 0
                            && n.asLong() <= l(items.get(i), "quantity"),
                    "套餐本次发货数量超出购买数量");
            ((ObjectNode) items.get(i)).put("shipQuantity", n.asLong());
        }
    }

    public ObjectNode quote(JsonNode body, JsonNode user) {
        expire();
        ObjectNode value = OrderInput.checkout(body);
        return db.tx(
                () -> {
                    JsonNode selected =
                            value.has("offerId")
                                    ? new com.jianyanji.catalog.OfferService(db, access)
                                            .items(
                                                    l(value, "offerId"),
                                                    l(value, "offerQuantity"),
                                                    s(value, "offerDelivery"))
                                    : value.get("items");
                    applyOfferShipping(selected, value);
                    ObjectNode snapshot = buildQuote(user, l(value, "addressId"), selected);
                    for (String field :
                            new String[] {
                                "offerId", "offerQuantity", "offerDelivery", "offerShipQuantities"
                            }) if (value.has(field)) snapshot.set(field, value.get(field));
                    new com.jianyanji.storage.StorageService(db, access)
                            .quoteCombined(snapshot, value, user);
                    attachSales(snapshot, value);
                    long userId = l(user, "id");
                    long quoteId =
                            db.insert(
                                    "INSERT INTO quotes(user_id,snapshot,expires_at,create_user_id,update_user_id) VALUES(?,?,?,?,?)",
                                    userId,
                                    stringify(snapshot),
                                    Instant.now().plus(10, ChronoUnit.MINUTES).toString(),
                                    userId,
                                    userId);
                    ObjectNode result = obj("id", quoteId);
                    result.setAll(snapshot);
                    long available = new com.jianyanji.wallet.PointsService(db).available(userId);
                    result.put("availableCents", available);
                    result.put(
                            "shortfallCents", Math.max(0, l(snapshot, "totalCents") - available));
                    return result;
                });
    }

    public ObjectNode create(JsonNode body, JsonNode user) {
        Input.strict(body, "quoteId", "requestKey");
        long quoteId = OrderInput.entityId(body.get("quoteId")), userId = l(user, "id");
        String requestKey = OrderInput.requestKey(body);
        ObjectNode order =
                db.tx(
                        () -> {
                            ObjectNode old =
                                    db.one(
                                            "SELECT * FROM orders WHERE user_id=? AND request_key=? AND is_deleted=0",
                                            userId,
                                            requestKey);
                            if (old != null) {
                                if (!has(old, "quote_id") || l(old, "quote_id") != quoteId)
                                    throw conflict("请求编号已用于其他订单");
                                return old;
                            }
                            payments.assertNoUnresolved(userId, 0);
                            ObjectNode quote =
                                    db.one(
                                            "SELECT * FROM quotes WHERE id=? AND user_id=? AND is_deleted=0 AND expires_at>?",
                                            quoteId,
                                            userId,
                                            now());
                            if (quote == null) throw conflict("确认信息已过期，请重新结算");
                            JsonNode original = parse(s(quote, "snapshot"));
                            ArrayNode items = arr();
                            for (JsonNode line : original.path("lines"))
                                items.add(
                                        obj(
                                                "skuId",
                                                line.get("skuId"),
                                                "quantity",
                                                line.get("quantity"),
                                                "shipQuantity",
                                                line.path("shipQuantity")
                                                        .asLong(l(line, "quantity"))));
                            if (original.has("offerId"))
                                items =
                                        new com.jianyanji.catalog.OfferService(db, access)
                                                .items(
                                                        l(original, "offerId"),
                                                        l(original, "offerQuantity"),
                                                        s(original, "offerDelivery"));
                            applyOfferShipping(items, original);
                            ObjectNode latest =
                                    buildQuote(user, l(original.path("address"), "id"), items);
                            for (String field :
                                    new String[] {
                                        "offerId",
                                        "offerQuantity",
                                        "offerDelivery",
                                        "offerShipQuantities"
                                    })
                                if (original.has(field)) latest.set(field, original.get(field));
                            new com.jianyanji.storage.StorageService(db, access)
                                    .quoteCombined(latest, original, user);
                            if (has(original, "salesAgent"))
                                attachSales(
                                        latest,
                                        obj(
                                                "salesCode",
                                                s(original.path("salesAgent"), "shareCode"),
                                                "salesConfirmed",
                                                true));
                            if (!stringify(latest).equals(s(quote, "snapshot")))
                                throw conflict("QUOTE_CHANGED", "价格、身份或配送信息已变化，请重新确认");
                            for (JsonNode line : latest.withArray("lines")) {
                                long quantity = l(line, "quantity");
                                if (db.update(
                                                "UPDATE skus SET reserved=reserved+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0 AND stock-reserved>=?",
                                                quantity,
                                                userId,
                                                l(line, "skuId"),
                                                quantity)
                                        != 1) throw conflict("库存不足");
                            }
                            String number =
                                    "JY"
                                            + now().substring(0, 10).replace("-", "")
                                            + id().substring(0, 16);
                            long orderId =
                                    db.insert(
                                            "INSERT INTO orders(number,user_id,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key,quote_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                                            number,
                                            userId,
                                            l(latest, "totalCents"),
                                            l(latest, "goodsCents"),
                                            l(latest, "shippingCents"),
                                            stringify(latest),
                                            Instant.now()
                                                    .plus(
                                                            l(latest, "pendingMinutes"),
                                                            ChronoUnit.MINUTES)
                                                    .toString(),
                                            requestKey,
                                            quoteId,
                                            userId,
                                            userId);
                            for (JsonNode line : latest.withArray("lines"))
                                db.insert(
                                        "INSERT INTO order_lines(order_id,sku_id,quantity,unit_cents,principal_cents,product_type,retail_cents,price_rule,storage_qty,is_gift,bundle_key,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                        orderId,
                                        l(line, "skuId"),
                                        l(line, "quantity"),
                                        l(line, "unitCents"),
                                        has(line, "principalCents")
                                                ? l(line, "principalCents")
                                                : null,
                                        s(line, "productType"),
                                        line.get("retailCents").isNull()
                                                ? null
                                                : l(line, "retailCents"),
                                        s(line, "priceRule"),
                                        l(line, "quantity") - l(line, "shipQuantity"),
                                        b(line, "isGift") ? 1 : 0,
                                        s(line, "bundleKey"),
                                        userId,
                                        userId);
                            new com.jianyanji.storage.StorageService(db, access)
                                    .reserveCombined(orderId, latest, userId);
                            // 建单成功后移除本人本单商品；与订单共用事务，失败时一并回滚。
                            db.update(
                                    "UPDATE cart_items SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE user_id=? AND is_deleted=0 AND sku_id IN (SELECT sku_id FROM order_lines WHERE order_id=? AND is_deleted=0)",
                                    userId,
                                    userId,
                                    orderId);
                            access.audit("order.create", "order", orderId, "确认订单并占用库存，尚未扣款");
                            return owned(orderId, userId);
                        });
        return view(order);
    }

    public ArrayNode orders(Long userId) {
        expire();
        List<ObjectNode> rows =
                userId == null
                        ? db.list(
                                "SELECT * FROM orders WHERE is_deleted=0 ORDER BY id DESC LIMIT 200")
                        : db.list(
                                "SELECT * FROM orders WHERE user_id=? AND is_deleted=0 ORDER BY id DESC LIMIT 100",
                                userId);
        ArrayNode result = arr();
        for (ObjectNode row : rows) result.add(view(row));
        return result;
    }

    public ObjectNode detail(long id, long userId) {
        expire();
        return view(owned(id, userId));
    }

    public ObjectNode cancel(long id, long userId) {
        return db.tx(
                () -> {
                    ObjectNode order = owned(id, userId);
                    if ("closed".equals(s(order, "status"))) return obj("ok", true);
                    if (!release(order)) throw conflict("支付已发起或已付款，请先查询原交易结果");
                    access.audit("order.cancel", "order", id, "用户取消未支付订单");
                    return obj("ok", true);
                });
    }

    /** 未决售后（包括失败待补偿）阻断收货及履约完成。 */
    boolean unsettledAftersales(long orderId) {
        return db.one(
                        "SELECT id FROM refunds WHERE order_id=? AND is_deleted=0 AND status IN ('requested','approved','processing','unknown','failed') LIMIT 1",
                        orderId)
                != null;
    }

    /** 可在售后事务内调用；真实收货、剩余有效商品、未决售后与未收包裹共同决定完成状态。 */
    public void refreshFulfillment(long orderId) {
        db.tx(
                () -> {
                    ObjectNode order = owned(orderId, null);
                    String payment = s(order, "payment_state");
                    if (!("paid".equals(payment) || "partially_refunded".equals(payment))) return;
                    List<ObjectNode> lines =
                            db.list(
                                    "SELECT * FROM order_lines WHERE order_id=? AND is_deleted=0 ORDER BY id",
                                    orderId);
                    boolean remaining = false, held = false, received = false, shipped = false;
                    for (ObjectNode line : lines) {
                        // 仍有有效商品未发出时不能完成，避免历史收货数包含后来退货商品而提前完成。
                        remaining |=
                                l(line, "quantity") - l(line, "refunded_qty")
                                                > l(line, "received_qty")
                                        || l(line, "quantity")
                                                        - l(line, "shipped_qty")
                                                        - l(line, "refunded_unshipped")
                                                > 0;
                        held |= l(line, "refund_held") > 0;
                        received |= l(line, "received_qty") > 0;
                        shipped |= l(line, "shipped_qty") > 0;
                    }
                    boolean pending = held || unsettledAftersales(orderId);
                    boolean openParcels =
                            db.one(
                                            "SELECT id FROM shipments WHERE order_id=? AND is_deleted=0 AND received_at IS NULL LIMIT 1",
                                            orderId)
                                    != null;
                    String status =
                            !remaining && !pending && !openParcels && received
                                    ? "completed"
                                    : shipped ? "shipped" : "paid";
                    db.update(
                            "UPDATE orders SET status=?,received_at=CASE WHEN ?='completed' THEN COALESCE(received_at,?) ELSE NULL END,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            status,
                            status,
                            now(),
                            currentActor(),
                            orderId);
                });
    }
}
