package com.jianyanji.membership;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;
import static com.jianyanji.wallet.WalletService.choice;
import static com.jianyanji.wallet.WalletService.entity;
import static com.jianyanji.wallet.WalletService.rawText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Db;
import com.jianyanji.common.Input;
import com.jianyanji.common.PageQuery;
import com.jianyanji.orders.OrderService;
import com.jianyanji.wallet.WalletService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** 入驻产品包配置、申请、核款交付、到账激活及退款身份处理。 */
@Service
public class MembershipService {
    /** 数据库与原子事务组件。 */
    private final Db db;
    /** 请求身份及审计组件。 */
    private final Access access;
    /** 公司收款登记和有效代理校验服务。 */
    private final WalletService wallet;
    /** 订单查询与展示服务，延迟注入防止退款业务依赖环。 */
    private final OrderService orders;
    /** 各次资格及交接服务。 */
    private final QualificationService qualifications;
    /** 邀请及关系校验服务。 */
    private final InvitationService invitations;

    /** 后台按页查询全部有效记录，筛选和计数在数据库执行。 */
    public ObjectNode adminPage(java.util.Map<String, String> query) {
        return new PageQuery(
                        query,
                        "FROM onboardings o JOIN users u ON u.id=o.user_id AND u.is_deleted=0 WHERE o.is_deleted=0")
                .eq("status", "o.status")
                .eq("identity", "o.target_identity")
                .keyword("CAST(o.id AS CHAR)", "CAST(o.user_id AS CHAR)", "u.nickname")
                .select(db, "o.*,u.nickname", "o.id DESC", this::applicationView);
    }

    public MembershipService(
            Db db, Access access, @Lazy WalletService wallet, @Lazy OrderService orders) {
        this.db = db;
        this.access = access;
        this.wallet = wallet;
        this.orders = orders;
        this.qualifications = new QualificationService(db, access);
        this.invitations = new InvitationService(db, access);
    }

    /** 读取公开产品包，展示有效商品快照及公司转款资料。 */
    public ObjectNode publicView() {
        JsonNode settings = db.setting("membership");
        ArrayNode packages = arr();
        if (settings != null && settings.has("packages")) {
            for (JsonNode p : list(settings.get("packages"))) {
                if (!b(p, "active")) continue;
                ObjectNode item = ((ObjectNode) p).deepCopy();
                item.set("lines", packageLines(p));
                packages.add(item);
            }
        }
        return obj(
                "packages",
                packages,
                "aftersales",
                db.setting("aftersales"),
                "transfer",
                wallet.companyTransfer());
    }

    /** 返回后台原始配置，未配置时为null。 */
    public JsonNode settings() {
        return db.setting("membership");
    }

    /** 一次读取产品包可选规格，只返回未删除且商品、分类、规格均启用的名称与编号。 */
    public List<ObjectNode> packageSkuOptions() {
        return db.list(
                "SELECT s.id,p.name AS productName,s.name AS skuName,s.unit "
                        + "FROM skus s JOIN products p ON p.id=s.product_id "
                        + "JOIN categories c ON c.id=p.category_id "
                        + "WHERE s.is_deleted=0 AND p.is_deleted=0 AND c.is_deleted=0 "
                        + "AND s.active=1 AND p.active=1 AND c.active=1 ORDER BY p.id,s.id");
    }

    /** 严格验证恰好两种身份产品包，单价分摊总和必须等于实收货款。 */
    public ObjectNode validateSettings(JsonNode raw) {
        Input.strict(raw, "packages");
        ArrayNode source = Input.array(raw, "packages", 2, 2), packages = arr();
        Set<String> identities = new HashSet<>();
        for (JsonNode p : source) {
            Input.strict(
                    p,
                    "targetIdentity",
                    "name",
                    "amountCents",
                    "valueCents",
                    "selfUseLabel",
                    "benefits",
                    "conditions",
                    "agreement",
                    "rightsImage",
                    "active",
                    "lines");
            String target = choice(p, "targetIdentity", "B", "C");
            Input.require(identities.add(target), "须分别配置主理人和店主产品包");
            long amount = Input.integer(p, "amountCents", 1, 100000000);
            Input.require(
                    amount == ("B".equals(target) ? 1980000 : 298000),
                    "店主产品包货款须为2980元，主理人产品包须为19800元");
            ObjectNode item =
                    obj(
                            "targetIdentity",
                            target,
                            "name",
                            Input.text(p, "name", 1, 80),
                            "amountCents",
                            amount,
                            "valueCents",
                            Input.integer(p, "valueCents", 1, 100000000),
                            "selfUseLabel",
                            Input.text(p, "selfUseLabel", 1, 40),
                            "benefits",
                            Input.text(p, "benefits", 1, 2000),
                            "conditions",
                            Input.text(p, "conditions", 1, 2000),
                            "agreement",
                            Input.text(p, "agreement", 1, 2000),
                            "active",
                            Input.bool(p, "active"));
            String image = rawText(p, "rightsImage");
            Input.require(
                    image.length() <= 1000
                            && (image.isEmpty()
                                    || image.startsWith("https://")
                                    || image.matches("/assets/[a-zA-Z0-9_./-]+")),
                    "权益图片须为本站素材或HTTPS地址");
            item.put("rightsImage", image);
            ArrayNode sourceLines = Input.array(p, "lines", 1, 50), lines = arr();
            Set<String> skus = new HashSet<>();
            long sum = 0;
            for (JsonNode line : sourceLines) {
                Input.strict(line, "skuId", "quantity", "unitCents", "isGift");
                long skuId = entity(line, "skuId"),
                        quantity = Input.integer(line, "quantity", 1, 999);
                boolean gift = line.has("isGift") && Input.bool(line, "isGift");
                long cents = Input.integer(line, "unitCents", gift ? 0 : 1, 100000000);
                Input.require(!gift || cents == 0, "赠品分摊价格须为0");
                Input.require(skus.add(skuId + ":" + gift), "产品包规格不可重复");
                sum += quantity * cents;
                lines.add(
                        obj(
                                "skuId",
                                skuId,
                                "quantity",
                                quantity,
                                "unitCents",
                                cents,
                                "isGift",
                                gift));
            }
            Input.require(sum == amount, "产品包各商品分摊货款之和须等于产品包货款");
            item.set("lines", lines);
            packages.add(item);
        }
        return obj("packages", packages);
    }

    /** 配置修改与审计在同一事务中完成。 */
    public ObjectNode updateSettings(JsonNode raw, long actor) {
        Input.strict(raw, "packages", "reason");
        String reason = Input.text(raw, "reason", 1, 200);
        ObjectNode value = validateSettings(obj("packages", raw.get("packages")));
        return db.tx(
                () -> {
                    for (JsonNode p : list(value.get("packages"))) packageLines(p);
                    db.putSetting("membership", value, actor);
                    access.audit("membership.settings", "membership", "settings", reason);
                    return value;
                });
    }

    /** 读取申请，传入用户编号时强制所有权，不存在时返回404。 */
    public ObjectNode application(long applicationId, Long userId) {
        ObjectNode a =
                userId == null
                        ? db.one(
                                "SELECT * FROM onboardings WHERE id=? AND is_deleted=0",
                                applicationId)
                        : db.one(
                                "SELECT * FROM onboardings WHERE id=? AND user_id=? AND is_deleted=0",
                                applicationId,
                                userId);
        if (a == null) throw notFound("入驻申请不存在");
        return a;
    }

    /** 返回申请、产品包和资格快照、原收款、身份事件及交付订单。 */
    public ObjectNode applicationView(ObjectNode application) {
        ObjectNode result = application.deepCopy();
        result.set("package_snapshot", parse(s(application, "package_snapshot")));
        result.set(
                "qualification_snapshot",
                has(application, "qualification_snapshot")
                        ? parse(s(application, "qualification_snapshot"))
                        : null);
        result.set(
                "receipt",
                has(application, "receipt_id")
                        ? db.one(
                                "SELECT id,purpose,amount_cents,received_at,confirmed_at,external_transaction_id FROM company_receipts WHERE id=? AND is_deleted=0",
                                l(application, "receipt_id"))
                        : null);
        result.set(
                "identityEvents",
                json(
                        db.list(
                                "SELECT * FROM onboarding_identity_events WHERE onboarding_id=? AND is_deleted=0 ORDER BY id",
                                l(application, "id"))));
        result.set(
                "order",
                has(application, "order_id")
                        ? orders.view(
                                orders.owned(l(application, "order_id"), l(application, "user_id")))
                        : null);
        return result;
    }

    /** 用户或后台申请列表，保持原有数量上限。 */
    public ArrayNode applications(Long userId) {
        List<ObjectNode> rows =
                userId == null
                        ? db.list(
                                "SELECT o.*,u.nickname FROM onboardings o JOIN users u ON u.id=o.user_id AND u.is_deleted=0 WHERE o.is_deleted=0 ORDER BY o.id DESC LIMIT 200")
                        : db.list(
                                "SELECT * FROM onboardings WHERE user_id=? AND is_deleted=0 ORDER BY id DESC LIMIT 100",
                                userId);
        ArrayNode result = arr();
        for (ObjectNode row : rows) result.add(applicationView(row));
        return result;
    }

    /** 本人申请身份；冻结商品、收货地址、协议、售后政策及当次奖励档位。 */
    public ObjectNode apply(JsonNode raw, ObjectNode user) {
        return applyInternal(raw, user, null);
    }

    /** 授权首批新入驻特例，仍须真实付款与交付后才激活，不用存量核验伪装购包。 */
    public ObjectNode initialApplication(JsonNode body, long actor) {
        Input.strict(
                body,
                "userId",
                "targetIdentity",
                "addressId",
                "principalId",
                "agreementAccepted",
                "conditionsMet",
                "evidence",
                "reason",
                "requestKey");
        long uid = entity(body, "userId");
        String target = choice(body, "targetIdentity", "B", "C");
        String evidence = Input.text(body, "evidence", 1, 2000),
                reason = Input.text(body, "reason", 1, 2000);
        long principal = "B".equals(target) ? uid : entity(body, "principalId");
        return db.tx(
                () -> {
                    ObjectNode user =
                            db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", uid);
                    if (user == null) throw notFound("申请人不存在");
                    if (!"D".equals(effectiveIdentity(user))) throw conflict("首批新入驻仅供尚未取得代理资格的用户");
                    if ("C".equals(target)) wallet.requireAgent(principal);
                    if ("C".equals(target)
                            && !"B"
                                    .equals(
                                            effectiveIdentity(
                                                    db.one(
                                                            "SELECT * FROM users WHERE id=? AND is_deleted=0",
                                                            principal))))
                        throw conflict("首批店主仍须有效主理人归属");
                    ObjectNode request =
                            obj(
                                    "targetIdentity",
                                    target,
                                    "addressId",
                                    entity(body, "addressId"),
                                    "principalId",
                                    principal,
                                    "inviterId",
                                    null,
                                    "relationshipsConfirmed",
                                    true,
                                    "conditionsMet",
                                    Input.bool(body, "conditionsMet"),
                                    "agreementAccepted",
                                    Input.bool(body, "agreementAccepted"),
                                    "requestKey",
                                    Input.text(body, "requestKey", 12, 100));
                    ObjectNode result =
                            applyInternal(
                                    request,
                                    user,
                                    obj(
                                            "inviterId",
                                            null,
                                            "inviterIdentity",
                                            null,
                                            "principalId",
                                            principal,
                                            "inviterRelationVersion",
                                            0,
                                            "evidence",
                                            evidence));
                    access.audit(
                            "onboarding.initial",
                            "onboarding",
                            l(result, "id"),
                            reason + "；首批免邀请依据：" + evidence);
                    return result;
                });
    }

    /** 普通与首批特例复用同一商品、地址、资格和资金快照。 */
    private ObjectNode applyInternal(JsonNode raw, ObjectNode user, ObjectNode initialRelation) {
        Input.strict(
                raw,
                "targetIdentity",
                "addressId",
                "inviterId",
                "shareCode",
                "principalId",
                "relationshipsConfirmed",
                "conditionsMet",
                "agreementAccepted",
                "deliveryMode",
                "shipQuantities",
                "storedItems",
                "requestKey");
        String target = choice(raw, "targetIdentity", "B", "C"),
                requestKey = Input.text(raw, "requestKey", 12, 100);
        long addressId = entity(raw, "addressId"), userId = l(user, "id");
        Long inviterId = nullableId(raw, "inviterId");
        Input.require(
                Input.bool(raw, "relationshipsConfirmed") && Input.bool(raw, "conditionsMet"),
                "须在付款前确认邀请归属和入驻资格");
        Input.require(initialRelation != null || inviterId != null, "普通入驻必须有有效邀请人");
        ObjectNode relation =
                initialRelation != null
                        ? initialRelation
                        : invitations.candidate(
                                raw.has("shareCode") ? Input.text(raw, "shareCode", 0, 64) : "",
                                inviterId,
                                target,
                                userId);
        Input.require(l(relation, "principalId") == entity(raw, "principalId"), "归属已变化，请重新确认");
        Input.require(
                initialRelation != null || l(relation, "inviterId") == inviterId.longValue(),
                "邀请候选已变化，请重新确认");
        Input.require(Input.bool(raw, "agreementAccepted"), "必须确认入驻协议");
        long applicationId =
                db.tx(
                        () -> {
                            ObjectNode old =
                                    db.one(
                                            "SELECT * FROM onboardings WHERE user_id=? AND request_key=? AND is_deleted=0",
                                            userId,
                                            requestKey);
                            if (old != null) {
                                JsonNode snap = parse(s(old, "package_snapshot"));
                                if (!target.equals(s(old, "target_identity"))
                                        || !sameNullableId(
                                                old.get("requested_inviter_id"), inviterId)
                                        || l(snap.get("address"), "id") != addressId)
                                    throw conflict("申请编号已用于其他内容");
                                JsonNode requested = raw.get("shipQuantities");
                                if (requested != null) {
                                    Input.require(
                                            requested.isArray()
                                                    && requested.size()
                                                            == snap.path("lines").size(),
                                            "重复申请的固定商品明细已变化");
                                    for (int i = 0; i < requested.size(); i++)
                                        if (!requested.get(i).isIntegralNumber()
                                                || requested.get(i).asLong()
                                                        != snap.path("lines")
                                                                .get(i)
                                                                .path("shipQuantity")
                                                                .asLong(
                                                                        l(
                                                                                snap.path("lines")
                                                                                        .get(i),
                                                                                "quantity")))
                                            throw conflict("申请编号已用于其他发货/寄存选择");
                                }
                                return l(old, "id");
                            }
                            ObjectNode current =
                                    db.one(
                                            "SELECT * FROM users WHERE id=? AND is_deleted=0",
                                            userId);
                            if (current == null) throw notFound("申请人不存在");
                            String identity = effectiveIdentity(current);
                            if ("B".equals(identity)
                                    || ("C".equals(identity) && "C".equals(target)))
                                throw conflict("已具备该身份，请勿重复申请");
                            if (db.one(
                                            "SELECT id FROM onboardings WHERE user_id=? AND status='submitted' AND is_deleted=0",
                                            userId)
                                    != null) throw conflict("已有办理中的入驻申请");
                            if (inviterId != null) {
                                if (inviterId == userId) throw conflict("不能邀请自己");
                                wallet.requireAgent(inviterId);
                            }
                            ObjectNode p = findPackage(target);
                            JsonNode policy = aftersales();
                            ObjectNode address =
                                    db.one(
                                            "SELECT id,recipient,phone,province,city,district,detail FROM addresses WHERE id=? AND user_id=? AND is_deleted=0",
                                            addressId,
                                            userId);
                            if (address == null) throw conflict("请选择本人有效收货地址");
                            JsonNode shipping = db.setting("shipping");
                            boolean supported = false;
                            if (shipping != null && shipping.has("provinces"))
                                for (JsonNode province : list(shipping.get("provinces")))
                                    if (province.asText().equals(s(address, "province")))
                                        supported = true;
                            if (!supported) throw conflict("该地址暂不支持配送，请联系品牌方");
                            long rewardCents = initialRelation != null ? 0 : rewardAmount(target);
                            ObjectNode snapshot = p.deepCopy();
                            String deliveryMode =
                                    raw.has("deliveryMode")
                                            ? choice(raw, "deliveryMode", "ship", "store")
                                            : "ship";
                            snapshot.put("deliveryMode", deliveryMode);
                            snapshot.put("businessVersion", "points-storage-v3");
                            snapshot.setAll(
                                    obj(
                                            "lines",
                                            packageLines(p),
                                            "address",
                                            address,
                                            "identity",
                                            target,
                                            "inviterId",
                                            inviterId,
                                            "principalId",
                                            l(relation, "principalId"),
                                            "inviterIdentity",
                                            s(relation, "inviterIdentity"),
                                            "inviterRelationVersion",
                                            l(relation, "inviterRelationVersion"),
                                            "relationshipsConfirmed",
                                            true,
                                            "conditionsMet",
                                            true,
                                            "rewardCents",
                                            rewardCents,
                                            "afterSalesDays",
                                            l(policy, "days"),
                                            "afterSalesPolicy",
                                            s(policy, "policy"),
                                            "agreementAcceptedAt",
                                            now()));
                            JsonNode selectedQuantities = raw.get("shipQuantities");
                            if (selectedQuantities != null)
                                Input.require(
                                        selectedQuantities.isArray()
                                                && selectedQuantities.size()
                                                        == snapshot.path("lines").size(),
                                        "产品包发货数量须对应固定配置");
                            int packageIndex = 0;
                            for (JsonNode packageLine : snapshot.path("lines")) {
                                ((ObjectNode) packageLine)
                                        .put(
                                                "shipQuantity",
                                                "store".equals(deliveryMode)
                                                        ? 0
                                                        : l(packageLine, "quantity"));
                                if (selectedQuantities != null) {
                                    JsonNode n = selectedQuantities.get(packageIndex);
                                    Input.require(
                                            n.isIntegralNumber()
                                                    && n.canConvertToLong()
                                                    && n.asLong() >= 0
                                                    && n.asLong() <= l(packageLine, "quantity"),
                                            "产品包本次发货数量超出固定数量");
                                    ((ObjectNode) packageLine).put("shipQuantity", n.asLong());
                                }
                                packageIndex++;
                                if (l(packageLine, "shipQuantity") < l(packageLine, "quantity")
                                        && !b(packageLine, "storageEnabled"))
                                    throw conflict("产品包存在不支持寄存的商品，请选择全部发货或联系总部调整配置");
                            }
                            snapshot.put("goodsCents", l(p, "amountCents"));
                            new com.jianyanji.storage.StorageService(db, access)
                                    .quoteCombined(snapshot, raw, current);
                            long value =
                                    db.insert(
                                            "INSERT INTO onboardings(user_id,target_identity,package_snapshot,requested_inviter_id,reward_cents,after_sales_days,request_key,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?)",
                                            userId,
                                            target,
                                            stringify(snapshot),
                                            inviterId,
                                            rewardCents,
                                            l(policy, "days"),
                                            requestKey,
                                            userId,
                                            userId);
                            snapshot.put("onboardingId", value);
                            if (initialRelation != null) {
                                snapshot.put("initialVerified", true);
                                snapshot.put("initialEvidence", s(initialRelation, "evidence"));
                            }
                            db.update(
                                    "UPDATE onboardings SET inviter_id=?,principal_id=?,relation_confirmed=1,relation_version=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    inviterId,
                                    l(relation, "principalId"),
                                    l(current, "relation_version"),
                                    userId,
                                    value);
                            db.update(
                                    "UPDATE onboardings SET package_snapshot=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    stringify(snapshot),
                                    userId,
                                    value);
                            access.audit(
                                    "onboarding.apply",
                                    "onboarding",
                                    value,
                                    "已确认资格、协议与邀请归属，等待真实到账激活");
                            return value;
                        });
        return applicationView(application(applicationId, userId));
    }

    /** 本人待付款申请创建唯一交付订单；两渠道复用该订单，库存按订单统一占用。 */
    public ObjectNode preparePayment(long applicationId, long uid) {
        return db.tx(
                () -> {
                    ObjectNode a = application(applicationId, uid);
                    if (!"submitted".equals(s(a, "status"))) throw conflict("申请不在待付款状态");
                    if (has(a, "order_id")) return orders.view(orders.owned(l(a, "order_id"), uid));
                    assertConfirmed(a);
                    long oid = ensureOrder(a, uid);
                    return orders.view(orders.owned(oid, uid));
                });
    }

    /** 关系变化后由本人重新确认，存在未决支付时先查明原交易。 */
    public ObjectNode reconfirm(long applicationId, JsonNode body, long uid) {
        Input.strict(body, "inviterId", "principalId", "relationshipsConfirmed", "conditionsMet");
        Input.require(
                Input.bool(body, "relationshipsConfirmed") && Input.bool(body, "conditionsMet"),
                "请确认资格及邀请归属");
        return db.tx(
                () -> {
                    ObjectNode a = application(applicationId, uid);
                    if (!"submitted".equals(s(a, "status"))) throw conflict("已付款申请不能改写关系");
                    if (has(a, "order_id")) assertNoPayment(l(a, "order_id"));
                    ObjectNode rel =
                            invitations.candidate(
                                    null, entity(body, "inviterId"), s(a, "target_identity"), uid);
                    if (entity(body, "principalId") != l(rel, "principalId"))
                        throw conflict("请重新核对归属主理人");
                    ObjectNode snap = (ObjectNode) parse(s(a, "package_snapshot"));
                    snap.remove("initialVerified");
                    snap.remove("initialEvidence");
                    snap.put("inviterId", l(rel, "inviterId"))
                            .put("inviterIdentity", s(rel, "inviterIdentity"))
                            .put("principalId", l(rel, "principalId"))
                            .put("inviterRelationVersion", l(rel, "inviterRelationVersion"));
                    db.update(
                            "UPDATE onboardings SET inviter_id=?,requested_inviter_id=?,principal_id=?,package_snapshot=?,relation_confirmed=1,relation_version=(SELECT relation_version FROM users WHERE id=? AND is_deleted=0),update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            l(rel, "inviterId"),
                            l(rel, "inviterId"),
                            l(rel, "principalId"),
                            stringify(snap),
                            uid,
                            uid,
                            applicationId);
                    if (has(a, "order_id")) {
                        ObjectNode order = orders.owned(l(a, "order_id"), uid);
                        ObjectNode os = (ObjectNode) parse(s(order, "snapshot"));
                        os.setAll(
                                obj(
                                        "inviterId",
                                        rel.get("inviterId"),
                                        "inviterIdentity",
                                        rel.get("inviterIdentity"),
                                        "principalId",
                                        rel.get("principalId"),
                                        "inviterRelationVersion",
                                        rel.get("inviterRelationVersion")));
                        db.update(
                                "UPDATE orders SET snapshot=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                stringify(os),
                                uid,
                                l(order, "id"));
                    }
                    access.audit(
                            "onboarding.reconfirm", "onboarding", applicationId, "本人重新确认付款前关系");
                    return applicationView(application(applicationId, uid));
                });
    }

    /** 财务核实公司款后同事务完成订单交付和资格激活，不再独立审批。 */
    public ObjectNode confirmReceipt(long applicationId, JsonNode raw, long actor) {
        Input.strict(
                raw,
                "sourceChannel",
                "accountRef",
                "externalTransactionId",
                "amountCents",
                "receivedAt",
                "evidence",
                "reason",
                "verified");
        String reason = Input.text(raw, "reason", 1, 200);
        db.tx(
                () -> {
                    ObjectNode a = application(applicationId, null);
                    if (has(a, "receipt_id")) {
                        ObjectNode r =
                                db.one(
                                        "SELECT * FROM company_receipts WHERE id=? AND is_deleted=0",
                                        l(a, "receipt_id"));
                        if (r != null
                                && s(r, "source_channel").equals(s(raw, "sourceChannel"))
                                && s(r, "account_ref").equals(s(raw, "accountRef"))
                                && s(r, "external_transaction_id")
                                        .equals(s(raw, "externalTransactionId"))
                                && l(r, "amount_cents") == l(raw, "amountCents")) return;
                        throw conflict("申请已有其他有效收款，请核对原交易");
                    }
                    if (!"submitted".equals(s(a, "status"))) throw conflict("申请不在待付款状态");
                    assertConfirmed(a);
                    long orderId = ensureOrder(a, actor);
                    assertNoPayment(orderId);
                    ObjectNode receiptInput = ((ObjectNode) raw).deepCopy();
                    receiptInput
                            .put("userId", l(a, "user_id"))
                            .put("purpose", "package")
                            .put("onboardingId", applicationId);
                    long receiptId = wallet.registerReceipt(receiptInput, actor);
                    ObjectNode order = orders.owned(orderId, null);
                    for (ObjectNode line :
                            db.list(
                                    "SELECT sku_id,quantity FROM order_lines WHERE order_id=? AND is_deleted=0",
                                    orderId)) {
                        long q = l(line, "quantity");
                        if (db.update(
                                        "UPDATE skus SET stock=stock-?,reserved=reserved-?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND stock>=? AND reserved>=? AND is_deleted=0",
                                        q,
                                        q,
                                        actor,
                                        l(line, "sku_id"),
                                        q,
                                        q)
                                != 1) throw conflict("产品包库存与占用不一致，请核实");
                    }
                    db.update(
                            "UPDATE orders SET status='paid',payment_state='paid',payment_method='offline',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            actor,
                            orderId);
                    db.insert(
                            "INSERT INTO payments(order_id,method,transaction_id,cents,paid_at,create_user_id,update_user_id) VALUES(?,'offline',?,?,?,?,?)",
                            orderId,
                            "company_receipt:" + receiptId,
                            l(order, "total_cents"),
                            now(),
                            actor,
                            actor);
                    db.update(
                            "UPDATE onboardings SET receipt_id=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            receiptId,
                            actor,
                            applicationId);
                    activatePaid(orders.owned(orderId, null), actor);
                    access.audit("onboarding.receipt", "onboarding", applicationId, reason);
                });
        return applicationView(application(applicationId, null));
    }

    /** 停用旧付款后审批入口，避免绕过付款前资格确认。 */
    public ObjectNode review(long applicationId, JsonNode raw, long actor) {
        throw conflict("V2核实到账后自动激活；资格和邀请归属须在付款前确认");
    }

    /** 支付成功事务的资格处理钩子；微信和公司转款均关联唯一真实订单。 */
    public void activatePaid(ObjectNode order, long actor) {
        db.assertTransaction();
        ObjectNode a =
                db.one(
                        "SELECT * FROM onboardings WHERE order_id=? AND user_id=? AND is_deleted=0",
                        l(order, "id"),
                        l(order, "user_id"));
        if (a == null) throw conflict("产品包缺少原申请依据");
        if ("approved".equals(s(a, "status"))) return;
        if (!"submitted".equals(s(a, "status"))
                || !b(a, "relation_confirmed")
                || !"paid".equals(s(order, "payment_state")))
            throw conflict("产品包申请／到账状态不允许激活，须按原交易核对");
        ObjectNode snap = (ObjectNode) parse(s(order, "snapshot"));
        snap.put("onboardingApproved", true);
        ObjectNode qualification =
                obj(
                        "conditionsMet",
                        true,
                        "relationshipsConfirmed",
                        true,
                        "inviterId",
                        a.get("inviter_id"),
                        "inviterIdentity",
                        snap.get("inviterIdentity"),
                        "principalId",
                        a.get("principal_id"),
                        "targetIdentity",
                        s(a, "target_identity"),
                        "orderId",
                        l(order, "id"),
                        "receiptId",
                        a.get("receipt_id"));
        snap.set("qualification", qualification);
        db.update(
                "UPDATE onboardings SET status='approved',approved_at=?,qualification_snapshot=?,package_snapshot=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                now(),
                stringify(qualification),
                stringify(snap),
                actor,
                l(a, "id"));
        db.update(
                "UPDATE orders SET snapshot=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                stringify(snap),
                actor,
                l(order, "id"));
        qualifications.activate(a, actor);
        access.auditSystem(
                "system",
                actor,
                "qualification.activate",
                "onboarding",
                l(a, "id"),
                "真实产品包到账并关联交付",
                "success",
                "onboarding:" + l(a, "id"));
    }

    /** 微信发起前核对付款前确认，不在支付回调时重新解释历史邀请关系。 */
    public void assertOrderPayable(ObjectNode order) {
        if (!"package".equals(s(order, "kind"))) return;
        ObjectNode a =
                db.one(
                        "SELECT * FROM onboardings WHERE order_id=? AND user_id=? AND is_deleted=0",
                        l(order, "id"),
                        l(order, "user_id"));
        if (a == null) throw conflict("产品包申请不存在");
        assertConfirmed(a);
    }

    /** 付款前核对已确认关系仍与当前候选一致，变更后必须本人再次确认。 */
    private void assertConfirmed(ObjectNode a) {
        if (!b(a, "relation_confirmed")) throw conflict("邀请关系待本人重新确认");
        JsonNode snap = parse(s(a, "package_snapshot"));
        if (b(snap, "initialVerified")) {
            ObjectNode current =
                    db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", l(a, "user_id"));
            if (current == null || !"D".equals(effectiveIdentity(current)))
                throw conflict("首批申请资格已变化，请核对");
            if ("C".equals(s(a, "target_identity"))
                    && !"B".equals(effectiveIdentity(wallet.requireAgent(l(a, "principal_id")))))
                throw conflict("首批店主归属主理人失效");
            return;
        }
        ObjectNode rel =
                invitations.candidate(
                        null, l(a, "inviter_id"), s(a, "target_identity"), l(a, "user_id"));
        ObjectNode u = db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", l(a, "user_id"));
        if (l(rel, "principalId") != l(a, "principal_id")
                || l(rel, "inviterRelationVersion") != l(snap, "inviterRelationVersion")
                || u == null
                || l(u, "relation_version") != l(a, "relation_version"))
            throw conflict("邀请或归属已变化，请本人重新确认后付款");
        String current = effectiveIdentity(u);
        if ("B".equals(current) || ("C".equals(current) && "C".equals(s(a, "target_identity"))))
            throw conflict("已具有该资格，请核对原申请");
    }

    /** 有效支付未查明前不切换渠道或重建订单。 */
    private void assertNoPayment(long orderId) {
        ObjectNode o = orders.owned(orderId, null);
        if (!"pending".equals(s(o, "status")) || !in(s(o, "payment_state"), "unpaid", "failed"))
            throw conflict("原订单已结算、关闭或支付结果未明，请先核对");
        if (db.one(
                        "SELECT id FROM payment_attempts WHERE order_id=? AND state NOT IN ('closed') AND is_deleted=0 LIMIT 1",
                        orderId)
                != null) throw conflict("请先查询并关闭原微信交易，再切换公司转款");
    }

    /** 创建一次产品包待付款订单及实际SKU占用，过期关闭后须重新申请。 */
    private long ensureOrder(ObjectNode a, long actor) {
        if (has(a, "order_id")) {
            assertNoPayment(l(a, "order_id"));
            return l(a, "order_id");
        }
        JsonNode p = parse(s(a, "package_snapshot"));
        long amount = l(p, "amountCents");
        JsonNode shipping = db.setting("shipping");
        long minutes = shipping == null ? 0 : l(shipping, "pendingMinutes");
        if (minutes < 1 || minutes > 10080) throw conflict("请先配置有效待付款时长");
        ObjectNode snapshot = ((ObjectNode) p).deepCopy();
        snapshot.setAll(obj("goodsCents", amount, "shippingCents", 0, "totalCents", amount));
        new com.jianyanji.storage.StorageService(db, access)
                .quoteCombined(
                        snapshot,
                        p,
                        db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", l(a, "user_id")));
        long oid =
                db.insert(
                        "INSERT INTO orders(number,user_id,kind,status,payment_state,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key,create_user_id,update_user_id) VALUES(?,?,'package','pending','unpaid',?,?,0,?,?,?,?,?)",
                        "PK" + id(),
                        l(a, "user_id"),
                        amount,
                        amount,
                        stringify(snapshot),
                        java.time.Instant.now().plusSeconds(minutes * 60).toString(),
                        "onboarding:" + l(a, "id"),
                        actor,
                        actor);
        for (JsonNode line : list(p.get("lines"))) {
            long qty = l(line, "quantity");
            if (db.update(
                            "UPDATE skus SET reserved=reserved+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND stock-reserved>=? AND is_deleted=0",
                            qty,
                            actor,
                            l(line, "skuId"),
                            qty)
                    != 1) throw conflict("产品包可用库存不足，请联系品牌方");
            db.insert(
                    "INSERT INTO order_lines(order_id,sku_id,quantity,unit_cents,principal_cents,storage_qty,is_gift,create_user_id,update_user_id) VALUES(?,?,?,?,NULL,?,?,?,?)",
                    oid,
                    l(line, "skuId"),
                    qty,
                    l(line, "unitCents"),
                    qty - line.path("shipQuantity").asLong(qty),
                    b(line, "isGift") ? 1 : 0,
                    actor,
                    actor);
        }
        db.update(
                "UPDATE onboardings SET order_id=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                oid,
                actor,
                l(a, "id"));
        if (!snapshot.has("storedItems")) snapshot.set("storedItems", arr());
        new com.jianyanji.storage.StorageService(db, access).reserveCombined(oid, snapshot, actor);
        return oid;
    }

    /** 提供各次资格及首批核验入口。 */
    public QualificationService qualifications() {
        return qualifications;
    }
    /** 提供明确邀请关系接口。 */
    public InvitationService invitations() {
        return invitations;
    }
    /** 退款审核时提前核对主理人团队接管条件。 */
    public void assertRefundRevocable(ObjectNode order) {
        if (!"package".equals(s(order, "kind"))) return;
        ObjectNode source =
                db.one(
                        "SELECT * FROM onboardings WHERE order_id=? AND user_id=? AND is_deleted=0",
                        l(order, "id"),
                        l(order, "user_id"));
        if (source == null) throw conflict("缺少产品包原资格来源，须先核对");
        qualifications.assertSourceRevocable(source);
    }

    /** 仅在实际退款成功事务内调用，按原收款累计核对金额，撤销不影响用户后续另次入驻身份。 */
    public void applyPackageRefundIdentity(
            ObjectNode order, boolean full, String decision, long actor, String reason) {
        db.assertTransaction();
        Input.require(
                reason != null && !reason.trim().isEmpty() && reason.trim().length() <= 2000,
                "退款身份处理须说明原因");
        if (!"package".equals(s(order, "kind"))) throw conflict("该订单不是产品包");
        ObjectNode a =
                db.one(
                        "SELECT * FROM onboardings WHERE order_id=? AND user_id=? AND is_deleted=0",
                        l(order, "id"),
                        l(order, "user_id"));
        if (a == null) throw conflict("找不到原产品包入驻依据");
        boolean offline = "offline".equals(s(order, "payment_method"));
        ObjectNode receipt =
                !offline
                        ? obj("amount_cents", l(order, "total_cents"), "cash_refunded_cents", 0)
                        : db.one(
                                "SELECT * FROM company_receipts WHERE id=? AND purpose='package' AND onboarding_id=? AND user_id=? AND is_deleted=0",
                                l(a, "receipt_id"),
                                l(a, "id"),
                                l(order, "user_id"));
        if (receipt == null) throw conflict("原产品包收款不存在");
        long refunded =
                l(
                        db.one(
                                "SELECT COALESCE(SUM(total_cents),0) AS total FROM refunds WHERE order_id=? AND status='success' AND is_deleted=0",
                                l(order, "id")),
                        "total");
        if (refunded == 0
                || refunded > l(receipt, "amount_cents")
                || refunded < l(receipt, "cash_refunded_cents")) throw conflict("实际成功退款与原产品包收款不一致");
        if (full && refunded != l(receipt, "amount_cents")) throw conflict("产品包全退金额与原收款不一致");
        String chosen = full ? "revoke" : decision;
        if (chosen == null || chosen.isEmpty()) throw conflict("部分退款须明确保留或撤销身份");
        Input.require("retain".equals(chosen) || "revoke".equals(chosen), "退款身份结论无效");
        ObjectNode existing =
                db.one(
                        "SELECT decision,full_refund FROM onboarding_identity_events WHERE onboarding_id=? AND refunded_cents=? AND is_deleted=0",
                        l(a, "id"),
                        refunded);
        if (existing != null) {
            if (!chosen.equals(s(existing, "decision")) || b(existing, "full_refund") != full)
                throw conflict("本次退款身份结论已登记，不能无痕更改");
            return;
        }
        ObjectNode user =
                db.one(
                        "SELECT identity,identity_valid_until,inviter_id,principal_id,onboarding_id FROM users WHERE id=? AND is_deleted=0",
                        l(order, "user_id"));
        if (user == null) throw conflict("原申请人不存在");
        if (offline
                && db.update(
                                "UPDATE company_receipts SET cash_refunded_cents=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0 AND cash_held_cents+?<=amount_cents",
                                refunded,
                                actor,
                                l(a, "receipt_id"),
                                refunded)
                        != 1) throw conflict("原产品包款正在办理其他退款，须先核对");
        if ("revoke".equals(chosen)) {
            db.update(
                    "UPDATE onboardings SET status='revoked',review_reason=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                    reason,
                    actor,
                    l(a, "id"));
            qualifications.revoke(a, actor, reason);
            ObjectNode snapshot = (ObjectNode) parse(s(order, "snapshot"));
            snapshot.put("onboardingApproved", false).put("identityRefundDecision", "revoke");
            db.update(
                    "UPDATE orders SET snapshot=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                    stringify(snapshot),
                    actor,
                    l(order, "id"));
        }
        db.insert(
                "INSERT INTO onboarding_identity_events(onboarding_id,order_id,prior_status,decision,full_refund,refunded_cents,user_snapshot,reason,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                l(a, "id"),
                l(order, "id"),
                s(a, "status"),
                chosen,
                full ? 1 : 0,
                refunded,
                stringify(user),
                reason,
                actor,
                actor);
    }

    /** 校验商品仍在售并冻结交付所需商品信息；库存留给核款事务统一扣减。 */
    private ArrayNode packageLines(JsonNode p) {
        ArrayNode result = arr();
        for (JsonNode line : list(p.get("lines"))) {
            ObjectNode sku =
                    db.one(
                            "SELECT s.id,s.name AS skuName,s.unit,s.stock,s.reserved,s.storage_enabled,s.storage_exclusion,s.active,p.id AS productId,p.name,p.image,p.active AS pa,c.active AS ca FROM skus s JOIN products p ON p.id=s.product_id AND p.is_deleted=0 JOIN categories c ON c.id=p.category_id AND c.is_deleted=0 WHERE s.id=? AND s.is_deleted=0",
                            l(line, "skuId"));
            if (sku == null || !b(sku, "active") || !b(sku, "pa") || !b(sku, "ca"))
                throw conflict("产品包商品已停售，请品牌方重新配置");
            result.add(
                    obj(
                            "skuId",
                            l(line, "skuId"),
                            "quantity",
                            l(line, "quantity"),
                            "unitCents",
                            l(line, "unitCents"),
                            "isGift",
                            b(line, "isGift"),
                            "lineKey",l(line,"skuId")+":"+b(line,"isGift"),
                            "storageEnabled",
                            b(sku, "storage_enabled") && "none".equals(s(sku, "storage_exclusion")),
                            "principalCents",
                            null,
                            "productId",
                            l(sku, "productId"),
                            "name",
                            s(sku, "name"),
                            "skuName",
                            s(sku, "skuName"),
                            "unit",
                            s(sku, "unit"),
                            "image",
                            s(sku, "image")));
        }
        return result;
    }

    /** 查找开放身份的产品包，并严格验证存储配置。 */
    private ObjectNode findPackage(String target) {
        JsonNode settings = db.setting("membership");
        if (settings == null || settings.isNull()) throw conflict("产品包尚未配置");
        ObjectNode validated = validateSettings(settings);
        for (JsonNode p : list(validated.get("packages")))
            if (target.equals(s(p, "targetIdentity")) && b(p, "active")) return (ObjectNode) p;
        throw conflict("该身份产品包暂未开放申请");
    }

    /** 售后期必须已统一配置，申请时冻结其天数和政策。 */
    private JsonNode aftersales() {
        JsonNode policy = db.setting("aftersales");
        if (policy == null
                || !policy.hasNonNull("days")
                || !policy.get("days").isIntegralNumber()
                || !policy.get("days").canConvertToLong()
                || l(policy, "days") < 0
                || s(policy, "policy").trim().isEmpty()) throw conflict("售后期和售后政策尚未配置，暂不能申请入驻");
        return policy;
    }

    /** 未确认主理人奖励关闭，不以奖励配置作为付款或激活的前置。 */
    private long rewardAmount(String target) {
        return "C".equals(target) ? 100000 : 0;
    }

    /** nullable编号字段必须出现，只有JSON null表示无直接邀请人。 */
    private static Long nullableId(JsonNode raw, String key) {
        Input.require(raw.has(key), key + "为必填字段，可为null");
        return raw.get(key).isNull() ? null : entity(raw, key);
    }

    /** 比较原请求可空编号，避免把null等同于零。 */
    private static boolean sameNullableId(JsonNode old, Long value) {
        return value == null
                ? old == null || old.isNull()
                : old != null && !old.isNull() && old.asLong() == value;
    }
    /** 校验固定枚举集合。 */
    private static boolean in(String value, String... values) {
        return java.util.Arrays.asList(values).contains(value);
    }
}
