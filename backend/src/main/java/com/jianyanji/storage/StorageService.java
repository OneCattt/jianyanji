package com.jianyanji.storage;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import com.jianyanji.wallet.PointsService;
import java.time.*;
import java.util.*;

/** 用户专属寄存、跨订单提货与履约服务；所有数量和积分写入共用业务事务，提货不再扣可售库存。 */
public class StorageService {
    /** 数据访问与全局业务事务。 */
    private final Db db;
    /** 当前操作人、请求编号和审计。 */
    private final Access access;
    /** 所有寄存日期统一采用上海时区。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public StorageService(Db db, Access access) {
        this.db = db;
        this.access = access;
    }

    /** 金额单位分；纯新购及全寄存不收运费，赠品金额由调用方排除。 */
    public static long shippingFee(
            String identity, long newCents, long storedCents, boolean hasStored) {
        if (!hasStored || newCents >= 100000 || !"B".equals(identity)) return 0;
        return storedCents >= 100000 ? 0 : 1000;
    }
    /** 按上海支付日期增加12个日历月，到期日当天有效。 */
    public static LocalDate expiresOn(Instant paid) {
        return paid.atZone(ZONE).toLocalDate().plusMonths(12);
    }

    /** 本人或授权后台分页查询，筛选在数据库执行。 */
    public ObjectNode lots(Long user, Map<String, String> filters) {
        Map<String, String> q = new HashMap<>(filters);
        if (user != null) q.put("userId", String.valueOf(user));
        String from =
                "FROM storage_lots l JOIN orders o ON o.id=l.order_id AND o.is_deleted=0 JOIN order_lines ol ON ol.id=l.line_id AND ol.is_deleted=0 LEFT JOIN skus s ON s.id=l.sku_id AND s.is_deleted=0 LEFT JOIN products p ON p.id=s.product_id AND p.is_deleted=0 JOIN users u ON u.id=l.user_id AND u.is_deleted=0 WHERE l.is_deleted=0";
        if ("true".equals(q.get("expiring")))
            from +=
                    " AND l.remaining_qty>0 AND l.expires_on<=DATE_ADD(DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')),INTERVAL 30 DAY)";
        for (String key : new String[] {"from", "to"})
            if (q.containsKey(key) && !q.get(key).isEmpty()) {
                LocalDate date = date(q.get(key));
                from += " AND l.starts_on" + ("from".equals(key) ? ">=" : "<=") + "'" + date + "'";
            }
        return new PageQuery(q, from)
                .eq("userId", "l.user_id")
                .eq("skuId", "l.sku_id")
                .eq("kind", "o.kind")
                .keyword("p.name", "s.name", "o.number", "u.nickname")
                .select(
                        db,
                        "l.*,l.remaining_qty-l.held_qty-l.refund_held_qty AS available_qty,p.name,s.name AS sku_name,s.unit,o.number AS order_number,o.kind AS source_kind,COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(o.snapshot,'$.bundleName')),''),JSON_UNQUOTE(JSON_EXTRACT(o.snapshot,'$.name')),'普通商品订单') AS package_name,ol.is_gift,ol.quantity AS purchased_qty,ol.shipped_qty AS total_shipped_qty,ol.received_qty,u.nickname,o.snapshot AS source_snapshot",
                        "l.id DESC",
                        r -> {
                            historicalName(r);
                            r.put("expired", LocalDate.now(ZONE).isAfter(date(s(r, "expires_on"))));
                            return r;
                        });
    }
    /** 本人提货历史与授权后台审核列表。 */
    public ObjectNode pickups(Long user, Map<String, String> filters) {
        Map<String, String> q = new HashMap<>(filters);
        if (user != null) q.put("userId", String.valueOf(user));
        return new PageQuery(q, "FROM pickup_orders WHERE is_deleted=0")
                .eq("userId", "user_id")
                .eq("status", "status")
                .keyword("number", "tracking", "CAST(user_id AS CHAR)")
                .select(db, "*", "id DESC", this::view);
    }
    /** 提货明细保留原订单、商品及赠品，地址为提交时快照。 */
    public ObjectNode view(ObjectNode pickup) {
        ObjectNode result = pickup.deepCopy();
        result.set("address", parse(s(pickup, "address_snapshot")));
        result.set(
                "lines",
                json(
                        db.list(
                                "SELECT pl.*,ol.order_id,ol.sku_id,ol.is_gift,ol.unit_cents,p.name,s.name AS sku_name,s.unit,o.snapshot AS source_snapshot FROM pickup_lines pl JOIN order_lines ol ON ol.id=pl.line_id AND ol.is_deleted=0 JOIN orders o ON o.id=ol.order_id AND o.is_deleted=0 LEFT JOIN skus s ON s.id=ol.sku_id AND s.is_deleted=0 LEFT JOIN products p ON p.id=s.product_id AND p.is_deleted=0 WHERE pl.pickup_id=? AND pl.is_deleted=0 ORDER BY pl.id",
                                l(pickup, "id"))));
        for (JsonNode line : result.path("lines")) historicalName((ObjectNode) line);
        long fresh = 0, stored = 0;
        for (JsonNode line : result.path("lines")) {
            if (has(line, "lot_id")) stored += l(line, "quantity");
            else fresh += l(line, "quantity");
        }
        result.put("newGoodsQuantity", fresh);
        result.put("storedGoodsQuantity", stored);
        return result;
    }
    /** 按原成交快照展示历史名称，商品下架或逻辑删除不隐藏提货记录。 */
    private void historicalName(ObjectNode row) {
        for (JsonNode item : parse(s(row, "source_snapshot")).path("lines")) {
            if (l(item, "skuId") == l(row, "sku_id") && b(item, "isGift") == b(row, "is_gift")) {
                row.put("name", s(item, "name"));
                row.put("sku_name", s(item, "skuName"));
                row.put("unit", s(item, "unit"));
                break;
            }
        }
        row.remove("source_snapshot");
    }
    /** 不存在或不属于本人时统一返回404。 */
    public ObjectNode owned(long id, Long user) {
        ObjectNode p =
                user == null
                        ? db.one("SELECT * FROM pickup_orders WHERE id=? AND is_deleted=0", id)
                        : db.one(
                                "SELECT * FROM pickup_orders WHERE id=? AND user_id=? AND is_deleted=0",
                                id,
                                user);
        if (p == null) throw notFound("提货单不存在");
        return p;
    }
    /** 严格规范化提货项，核对归属、原付款、有效期和可用数量；后台可人工处理过期批次。 */
    private ObjectNode stored(JsonNode items, long user, boolean admin) {
        ArrayNode lines = arr();
        long amount = 0;
        Set<Long> unique = new HashSet<>();
        Input.require(items != null && items.isArray() && items.size() <= 100, "提货明细须为数组且不超过100项");
        for (JsonNode item : items) {
            Input.strict(item, "lotId", "quantity");
            long id = Input.integer(item, "lotId", 1, 9007199254740991L),
                    qty = Input.integer(item, "quantity", 1, 10000000);
            Input.require(unique.add(id), "寄存批次不能重复");
            ObjectNode lot =
                    db.one(
                            "SELECT l.*,ol.is_gift,o.payment_state FROM storage_lots l JOIN order_lines ol ON ol.id=l.line_id AND ol.is_deleted=0 JOIN orders o ON o.id=l.order_id AND o.is_deleted=0 WHERE l.id=? AND l.user_id=? AND l.is_deleted=0",
                            id,
                            user);
            if (lot == null) throw notFound("本人寄存货品不存在");
            if (!Arrays.asList("paid", "partially_refunded").contains(s(lot, "payment_state")))
                throw conflict("原订单未有效付款");
            if (!admin && LocalDate.now(ZONE).isAfter(date(s(lot, "expires_on"))))
                throw conflict("寄存已到期，请联系总部人工处理或延期");
            if (l(lot, "remaining_qty") - l(lot, "held_qty") - l(lot, "refund_held_qty") < qty)
                throw conflict("寄存剩余数量不足或正在提货/退款");
            lines.add(obj("lotId", id, "quantity", qty));
            if (!b(lot, "is_gift"))
                amount = Math.addExact(amount, Math.multiplyExact(l(lot, "unit_cents"), qty));
        }
        return obj("items", lines, "amount", amount);
    }
    /** 将寄存明细及运费依据冻结进新购报价，不将钱包余额写入报价比较。 */
    public void quoteCombined(ObjectNode quote, JsonNode input, JsonNode user) {
        JsonNode source = input.has("storedItems") ? input.get("storedItems") : arr();
        ObjectNode check = stored(source, l(user, "id"), false);
        boolean immediate = false;
        for (JsonNode line : quote.path("lines")) immediate |= l(line, "shipQuantity") > 0;
        if (source.size() > 0 && !immediate) throw conflict("合并提货须选择本次新购发货商品；纯寄存请从我的寄存申请");
        quote.set("storedItems", check.get("items"));
        quote.put("storedGoodsCents", l(check, "amount"));
        long fee =
                shippingFee(
                        effectiveIdentity(user),
                        l(quote, "goodsCents"),
                        l(check, "amount"),
                        source.size() > 0);
        quote.put("shippingCents", fee);
        quote.put("totalCents", l(quote, "goodsCents") + fee);
        quote.put("businessVersion", "points-storage-v3");
    }
    /** 新购建单时占用关联寄存，并建立首发/合并配送单；关闭未付款单会释放占用。 */
    public void reserveCombined(long orderId, ObjectNode snapshot, long actor) {
        db.assertTransaction();
        ArrayNode lines = arr();
        for (ObjectNode line :
                db.list(
                        "SELECT * FROM order_lines WHERE order_id=? AND quantity>storage_qty AND is_deleted=0",
                        orderId))
            lines.add(
                    obj(
                            "lineId",
                            l(line, "id"),
                            "quantity",
                            l(line, "quantity") - l(line, "storage_qty")));
        ArrayNode storedItems = (ArrayNode) snapshot.path("storedItems");
        if (lines.size() == 0 && storedItems.size() == 0) return;
        long owner =
                l(
                        db.one("SELECT user_id FROM orders WHERE id=? AND is_deleted=0", orderId),
                        "user_id");
        long id =
                insertPickup(
                        owner,
                        orderId,
                        "awaiting_payment",
                        s(snapshot, "identity"),
                        l(snapshot, "goodsCents"),
                        l(snapshot, "storedGoodsCents"),
                        l(snapshot, "shippingCents"),
                        snapshot.path("address"),
                        "order:" + orderId,
                        snapshot,
                        actor);
        for (JsonNode line : lines)
            addLine(id, null, l(line, "lineId"), l(line, "quantity"), actor);
        for (JsonNode item : storedItems) hold(id, l(item, "lotId"), l(item, "quantity"), actor);
    }
    /** 支付成功才建立寄存所有权；付款与身份激活任何一步失败都回滚。 */
    public void paid(ObjectNode order, long actor) {
        db.assertTransaction();
        JsonNode snapshot = parse(s(order, "snapshot"));
        Instant paid = Instant.now();
        ObjectNode payment =
                db.one(
                        "SELECT paid_at FROM payments WHERE order_id=? AND is_deleted=0",
                        l(order, "id"));
        if (payment != null) paid = Instant.parse(s(payment, "paid_at"));
        for (ObjectNode line :
                db.list(
                        "SELECT * FROM order_lines WHERE order_id=? AND storage_qty>0 AND is_deleted=0",
                        l(order, "id")))
            createLot(order, line, l(line, "storage_qty"), paid, actor, "create");
        for (ObjectNode p :
                db.list(
                        "SELECT * FROM pickup_orders WHERE purchase_order_id=? AND status='awaiting_payment' AND is_deleted=0",
                        l(order, "id"))) {
            boolean stored =
                    db.one(
                                    "SELECT id FROM pickup_lines WHERE pickup_id=? AND lot_id IS NOT NULL AND is_deleted=0 LIMIT 1",
                                    l(p, "id"))
                            != null;
            db.update(
                    "UPDATE pickup_orders SET status=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                    stored ? "review" : "approved",
                    actor,
                    l(p, "id"));
        }
    }

    private long createLot(
            ObjectNode order, ObjectNode line, long qty, Instant paid, long actor, String kind) {
        ObjectNode old =
                db.one(
                        "SELECT * FROM storage_lots WHERE line_id=? AND is_deleted=0",
                        l(line, "id"));
        if (old != null) return l(old, "id");
        long id =
                db.insert(
                        "INSERT INTO storage_lots(user_id,order_id,line_id,sku_id,total_qty,remaining_qty,unit_cents,starts_on,expires_on,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        l(order, "user_id"),
                        l(order, "id"),
                        l(line, "id"),
                        l(line, "sku_id"),
                        qty,
                        qty,
                        b(line, "is_gift") ? 0 : l(line, "unit_cents"),
                        paid.atZone(ZONE).toLocalDate().toString(),
                        expiresOn(paid).toString(),
                        actor,
                        actor);
        event(id, kind, qty, "line:" + l(line, "id"), "原购买订单已有效付款，建立专属寄存", actor);
        return id;
    }

    private long insertPickup(
            long user,
            Long order,
            String status,
            String identity,
            long goods,
            long stored,
            long fee,
            JsonNode address,
            String key,
            JsonNode snapshot,
            long actor) {
        return db.insert(
                "INSERT INTO pickup_orders(number,user_id,purchase_order_id,status,identity_snapshot,new_goods_cents,stored_goods_cents,fee_cents,address_snapshot,request_key,request_snapshot,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "TH" + id(),
                user,
                order,
                status,
                identity,
                goods,
                stored,
                fee,
                stringify(address),
                key,
                stringify(snapshot),
                actor,
                actor);
    }

    private void addLine(long pickup, Long lot, long line, long qty, long actor) {
        db.insert(
                "INSERT INTO pickup_lines(pickup_id,lot_id,line_id,quantity,create_user_id,update_user_id) VALUES(?,?,?,?,?,?)",
                pickup,
                lot,
                line,
                qty,
                actor,
                actor);
    }

    private void hold(long pickup, long lot, long qty, long actor) {
        ObjectNode row = db.one("SELECT * FROM storage_lots WHERE id=? AND is_deleted=0", lot);
        if (row == null
                || db.update(
                                "UPDATE storage_lots SET held_qty=held_qty+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND remaining_qty-held_qty-refund_held_qty>=? AND is_deleted=0",
                                qty,
                                actor,
                                lot,
                                qty)
                        != 1) throw conflict("寄存数量已变化，请重新确认");
        addLine(pickup, lot, l(row, "line_id"), qty, actor);
        event(lot, "hold", qty, "pickup:" + pickup, "提交提货占用，尚未出库", actor);
    }
    /** 本人/后台提货报价；后台人工申请可处理过期批次。 */
    public ObjectNode quote(JsonNode input, ObjectNode user, boolean admin) {
        Input.strict(
                input,
                "addressId",
                "storedItems",
                "requestKey",
                "expectedFeeCents",
                "userId",
                "reason");
        long uid = l(user, "id"),
                addressId = Input.integer(input, "addressId", 1, 9007199254740991L);
        ObjectNode address =
                db.one(
                        "SELECT id,recipient,phone,province,city,district,detail FROM addresses WHERE id=? AND user_id=? AND is_deleted=0",
                        addressId,
                        uid);
        if (address == null) throw conflict("请选择本人有效收货地址");
        JsonNode shipping = db.setting("shipping");
        boolean supported = false;
        for (JsonNode province : shipping.path("provinces"))
            supported |= province.asText().equals(s(address, "province"));
        if (!supported) throw conflict("该地区暂不支持配送");
        ObjectNode check = stored(input.path("storedItems"), uid, admin);
        Input.require(check.path("items").size() > 0, "请选择提货货品");
        long fee = shippingFee(effectiveIdentity(user), 0, l(check, "amount"), true),
                available = new PointsService(db).available(uid);
        return obj(
                "address",
                address,
                "storedItems",
                check.get("items"),
                "storedGoodsCents",
                l(check, "amount"),
                "identity",
                effectiveIdentity(user),
                "shippingCents",
                fee,
                "availableCents",
                available,
                "shortfallCents",
                Math.max(0, fee - available));
    }
    /** 纯寄存提货占用、扣运费和提交审核同一事务，重试必须与原内容相同。 */
    public ObjectNode submit(JsonNode input, ObjectNode user, boolean admin, long actor) {
        return db.tx(
                () -> {
                    String key = Input.text(input, "requestKey", 8, 191);
                    long uid = l(user, "id");
                    ObjectNode old =
                            db.one(
                                    "SELECT * FROM pickup_orders WHERE user_id=? AND request_key=? AND is_deleted=0",
                                    uid,
                                    key);
                    if (old != null) {
                        if (!s(old, "request_snapshot").equals(stringify(input)))
                            throw conflict("提货请求编号已用于其他内容");
                        return view(old);
                    }
                    ObjectNode q = quote(input, user, admin);
                    long fee = l(q, "shippingCents");
                    if (input.has("expectedFeeCents")
                            && Input.integer(input, "expectedFeeCents", 0, 1000) != fee)
                        throw conflict("运费或身份已变化，请重新确认");
                    if (admin) Input.text(input, "reason", 1, 2000);
                    long pickup =
                            insertPickup(
                                    uid,
                                    null,
                                    "review",
                                    s(q, "identity"),
                                    0,
                                    l(q, "storedGoodsCents"),
                                    fee,
                                    q.get("address"),
                                    key,
                                    input,
                                    actor);
                    for (JsonNode item : q.path("storedItems"))
                        hold(pickup, l(item, "lotId"), l(item, "quantity"), actor);
                    new PointsService(db).spend(uid, null, pickup, fee, actor);
                    access.audit(
                            "pickup.create",
                            "pickup",
                            pickup,
                            admin ? Input.text(input, "reason", 1, 2000) : "本人提交寄存提货，按原批次扣运费积分");
                    return view(owned(pickup, uid));
                });
    }
    /** 未付款新购单关闭时只释放寄存，不退从未扣除的运费。 */
    public void releaseUnpaid(long order, long actor) {
        db.assertTransaction();
        for (ObjectNode p :
                db.list(
                        "SELECT * FROM pickup_orders WHERE purchase_order_id=? AND status='awaiting_payment' AND is_deleted=0",
                        order)) {
            releaseHolds(l(p, "id"), actor);
            db.update(
                    "UPDATE pickup_orders SET status='cancelled',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                    actor,
                    l(p, "id"));
        }
    }

    private void releaseHolds(long pickup, long actor) {
        for (ObjectNode line :
                db.list(
                        "SELECT * FROM pickup_lines WHERE pickup_id=? AND lot_id IS NOT NULL AND is_deleted=0",
                        pickup)) {
            if (db.update(
                            "UPDATE storage_lots SET held_qty=held_qty-?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND held_qty>=? AND is_deleted=0",
                            l(line, "quantity"),
                            actor,
                            l(line, "lot_id"),
                            l(line, "quantity"))
                    != 1) throw conflict("提货占用数量不一致");
            event(
                    l(line, "lot_id"),
                    "release",
                    l(line, "quantity"),
                    "pickup:" + pickup,
                    "取消或驳回释放申请占用",
                    actor);
        }
    }
    /** 取消/驳回未发货提货只退本次运费，不改变原商品购买关系。 */
    public ObjectNode cancel(long id, Long user, long actor, String reason, boolean reject) {
        return db.tx(
                () -> {
                    ObjectNode p = owned(id, user);
                    String status = s(p, "status");
                    if (Arrays.asList("cancelled", "rejected").contains(status)) return view(p);
                    if (!Arrays.asList("review", "approved").contains(status))
                        throw conflict("提货已发货或尚未支付，不能取消");
                    releaseHolds(id, actor);
                    new PointsService(db).refundFee(p, actor);
                    db.update(
                            "UPDATE pickup_orders SET status=?,review_reason=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            reject ? "rejected" : "cancelled",
                            reason,
                            actor,
                            id);
                    if (reject)
                        message(
                                l(p, "user_id"),
                                null,
                                "rejected",
                                "提货申请未通过",
                                reason,
                                "pickup-rejected:" + id,
                                actor);
                    access.audit(reject ? "pickup.reject" : "pickup.cancel", "pickup", id, reason);
                    return view(owned(id, user));
                });
    }
    /** 审核仅修改状态，不提前计为发货。 */
    public ObjectNode review(long id, JsonNode body, long actor) {
        Input.strict(body, "approve", "reason");
        boolean approve = Input.bool(body, "approve");
        String reason = Input.text(body, "reason", 1, 2000);
        if (!approve) return cancel(id, null, actor, reason, true);
        return db.tx(
                () -> {
                    ObjectNode p = owned(id, null);
                    if ("approved".equals(s(p, "status"))) return view(p);
                    if (!"review".equals(s(p, "status"))) throw conflict("提货单不在待审核状态");
                    db.update(
                            "UPDATE pickup_orders SET status='approved',review_reason=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            reason,
                            actor,
                            id);
                    access.audit("pickup.approve", "pickup", id, reason);
                    return view(owned(id, null));
                });
    }
    /** 一单一实体包裹，按来源订单建立关联履约记录，跨订单出库原子完成。 */
    public ObjectNode ship(long id, JsonNode body, long actor) {
        Input.strict(body, "carrier", "tracking", "reason");
        String carrier = Input.text(body, "carrier", 1, 60),
                tracking = Input.text(body, "tracking", 4, 80),
                reason = Input.text(body, "reason", 1, 2000);
        Input.require(tracking.matches("[a-zA-Z0-9-]{4,80}"), "快递单号格式错误");
        return db.tx(
                () -> {
                    ObjectNode p = owned(id, null);
                    if (Arrays.asList("shipped", "received").contains(s(p, "status"))) {
                        if (!carrier.equals(s(p, "carrier")) || !tracking.equals(s(p, "tracking")))
                            throw conflict("原提货单已有其他物流，不能重复发货");
                        return view(p);
                    }
                    if (!"approved".equals(s(p, "status"))) throw conflict("审核通过后才能发货");
                    Map<Long, ArrayNode> parcels = new LinkedHashMap<>();
                    for (ObjectNode line :
                            db.list(
                                    "SELECT pl.*,ol.order_id,o.payment_state FROM pickup_lines pl JOIN order_lines ol ON ol.id=pl.line_id AND ol.is_deleted=0 JOIN orders o ON o.id=ol.order_id AND o.is_deleted=0 WHERE pl.pickup_id=? AND pl.is_deleted=0 ORDER BY pl.id",
                                    id)) {
                        long qty = l(line, "quantity"), lineId = l(line, "line_id");
                        if (!Arrays.asList("paid", "partially_refunded")
                                .contains(s(line, "payment_state"))) throw conflict("来源订单不允许发货");
                        if (db.update(
                                        "UPDATE order_lines SET shipped_qty=shipped_qty+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND quantity-shipped_qty-refunded_unshipped-held_unshipped>=? AND is_deleted=0",
                                        qty,
                                        actor,
                                        lineId,
                                        qty)
                                != 1) throw conflict("商品售后占用或可发货量不足");
                        if (has(line, "lot_id")) {
                            if (db.update(
                                            "UPDATE storage_lots SET remaining_qty=remaining_qty-?,held_qty=held_qty-?,shipped_qty=shipped_qty+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND held_qty>=? AND remaining_qty>=? AND is_deleted=0",
                                            qty,
                                            qty,
                                            qty,
                                            actor,
                                            l(line, "lot_id"),
                                            qty,
                                            qty)
                                    != 1) throw conflict("寄存出库占用不一致");
                            event(l(line, "lot_id"), "ship", qty, "pickup:" + id, reason, actor);
                        }
                        parcels.computeIfAbsent(l(line, "order_id"), key -> arr())
                                .add(obj("lineId", lineId, "quantity", qty));
                    }
                    for (Map.Entry<Long, ArrayNode> parcel : parcels.entrySet()) {
                        db.insert(
                                "INSERT INTO shipments(order_id,pickup_id,carrier,tracking,`lines`,shipped_at,request_key,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?)",
                                parcel.getKey(),
                                id,
                                carrier,
                                tracking,
                                stringify(parcel.getValue()),
                                now(),
                                "pickup:" + id,
                                actor,
                                actor);
                        db.update(
                                "UPDATE orders SET status='shipped',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                actor,
                                parcel.getKey());
                    }
                    db.update(
                            "UPDATE pickup_orders SET status='shipped',carrier=?,tracking=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            carrier,
                            tracking,
                            actor,
                            id);
                    access.audit("pickup.ship", "pickup", id, reason);
                    return view(owned(id, null));
                });
    }
    /** 一次确认同一物理包裹的全部来源；退款货品不累计为已收货。 */
    public ObjectNode receive(long id, long user) {
        return db.tx(
                () -> {
                    ObjectNode p = owned(id, user);
                    if ("received".equals(s(p, "status"))) return view(p);
                    if (!"shipped".equals(s(p, "status"))) throw conflict("提货尚未发货");
                    Set<Long> orders = new HashSet<>();
                    for (ObjectNode line :
                            db.list(
                                    "SELECT ol.*,pl.quantity AS parcel_qty FROM pickup_lines pl JOIN order_lines ol ON ol.id=pl.line_id AND ol.is_deleted=0 WHERE pl.pickup_id=? AND pl.is_deleted=0",
                                    id)) {
                        if (l(line, "refund_held") > 0) throw conflict("本次货品尚有未决售后，请处理后确认收货");
                        long qty =
                                Math.min(
                                        l(line, "parcel_qty"),
                                        Math.max(
                                                0,
                                                l(line, "shipped_qty")
                                                        - l(line, "refunded_qty")
                                                        + l(line, "refunded_unshipped")
                                                        - l(line, "received_qty")));
                        db.update(
                                "UPDATE order_lines SET received_qty=received_qty+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                qty,
                                user,
                                l(line, "id"));
                        orders.add(l(line, "order_id"));
                    }
                    db.update(
                            "UPDATE shipments SET received_at=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE pickup_id=? AND received_at IS NULL AND is_deleted=0",
                            now(),
                            user,
                            id);
                    db.update(
                            "UPDATE pickup_orders SET status='received',received_at=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            now(),
                            user,
                            id);
                    for (Long order : orders) refresh(order, user);
                    access.audit("pickup.receive", "pickup", id, "用户确认同一包裹实际收货");
                    return view(owned(id, user));
                });
    }

    private void refresh(long order, long actor) {
        ObjectNode count =
                db.one(
                        "SELECT SUM(quantity-refunded_qty>received_qty OR quantity-shipped_qty-refunded_unshipped>0) AS remaining,SUM(refund_held) AS held FROM order_lines WHERE order_id=? AND is_deleted=0",
                        order);
        boolean complete =
                l(count, "remaining") == 0
                        && l(count, "held") == 0
                        && db.one(
                                        "SELECT id FROM shipments WHERE order_id=? AND received_at IS NULL AND is_deleted=0 LIMIT 1",
                                        order)
                                == null;
        db.update(
                "UPDATE orders SET status=?,received_at=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                complete ? "completed" : "shipped",
                complete ? now() : null,
                actor,
                order);
    }
    /** 售后受理前占用寄存部分；先退未寄存待发货部分，避免重复占用提货中货品。 */
    public long holdRefund(ObjectNode line, long unshipped, long actor) {
        db.assertTransaction();
        ObjectNode lot =
                db.one(
                        "SELECT * FROM storage_lots WHERE line_id=? AND is_deleted=0",
                        l(line, "id"));
        if (lot == null || unshipped == 0) return 0;
        long nonStored =
                Math.max(
                        0,
                        l(line, "quantity")
                                - l(line, "shipped_qty")
                                - l(line, "refunded_unshipped")
                                - l(line, "held_unshipped")
                                - l(lot, "remaining_qty"));
        long qty = Math.max(0, unshipped - nonStored);
        if (qty > 0
                && db.update(
                                "UPDATE storage_lots SET refund_held_qty=refund_held_qty+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND remaining_qty-held_qty-refund_held_qty>=? AND is_deleted=0",
                                qty,
                                actor,
                                l(lot, "id"),
                                qty)
                        != 1) throw conflict("寄存货品已在提货申请中，请先取消提货后申请退款");
        return qty;
    }
    /** 售后驳回释放占用；实际成功退款才减少寄存并记流水。 */
    public void refund(JsonNode refund, boolean success, long actor) {
        db.assertTransaction();
        for (JsonNode line : parse(s(refund, "lines"))) {
            long qty = l(line, "storageQuantity");
            if (qty == 0) continue;
            ObjectNode lot =
                    db.one(
                            "SELECT * FROM storage_lots WHERE line_id=? AND is_deleted=0",
                            l(line, "lineId"));
            if (lot == null) throw conflict("原寄存退款批次缺失");
            String delta =
                    success ? ",remaining_qty=remaining_qty-?,refunded_qty=refunded_qty+?" : "";
            List<Object> args = new ArrayList<>();
            args.add(qty);
            if (success) {
                args.add(qty);
                args.add(qty);
            }
            args.add(actor);
            args.add(l(lot, "id"));
            args.add(qty);
            if (db.update(
                            "UPDATE storage_lots SET refund_held_qty=refund_held_qty-?"
                                    + delta
                                    + ",update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND refund_held_qty>=? AND is_deleted=0",
                            args.toArray())
                    != 1) throw conflict("寄存退款占用不一致");
            if (success)
                event(
                        l(lot, "id"),
                        "refund",
                        qty,
                        "refund:" + l(refund, "id"),
                        "商品退款成功，同步取消未提寄存数量",
                        actor);
        }
    }
    /** 过期批次人工延期，必须给出原因且新到期日后于原日期。 */
    public ObjectNode extend(long id, JsonNode body, long actor) {
        Input.strict(body, "expiresOn", "reason");
        String reason = Input.text(body, "reason", 1, 2000);
        LocalDate expiry = date(Input.text(body, "expiresOn", 10, 10));
        return db.tx(
                () -> {
                    ObjectNode lot =
                            db.one("SELECT * FROM storage_lots WHERE id=? AND is_deleted=0", id);
                    if (lot == null) throw notFound("寄存批次不存在");
                    if (!expiry.isAfter(date(s(lot, "expires_on")))) throw conflict("新到期日必须晚于原到期日");
                    db.update(
                            "UPDATE storage_lots SET expires_on=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            expiry.toString(),
                            actor,
                            id);
                    event(id, "extend", 0, "expiry:" + expiry, reason, actor);
                    access.audit("storage.extend", "storage", id, reason);
                    return obj("ok", true);
                });
    }
    /** 老订单仅人工转换真实未履约数量，不重扣款、不重扣可售库存。 */
    public ObjectNode importLine(long lineId, JsonNode body, long actor) {
        Input.strict(body, "quantity", "reason");
        long qty = Input.integer(body, "quantity", 1, 10000000);
        String reason = Input.text(body, "reason", 1, 2000);
        return db.tx(
                () -> {
                    ObjectNode line =
                            db.one(
                                    "SELECT ol.* FROM order_lines ol JOIN skus s ON s.id=ol.sku_id AND s.is_deleted=0 WHERE ol.id=? AND ol.is_deleted=0 AND s.storage_enabled=1 AND s.storage_exclusion='none'",
                                    lineId);
                    if (line == null) throw conflict("订单明细不存在或不在寄存白名单");
                    ObjectNode order =
                            db.one(
                                    "SELECT * FROM orders WHERE id=? AND is_deleted=0",
                                    l(line, "order_id"));
                    if (!Arrays.asList("paid", "partially_refunded")
                            .contains(s(order, "payment_state"))) throw conflict("只能转换有效已付款订单");
                    ObjectNode old =
                            db.one(
                                    "SELECT * FROM storage_lots WHERE line_id=? AND is_deleted=0",
                                    lineId);
                    if (old != null) {
                        if (l(old, "total_qty") == qty) return old;
                        throw conflict("该行已有寄存，不得重复转换");
                    }
                    if (l(line, "refund_held") > 0
                            || qty
                                    > l(line, "quantity")
                                            - l(line, "shipped_qty")
                                            - l(line, "refunded_unshipped")
                            || db.one(
                                            "SELECT pl.id FROM pickup_lines pl JOIN pickup_orders p ON p.id=pl.pickup_id AND p.is_deleted=0 WHERE pl.line_id=? AND pl.is_deleted=0 AND p.status IN ('awaiting_payment','review','approved') LIMIT 1",
                                            lineId)
                                    != null) throw conflict("该商品已履约、售后占用或已有配送申请");
                    ObjectNode payment =
                            db.one(
                                    "SELECT paid_at FROM payments WHERE order_id=? AND is_deleted=0",
                                    l(order, "id"));
                    if (payment == null) throw conflict("缺少原付款日期，请核实原支付记录");
                    long lot =
                            createLot(
                                    order,
                                    line,
                                    qty,
                                    Instant.parse(s(payment, "paid_at")),
                                    actor,
                                    "import");
                    db.update(
                            "UPDATE order_lines SET storage_qty=storage_qty+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            qty,
                            actor,
                            lineId);
                    access.audit("storage.import", "storage", lot, reason);
                    return obj("id", lot);
                });
    }
    /** 寄存操作不可变流水，唯一键防止重复计数。 */
    private void event(
            long lot, String kind, long qty, String reference, String reason, long actor) {
        db.insert(
                "INSERT INTO storage_events(lot_id,kind,quantity,reference_key,reason,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?)",
                lot,
                kind,
                qty,
                reference,
                reason,
                actor,
                actor);
    }
    /** 站内提醒先落库，外部消息在事务外发送。 */
    public void message(
            long user, Long lot, String kind, String title, String body, String key, long actor) {
        if (db.one("SELECT id FROM storage_messages WHERE dedup_key=? AND is_deleted=0", key)
                != null) return;
        db.insert(
                "INSERT INTO storage_messages(user_id,lot_id,kind,title,body,dedup_key,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?)",
                user,
                lot,
                kind,
                title,
                body,
                key,
                actor,
                actor);
    }
    /** ISO日期严格解析，客户端值不作为SQL片段使用。 */
    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            throw invalid("日期须为YYYY-MM-DD");
        }
    }
}
