package com.jianyanji.wallet;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Db;
import com.jianyanji.common.Input;
import java.util.List;

/** 全客户积分扣款及运费原批次退回；调用方必须在同一业务事务内，单位为百分之一积分。 */
public final class PointsService {
    /** 事务和积分批次数据访问。 */
    private final Db db;

    public PointsService(Db db) {
        this.db = db;
    }

    /** 查询全部可用积分，不受列表分页影响。 */
    public long available(long userId) {
        return l(
                db.one(
                        "SELECT COALESCE(SUM(remaining_cents-held_cents),0) AS amount FROM wallet_lots WHERE user_id=? AND is_deleted=0",
                        userId),
                "amount");
    }

    /** 原子扣除足额积分；订单和纯提货运费使用独立业务分摊，禁止部分扣款。 */
    public void spend(long userId, Long orderId, Long pickupId, long amount, long actor) {
        db.assertTransaction();
        Input.require(amount >= 0 && amount <= 100000000, "积分支付金额无效");
        if (amount == 0) return;
        boolean pickup = pickupId != null;
        String table = pickup ? "pickup_point_allocations" : "wallet_spend_allocations";
        String field = pickup ? "pickup_id" : "order_id";
        long businessId = pickup ? pickupId : orderId;
        ObjectNode paid =
                db.one(
                        "SELECT COALESCE(SUM(spent_cents),0) AS amount FROM "
                                + table
                                + " WHERE "
                                + field
                                + "=? AND is_deleted=0",
                        businessId);
        if (l(paid, "amount") != 0) {
            if (l(paid, "amount") != amount) throw conflict("积分业务编号已用于其他金额");
            return;
        }
        if (available(userId) < amount)
            throw conflict(
                    "POINTS_INSUFFICIENT",
                    "积分不足，还差 "
                            + String.format(
                                    java.util.Locale.ROOT,
                                    "%.2f",
                                    (amount - available(userId)) / 100.0)
                            + " 积分，请联系总部充值，足额后再支付");
        long left = amount;
        List<ObjectNode> lots =
                db.list(
                        "SELECT * FROM wallet_lots WHERE user_id=? AND remaining_cents>held_cents AND is_deleted=0 ORDER BY id",
                        userId);
        for (ObjectNode lot : lots) {
            if (left == 0) break;
            long n = Math.min(left, l(lot, "remaining_cents") - l(lot, "held_cents"));
            if (db.update(
                            "UPDATE wallet_lots SET remaining_cents=remaining_cents-?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND remaining_cents-held_cents>=? AND is_deleted=0",
                            n,
                            actor,
                            l(lot, "id"),
                            n)
                    != 1) throw conflict("可用积分已变化，请重试");
            if (pickup)
                db.insert(
                        "INSERT INTO pickup_point_allocations(pickup_id,lot_id,receipt_id,spent_cents,create_user_id,update_user_id) VALUES(?,?,?,?,?,?)",
                        businessId,
                        l(lot, "id"),
                        l(lot, "receipt_id"),
                        n,
                        actor,
                        actor);
            else
                db.insert(
                        "INSERT INTO wallet_spend_allocations(order_id,user_id,lot_id,receipt_id,spent_cents,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?)",
                        businessId,
                        userId,
                        l(lot, "id"),
                        l(lot, "receipt_id"),
                        n,
                        actor,
                        actor);
            ledger(
                    userId,
                    orderId,
                    lot,
                    "spend",
                    -n,
                    (pickup ? "提货运费#" : "订单#") + businessId,
                    (pickup ? "pickup-spend:" : "spend:") + businessId + ":" + l(lot, "id"),
                    "足额积分支付",
                    actor);
            left -= n;
        }
        if (left != 0) throw conflict("积分批次扣款不完整");
    }

    /** 取消提货只退本次运费；合并购买从原订单分摊退回，纯提货从运费分摊退回。 */
    public void refundFee(ObjectNode pickup, long actor) {
        db.assertTransaction();
        long amount = l(pickup, "fee_cents"), id = l(pickup, "id"), user = l(pickup, "user_id");
        if (amount == 0 || b(pickup, "fee_refunded")) return;
        boolean combined = has(pickup, "purchase_order_id");
        String table = combined ? "wallet_spend_allocations" : "pickup_point_allocations";
        String field = combined ? "order_id" : "pickup_id";
        long owner = combined ? l(pickup, "purchase_order_id") : id;
        long left = amount;
        for (ObjectNode allocation :
                db.list(
                        "SELECT * FROM "
                                + table
                                + " WHERE "
                                + field
                                + "=? AND is_deleted=0 ORDER BY id",
                        owner)) {
            long n = Math.min(left, l(allocation, "spent_cents") - l(allocation, "refunded_cents"));
            if (n == 0) continue;
            if (db.update(
                            "UPDATE "
                                    + table
                                    + " SET refunded_cents=refunded_cents+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND spent_cents-refunded_cents>=? AND is_deleted=0",
                            n,
                            actor,
                            l(allocation, "id"),
                            n)
                    != 1) throw conflict("运费退款来源已变化");
            if (db.update(
                            "UPDATE wallet_lots SET remaining_cents=remaining_cents+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND remaining_cents+?<=issued_cents AND is_deleted=0",
                            n,
                            actor,
                            l(allocation, "lot_id"),
                            n)
                    != 1) throw conflict("原积分批次无法恢复");
            ObjectNode lot =
                    obj("id", allocation.get("lot_id"), "receipt_id", allocation.get("receipt_id"));
            ledger(
                    user,
                    combined ? owner : null,
                    lot,
                    "order_refund",
                    n,
                    "提货运费#" + id,
                    "pickup-refund:" + id + ":" + l(allocation, "id"),
                    "取消或驳回未发货提货，退回运费",
                    actor);
            left -= n;
            if (left == 0) break;
        }
        if (left != 0) throw conflict("原运费可退积分不足");
        db.update(
                "UPDATE pickup_orders SET fee_refunded=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                actor,
                id);
    }

    /** 不可变积分流水，幂等键关联明确原业务与积分来源。 */
    private void ledger(
            long user,
            Long order,
            ObjectNode lot,
            String kind,
            long delta,
            String reference,
            String key,
            String reason,
            long actor) {
        db.insert(
                "INSERT INTO wallet_ledger(user_id,receipt_id,lot_id,order_id,kind,delta_cents,reference,idempotency_key,reason,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                user,
                l(lot, "receipt_id"),
                l(lot, "id"),
                order,
                kind,
                delta,
                reference,
                key,
                reason,
                actor,
                actor);
    }
}
