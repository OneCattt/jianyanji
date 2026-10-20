package com.jianyanji.payments;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import com.jianyanji.orders.OrderService;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 支付状态机：到账凭据、库存扣减同事务，原渠道单号和数据库租约确保核对幂等。 */
@Service
public class PaymentService {
    /** 结构化中文诊断日志，不记录密钥或支付签名。 */
    private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);
    /** MySQL 数据访问与串行业务事务。 */
    private final Db db;
    /** 当前操作人与请求审计上下文。 */
    private final Access access;
    /** 订单归属、库存释放和响应装配接口，延迟注入避免循环依赖。 */
    private final OrderService orders;
    /** 微信真实渠道。 */
    private final WechatPayClient channel;
    /** 产品包到账激活钩子，延迟注入避免订单与退款依赖环。 */
    @org.springframework.beans.factory.annotation.Autowired @Lazy
    private com.jianyanji.membership.MembershipService membership;
    /** 单进程定时核对重入保护；跨进程由数据库租约保护。 */
    private final AtomicBoolean running = new AtomicBoolean();
    /** 更新审计字段的 SQL 片段，操作人由参数明确传入。 */
    private static final String STAMP = "update_user_id=?,update_time=CURRENT_TIMESTAMP(3)";

    public PaymentService(
            Db db, Access access, @Lazy OrderService orders, WechatPayClient channel) {
        this.db = db;
        this.access = access;
        this.orders = orders;
        this.channel = channel;
    }

    /** 必须由调用方持有事务，积分与公司转款结算复用该入口。 */
    public ObjectNode settlePaid(ObjectNode order, String method, String transactionId) {
        db.assertTransaction();
        if (transactionId == null || transactionId.trim().isEmpty()) throw conflict("缺少支付流水号");
        ObjectNode current = orders.owned(l(order, "id"), null);
        long orderId = l(current, "id"), userId = l(current, "user_id");
        ObjectNode receipt =
                db.one("SELECT * FROM payments WHERE order_id=? AND is_deleted=0", orderId);
        if (receipt != null) {
            if (!method.equals(s(receipt, "method"))
                    || !transactionId.equals(s(receipt, "transaction_id"))
                    || l(receipt, "cents") != l(current, "total_cents"))
                throw conflict("PAYMENT_MISMATCH", "订单已由其他支付流水结算");
            return obj("alreadyPaid", true, "order", current);
        }
        if (!"pending".equals(s(current, "status")) || "paid".equals(s(current, "payment_state")))
            throw conflict("SETTLEMENT_CONFLICT", "订单状态不允许结算");
        if (!"wechat".equals(method)) {
            assertNoUnresolved(userId, 0);
            if (!in(s(current, "payment_state"), "unpaid", "failed"))
                throw conflict("原支付结果尚未确定，禁止切换支付方式");
        } else if (!"wechat".equals(s(current, "payment_method"))
                || !in(s(current, "payment_state"), "paying", "unknown", "review"))
            throw conflict("微信支付状态不匹配");
        List<ObjectNode> lines =
                db.list(
                        "SELECT sku_id,quantity FROM order_lines WHERE order_id=? AND is_deleted=0",
                        orderId);
        if (lines.isEmpty()) throw conflict("订单没有可结算商品");
        for (ObjectNode line : lines) {
            long quantity = l(line, "quantity");
            if (db.update(
                            "UPDATE skus SET stock=stock-?,reserved=reserved-?,"
                                    + STAMP
                                    + " WHERE id=? AND is_deleted=0 AND stock>=? AND reserved>=?",
                            quantity,
                            quantity,
                            userId,
                            l(line, "sku_id"),
                            quantity,
                            quantity)
                    == 0) throw conflict("STOCK_SETTLEMENT_CONFLICT", "库存与占用不一致，需要人工核对");
        }
        db.update(
                "UPDATE orders SET status='paid',payment_state='paid',payment_method=?,"
                        + STAMP
                        + " WHERE id=? AND is_deleted=0",
                method,
                userId,
                orderId);
        db.insert(
                "INSERT INTO payments(order_id,method,transaction_id,cents,paid_at,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?)",
                orderId,
                method,
                transactionId,
                l(current, "total_cents"),
                now(),
                userId,
                userId);
        if ("package".equals(s(current, "kind")))
            membership.activatePaid(orders.owned(orderId, null), userId);
        return obj("alreadyPaid", false, "order", orders.owned(orderId, null));
    }

    public void assertNoUnresolved(long userId, long exceptOrderId) {
        if (db.one(
                        "SELECT p.id FROM payment_attempts p JOIN orders o ON o.id=p.order_id AND o.is_deleted=0 "
                                + "WHERE o.user_id=? AND o.id!=? AND p.is_deleted=0 AND p.state NOT IN ('closed','success') LIMIT 1",
                        userId,
                        exceptOrderId)
                != null) throw conflict("PAYMENT_UNRESOLVED", "存在待确认的原微信交易，请先查询或关闭原交易");
    }

    public ObjectNode capabilities() {
        return obj(
                "wechatAvailable",
                channel.configured(),
                "message",
                channel.configured() ? "" : "微信支付尚未配置，请联系品牌方");
    }

    public ObjectNode start(long orderId, ObjectNode user) {
        channel.requireConfigured();
        final String requestId = access.requestId();
        long actor = l(user, "id");
        ObjectNode work =
                db.tx(
                        () -> {
                            ObjectNode order = orders.owned(orderId, actor);
                            assertNoUnresolved(actor, orderId);
                            if ("package".equals(s(order, "kind")))
                                membership.assertOrderPayable(order);
                            else orders.assertRelationsCurrent(order);
                            if (!"pending".equals(s(order, "status")) || expired(order))
                                throw conflict("订单已付款、关闭或过期");
                            if (!WechatPayClient.safeInteger(order.path("total_cents"))
                                    || l(order, "total_cents") < 1) throw conflict("微信支付金额必须为正整数分");
                            ObjectNode old = active(orderId);
                            if (old != null) {
                                if (b(old, "close_requested")) throw conflict("原交易正在关闭，请先查询");
                                if ("ready".equals(s(old, "state")) && has(old, "prepay"))
                                    return obj("attempt", old, "token", "");
                                throw conflict("PAYMENT_UNRESOLVED", "原交易尚未确定，请查询原支付结果");
                            }
                            if (!in(s(order, "payment_state"), "unpaid", "failed"))
                                throw conflict("订单已有未决支付");
                            long attemptId =
                                    db.insert(
                                            "INSERT INTO payment_attempts(order_id,number,state,app_id,mch_id,cents,openid,last_request_id,create_user_id,update_user_id) "
                                                    + "VALUES(?,?,'creating',?,?,?,?,?,?,?)",
                                            orderId,
                                            "P" + id().substring(0, 31),
                                            channel.appId(),
                                            channel.mchId(),
                                            l(order, "total_cents"),
                                            s(user, "openid"),
                                            requestId,
                                            actor,
                                            actor);
                            db.update(
                                    "UPDATE orders SET payment_method='wechat',payment_state='paying',"
                                            + STAMP
                                            + " WHERE id=? AND is_deleted=0",
                                    actor,
                                    orderId);
                            ObjectNode attempt = current(attemptId);
                            String token = claim(attempt, actor);
                            access.audit("payment.start", "order", orderId, "发起微信支付并锁定支付方式");
                            return obj("attempt", attempt, "token", token);
                        });
        ObjectNode attempt = (ObjectNode) work.get("attempt");
        String token = s(work, "token");
        if (token.isEmpty())
            return obj("state", s(attempt, "state"), "parameters", parse(s(attempt, "prepay")));
        try {
            ObjectNode order = orders.owned(orderId, null);
            JsonNode parameters =
                    channel.create(
                            obj(
                                    "number",
                                    s(attempt, "number"),
                                    "cents",
                                    l(attempt, "cents"),
                                    "openid",
                                    s(attempt, "openid"),
                                    "description",
                                    "清欣简妍肌商品订单",
                                    "expiresAt",
                                    s(order, "expires_at")));
            db.tx(
                    () -> {
                        db.update(
                                "UPDATE payment_attempts SET state='ready',prepay=?,"
                                        + STAMP
                                        + " WHERE id=? AND state='creating' AND lease_token=? AND is_deleted=0",
                                stringify(parameters),
                                actor,
                                l(attempt, "id"),
                                token);
                        event(attempt, "prepay", "success", "预支付已生成，仍以通知或查询到账为准", requestId, actor);
                    });
            ObjectNode latest = current(l(attempt, "id"));
            ObjectNode result = obj("state", s(latest, "state"));
            if ("ready".equals(s(latest, "state")) && !b(latest, "close_requested"))
                result.set("parameters", parameters);
            return result;
        } catch (RuntimeException e) {
            unknown(attempt, "prepay", e, requestId, actor, token);
            throw new BusinessException(
                    502,
                    "PAYMENT_UNRESOLVED",
                    "支付发起结果未确定，请查询原交易；订单=" + orderId + "，requestId=" + requestId);
        } finally {
            unclaim(attempt, token, actor);
        }
    }

    public ObjectNode details(long id, Long userId, boolean admin) {
        ObjectNode order = orders.owned(id, userId), attempt = active(id), view = null;
        if (attempt != null) {
            view =
                    obj(
                            "id",
                            l(attempt, "id"),
                            "number",
                            s(attempt, "number"),
                            "state",
                            s(attempt, "state"),
                            "closeRequested",
                            b(attempt, "close_requested"),
                            "transactionId",
                            attempt.get("transaction_id"));
            if (admin) view.put("lastError", s(attempt, "last_error"));
        }
        ObjectNode result =
                obj(
                        "order",
                        orders.view(order),
                        "attempt",
                        view,
                        "receipt",
                        db.one(
                                "SELECT method,transaction_id,cents,paid_at FROM payments WHERE order_id=? AND is_deleted=0",
                                id));
        if (admin) {
            result.set(
                    "attempts",
                    json(
                            db.list(
                                    "SELECT id,number,state,transaction_id,last_error,last_request_id,close_requested,create_time,update_time "
                                            + "FROM payment_attempts WHERE order_id=? AND is_deleted=0 ORDER BY id DESC",
                                    id)));
            result.set(
                    "events",
                    json(
                            db.list(
                                    "SELECT * FROM payment_events WHERE order_id=? AND is_deleted=0 ORDER BY id DESC LIMIT 100",
                                    id)));
        }
        return result;
    }

    public ObjectNode query(long id, Long userId) {
        ObjectNode order = orders.owned(id, userId), attempt = active(id);
        if (attempt != null && !"success".equals(s(attempt, "state"))) {
            channel.requireConfigured();
            reconcileAttempt(
                    attempt,
                    access.requestId(),
                    b(attempt, "close_requested") || expired(order),
                    access.actor());
        }
        db.tx(() -> access.audit("payment.query", "order", id, "查询原微信交易"));
        return orders.view(orders.owned(id, null));
    }

    public ObjectNode close(long id, Long userId, String reason) {
        long actor = access.actor();
        ObjectNode attempt =
                db.tx(
                        () -> {
                            ObjectNode order = orders.owned(id, userId), a = active(id);
                            if (a != null && !"success".equals(s(a, "state")))
                                db.update(
                                        "UPDATE payment_attempts SET close_requested=1,"
                                                + STAMP
                                                + " WHERE id=? AND is_deleted=0",
                                        actor,
                                        l(a, "id"));
                            else if (!"paid".equals(s(order, "payment_state"))
                                    && !"closed".equals(s(order, "status"))) orders.release(order);
                            access.audit("payment.close.request", "order", id, reason);
                            return a;
                        });
        if (attempt != null && !"success".equals(s(attempt, "state"))) {
            channel.requireConfigured();
            reconcileAttempt(attempt, access.requestId(), true, actor);
        }
        return orders.view(orders.owned(id, null));
    }

    /** 回调无会话，所有失败仍返回微信通知约定的 FAIL 结构。 */
    public org.springframework.http.ResponseEntity<ObjectNode> notify(
            byte[] raw, HttpServletRequest request) {
        ObjectNode attempt = null;
        String requestId = access.requestId();
        try {
            if (raw == null) throw new BusinessException(503, "RAW_BODY_REQUIRED", "微信通知验签需要原始请求体");
            if (raw.length > 128 * 1024)
                throw new BusinessException(413, "PAYLOAD_TOO_LARGE", "微信通知请求体过大");
            JsonNode trade = channel.notification(raw, request);
            attempt =
                    db.one(
                            "SELECT * FROM payment_attempts WHERE number=? AND is_deleted=0",
                            s(trade, "out_trade_no"));
            if (attempt == null) throw new BusinessException(404, "PAYMENT_NOT_FOUND", "原支付记录不存在");
            applyTrade(attempt, trade, requestId, 0, null);
            return org.springframework.http.ResponseEntity.ok(
                    obj("code", "SUCCESS", "message", "成功"));
        } catch (RuntimeException e) {
            if (attempt != null) unknown(attempt, "notify", e, requestId, 0, null);
            else db.tx(() -> event(null, "notify", "failure", e.getMessage(), requestId, 0));
            return org.springframework.http.ResponseEntity.status(exceptionStatus(e))
                    .body(obj("code", "FAIL", "message", "通知未确认，请重试或查询原交易"));
        }
    }

    private ObjectNode active(long orderId) {
        return db.one(
                "SELECT * FROM payment_attempts WHERE order_id=? AND is_deleted=0 AND state!='closed' ORDER BY id DESC LIMIT 1",
                orderId);
    }

    private ObjectNode current(long id) {
        ObjectNode attempt =
                db.one("SELECT * FROM payment_attempts WHERE id=? AND is_deleted=0", id);
        if (attempt == null) throw notFound("原支付记录不存在");
        return attempt;
    }

    private String claim(ObjectNode attempt, long actor) {
        db.assertTransaction();
        String token = id();
        if (db.update(
                        "UPDATE payment_attempts SET lease_token=?,lease_until=?,"
                                + STAMP
                                + " WHERE id=? AND is_deleted=0 AND (lease_until IS NULL OR lease_until<?)",
                        token,
                        Instant.now().plusSeconds(30).toString(),
                        actor,
                        l(attempt, "id"),
                        now())
                == 0) throw conflict("PAYMENT_IN_PROGRESS", "原交易正在处理，请稍后查询");
        return token;
    }

    private void unclaim(ObjectNode attempt, String token, long actor) {
        db.tx(
                () -> {
                    db.update(
                            "UPDATE payment_attempts SET lease_token=NULL,lease_until=NULL,"
                                    + STAMP
                                    + " WHERE id=? AND lease_token=? AND is_deleted=0",
                            actor,
                            l(attempt, "id"),
                            token);
                });
    }

    private void event(
            ObjectNode attempt,
            String action,
            String result,
            String detail,
            String requestId,
            long actor) {
        db.assertTransaction();
        String message = truncate(detail == null ? "渠道处理异常" : detail);
        db.insert(
                "INSERT INTO payment_events(order_id,attempt_id,action,result,detail,request_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?)",
                attempt == null ? null : l(attempt, "order_id"),
                attempt == null ? null : l(attempt, "id"),
                action,
                result,
                message,
                requestId,
                actor,
                actor);
        // 系统回调和定时任务没有会话，直接写入与 Node 一致的系统审计记录。
        db.insert(
                "INSERT INTO audit_events(actor_kind,actor_id,action,object_type,object_id,result,reason,request_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "system",
                0,
                "payment." + action,
                "order",
                attempt == null ? "notification" : String.valueOf(l(attempt, "order_id")),
                "success".equals(result) ? "success" : "failure",
                message,
                requestId,
                actor,
                actor);
    }

    private void unknown(
            ObjectNode attempt,
            String action,
            RuntimeException error,
            String requestId,
            long actor,
            String token) {
        db.tx(
                () -> {
                    ObjectNode latest = current(l(attempt, "id"));
                    if (token != null && !token.equals(s(latest, "lease_token"))) return;
                    String detail =
                            truncate(error.getMessage() == null ? "渠道处理异常" : error.getMessage());
                    db.update(
                            "UPDATE payment_attempts SET state=CASE WHEN state IN ('success','closed') THEN state ELSE 'unknown' END,last_error=?,last_request_id=?,"
                                    + STAMP
                                    + " WHERE id=? AND is_deleted=0",
                            detail,
                            requestId,
                            actor,
                            l(attempt, "id"));
                    db.update(
                            "UPDATE orders SET payment_state='unknown',"
                                    + STAMP
                                    + " WHERE id=? AND payment_state!='paid' AND status='pending' AND is_deleted=0",
                            actor,
                            l(attempt, "order_id"));
                    event(attempt, action, "failure", detail, requestId, actor);
                });
        LOG.error(
                "支付结果待核查，orderId={}，支付单={}，requestId={}，原因={}",
                l(attempt, "order_id"),
                s(attempt, "number"),
                requestId,
                error.getMessage());
    }

    static void match(JsonNode attempt, JsonNode trade) {
        JsonNode amount = trade.path("amount"), payer = trade.path("payer");
        if (!s(attempt, "number").equals(s(trade, "out_trade_no"))
                || !s(attempt, "mch_id").equals(s(trade, "mchid"))
                || !s(attempt, "app_id").equals(s(trade, "appid"))
                || !WechatPayClient.safeInteger(amount.path("total"))
                || l(amount, "total") != l(attempt, "cents")
                || !"CNY".equals(s(amount, "currency"))
                || (!s(payer, "openid").isEmpty()
                        && !s(attempt, "openid").equals(s(payer, "openid"))))
            throw conflict("PAYMENT_MISMATCH", "支付订单、金额、付款人或商户不匹配");
        if ("SUCCESS".equals(s(trade, "trade_state"))
                && (s(trade, "transaction_id").isEmpty()
                        || !s(attempt, "openid").equals(s(payer, "openid"))
                        || s(payer, "openid").isEmpty()))
            throw conflict("PAYMENT_MISMATCH", "成功交易缺少有效流水或付款人");
    }

    private ObjectNode applyTrade(
            ObjectNode attempt, JsonNode trade, String requestId, long actor, String token) {
        match(attempt, trade);
        long attemptId = l(attempt, "id"), orderId = l(attempt, "order_id");
        // 验明的渠道到账证据先单独提交，后续本地结算失败也可人工补偿。
        if ("SUCCESS".equals(s(trade, "trade_state")))
            db.tx(
                    () -> {
                        db.update(
                                "UPDATE payment_attempts SET transaction_id=?,verified_trade=?,"
                                        + STAMP
                                        + " WHERE id=? AND is_deleted=0",
                                s(trade, "transaction_id"),
                                stringify(trade),
                                actor,
                                attemptId);
                    });
        return db.tx(
                () -> {
                    ObjectNode latest = current(attemptId), order = orders.owned(orderId, null);
                    if (token != null && !token.equals(s(latest, "lease_token"))) return order;
                    String state = s(trade, "trade_state");
                    if ("SUCCESS".equals(state)) {
                        if ("closed".equals(s(latest, "state")))
                            throw conflict("SETTLEMENT_CONFLICT", "已关闭交易发生异常到账，需要按原渠道流水人工补偿");
                        ObjectNode result = settlePaid(order, "wechat", s(trade, "transaction_id"));
                        db.update(
                                "UPDATE payments SET out_trade_no=?,"
                                        + STAMP
                                        + " WHERE order_id=? AND is_deleted=0",
                                s(attempt, "number"),
                                actor,
                                orderId);
                        db.update(
                                "UPDATE payment_attempts SET state='success',last_error='',last_request_id=?,"
                                        + STAMP
                                        + " WHERE id=? AND is_deleted=0",
                                requestId,
                                actor,
                                attemptId);
                        event(
                                attempt,
                                "verified",
                                "success",
                                b(result, "alreadyPaid") ? "重复成功通知，已幂等处理" : "服务端核验到账并结算库存",
                                requestId,
                                actor);
                        return (ObjectNode) result.get("order");
                    }
                    if ("success".equals(s(latest, "state"))
                            || "paid".equals(s(order, "payment_state"))) return order;
                    if (in(state, "CLOSED", "REVOKED")) {
                        db.update(
                                "UPDATE payment_attempts SET state='closed',prepay=NULL,last_error='',last_request_id=?,"
                                        + STAMP
                                        + " WHERE id=? AND is_deleted=0",
                                requestId,
                                actor,
                                attemptId);
                        db.update(
                                "UPDATE orders SET payment_state='failed',"
                                        + STAMP
                                        + " WHERE id=? AND status='pending' AND is_deleted=0",
                                actor,
                                orderId);
                        ObjectNode updated = orders.owned(orderId, null);
                        if (b(latest, "close_requested") || expired(updated))
                            orders.release(updated);
                        event(attempt, "closed", "success", "渠道已关闭，允许释放原订单库存", requestId, actor);
                    } else if (!in(state, "NOTPAY", "USERPAYING", "PAYERROR"))
                        throw conflict("未知微信交易状态，保留支付锁");
                    else event(attempt, "query", "success", "微信交易状态：" + state, requestId, actor);
                    return orders.owned(orderId, null);
                });
    }

    private ObjectNode reconcileAttempt(
            ObjectNode attempt, String requestId, boolean close, long actor) {
        String token = db.tx(() -> claim(attempt, actor));
        try {
            if (!channel.appId().equals(s(attempt, "app_id"))
                    || !channel.mchId().equals(s(attempt, "mch_id")))
                throw conflict("支付渠道配置与原商户不匹配，保留原交易待核查");
            JsonNode trade;
            try {
                trade = channel.query(s(attempt, "number"));
            } catch (RuntimeException e) {
                ObjectNode order = orders.owned(l(attempt, "order_id"), null),
                        latest = current(l(attempt, "id"));
                if (e instanceof BusinessException
                        && "ORDER_NOT_EXIST".equals(((BusinessException) e).getCode())
                        && expired(order)
                        && !has(latest, "prepay")
                        && !has(latest, "verified_trade")) {
                    return db.tx(
                            () -> {
                                ObjectNode current = orders.owned(l(attempt, "order_id"), null),
                                        fresh = current(l(attempt, "id"));
                                if ("paid".equals(s(current, "payment_state"))
                                        || !token.equals(s(fresh, "lease_token"))
                                        || has(fresh, "prepay")
                                        || has(fresh, "verified_trade")) return current;
                                db.update(
                                        "UPDATE payment_attempts SET state='closed',last_error='',last_request_id=?,"
                                                + STAMP
                                                + " WHERE id=? AND is_deleted=0",
                                        requestId,
                                        actor,
                                        l(attempt, "id"));
                                db.update(
                                        "UPDATE orders SET payment_state='failed',"
                                                + STAMP
                                                + " WHERE id=? AND status='pending' AND is_deleted=0",
                                        actor,
                                        l(current, "id"));
                                orders.release(orders.owned(l(current, "id"), null));
                                event(
                                        attempt,
                                        "expired-absent",
                                        "success",
                                        "原固定有效期已过，原商户验签查询确认不存在，释放未付款占用",
                                        requestId,
                                        actor);
                                return orders.owned(l(current, "id"), null);
                            });
                }
                throw e;
            }
            ObjectNode order = applyTrade(attempt, trade, requestId, actor, token);
            if ((close || "PAYERROR".equals(s(trade, "trade_state")))
                    && !"paid".equals(s(order, "payment_state"))
                    && !in(s(trade, "trade_state"), "CLOSED", "REVOKED")
                    && token.equals(s(current(l(attempt, "id")), "lease_token"))) {
                try {
                    channel.close(s(attempt, "number"));
                } catch (RuntimeException e) {
                    order =
                            applyTrade(
                                    attempt,
                                    channel.query(s(attempt, "number")),
                                    requestId,
                                    actor,
                                    token);
                    if (!"paid".equals(s(order, "payment_state"))
                            && !"closed".equals(s(current(l(attempt, "id")), "state"))) throw e;
                    return order;
                }
                ObjectNode closed = trade.deepCopy();
                closed.put("trade_state", "CLOSED");
                order = applyTrade(attempt, closed, requestId, actor, token);
            }
            return order;
        } catch (RuntimeException e) {
            unknown(attempt, "reconcile", e, requestId, actor, token);
            throw e;
        } finally {
            unclaim(attempt, token, actor);
        }
    }

    /** 每轮最多二十笔，逐项失败独立记录；不会把未决交易计为结算成功。 */
    public void reconcile() {
        if (!channel.configured() || !running.compareAndSet(false, true)) return;
        try {
            List<ObjectNode> attempts =
                    db.list(
                            "SELECT p.* FROM payment_attempts p JOIN orders o ON o.id=p.order_id AND o.is_deleted=0 "
                                    + "WHERE p.is_deleted=0 AND p.state NOT IN ('closed','success') AND (p.lease_until IS NULL OR p.lease_until<?) ORDER BY p.update_time,p.id LIMIT 20",
                            now());
            for (ObjectNode attempt : attempts) {
                String requestId = "payment-reconcile-" + id();
                try {
                    ObjectNode order = orders.owned(l(attempt, "order_id"), null);
                    reconcileAttempt(
                            attempt, requestId, b(attempt, "close_requested") || expired(order), 0);
                } catch (RuntimeException e) {
                    LOG.error(
                            "支付定时核对单项失败，orderId={}，支付单={}，requestId={}，原因={}",
                            l(attempt, "order_id"),
                            s(attempt, "number"),
                            requestId,
                            e.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${payments.reconcile-delay-ms:30000}")
    public void scheduledReconcile() {
        if (com.jianyanji.common.MaintenanceMode.enabled()) return;
        try {
            reconcile();
        } catch (RuntimeException e) {
            LOG.error("支付核对任务失败，requestId=payment-reconcile，原因={}", e.getMessage());
        }
    }

    private static boolean expired(JsonNode order) {
        return !Instant.parse(s(order, "expires_at")).isAfter(Instant.now());
    }

    private static boolean in(String value, String... options) {
        return Arrays.asList(options).contains(value);
    }

    private static String truncate(String value) {
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static int exceptionStatus(RuntimeException error) {
        return error instanceof BusinessException ? ((BusinessException) error).getStatus() : 400;
    }
}
