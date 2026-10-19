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
import com.jianyanji.orders.OrderService;
import com.jianyanji.wallet.WalletService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** 入驻产品包配置、申请、核款交付、独立身份审批及退款身份处理。 */
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

    public MembershipService(
            Db db, Access access, @Lazy WalletService wallet, @Lazy OrderService orders) {
        this.db = db;
        this.access = access;
        this.wallet = wallet;
        this.orders = orders;
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
            Set<Long> skus = new HashSet<>();
            long sum = 0;
            for (JsonNode line : sourceLines) {
                Input.strict(line, "skuId", "quantity", "unitCents");
                long skuId = entity(line, "skuId"),
                        quantity = Input.integer(line, "quantity", 1, 999);
                long cents = Input.integer(line, "unitCents", 1, 100000000);
                Input.require(skus.add(skuId), "产品包规格不可重复");
                sum += quantity * cents;
                lines.add(obj("skuId", skuId, "quantity", quantity, "unitCents", cents));
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
        Input.strict(
                raw, "targetIdentity", "addressId", "inviterId", "agreementAccepted", "requestKey");
        String target = choice(raw, "targetIdentity", "B", "C"),
                requestKey = Input.text(raw, "requestKey", 12, 100);
        long addressId = entity(raw, "addressId"), userId = l(user, "id");
        Long inviterId = nullableId(raw, "inviterId");
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
                            long rewardCents = rewardAmount(target);
                            ObjectNode snapshot = p.deepCopy();
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
                                            null,
                                            "rewardCents",
                                            rewardCents,
                                            "afterSalesDays",
                                            l(policy, "days"),
                                            "afterSalesPolicy",
                                            s(policy, "policy"),
                                            "agreementAcceptedAt",
                                            now()));
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
                            db.update(
                                    "UPDATE onboardings SET package_snapshot=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    stringify(snapshot),
                                    userId,
                                    value);
                            access.audit(
                                    "onboarding.apply",
                                    "onboarding",
                                    value,
                                    "确认产品包及协议，等待真实到账核实与身份审批");
                            return value;
                        });
        return applicationView(application(applicationId, userId));
    }

    /** 财务核款，直接扣实物可用库存并生成产品包订单，不占用其他商品订单库存。 */
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
                    if (!"submitted".equals(s(a, "status")) || has(a, "receipt_id"))
                        throw conflict("该申请已核款或不在办理中");
                    JsonNode p = parse(s(a, "package_snapshot"));
                    ObjectNode receiptInput = ((ObjectNode) raw).deepCopy();
                    receiptInput
                            .put("userId", l(a, "user_id"))
                            .put("purpose", "package")
                            .put("onboardingId", applicationId);
                    long receiptId = wallet.registerReceipt(receiptInput, actor);
                    for (JsonNode line : list(p.get("lines"))) {
                        if (db.update(
                                        "UPDATE skus SET stock=stock-?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0 AND stock-reserved>=?",
                                        l(line, "quantity"),
                                        actor,
                                        l(line, "skuId"),
                                        l(line, "quantity"))
                                != 1) throw conflict("产品包库存不足，收款登记与交付订单未提交，请人工核实后重试");
                    }
                    long amount = l(p, "amountCents");
                    ObjectNode snapshot = ((ObjectNode) p).deepCopy();
                    snapshot.setAll(
                            obj(
                                    "onboardingId",
                                    applicationId,
                                    "receiptId",
                                    receiptId,
                                    "goodsCents",
                                    amount,
                                    "shippingCents",
                                    0,
                                    "totalCents",
                                    amount));
                    long orderId =
                            db.insert(
                                    "INSERT INTO orders(number,user_id,kind,status,payment_method,payment_state,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key,create_user_id,update_user_id) VALUES(?,?,'package','paid','offline','paid',?,?,0,?,?,?,?,?)",
                                    "PK" + id(),
                                    l(a, "user_id"),
                                    amount,
                                    amount,
                                    stringify(snapshot),
                                    now(),
                                    "onboarding:" + applicationId,
                                    actor,
                                    actor);
                    db.insert(
                            "INSERT INTO payments(order_id,method,transaction_id,cents,paid_at,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?)",
                            orderId,
                            "offline",
                            "company_receipt:" + receiptId,
                            amount,
                            now(),
                            actor,
                            actor);
                    for (JsonNode line : list(p.get("lines")))
                        db.insert(
                                "INSERT INTO order_lines(order_id,sku_id,quantity,unit_cents,principal_cents,create_user_id,update_user_id) VALUES(?,?,?,?,NULL,?,?)",
                                orderId,
                                l(line, "skuId"),
                                l(line, "quantity"),
                                l(line, "unitCents"),
                                actor,
                                actor);
                    db.update(
                            "UPDATE onboardings SET receipt_id=?,order_id=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            receiptId,
                            orderId,
                            actor,
                            applicationId);
                    access.audit("onboarding.receipt", "onboarding", applicationId, reason);
                });
        return applicationView(application(applicationId, null));
    }

    /** 独立审批身份和两类代理关系，通过时冻结资格依据，不因核款自动取得身份。 */
    public ObjectNode review(long applicationId, JsonNode raw, long actor) {
        String decision = choice(raw, "decision", "approved", "rejected");
        boolean approved = "approved".equals(decision);
        if (approved)
            Input.strict(
                    raw,
                    "decision",
                    "inviterId",
                    "principalId",
                    "relationshipsConfirmed",
                    "conditionsMet",
                    "reason");
        else Input.strict(raw, "decision", "reason");
        String reason = Input.text(raw, "reason", 1, 200);
        Long inviterId = approved ? nullableId(raw, "inviterId") : null;
        long principalId = approved ? entity(raw, "principalId") : 0;
        if (approved) {
            Input.require(Input.bool(raw, "relationshipsConfirmed"), "必须确认直接邀请人与归属主理人关系");
            Input.require(Input.bool(raw, "conditionsMet"), "必须确认符合入驻条件");
        }
        db.tx(
                () -> {
                    ObjectNode a = application(applicationId, null);
                    if (!"submitted".equals(s(a, "status"))) throw conflict("该申请已审批，不得重复修改审批结果");
                    if (approved) {
                        if (!has(a, "receipt_id") || !has(a, "order_id"))
                            throw conflict("须先核实有效产品包收款并建立实物交付订单");
                        ObjectNode receipt =
                                db.one(
                                        "SELECT * FROM company_receipts WHERE id=? AND user_id=? AND purpose='package' AND onboarding_id=? AND is_deleted=0",
                                        l(a, "receipt_id"),
                                        l(a, "user_id"),
                                        applicationId);
                        if (receipt == null
                                || l(receipt, "cash_refunded_cents") != 0
                                || l(receipt, "cash_held_cents") != 0)
                            throw conflict("收款无效、已退款或正在退款，不能审批通过");
                        ObjectNode order = orders.owned(l(a, "order_id"), null);
                        if (!"paid".equals(s(order, "payment_state"))
                                || !"package".equals(s(order, "kind"))
                                || !"offline".equals(s(order, "payment_method")))
                            throw conflict("产品包交付订单状态无效");
                        if (db.one(
                                        "SELECT id FROM refunds WHERE order_id=? AND status IN ('requested','approved','processing','unknown','failed') AND is_deleted=0 LIMIT 1",
                                        l(order, "id"))
                                != null) throw conflict("产品包正在办理售后，须先核实最终收款与资格");
                        if ((inviterId != null && inviterId == l(a, "user_id"))
                                || principalId == l(a, "user_id"))
                            throw conflict("直接邀请人或归属主理人不能是申请人本人");
                        ObjectNode inviter =
                                inviterId == null ? null : wallet.requireAgent(inviterId);
                        ObjectNode principal = wallet.requireAgent(principalId);
                        if (!"B".equals(effectiveIdentity(principal)))
                            throw conflict("归属对象必须是当前有效调肤主理人");
                        long rewardCents = rewardAmount(s(a, "target_identity"));
                        JsonNode p = parse(s(a, "package_snapshot"));
                        ObjectNode qualification =
                                obj(
                                        "conditionsMet",
                                        true,
                                        "conditions",
                                        p.get("conditions"),
                                        "relationshipsConfirmed",
                                        true,
                                        "inviterId",
                                        inviterId,
                                        "inviterIdentity",
                                        inviter == null ? null : effectiveIdentity(inviter),
                                        "principalId",
                                        principalId,
                                        "targetIdentity",
                                        s(a, "target_identity"),
                                        "receiptId",
                                        l(a, "receipt_id"),
                                        "orderId",
                                        l(a, "order_id"),
                                        "rewardCents",
                                        rewardCents,
                                        "afterSalesDays",
                                        p.get("afterSalesDays"),
                                        "afterSalesPolicy",
                                        p.get("afterSalesPolicy"));
                        ObjectNode snapshot = ((ObjectNode) p).deepCopy();
                        snapshot.setAll(
                                obj(
                                        "inviterId",
                                        inviterId,
                                        "principalId",
                                        principalId,
                                        "rewardCents",
                                        rewardCents,
                                        "qualification",
                                        qualification,
                                        "onboardingApproved",
                                        true));
                        db.update(
                                "UPDATE onboardings SET status='approved',inviter_id=?,principal_id=?,reward_cents=?,package_snapshot=?,qualification_snapshot=?,approved_at=?,reviewed_by=?,review_reason=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                inviterId,
                                principalId,
                                rewardCents,
                                stringify(snapshot),
                                stringify(qualification),
                                now(),
                                actor,
                                reason,
                                actor,
                                applicationId);
                        ObjectNode orderSnapshot = (ObjectNode) parse(s(order, "snapshot"));
                        orderSnapshot.setAll(snapshot);
                        db.update(
                                "UPDATE orders SET snapshot=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                stringify(orderSnapshot),
                                actor,
                                l(order, "id"));
                        db.update(
                                "UPDATE users SET identity=?,identity_valid_until=NULL,inviter_id=?,principal_id=?,onboarding_id=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                s(a, "target_identity"),
                                inviterId,
                                principalId,
                                applicationId,
                                actor,
                                l(a, "user_id"));
                    } else {
                        db.update(
                                "UPDATE onboardings SET status='rejected',reviewed_by=?,review_reason=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                actor,
                                reason,
                                actor,
                                applicationId);
                    }
                    access.audit("onboarding.review", "onboarding", applicationId, reason);
                });
        return applicationView(application(applicationId, null));
    }

    /** 仅在实际退款成功事务内调用，按原收款累计核对金额，撤销不影响用户后续另次入驻身份。 */
    public void applyPackageRefundIdentity(
            ObjectNode order, boolean full, String decision, long actor, String reason) {
        db.assertTransaction();
        Input.require(
                reason != null && !reason.trim().isEmpty() && reason.trim().length() <= 2000,
                "退款身份处理须说明原因");
        if (!"package".equals(s(order, "kind")) || !"offline".equals(s(order, "payment_method")))
            throw conflict("该订单不是公司转款产品包");
        ObjectNode a =
                db.one(
                        "SELECT * FROM onboardings WHERE order_id=? AND user_id=? AND is_deleted=0",
                        l(order, "id"),
                        l(order, "user_id"));
        if (a == null || !has(a, "receipt_id")) throw conflict("找不到原产品包入驻与收款依据");
        ObjectNode receipt =
                db.one(
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
        if (db.update(
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
            db.update(
                    "UPDATE users SET identity='D',identity_valid_until=NULL,onboarding_id=NULL,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND onboarding_id=? AND is_deleted=0",
                    actor,
                    l(order, "user_id"),
                    l(a, "id"));
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
                            "SELECT s.id,s.name AS skuName,s.unit,s.stock,s.reserved,s.active,p.id AS productId,p.name,p.image,p.active AS pa,c.active AS ca FROM skus s JOIN products p ON p.id=s.product_id AND p.is_deleted=0 JOIN categories c ON c.id=p.category_id AND c.is_deleted=0 WHERE s.id=? AND s.is_deleted=0",
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
        if (settings == null || settings.isNull()) throw conflict("产品包和两档入驻奖励尚未配置");
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

    /** 从奖励域固定配置表读取当次档位；审批时再次冻结，历史订单不随配置变化。 */
    private long rewardAmount(String target) {
        ObjectNode row = db.one("SELECT value FROM reward_settings WHERE id=1 AND is_deleted=0");
        if (row == null) throw conflict("两档入驻奖励尚未配置");
        JsonNode value = parse(s(row, "value"));
        for (String key : new String[] {"principalRewardCents", "storeRewardCents"}) {
            if (!has(value, key)
                    || !value.get(key).isIntegralNumber()
                    || !value.get(key).canConvertToLong()
                    || l(value, key) <= 0
                    || l(value, key) > 100000000) throw conflict("两档入驻奖励尚未配置");
        }
        return l(value, "B".equals(target) ? "principalRewardCents" : "storeRewardCents");
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
}
