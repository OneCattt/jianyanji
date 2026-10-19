package com.jianyanji.wallet;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;
import static com.jianyanji.wallet.WalletService.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Db;
import com.jianyanji.common.Input;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/** 积分余额人工退现金，按原积分批次和原收款同时占用、完成或释放。 */
@Service
public class CashRefundService {
    /** 账务数据库和事务组件。 */
    private final Db db;
    /** 当前请求审计组件。 */
    private final Access access;
    /** 原积分批次流水服务。 */
    private final WalletService wallet;

    public CashRefundService(Db db, Access access, WalletService wallet) {
        this.db = db;
        this.access = access;
        this.wallet = wallet;
    }

    /** 查询可见退款记录，用户查询最多一百条，财务查询最多二百条。 */
    public List<ObjectNode> listRefunds(Long userId) {
        return userId == null
                ? db.list(
                        "SELECT * FROM cash_refunds WHERE is_deleted=0 ORDER BY id DESC LIMIT 200")
                : db.list(
                        "SELECT * FROM cash_refunds WHERE user_id=? AND is_deleted=0 ORDER BY id DESC LIMIT 100",
                        userId);
    }

    /** 读取有效退款，不存在时返回404。 */
    public ObjectNode get(long refundId) {
        ObjectNode result =
                db.one("SELECT * FROM cash_refunds WHERE id=? AND is_deleted=0", refundId);
        if (result == null) throw notFound("积分余额退款记录不存在");
        return result;
    }

    /** 在同一事务占用未使用积分和公司收款额度，请求编号重复不再次占用。 */
    public ObjectNode reserve(JsonNode raw, long actor, String requestId) {
        Input.strict(raw, "userId", "receiptId", "amountCents", "reason", "requestKey");
        long userId = entity(raw, "userId"), receiptId = entity(raw, "receiptId");
        long amount = Input.integer(raw, "amountCents", 1, 100000000);
        String reason = Input.text(raw, "reason", 1, 200), key = rawText(raw, "requestKey");
        Input.require(key.matches("[a-zA-Z0-9_-]{12,100}"), "请求编号须为12至100位字母、数字、下划线或短横线");
        return db.tx(
                () -> {
                    ObjectNode old =
                            db.one(
                                    "SELECT * FROM cash_refunds WHERE request_key=? AND is_deleted=0",
                                    key);
                    if (old != null) {
                        if (l(old, "user_id") != userId
                                || l(old, "receipt_id") != receiptId
                                || l(old, "amount_cents") != amount)
                            throw conflict("该请求编号已用于其他余额退款");
                        return old;
                    }
                    ObjectNode lot =
                            db.one(
                                    "SELECT l.*,r.amount_cents,r.cash_refunded_cents,r.cash_held_cents,r.purpose FROM wallet_lots l JOIN company_receipts r ON r.id=l.receipt_id AND r.is_deleted=0 WHERE l.receipt_id=? AND l.user_id=? AND r.user_id=? AND l.is_deleted=0 AND r.purpose='points'",
                                    receiptId,
                                    userId,
                                    userId);
                    if (lot == null) throw conflict("该收款不是本人有效订货积分来源，产品包款不能退积分现金");
                    if (l(lot, "remaining_cents") - l(lot, "held_cents") < amount
                            || l(lot, "amount_cents")
                                            - l(lot, "cash_refunded_cents")
                                            - l(lot, "cash_held_cents")
                                    < amount) throw conflict("可用积分或原收款剩余未退现金不足");
                    if (db.update(
                                    "UPDATE wallet_lots SET held_cents=held_cents+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0 AND remaining_cents-held_cents>=?",
                                    amount,
                                    actor,
                                    l(lot, "id"),
                                    amount)
                            != 1) throw conflict("可用积分已变化");
                    if (db.update(
                                    "UPDATE company_receipts SET cash_held_cents=cash_held_cents+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0 AND amount_cents-cash_refunded_cents-cash_held_cents>=?",
                                    amount,
                                    actor,
                                    receiptId,
                                    amount)
                            != 1) throw conflict("原收款可退金额已变化");
                    long refundId =
                            db.insert(
                                    "INSERT INTO cash_refunds(number,user_id,receipt_id,lot_id,amount_cents,reason,request_key,request_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                                    "CR" + id(),
                                    userId,
                                    receiptId,
                                    l(lot, "id"),
                                    amount,
                                    reason,
                                    key,
                                    requestId,
                                    actor,
                                    actor);
                    access.audit("cash_refund.reserve", "cash_refund", refundId, reason);
                    return get(refundId);
                });
    }

    /** 人工核实真实付款结果，未明时保留双重占用；成功和释放均只执行一次。 */
    public ObjectNode resolve(long refundId, JsonNode raw, long actor, String requestId) {
        Input.strict(
                raw,
                "outcome",
                "recipient",
                "paidAt",
                "proof",
                "confirmed",
                "confirmedNoPayment",
                "reason");
        String outcome = choice(raw, "outcome", "success", "failed", "rejected", "unknown");
        String recipient = Input.text(raw, "recipient", 0, 100), paidAt = rawText(raw, "paidAt");
        String proof = Input.text(raw, "proof", 0, 1000),
                reason = Input.text(raw, "reason", 1, 200);
        boolean confirmed = Input.bool(raw, "confirmed"),
                noPayment = Input.bool(raw, "confirmedNoPayment");
        if ("success".equals(outcome)) {
            if (!confirmed || recipient.isEmpty() || proof.isEmpty())
                throw conflict("实际退款成功需确认收款人、付款时间和凭证");
            try {
                pastTime(paidAt, true);
            } catch (com.jianyanji.common.BusinessException error) {
                throw conflict("实际退款成功需确认收款人、付款时间和凭证");
            }
        }
        return db.tx(
                () -> {
                    ObjectNode r = get(refundId);
                    if ("success".equals(s(r, "status"))) {
                        if (!"success".equals(outcome)) throw conflict("已完成退款不能改为其他状态");
                        return r;
                    }
                    if (Arrays.asList("failed", "rejected").contains(s(r, "status")))
                        throw conflict("该退款已结束，重新办理须提交新申请");
                    if ("unknown".equals(outcome)) {
                        db.update(
                                "UPDATE cash_refunds SET status='unknown',last_error=?,request_id=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                reason,
                                requestId,
                                actor,
                                refundId);
                        access.audit(
                                "cash_refund.unknown", "cash_refund", refundId, reason, "failure");
                        return get(refundId);
                    }
                    if (Arrays.asList("failed", "rejected").contains(outcome)
                            && (!noPayment
                                    || ("unknown".equals(s(r, "status")) && proof.isEmpty())))
                        throw conflict("释放占用前须核实未实际退款，结果未明时须补充核查凭证");
                    long amount = l(r, "amount_cents"),
                            debit = "success".equals(outcome) ? amount : 0;
                    if (db.update(
                                    "UPDATE wallet_lots SET held_cents=held_cents-?,remaining_cents=remaining_cents-?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=? AND receipt_id=? AND is_deleted=0 AND held_cents>=? AND remaining_cents>=?",
                                    amount,
                                    debit,
                                    actor,
                                    l(r, "lot_id"),
                                    l(r, "user_id"),
                                    l(r, "receipt_id"),
                                    amount,
                                    amount)
                            != 1) throw conflict("原积分占用不一致，请核查");
                    if (db.update(
                                    "UPDATE company_receipts SET cash_held_cents=cash_held_cents-?,cash_refunded_cents=cash_refunded_cents+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=? AND purpose='points' AND is_deleted=0 AND cash_held_cents>=? AND cash_refunded_cents+?<=amount_cents",
                                    amount,
                                    debit,
                                    actor,
                                    l(r, "receipt_id"),
                                    l(r, "user_id"),
                                    amount,
                                    debit)
                            != 1) throw conflict("原公司收款退款占用不一致，请核查");
                    if ("success".equals(outcome))
                        wallet.ledger(
                                l(r, "user_id"),
                                l(r, "receipt_id"),
                                l(r, "lot_id"),
                                null,
                                "cash_refund",
                                -amount,
                                s(r, "number"),
                                "cash-refund:" + refundId,
                                reason,
                                actor);
                    db.update(
                            "UPDATE cash_refunds SET status=?,recipient=?,paid_at=?,proof=?,completed_at=?,last_error=?,request_id=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            outcome,
                            recipient.isEmpty() ? null : recipient,
                            paidAt.isEmpty() ? null : paidAt,
                            proof.isEmpty() ? null : proof,
                            now(),
                            "success".equals(outcome) ? "" : reason,
                            requestId,
                            actor,
                            refundId);
                    access.audit(
                            "cash_refund." + outcome,
                            "cash_refund",
                            refundId,
                            reason,
                            "failed".equals(outcome) ? "failure" : "success");
                    return get(refundId);
                });
    }
}
