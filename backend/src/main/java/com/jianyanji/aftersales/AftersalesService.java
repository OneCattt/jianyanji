package com.jianyanji.aftersales;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import com.jianyanji.common.Input;
import com.jianyanji.membership.MembershipService;
import com.jianyanji.orders.OrderService;
import com.jianyanji.payments.WechatPayClient;
import com.jianyanji.rewards.RewardService;
import com.jianyanji.wallet.WalletService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** 商品订单售后：按原商品数量和支付依据退款，积分、身份、奖励冲回与退款完成原子提交。 */
@Service
public class AftersalesService {
    /** 失败诊断日志，包含订单、退款单及请求编号。 */
    private static final Logger LOG = LoggerFactory.getLogger(AftersalesService.class);
    /** MySQL 持久化和全局业务事务。 */
    private final Db db;
    /** 当前后台操作人及审计上下文。 */
    private final Access access;
    /** 订单权限、快照和履约聚合服务。 */
    private final OrderService orders;
    /** 仅退回原积分支付的领域服务。 */
    private final WalletService wallet;
    /** 产品包身份保留与撤销服务。 */
    private final MembershipService membership;
    /** 原订单奖励冲回服务。 */
    private final RewardService rewards;
    /** 微信真实退款和原退款单查询接口。 */
    private final WechatPayClient channel;
    /** 更新操作人和毫秒审计时间的 SQL 片段。 */
    private static final String STAMP = "update_user_id=?,update_time=CURRENT_TIMESTAMP(3)";

    public AftersalesService(
            Db db,
            Access access,
            @Lazy OrderService orders,
            @Lazy WalletService wallet,
            @Lazy MembershipService membership,
            @Lazy RewardService rewards,
            WechatPayClient channel) {
        this.db = db;
        this.access = access;
        this.orders = orders;
        this.wallet = wallet;
        this.membership = membership;
        this.rewards = rewards;
        this.channel = channel;
    }

    public JsonNode settings() {
        return db.setting("aftersales");
    }

    public JsonNode updateSettings(JsonNode body) {
        Input.strict(body, "reason", "days", "policy");
        String reason = Input.reason(body);
        long days = Input.integer(body, "days", 0, 365);
        String policy = Input.text(body, "policy", 1, 4000);
        ObjectNode value = obj("days", days, "policy", policy);
        db.tx(
                () -> {
                    db.putSetting("aftersales", value, access.actor());
                    access.audit("aftersales.settings", "aftersales", "policy", reason);
                });
        return value;
    }

    public List<ObjectNode> listRefunds(Long userId) {
        List<ObjectNode> rows =
                userId == null
                        ? db.list(
                                "SELECT * FROM refunds WHERE is_deleted=0 ORDER BY id DESC LIMIT 200")
                        : db.list(
                                "SELECT r.* FROM refunds r JOIN orders o ON o.id=r.order_id AND o.is_deleted=0 WHERE o.user_id=? AND r.is_deleted=0 ORDER BY r.id DESC LIMIT 100",
                                userId);
        return rows.stream().map(this::view).collect(Collectors.toList());
    }

    public ObjectNode getRefund(long id) {
        ObjectNode refund = db.one("SELECT * FROM refunds WHERE id=? AND is_deleted=0", id);
        if (refund == null) throw notFound("售后记录不存在");
        return refund;
    }

    public ObjectNode view(ObjectNode row) {
        ObjectNode result = row.deepCopy();
        result.remove(Arrays.asList("channel_token", "channel_until"));
        result.set("lines", parse(s(row, "lines")));
        result.set("policy", parse(s(row, "policy_snapshot")));
        return result;
    }

    /** 校验原请求全部字段；禁止数量隐式转换、重复行及空的退款申请。 */
    public ObjectNode request(JsonNode body, ObjectNode staff) {
        Input.strict(
                body,
                "orderId",
                "items",
                "shippingCents",
                "reason",
                "requestKey",
                "exceptionReason");
        long orderId = Input.integer(body, "orderId", 1, 9007199254740991L);
        ArrayNode items = Input.array(body, "items", 0, 50);
        long shipping = Input.integer(body, "shippingCents", 0, 10000000);
        String reason = Input.reason(body);
        Input.require(
                body.path("requestKey").isTextual()
                        && s(body, "requestKey").matches("[a-zA-Z0-9_-]{12,100}"),
                "请求编号格式不合法");
        String key = s(body, "requestKey");
        String exceptionReason =
                body.has("exceptionReason") ? Input.text(body, "exceptionReason", 1, 200) : null;
        Set<Long> seen = new HashSet<>();
        for (JsonNode item : items) {
            Input.strict(item, "lineId", "quantity");
            long lineId = Input.integer(item, "lineId", 1, 9007199254740991L);
            Input.integer(item, "quantity", 1, 999);
            Input.require(seen.add(lineId), "商品行不能重复");
        }
        Input.require(items.size() > 0 || shipping > 0, "请选择退款商品或运费");
        long actor = l(staff, "id");
        return view(
                db.tx(
                        () -> {
                            ObjectNode old =
                                    db.one(
                                            "SELECT * FROM refunds WHERE request_key=? AND is_deleted=0",
                                            key);
                            if (old != null) {
                                JsonNode policy = parse(s(old, "policy_snapshot"));
                                ArrayNode oldItems = arr();
                                for (JsonNode line : list(parse(s(old, "lines"))))
                                    oldItems.add(
                                            obj(
                                                    "lineId",
                                                    l(line, "lineId"),
                                                    "quantity",
                                                    l(line, "quantity")));
                                boolean sameItems = oldItems.size() == items.size();
                                for (int i = 0; sameItems && i < items.size(); i++)
                                    sameItems =
                                            l(oldItems.get(i), "lineId")
                                                            == l(items.get(i), "lineId")
                                                    && l(oldItems.get(i), "quantity")
                                                            == l(items.get(i), "quantity");
                                if (l(old, "order_id") != orderId
                                        || !reason.equals(s(old, "reason"))
                                        || l(old, "shipping_cents") != shipping
                                        || !Objects.equals(
                                                has(policy, "exceptionReason")
                                                        ? s(policy, "exceptionReason")
                                                        : null,
                                                exceptionReason)
                                        || !sameItems) throw conflict("请求编号已用于其他售后");
                                return old;
                            }
                            ObjectNode order = orders.owned(orderId, null);
                            if (!"paid".equals(s(order, "payment_state")))
                                throw conflict("订单尚未实际付款，不能退款");
                            JsonNode snapshot = parse(s(order, "snapshot"));
                            if (!snapshot.has("afterSalesDays")
                                    || s(snapshot, "afterSalesPolicy").isEmpty())
                                throw conflict("原订单缺少售后政策快照，请核对原购买依据");
                            boolean expired =
                                    has(order, "received_at")
                                            && Instant.now()
                                                    .isAfter(
                                                            Instant.parse(s(order, "received_at"))
                                                                    .plusSeconds(
                                                                            l(
                                                                                            snapshot,
                                                                                            "afterSalesDays")
                                                                                    * 86400));
                            if (expired && exceptionReason == null)
                                throw conflict("已超过原订单售后期限，须由具备财务权限的品牌方特批并记录原因");
                            if (exceptionReason != null && !hasPermission(staff, "finance:manage"))
                                throw new BusinessException(403, "FORBIDDEN", "超期特批须具备财务权限");
                            ArrayNode lines = arr();
                            long goods = 0;
                            for (JsonNode item : items) {
                                long lineId = l(item, "lineId"), quantity = l(item, "quantity");
                                ObjectNode line =
                                        db.one(
                                                "SELECT * FROM order_lines WHERE id=? AND order_id=? AND is_deleted=0",
                                                lineId,
                                                orderId);
                                if (line == null) throw conflict("商品不属于该订单");
                                if (quantity
                                        > l(line, "quantity")
                                                - l(line, "refunded_qty")
                                                - l(line, "refund_held"))
                                    throw conflict("商品已退款或正在办理售后，不能超退");
                                long unshipped =
                                        Math.min(
                                                quantity,
                                                l(line, "quantity")
                                                        - l(line, "shipped_qty")
                                                        - l(line, "refunded_unshipped")
                                                        - l(line, "held_unshipped"));
                                if (db.update(
                                                "UPDATE order_lines SET refund_held=refund_held+?,held_unshipped=held_unshipped+?,"
                                                        + STAMP
                                                        + " WHERE id=? AND quantity-refunded_qty-refund_held>=? AND is_deleted=0",
                                                quantity,
                                                unshipped,
                                                actor,
                                                lineId,
                                                quantity)
                                        == 0) throw conflict("可退数量不足");
                                lines.add(
                                        obj(
                                                "lineId",
                                                lineId,
                                                "skuId",
                                                l(line, "sku_id"),
                                                "quantity",
                                                quantity,
                                                "unitCents",
                                                l(line, "unit_cents"),
                                                "principalCents",
                                                line.get("principal_cents"),
                                                "unshippedQuantity",
                                                unshipped,
                                                "returnQuantity",
                                                quantity - unshipped));
                                goods =
                                        Math.addExact(
                                                goods,
                                                Math.multiplyExact(
                                                        l(line, "unit_cents"), quantity));
                            }
                            ObjectNode held =
                                    db.one(
                                            "SELECT COALESCE(SUM(shipping_cents),0) AS shipping,COALESCE(SUM(total_cents),0) AS total "
                                                    + "FROM refunds WHERE order_id=? AND status<>'rejected' AND is_deleted=0",
                                            orderId);
                            if (shipping + l(held, "shipping") > l(order, "shipping_cents")
                                    || goods + shipping + l(held, "total")
                                            > l(order, "total_cents"))
                                throw conflict("退款金额不能超过原订单剩余额度");
                            long refundId =
                                    db.insert(
                                            "INSERT INTO refunds(order_id,number,kind,goods_cents,shipping_cents,total_cents,`lines`,reason,policy_snapshot,request_key,request_id,create_user_id,update_user_id) "
                                                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                            orderId,
                                            "RF" + id(),
                                            s(order, "kind"),
                                            goods,
                                            shipping,
                                            goods + shipping,
                                            stringify(lines),
                                            reason,
                                            stringify(
                                                    obj(
                                                            "days",
                                                            snapshot.get("afterSalesDays"),
                                                            "policy",
                                                            snapshot.get("afterSalesPolicy"),
                                                            "exceptionReason",
                                                            exceptionReason)),
                                            key,
                                            access.requestId(),
                                            actor,
                                            actor);
                            access.audit(
                                    "refund.request",
                                    "refund",
                                    refundId,
                                    reason
                                            + (exceptionReason == null
                                                    ? ""
                                                    : "；品牌方特批：" + exceptionReason));
                            return getRefund(refundId);
                        }));
    }

    public ObjectNode review(long id, JsonNode body) {
        Input.strict(body, "approve", "identityDecision", "reason");
        boolean approve = Input.bool(body, "approve");
        Input.require(
                body.has("identityDecision")
                        && (body.get("identityDecision").isNull()
                                || (body.get("identityDecision").isTextual()
                                        && in(s(body, "identityDecision"), "retain", "revoke"))),
                "身份结论必须为 retain、revoke 或 null");
        String decision = has(body, "identityDecision") ? s(body, "identityDecision") : null,
                reason = Input.reason(body);
        long actor = access.actor();
        db.tx(
                () -> {
                    ObjectNode refund = getRefund(id);
                    if (!"requested".equals(s(refund, "status"))) throw conflict("该售后已审核");
                    if (approve && "package".equals(s(refund, "kind")) && decision == null)
                        throw conflict("产品包部分退款须记录身份保留或撤销结论");
                    if (approve && "package".equals(s(refund, "kind")) && "revoke".equals(decision))
                        membership.assertRefundRevocable(orders.owned(l(refund, "order_id"), null));
                    if (!approve)
                        for (JsonNode line : list(parse(s(refund, "lines")))) {
                            if (db.update(
                                            "UPDATE order_lines SET refund_held=refund_held-?,held_unshipped=held_unshipped-?,"
                                                    + STAMP
                                                    + " WHERE id=? AND order_id=? AND refund_held>=? AND held_unshipped>=? AND is_deleted=0",
                                            l(line, "quantity"),
                                            l(line, "unshippedQuantity"),
                                            actor,
                                            l(line, "lineId"),
                                            l(refund, "order_id"),
                                            l(line, "quantity"),
                                            l(line, "unshippedQuantity"))
                                    == 0) throw conflict("售后占用数量不一致，请核对");
                        }
                    db.update(
                            "UPDATE refunds SET status=?,identity_decision=?,"
                                    + STAMP
                                    + " WHERE id=? AND is_deleted=0",
                            approve ? "approved" : "rejected",
                            decision,
                            actor,
                            id);
                    if (!approve) orders.refreshFulfillment(l(refund, "order_id"));
                    access.audit("refund.review", "refund", id, reason);
                });
        return view(getRefund(id));
    }

    public ObjectNode inspect(long id, JsonNode body) {
        Input.strict(body, "returnedAt", "inspection", "restock", "reason");
        String returnedAt = pastDate(body, "returnedAt", "退货收货时间不能在未来");
        String inspection = Input.text(body, "inspection", 1, 1000), reason = Input.reason(body);
        boolean restock = Input.bool(body, "restock");
        long actor = access.actor();
        db.tx(
                () -> {
                    ObjectNode refund = getRefund(id);
                    if (!in(
                            s(refund, "status"),
                            "approved",
                            "processing",
                            "unknown",
                            "failed",
                            "success")) throw conflict("请先审核售后");
                    if (has(refund, "returned_at")) throw conflict("该退货已登记验收");
                    for (JsonNode line : list(parse(s(refund, "lines")))) {
                        long quantity = l(line, "returnQuantity");
                        if (quantity == 0) continue;
                        db.insert(
                                "INSERT INTO returned_inventory(refund_id,line_id,quantity,restocked,inspection,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?)",
                                id,
                                l(line, "lineId"),
                                quantity,
                                restock ? 1 : 0,
                                inspection,
                                actor,
                                actor);
                        if (restock
                                && db.update(
                                                "UPDATE skus SET stock=stock+?,"
                                                        + STAMP
                                                        + " WHERE id=? AND is_deleted=0",
                                                quantity,
                                                actor,
                                                l(line, "skuId"))
                                        == 0) throw conflict("原规格不存在，请先处理库存依据");
                    }
                    db.update(
                            "UPDATE refunds SET returned_at=?,inspection=?,"
                                    + STAMP
                                    + " WHERE id=? AND is_deleted=0",
                            returnedAt,
                            inspection,
                            actor,
                            id);
                    access.audit("refund.inspect", "refund", id, reason);
                });
        return view(getRefund(id));
    }

    public ObjectNode points(long id, JsonNode body) {
        String reason = strictReason(body);
        db.tx(
                () -> {
                    ObjectNode refund = getRefund(id),
                            order = orders.owned(l(refund, "order_id"), null);
                    if (!"points".equals(s(order, "payment_method"))) throw conflict("只能退回原积分支付");
                    finishRefund(refund, access.actor(), access.requestId());
                    access.audit("refund.points", "refund", id, reason);
                });
        return view(getRefund(id));
    }

    public ObjectNode offline(long id, JsonNode body) {
        Input.strict(body, "recipient", "paidAt", "proof", "confirmed", "reason");
        String recipient = Input.text(body, "recipient", 1, 100),
                paidAt = pastDate(body, "paidAt", "实际付款时间不能在未来"),
                proof = Input.text(body, "proof", 1, 1000),
                reason = Input.reason(body);
        Input.require(Input.bool(body, "confirmed"), "请确认实际已付款");
        long actor = access.actor();
        try {
            db.tx(
                    () -> {
                        ObjectNode refund = getRefund(id),
                                order = orders.owned(l(refund, "order_id"), null);
                        if (!"offline".equals(s(order, "payment_method")))
                            throw conflict("仅原线下付款订单可登记线下退款，积分订货请退回原积分");
                        if ("success".equals(s(refund, "status"))) {
                            if (!recipient.equals(s(refund, "recipient"))
                                    || !paidAt.equals(s(refund, "paid_at"))
                                    || !proof.equals(s(refund, "proof")))
                                throw conflict("退款已登记成功，不能改写原实际退款凭证");
                            return;
                        }
                        assertRefundRevocableIfNeeded(refund, order);
                        db.update(
                                "UPDATE refunds SET recipient=?,paid_at=?,proof=?,channel_ref=NULL,"
                                        + STAMP
                                        + " WHERE id=? AND is_deleted=0",
                                recipient,
                                paidAt,
                                proof,
                                actor,
                                id);
                        finishRefund(refund, actor, access.requestId());
                        access.audit("refund.offline", "refund", id, reason);
                    });
        } catch (RuntimeException e) {
            LOG.error(
                    "线下退款登记失败，refundId={}，requestId={}，经办人员={}，原因={}",
                    id,
                    access.requestId(),
                    actor,
                    e.getMessage());
            throw e;
        }
        return view(getRefund(id));
    }

    /** 必须在事务内运行；渠道已成功而本地补偿失败时整笔回滚，可查询原退款单再次完成补偿。 */
    public void finishRefund(ObjectNode refund, long actor, String requestId) {
        db.assertTransaction();
        ObjectNode current = getRefund(l(refund, "id"));
        if ("success".equals(s(current, "status"))) return;
        if (!in(s(current, "status"), "approved", "processing", "unknown", "failed"))
            throw conflict("售后尚未审核通过");
        ObjectNode order = orders.owned(l(current, "order_id"), null);
        List<JsonNode> lines = list(parse(s(current, "lines")));
        requireInspection(current);
        assertRefundRevocableIfNeeded(current, order);
        if ("points".equals(s(order, "payment_method")))
            wallet.restoreOrderPoints(
                    l(order, "id"), l(current, "id"), l(current, "total_cents"), actor, requestId);
        for (JsonNode line : lines) {
            long quantity = l(line, "quantity"), unshipped = l(line, "unshippedQuantity");
            if (db.update(
                            "UPDATE order_lines SET refund_held=refund_held-?,held_unshipped=held_unshipped-?,refunded_qty=refunded_qty+?,refunded_unshipped=refunded_unshipped+?,"
                                    + STAMP
                                    + " WHERE id=? AND order_id=? AND refund_held>=? AND held_unshipped>=? AND is_deleted=0",
                            quantity,
                            unshipped,
                            quantity,
                            unshipped,
                            actor,
                            l(line, "lineId"),
                            l(order, "id"),
                            quantity,
                            unshipped)
                    == 0) throw conflict("售后占用数量不一致，请核对");
        }
        db.update(
                "UPDATE refunds SET status='success',completed_at=?,last_error='',"
                        + STAMP
                        + " WHERE id=? AND is_deleted=0",
                now(),
                actor,
                l(current, "id"));
        boolean full =
                db.one(
                                "SELECT id FROM order_lines WHERE order_id=? AND quantity>refunded_qty AND is_deleted=0 LIMIT 1",
                                l(order, "id"))
                        == null;
        if ("package".equals(s(order, "kind")))
            membership.applyPackageRefundIdentity(
                    order,
                    full,
                    has(current, "identity_decision") ? s(current, "identity_decision") : null,
                    actor,
                    s(current, "reason"));
        else if ("offline".equals(s(order, "payment_method"))) {
            long refunded =
                    l(
                            db.one(
                                    "SELECT COALESCE(SUM(total_cents),0) AS total FROM refunds WHERE order_id=? AND status='success' AND is_deleted=0",
                                    l(order, "id")),
                            "total");
            if (db.update(
                            "UPDATE company_receipts SET cash_refunded_cents=?,"
                                    + STAMP
                                    + " WHERE order_id=? AND purpose='order' AND amount_cents=? AND cash_refunded_cents<=? AND cash_held_cents+?<=amount_cents AND is_deleted=0",
                            refunded,
                            actor,
                            l(order, "id"),
                            l(order, "total_cents"),
                            refunded,
                            refunded)
                    != 1) throw conflict("实际退款与原普通货款收款记录不一致，请核对原业务");
        }
        rewards.reconcileReversals(l(order, "id"), actor, requestId);
        if (full)
            db.update(
                    "UPDATE orders SET status='refunded'," + STAMP + " WHERE id=? AND is_deleted=0",
                    actor,
                    l(order, "id"));
        else orders.refreshFulfillment(l(order, "id"));
    }

    /** 实际退款前按累计原货款判定全退；全退即使误选保留也必须先具备可撤销资格及团队接管计划。 */
    private void assertRefundRevocableIfNeeded(ObjectNode refund, ObjectNode order) {
        if (!"package".equals(s(order, "kind"))) return;
        if ("revoke".equals(s(refund, "identity_decision"))) {
            membership.assertRefundRevocable(order);
            return;
        }
        long completed =
                l(
                        db.one(
                                "SELECT COALESCE(SUM(total_cents),0) AS total FROM refunds WHERE order_id=? AND status='success' AND is_deleted=0",
                                l(order, "id")),
                        "total");
        if (Math.addExact(completed, l(refund, "total_cents")) >= l(order, "total_cents"))
            membership.assertRefundRevocable(order);
    }

    private ObjectNode originalPayment(ObjectNode order) {
        ObjectNode payment =
                db.one(
                        "SELECT * FROM payments WHERE order_id=? AND method='wechat' AND is_deleted=0",
                        l(order, "id"));
        if (payment == null
                || l(payment, "cents") != l(order, "total_cents")
                || s(payment, "out_trade_no").trim().isEmpty()
                || s(payment, "transaction_id").trim().isEmpty())
            throw conflict("原微信支付金额或流水不完整，请核对原支付依据");
        return payment;
    }

    /** 发起与查询共用持久租约，退款单号持久复用；旧响应不能覆盖新租约结果。 */
    public ObjectNode channel(long id, JsonNode body, boolean query) {
        com.jianyanji.payments.PaymentService.requireOnlinePayment();
        String reason = strictReason(body);
        if (!channel.configured())
            throw new BusinessException(503, "WECHAT_PAY_NOT_CONFIGURED", "微信商户退款尚未配置");
        long actor = access.actor();
        String requestId = access.requestId();
        ObjectNode work =
                db.tx(
                        () -> {
                            ObjectNode refund = getRefund(id),
                                    order = orders.owned(l(refund, "order_id"), null);
                            if (!"wechat".equals(s(order, "payment_method")))
                                throw conflict("该订单不是微信支付");
                            if ("success".equals(s(refund, "status")))
                                return obj("refund", refund, "token", "");
                            if (!in(
                                    s(refund, "status"),
                                    "approved",
                                    "failed",
                                    "processing",
                                    "unknown")) throw conflict("请先审核售后");
                            requireInspection(refund);
                            if (!query) assertRefundRevocableIfNeeded(refund, order);
                            if (!query && in(s(refund, "status"), "processing", "unknown"))
                                throw conflict("原退款结果未明，请查询原退款单");
                            ObjectNode payment = originalPayment(order);
                            String token = id();
                            if (db.update(
                                            "UPDATE refunds SET status='processing',channel_token=?,channel_until=?,request_id=?,"
                                                    + STAMP
                                                    + " WHERE id=? AND is_deleted=0 AND (channel_until IS NULL OR channel_until<?)",
                                            token,
                                            Instant.now().plusSeconds(30).toString(),
                                            requestId,
                                            actor,
                                            id,
                                            now())
                                    == 0) throw conflict("原退款正在处理，请稍后查询");
                            return obj(
                                    "refund", refund, "order", order, "payment", payment, "token",
                                    token);
                        });
        ObjectNode refund = (ObjectNode) work.get("refund");
        String token = s(work, "token");
        if (token.isEmpty()) return view(refund);
        ObjectNode payment = (ObjectNode) work.get("payment");
        try {
            JsonNode result =
                    query
                            ? channel.queryRefund(s(refund, "number"))
                            : channel.refund(
                                    obj(
                                            "outTradeNo",
                                            s(payment, "out_trade_no"),
                                            "transactionId",
                                            s(payment, "transaction_id"),
                                            "outRefundNo",
                                            s(refund, "number"),
                                            "totalCents",
                                            l(payment, "cents"),
                                            "refundCents",
                                            l(refund, "total_cents"),
                                            "reason",
                                            reason));
            applyChannel(id, token, result, actor, requestId);
        } catch (RuntimeException e) {
            boolean recorded =
                    db.tx(
                            () -> {
                                ObjectNode latest = getRefund(id);
                                if ("success".equals(s(latest, "status"))
                                        || !token.equals(s(latest, "channel_token"))) return false;
                                String message =
                                        e instanceof BusinessException
                                                ? e.getMessage()
                                                : "渠道退款结果待核查";
                                message =
                                        "退款="
                                                + s(refund, "number")
                                                + "，订单="
                                                + s(work.path("order"), "number")
                                                + "，requestId="
                                                + requestId
                                                + "；"
                                                + message;
                                db.update(
                                        "UPDATE refunds SET status='unknown',last_error=?,"
                                                + STAMP
                                                + " WHERE id=? AND is_deleted=0",
                                        truncate(message),
                                        actor,
                                        id);
                                access.audit("refund.unknown", "refund", id, message, "failure");
                                return true;
                            });
            if (recorded)
                LOG.error(
                        "退款结果待核查，退款={}，订单={}，requestId={}，原因={}",
                        s(refund, "number"),
                        s(work.path("order"), "number"),
                        requestId,
                        e.getMessage());
        } finally {
            db.tx(
                    () -> {
                        db.update(
                                "UPDATE refunds SET channel_token=NULL,channel_until=NULL,"
                                        + STAMP
                                        + " WHERE id=? AND channel_token=? AND is_deleted=0",
                                actor,
                                id,
                                token);
                    });
        }
        return view(getRefund(id));
    }

    private void applyChannel(
            long id, String token, JsonNode result, long actor, String requestId) {
        db.tx(
                () -> {
                    ObjectNode refund = getRefund(id);
                    if (!token.equals(s(refund, "channel_token"))
                            || "success".equals(s(refund, "status"))) return;
                    ObjectNode payment = originalPayment(orders.owned(l(refund, "order_id"), null));
                    if (!s(result, "outRefundNo").equals(s(refund, "number"))
                            || !s(result, "outTradeNo").equals(s(payment, "out_trade_no"))
                            || !result.path("totalCents").isIntegralNumber()
                            || !result.path("refundCents").isIntegralNumber()
                            || l(result, "totalCents") != l(payment, "cents")
                            || l(result, "refundCents") != l(refund, "total_cents"))
                        throw conflict("渠道退款对象或金额不一致，保留待核查状态");
                    String status = s(result, "status");
                    if (!in(status, "success", "processing", "failed", "unknown"))
                        throw conflict("渠道退款状态未知，保留待核查状态");
                    db.update(
                            "UPDATE refunds SET status=?,channel_ref=?,last_error=?,"
                                    + STAMP
                                    + " WHERE id=? AND is_deleted=0",
                            "success".equals(status) ? "processing" : status,
                            has(result, "refundId") ? s(result, "refundId") : null,
                            s(result, "reason"),
                            actor,
                            id);
                    if ("success".equals(status)) finishRefund(getRefund(id), actor, requestId);
                    access.audit(
                            "refund.channel",
                            "refund",
                            id,
                            s(result, "reason").isEmpty() ? "核对渠道退款结果" : s(result, "reason"),
                            "failed".equals(status) ? "failure" : "success");
                });
    }

    private static void requireInspection(JsonNode refund) {
        for (JsonNode line : list(parse(s(refund, "lines"))))
            if (l(line, "returnQuantity") > 0 && !has(refund, "returned_at"))
                throw conflict("请先登记实际退货验收");
    }

    private static String strictReason(JsonNode body) {
        Input.strict(body, "reason");
        return Input.reason(body);
    }

    private static boolean hasPermission(JsonNode staff, String permission) {
        JsonNode permissions = staff.path("permissions");
        if (permissions.isTextual()) permissions = parse(permissions.asText());
        for (JsonNode item : permissions) if (permission.equals(item.asText())) return true;
        return false;
    }

    private static String pastDate(JsonNode body, String field, String futureError) {
        Input.require(body.path(field).isTextual(), field + " 必须为 ISO UTC 时间");
        String value = s(body, field);
        try {
            Input.require(
                    value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?Z"),
                    field + " 必须为 ISO UTC 时间");
            Instant instant =
                    Instant.parse(value.length() == 17 ? value.substring(0, 16) + ":00Z" : value);
            if (instant.isAfter(Instant.now())) throw conflict(futureError);
        } catch (DateTimeParseException e) {
            throw invalid(field + " 必须为有效时间");
        }
        return value;
    }

    private static boolean in(String value, String... choices) {
        return Arrays.asList(choices).contains(value);
    }

    private static String truncate(String value) {
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
