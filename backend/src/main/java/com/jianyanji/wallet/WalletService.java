package com.jianyanji.wallet;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import com.jianyanji.common.Input;
import com.jianyanji.common.PageQuery;
import com.jianyanji.orders.OrderService;
import com.jianyanji.payments.PaymentService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** 公司收款及订货积分领域服务；金额以分为单位，所有账务写入保持同一事务。 */
@Service
public class WalletService {
    /** 事务与数据库访问组件。 */
    private final Db db;
    /** 当前请求权限与审计组件。 */
    private final Access access;
    /** 订单查询与展示服务，延迟注入以解除支付退款依赖环。 */
    private final OrderService orders;
    /** 订单最终支付结算服务，延迟注入以解除依赖环。 */
    private final PaymentService payments;

    /** 后台按页查询全部有效记录，筛选和计数在数据库执行。 */
    public ObjectNode adminPage(java.util.Map<String, String> query) {
        for (String key : new String[] {"userId", "receiptId"})
            if (query.containsKey(key) && !query.get(key).isEmpty()) pathId(query.get(key));
        return new PageQuery(query, "FROM company_receipts WHERE is_deleted=0")
                .eq("userId", "user_id")
                .eq("receiptId", "id")
                .eq("externalTransactionId", "external_transaction_id")
                .select(db, "*", "id DESC", row -> row);
    }

    public WalletService(
            Db db, Access access, @Lazy OrderService orders, @Lazy PaymentService payments) {
        this.db = db;
        this.access = access;
        this.orders = orders;
        this.payments = payments;
    }

    /** 仅当前有效主理人或店主可以办理订货积分业务。 */
    public ObjectNode requireAgent(long userId) {
        ObjectNode user = db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", userId);
        if (user == null || !Arrays.asList("B", "C").contains(effectiveIdentity(user)))
            throw new BusinessException(403, "AGENT_REQUIRED", "仅当前有效代理可以办理订货积分业务");
        return user;
    }

    /** 小程序钱包及个人页汇总，不读取来源批次和明细。金额单位为分或百分之一积分。 */
    public ObjectNode summary(long userId) {
        ObjectNode result =
                db.one(
                        "SELECT COALESCE(SUM(remaining_cents),0) AS balanceCents,COALESCE(SUM(held_cents),0) AS heldCents,COALESCE(SUM(remaining_cents-held_cents),0) AS availableCents FROM wallet_lots WHERE user_id=? AND is_deleted=0",
                        userId);
        result.setAll(
                db.one(
                        "SELECT COALESCE(SUM(CASE WHEN kind='credit' THEN delta_cents ELSE 0 END),0) AS creditedCents,COALESCE(SUM(CASE WHEN kind='spend' THEN -delta_cents ELSE 0 END),0) AS spentCents FROM wallet_ledger WHERE user_id=? AND is_deleted=0",
                        userId));
        result.set("transfer", companyTransfer());
        return result;
    }

    /** 返回余额、占用、来源批次和最近二百条流水，不要求用户仍为代理。 */
    public ObjectNode walletView(long userId) {
        return walletView(userId, true);
    }

    /** 后台可跳过最近流水，改由独立分页接口读取；余额与批次仍完整返回。 */
    public ObjectNode walletView(long userId, boolean includeLedger) {
        ObjectNode result =
                db.one(
                        "SELECT COALESCE(SUM(remaining_cents),0) AS balanceCents,COALESCE(SUM(held_cents),0) AS heldCents,COALESCE(SUM(remaining_cents-held_cents),0) AS availableCents FROM wallet_lots WHERE user_id=? AND is_deleted=0",
                        userId);
        result.setAll(
                db.one(
                        "SELECT COALESCE(SUM(CASE WHEN kind='credit' THEN delta_cents ELSE 0 END),0) AS creditedCents,COALESCE(SUM(CASE WHEN kind='spend' THEN -delta_cents ELSE 0 END),0) AS spentCents FROM wallet_ledger WHERE user_id=? AND is_deleted=0",
                        userId));
        result.set(
                "lots",
                json(
                        db.list(
                                "SELECT l.*,r.source_channel,r.account_ref,r.external_transaction_id,r.amount_cents,r.cash_refunded_cents,r.cash_held_cents FROM wallet_lots l JOIN company_receipts r ON r.id=l.receipt_id AND r.is_deleted=0 WHERE l.user_id=? AND l.is_deleted=0 ORDER BY l.id",
                                userId)));
        result.set(
                "ledger",
                includeLedger
                        ? json(
                                db.list(
                                        "SELECT l.*,o.number AS order_number,r.external_transaction_id FROM wallet_ledger l JOIN company_receipts r ON r.id=l.receipt_id AND r.is_deleted=0 LEFT JOIN orders o ON o.id=l.order_id AND o.is_deleted=0 WHERE l.user_id=? AND l.is_deleted=0 ORDER BY l.id DESC LIMIT 200",
                                        userId))
                        : arr());
        return result;
    }

    /** 财务查看指定用户全部积分流水，页大小与页码沿用后台分页约束。 */
    public ObjectNode ledgerPage(long userId, java.util.Map<String, String> query) {
        java.util.Map<String, String> filters = new java.util.HashMap<>(query);
        filters.put("userId", String.valueOf(userId));
        return new PageQuery(
                        filters,
                        "FROM wallet_ledger l JOIN company_receipts r ON r.id=l.receipt_id AND r.is_deleted=0 LEFT JOIN orders o ON o.id=l.order_id AND o.is_deleted=0 WHERE l.is_deleted=0")
                .eq("userId", "l.user_id")
                .select(
                        db,
                        "l.*,o.number AS order_number,r.external_transaction_id",
                        "l.id DESC",
                        row -> row);
    }

    /** 公司转款说明，仅完整配置公司名称、开户行和账号时标记可用。 */
    public ObjectNode companyTransfer() {
        JsonNode content = db.setting("content");
        if (!s(content, "customerService").isEmpty()
                && (s(content, "bankName").isEmpty() || s(content, "bankAccount").isEmpty()))
            return obj(
                    "configured",
                    true,
                    "companyName",
                    s(content, "companyName"),
                    "bankName",
                    "",
                    "bankAccount",
                    "",
                    "transferNotes",
                    s(content, "transferNotes"),
                    "customerService",
                    s(content, "customerService"));
        if (s(content, "companyName").isEmpty()
                || s(content, "bankName").isEmpty()
                || s(content, "bankAccount").isEmpty())
            return obj(
                    "configured",
                    false,
                    "companyName",
                    "",
                    "bankName",
                    "",
                    "bankAccount",
                    "",
                    "transferNotes",
                    "请联系总部微信充值，核款到账后增加积分",
                    "customerService",
                    s(content, "customerService"));
        return obj(
                "configured",
                true,
                "companyName",
                s(content, "companyName"),
                "bankName",
                s(content, "bankName"),
                "bankAccount",
                s(content, "bankAccount"),
                "transferNotes",
                content.get("transferNotes"),
                "customerService",
                content.get("customerService"));
    }

    /** 严格校验收款内容；产品包核款使用同一校验，到账时间须包含时区且不晚于当前。 */
    public ObjectNode validateReceipt(JsonNode raw) {
        Input.strict(
                raw,
                "userId",
                "purpose",
                "onboardingId",
                "orderId",
                "sourceChannel",
                "accountRef",
                "externalTransactionId",
                "amountCents",
                "receivedAt",
                "evidence",
                "reason",
                "verified");
        long userId = entity(raw, "userId");
        String purpose = choice(raw, "purpose", "package", "points", "order");
        String channel = choice(raw, "sourceChannel", "bank", "wechat_company");
        ObjectNode v =
                obj(
                        "userId",
                        userId,
                        "purpose",
                        purpose,
                        "sourceChannel",
                        channel,
                        "accountRef",
                        Input.text(raw, "accountRef", 1, 200),
                        "externalTransactionId",
                        Input.text(raw, "externalTransactionId", 1, 200),
                        "amountCents",
                        Input.integer(raw, "amountCents", 1, 100000000),
                        "evidence",
                        Input.text(raw, "evidence", 1, 1000),
                        "reason",
                        Input.text(raw, "reason", 1, 200));
        String receivedAt = rawText(raw, "receivedAt");
        pastTime(receivedAt, false);
        v.put("receivedAt", receivedAt);
        Input.require(Input.bool(raw, "verified"), "必须确认已核实真实到账");
        v.put("verified", true);
        if (raw.has("onboardingId")) v.put("onboardingId", entity(raw, "onboardingId"));
        if (raw.has("orderId")) v.put("orderId", entity(raw, "orderId"));
        return v;
    }

    /** 调用方须处于事务内；同一原收款流水只能登记一次且不能跨用途复用。 */
    public long registerReceipt(JsonNode raw, long actor) {
        db.assertTransaction();
        ObjectNode v = validateReceipt(raw);
        long userId = l(v, "userId"), amount = l(v, "amountCents");
        if (db.one("SELECT id FROM users WHERE id=? AND is_deleted=0", userId) == null)
            throw conflict("收款用户不存在");
        if (db.one(
                        "SELECT id FROM company_receipts WHERE source_channel=? AND account_ref=? AND external_transaction_id=? AND is_deleted=0",
                        s(v, "sourceChannel"),
                        s(v, "accountRef"),
                        s(v, "externalTransactionId"))
                != null) throw conflict("RECEIPT_ALREADY_USED", "该原收款流水已登记，不得跨订单或用途重复使用");
        if (!"order".equals(s(v, "purpose")) && has(v, "orderId")) throw conflict("仅普通订单货款可指定订单编号");
        if ("points".equals(s(v, "purpose"))) {
            if (has(v, "onboardingId")) throw conflict("订货款不能绑定入驻用途");
        } else if ("order".equals(s(v, "purpose"))) {
            if (!has(v, "orderId") || has(v, "onboardingId"))
                throw conflict("普通货款必须指定原订单，不能绑定入驻申请");
            ObjectNode order = orders.owned(l(v, "orderId"), null);
            if (!"goods".equals(s(order, "kind"))
                    || !"pending".equals(s(order, "status"))
                    || l(order, "user_id") != userId
                    || l(order, "total_cents") != amount) throw conflict("普通货款与原待核款订单不一致");
        } else {
            if (!has(v, "onboardingId")) throw conflict("产品包款必须指定入驻申请");
            ObjectNode application =
                    db.one(
                            "SELECT id,package_snapshot,status,receipt_id FROM onboardings WHERE id=? AND user_id=? AND is_deleted=0",
                            l(v, "onboardingId"),
                            userId);
            if (application == null
                    || !"submitted".equals(s(application, "status"))
                    || has(application, "receipt_id")) throw conflict("该申请不能再确认产品包收款");
            if (l(parse(s(application, "package_snapshot")), "amountCents") != amount)
                throw conflict("到账金额须与当次产品包货款一致");
        }
        long receiptId =
                db.insert(
                        "INSERT INTO company_receipts(user_id,purpose,onboarding_id,source_channel,account_ref,external_transaction_id,amount_cents,received_at,evidence,reason,confirmed_by,confirmed_at,create_user_id,update_user_id,order_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        userId,
                        s(v, "purpose"),
                        has(v, "onboardingId") ? l(v, "onboardingId") : null,
                        s(v, "sourceChannel"),
                        s(v, "accountRef"),
                        s(v, "externalTransactionId"),
                        amount,
                        s(v, "receivedAt"),
                        s(v, "evidence"),
                        s(v, "reason"),
                        actor,
                        now(),
                        actor,
                        actor,
                        has(v, "orderId") ? l(v, "orderId") : null);
        if ("points".equals(s(v, "purpose"))) {
            long lotId =
                    db.insert(
                            "INSERT INTO wallet_lots(user_id,receipt_id,issued_cents,remaining_cents,create_user_id,update_user_id) VALUES(?,?,?,?,?,?)",
                            userId,
                            receiptId,
                            amount,
                            amount,
                            actor,
                            actor);
            ledger(
                    userId,
                    receiptId,
                    lotId,
                    null,
                    "credit",
                    amount,
                    String.valueOf(receiptId),
                    "receipt:" + receiptId,
                    s(v, "reason"),
                    actor);
        }
        return receiptId;
    }

    /** 写入来源明确的不可变账务流水，幂等键由调用业务构造。 */
    public void ledger(
            long userId,
            long receiptId,
            long lotId,
            Long orderId,
            String kind,
            long delta,
            String reference,
            String key,
            String reason,
            long actor) {
        db.assertTransaction();
        db.insert(
                "INSERT INTO wallet_ledger(user_id,receipt_id,lot_id,order_id,kind,delta_cents,reference,idempotency_key,reason,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                userId,
                receiptId,
                lotId,
                orderId,
                kind,
                delta,
                reference,
                key,
                reason,
                actor,
                actor);
    }

    /** 商品订单退款逐批归还原积分；同退款编号同金额可重复调用，禁止累计超退。 */
    public void restoreOrderPoints(
            long orderId, long refundId, long amount, long actor, String requestId) {
        db.assertTransaction();
        Input.id(orderId);
        Input.id(refundId);
        Input.require(amount >= 1 && amount <= 100000000, "退款金额不合法");
        String reference = String.valueOf(refundId);
        ObjectNode old =
                db.one(
                        "SELECT COALESCE(SUM(delta_cents),0) AS total FROM wallet_ledger WHERE kind='order_refund' AND order_id=? AND reference=? AND is_deleted=0",
                        orderId,
                        reference);
        if (l(old, "total") != 0) {
            if (l(old, "total") != amount) throw conflict("退款编号与已退金额不一致");
            return;
        }
        ObjectNode order = orders.owned(orderId, null);
        if (!"points".equals(s(order, "payment_method"))
                || !Arrays.asList("paid", "partially_refunded").contains(s(order, "payment_state")))
            throw conflict("仅原积分已支付订单可退回积分");
        List<ObjectNode> allocations =
                db.list(
                        "SELECT * FROM wallet_spend_allocations WHERE order_id=? AND is_deleted=0 ORDER BY id",
                        orderId);
        long available = 0;
        for (ObjectNode allocation : allocations)
            available += l(allocation, "spent_cents") - l(allocation, "refunded_cents");
        if (available < amount) throw conflict("退款超过原积分支付剩余额度");
        long left = amount;
        for (ObjectNode a : allocations) {
            if (left == 0) break;
            long n = Math.min(left, l(a, "spent_cents") - l(a, "refunded_cents"));
            if (n == 0) continue;
            if (db.update(
                            "UPDATE wallet_spend_allocations SET refunded_cents=refunded_cents+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND refunded_cents+?<=spent_cents AND is_deleted=0",
                            n,
                            actor,
                            l(a, "id"),
                            n)
                    != 1) throw conflict("退款来源已变化，请重新核对");
            if (db.update(
                            "UPDATE wallet_lots SET remaining_cents=remaining_cents+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=? AND is_deleted=0 AND remaining_cents+?<=issued_cents",
                            n,
                            actor,
                            l(a, "lot_id"),
                            l(order, "user_id"),
                            n)
                    != 1) throw conflict("原积分批次无法恢复");
            ledger(
                    l(order, "user_id"),
                    l(a, "receipt_id"),
                    l(a, "lot_id"),
                    orderId,
                    "order_refund",
                    n,
                    reference,
                    "refund:" + orderId + ":" + reference + ":" + l(a, "id"),
                    "商品订单退款返回原收款积分批次",
                    actor);
            left -= n;
        }
    }

    /** 整单积分支付，批次扣款、库存与支付记录由统一事务提交。 */
    public ObjectNode payWithPoints(long userId, long orderId) {
        return db.tx(
                () -> {
                    ObjectNode order = orders.owned(orderId, userId);
                    if ("points".equals(s(order, "payment_method"))
                            && "paid".equals(s(order, "payment_state"))) return orders.view(order);
                    if ("package".equals(s(order, "kind"))) payments.assertPackagePayable(order);
                    if (!"pending".equals(s(order, "status"))
                            || !Arrays.asList("unpaid", "failed")
                                    .contains(s(order, "payment_state")))
                        throw conflict("PAYMENT_UNRESOLVED", "原支付结果未明或订单已付款，不能改用积分");
                    if (!s(order, "payment_method").isEmpty()
                            && !"points".equals(s(order, "payment_method"))
                            && !"failed".equals(s(order, "payment_state")))
                        throw conflict("请先确认并关闭原微信交易");
                    if (!Instant.parse(s(order, "expires_at")).isAfter(Instant.now()))
                        throw conflict("待付款订单已过期");
                    orders.assertRelationsCurrent(order);

                    payments.assertNoUnresolved(userId, 0);
                    new PointsService(db)
                            .spend(userId, orderId, null, l(order, "total_cents"), userId);
                    payments.settlePaid(order, "points", "points:" + orderId);
                    access.audit("order.pay_points", "order", orderId, "按原收款批次扣积分支付整单");
                    return orders.view(orders.owned(orderId, userId));
                });
    }

    /** 后台确认普通订货收款并发放积分；失败另记审计，失败事务不留下部分账务。 */
    public ObjectNode confirmReceipt(JsonNode raw, long actor) {
        ObjectNode v = validateReceipt(raw);
        if (!"points".equals(s(v, "purpose"))) throw conflict("此入口仅办理积分充值，产品包和普通货款请从对应申请或订单核款");
        try {
            long receiptId =
                    db.tx(
                            () -> {
                                ObjectNode previous =
                                        db.one(
                                                "SELECT * FROM company_receipts WHERE source_channel=? AND account_ref=? AND external_transaction_id=? AND is_deleted=0",
                                                s(v, "sourceChannel"),
                                                s(v, "accountRef"),
                                                s(v, "externalTransactionId"));
                                if (previous != null) {
                                    if (l(previous, "user_id") != l(v, "userId")
                                            || l(previous, "amount_cents") != l(v, "amountCents")
                                            || !"points".equals(s(previous, "purpose")))
                                        throw conflict(
                                                "RECEIPT_ALREADY_USED", "原收款流水已用于其他金额、用户或用途");
                                    return l(previous, "id");
                                }
                                long value = registerReceipt(v, actor);
                                access.audit(
                                        "receipt.confirm",
                                        "company_receipt",
                                        value,
                                        s(v, "reason"));
                                return value;
                            });
            ObjectNode result = obj("id", receiptId);
            result.setAll(walletView(l(v, "userId")));
            return result;
        } catch (RuntimeException error) {
            db.tx(
                    () ->
                            access.audit(
                                    "receipt.confirm",
                                    "company_receipt",
                                    s(v, "externalTransactionId"),
                                    "代理"
                                            + l(v, "userId")
                                            + "；"
                                            + (error instanceof BusinessException
                                                    ? error.getMessage()
                                                    : "收款确认失败，请核实原流水"),
                                    "failure"));
            throw error;
        }
    }

    /** 按原批次扣减未使用且未占用积分；请求编号全局幂等。 */
    public ObjectNode correct(long lotId, JsonNode raw, long actor) {
        Input.strict(raw, "amountCents", "reason", "requestKey");
        long amount = Input.integer(raw, "amountCents", 1, 100000000);
        String reason = Input.text(raw, "reason", 1, 200),
                requestKey = Input.text(raw, "requestKey", 12, 100);
        db.tx(
                () -> {
                    String key = "correction:" + requestKey;
                    ObjectNode old =
                            db.one(
                                    "SELECT * FROM wallet_ledger WHERE idempotency_key=? AND is_deleted=0",
                                    key);
                    if (old != null) {
                        if (l(old, "lot_id") != lotId
                                || l(old, "delta_cents") != -amount
                                || !s(old, "reason").equals(reason)) throw conflict("纠错编号已用于其他操作");
                        return;
                    }
                    ObjectNode lot =
                            db.one("SELECT * FROM wallet_lots WHERE id=? AND is_deleted=0", lotId);
                    if (lot == null) throw conflict("原积分批次不存在");
                    if (db.update(
                                    "UPDATE wallet_lots SET remaining_cents=remaining_cents-?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND remaining_cents-held_cents>=? AND is_deleted=0",
                                    amount,
                                    actor,
                                    lotId,
                                    amount)
                            != 1) throw conflict("纠错金额超过原批次未使用积分，已消费差额必须人工核实处理");
                    ledger(
                            l(lot, "user_id"),
                            l(lot, "receipt_id"),
                            lotId,
                            null,
                            "correction",
                            -amount,
                            requestKey,
                            key,
                            reason,
                            actor);
                    access.audit("wallet.correct", "wallet_lot", lotId, reason);
                });
        return obj("ok", true);
    }

    /** 按可选用户、收款编号或外部流水查询，最多三百条。 */
    public List<ObjectNode> receipts(
            String userId, String receiptId, String externalTransactionId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM company_receipts WHERE is_deleted=0");
        List<Object> args = new ArrayList<>();
        if (userId != null) {
            sql.append(" AND user_id=?");
            args.add(pathId(userId));
        }
        if (receiptId != null) {
            sql.append(" AND id=?");
            args.add(pathId(receiptId));
        }
        if (externalTransactionId != null) {
            String value = externalTransactionId.trim();
            Input.require(!value.isEmpty() && value.length() <= 200, "收款流水长度须为1至200");
            sql.append(" AND external_transaction_id=?");
            args.add(value);
        }
        return db.list(sql.append(" ORDER BY id DESC LIMIT 300").toString(), args.toArray());
    }

    /** 保留原接口可转换的实体编号，拒绝小数、越界及空值。 */
    public static long entity(JsonNode raw, String key) {
        JsonNode value = raw == null ? null : raw.get(key);
        Input.require(
                value != null && !value.isNull() && (value.isNumber() || value.isTextual()),
                key + "必须是有效编号");
        return pathId(value.asText());
    }

    /** 校验路径或查询实体编号，最大值为 JavaScript 安全整数。 */
    public static long pathId(String value) {
        try {
            java.math.BigDecimal number = new java.math.BigDecimal(value.trim());
            return Input.id(number.longValueExact());
        } catch (NumberFormatException | ArithmeticException error) {
            throw invalid("编号必须是有效正整数");
        }
    }

    /** 读取必须存在的原始字符串，不做隐式类型转换。 */
    public static String rawText(JsonNode raw, String key) {
        Input.require(
                raw != null && raw.hasNonNull(key) && raw.get(key).isTextual(), key + "必须是字符串");
        return raw.get(key).textValue();
    }

    /** 校验枚举，不自动清除空白，以保持原严格参数约束。 */
    public static String choice(JsonNode raw, String key, String... values) {
        String value = rawText(raw, key);
        Input.require(Arrays.asList(values).contains(value), key + "取值无效");
        return value;
    }

    /** 校验实际到账或付款时间；付款凭证仅接受UTC的ISO时间。 */
    public static void pastTime(String value, boolean utcOnly) {
        Input.require(
                value != null
                        && value.matches(
                                utcOnly
                                        ? "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?Z"
                                        : "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})"),
                "实际时间须为有效ISO时间");
        try {
            Input.require(
                    !OffsetDateTime.parse(value).toInstant().isAfter(Instant.now()), "实际时间不能在未来");
        } catch (DateTimeParseException error) {
            throw invalid("实际时间须为有效ISO时间");
        }
    }
}
