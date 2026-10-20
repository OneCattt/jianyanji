package com.jianyanji.rewards;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Db;
import com.jianyanji.common.Input;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import org.springframework.stereotype.Service;

/** 人工奖励登记、月结、来源退款冲回与已付欠款追踪。 金额均为整数分；资金归属只取历史来源快照，不修改积分或自动打款。 */
@Service
public class RewardService {
    /** 原业务允许的单笔金额上限，单位分。 */
    private static final long MAX_MONEY = 100000000L;
    /** JavaScript 安全整数上限，用于接口编号、数量及累计金额。 */
    private static final long MAX_SAFE = 9007199254740991L;
    /** 统一业务时间输出格式，UTC 且至少保留三位小数。 */
    private static final DateTimeFormatter ISO_MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    /** 数据访问组件；外层事务串行化业务，嵌套事务参与调用方事务。 */
    private final Db db;
    /** 当前请求操作人、请求编号和管理审计。 */
    private final Access access;

    public RewardService(Db db, Access access) {
        this.db = db;
        this.access = access;
    }

    /** 读取奖励参数及统一售后天数；未配置时返回空。 */
    public ObjectNode settingsView() {
        return db.tx(
                () -> {
                    ObjectNode value = settings();
                    if (value != null) {
                        JsonNode policy = db.setting("aftersales");
                        value.set(
                                "afterSalesDays",
                                policy == null || !has(policy, "days")
                                        ? json(null)
                                        : policy.get("days"));
                    }
                    return value;
                });
    }

    /** 只修改新业务使用的奖励参数，不重算历史订单或奖励。 */
    public ObjectNode saveSettings(JsonNode body) {
        return db.tx(
                () -> {
                    Input.strict(
                            body,
                            "storeRewardCents",
                            "principalRewardCents",
                            "settlementDay",
                            "reason");
                    ObjectNode value =
                            obj(
                                    "storeRewardCents",
                                    100000L,
                                    "principalRewardCents",
                                    0L,
                                    "settlementDay",
                                    Input.integer(body, "settlementDay", 1, 28));
                    String reason = reason(body);
                    long actor = access.actor();
                    db.update(
                            "INSERT INTO reward_settings(id,value,create_user_id,update_user_id) VALUES(1,?,?,?) "
                                    + "ON DUPLICATE KEY UPDATE value=VALUES(value),update_user_id=VALUES(update_user_id),update_time=CURRENT_TIMESTAMP(3)",
                            stringify(value),
                            actor,
                            actor);
                    access.audit("reward.settings", "reward_settings", 1, reason);
                    return value;
                });
    }

    /** 从已核实付款的订单构建登记依据，保留原快照的奖励归属及价格。 */
    public ObjectNode source(long orderId) {
        return db.tx(() -> sourceOptionsInside(Input.id(orderId)));
    }

    /** 人工确认来源金额后登记；同一有效订单、类型及收益人只能登记一次。 */
    public ObjectNode register(JsonNode body) {
        return db.tx(
                () -> {
                    Input.strict(body, "orderId", "userId", "amountCents", "reason");
                    long orderId = entity(body, "orderId");
                    long amount = money(body, "amountCents", true);
                    String reason = reason(body);
                    ObjectNode options = sourceOptionsInside(orderId);
                    ArrayNode candidates = options.withArray("candidates");
                    requireConflict(candidates.size() > 0, "该来源没有可登记的奖励");
                    Input.require(candidates.size() == 1 || has(body, "userId"), "请选择本次登记的收益人");
                    long userId =
                            has(body, "userId") ? entity(body, "userId") : l(options, "userId");
                    ObjectNode source = null;
                    for (JsonNode candidate : candidates)
                        if (l(candidate, "userId") == userId) source = (ObjectNode) candidate;
                    requireConflict(source != null, "收益人不属于原来源核算范围");
                    requireConflict(amount == l(source, "amountCents"), "人工登记金额与原业务核算依据不一致");
                    requireConflict(
                            db.one(
                                            "SELECT id FROM rewards WHERE order_id=? AND kind=? AND user_id=? AND is_deleted=0",
                                            orderId,
                                            s(source, "kind"),
                                            userId)
                                    == null,
                            "该来源奖励已经登记");
                    long actor = access.actor();
                    long id =
                            db.insert(
                                    "INSERT INTO rewards(order_id,user_id,kind,amount_cents,snapshot,reason,request_id,create_user_id,update_user_id) "
                                            + "VALUES(?,?,?,?,?,?,?,?,?)",
                                    orderId,
                                    l(source, "userId"),
                                    s(source, "kind"),
                                    amount,
                                    stringify(source.get("snapshot")),
                                    reason,
                                    access.requestId(),
                                    actor,
                                    actor);
                    reconcileInside(orderId, actor, access.requestId());
                    access.audit("reward.register", "reward", id, reason);
                    return detailInside(reward(id, null), true);
                });
    }

    /** 后台最多查询最近五百条奖励及当前可结算状态。 */
    public ArrayNode adminRewards() {
        return db.tx(
                () -> {
                    ArrayNode result = arr();
                    for (ObjectNode row :
                            db.list(
                                    "SELECT * FROM rewards WHERE is_deleted=0 ORDER BY id DESC LIMIT 500"))
                        result.add(view(row, true));
                    return result;
                });
    }

    /** 查询奖励详情；本人查询必须附带用户编号限制。 */
    public ObjectNode detail(long id, Long userId, boolean admin) {
        return db.tx(() -> detailInside(reward(Input.id(id), userId), admin));
    }

    /** 人工重新核对来源成功退款，不重复生成冲回。 */
    public ObjectNode reconcile(long id, JsonNode body) {
        return db.tx(
                () -> {
                    Input.strict(body, "reason");
                    String reason = reason(body);
                    ObjectNode row = reward(Input.id(id), null);
                    reconcileInside(l(row, "order_id"), access.actor(), access.requestId());
                    access.audit("reward.reconcile", "reward", id, reason);
                    return detailInside(reward(id, null), true);
                });
    }

    /** 来源退款完成时由售后服务调用；加入调用方 REQUIRED 事务，任何异常使整笔退款回滚。 actor 为工作人员编号或系统零值；requestId 为原请求追踪编号。 */
    public void reconcileReversals(long orderId, long actor, String requestId) {
        db.tx(
                () -> {
                    reconcileInside(Input.id(orderId), actor, requestId);
                });
    }

    /** 本人奖励、冲回和上海时区当月统计，不暴露内部来源快照。 */
    public ObjectNode ownRewards(long userId) {
        return db.tx(
                () -> {
                    ArrayNode items = arr();
                    for (ObjectNode row :
                            db.list(
                                    "SELECT * FROM rewards WHERE user_id=? AND is_deleted=0 ORDER BY id DESC LIMIT 500",
                                    userId)) items.add(view(row, false));
                    String month = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();
                    ArrayNode reversals = arr();
                    for (ObjectNode row :
                            db.list(
                                    "SELECT v.id,v.reward_id,v.amount_cents,v.create_time,r.snapshot "
                                            + "FROM reward_reversals v JOIN rewards r ON r.id=v.reward_id AND r.is_deleted=0 "
                                            + "WHERE r.user_id=? AND v.is_deleted=0 AND v.amount_cents>0 ORDER BY v.id DESC LIMIT 500",
                                    userId)) {
                        reversals.add(
                                obj(
                                        "id",
                                        row.get("id"),
                                        "rewardId",
                                        row.get("reward_id"),
                                        "amountCents",
                                        row.get("amount_cents"),
                                        "createTime",
                                        row.get("create_time"),
                                        "orderNumber",
                                        parse(s(row, "snapshot")).get("orderNumber")));
                    }
                    // 审计时间为 MySQL DATETIME(3) UTC；转换到上海时区后按自然月统计。
                    ObjectNode totals =
                            db.one(
                                    "SELECT COALESCE(SUM(GREATEST(0,amount_cents-reversed_cents-settled_cents)),0) AS pending,"
                                            + "COALESCE(SUM(settled_cents),0) AS settled,COALESCE(SUM(reversed_cents),0) AS reversed,"
                                            + "COALESCE(SUM(CASE WHEN DATE_FORMAT(DATE_ADD(create_time,INTERVAL 8 HOUR),'%Y-%m')=? "
                                            + "THEN amount_cents-reversed_cents ELSE 0 END),0) AS estimated FROM rewards WHERE user_id=? AND is_deleted=0",
                                    month, userId);
                    long debt = 0;
                    for (ObjectNode row : debtRows(userId)) debt += l(row, "remainingCents");
                    return obj(
                            "items",
                            items,
                            "reversals",
                            reversals,
                            "summary",
                            obj(
                                    "month",
                                    month,
                                    "estimatedCents",
                                    l(totals, "estimated"),
                                    "pendingCents",
                                    l(totals, "pending"),
                                    "settledCents",
                                    l(totals, "settled"),
                                    "reversedCents",
                                    l(totals, "reversed"),
                                    "debtCents",
                                    debt));
                });
    }

    /** 后台查询仍有余额的已付奖励欠款。 */
    public ArrayNode debts() {
        return db.tx(
                () -> {
                    ArrayNode rows = arr();
                    for (ObjectNode user :
                            db.list("SELECT DISTINCT user_id FROM rewards WHERE is_deleted=0"))
                        for (ObjectNode debt : debtRows(l(user, "user_id")))
                            if (l(debt, "remainingCents") > 0) rows.add(debt);
                    return rows;
                });
    }

    /** 后台最近五百笔月结记录；列表将计划文本展开为 JSON。 */
    public ArrayNode settlements() {
        return db.tx(
                () -> {
                    ArrayNode rows = arr();
                    for (ObjectNode row :
                            db.list(
                                    "SELECT * FROM reward_settlements WHERE is_deleted=0 ORDER BY id DESC LIMIT 500")) {
                        row.set("plan", parse(s(row, "plan")));
                        rows.add(row);
                    }
                    return rows;
                });
    }

    /** 生成同一人同月的人工月结计划；创建响应仍保留原 plan 文本字段。 */
    public ObjectNode plan(JsonNode body) {
        return db.tx(
                () -> {
                    Input.strict(
                            body, "userId", "month", "rewardIds", "payee", "requestKey", "reason");
                    long userId = entity(body, "userId");
                    String month = rawText(body, "month");
                    Input.require(month.matches("\\d{4}-(0[1-9]|1[0-2])"), "结算月份格式无效");
                    List<Long> ids = rewardIds(body);
                    String payee = Input.text(body, "payee", 1, 100),
                            key = requestKey(body),
                            reason = reason(body);
                    requireConflict(settings() != null, "真实奖励档位和月结日期尚未配置");
                    ObjectNode old =
                            db.one(
                                    "SELECT * FROM reward_settlements WHERE request_key=? AND is_deleted=0",
                                    key);
                    if (old != null) {
                        List<Long> previous = new ArrayList<>();
                        for (JsonNode entry : parse(s(old, "plan")).path("rewards"))
                            previous.add(l(entry, "rewardId"));
                        requireConflict(
                                l(old, "user_id") == userId
                                        && s(old, "month").equals(month)
                                        && s(old, "payee").equals(payee)
                                        && previous.equals(ids),
                                "请求编号已用于其他结算");
                        return old;
                    }
                    requireConflict(
                            db.one(
                                            "SELECT id FROM reward_settlements WHERE user_id=? AND month=? "
                                                    + "AND status!='cancelled' AND is_deleted=0",
                                            userId,
                                            month)
                                    == null,
                            "该对象本月已有结算，请核对原记录");
                    ObjectNode plan = planInside(userId, ids);
                    long actor = access.actor();
                    long id =
                            db.insert(
                                    "INSERT INTO reward_settlements(user_id,month,payee,status,gross_cents,offset_cents,net_cents,plan,"
                                            + "reason,request_key,request_id,create_user_id,update_user_id) VALUES(?,?,?,'pending',?,?,?,?,?,?,?,?,?)",
                                    userId,
                                    month,
                                    payee,
                                    l(plan, "grossCents"),
                                    l(plan, "offsetCents"),
                                    l(plan, "netCents"),
                                    stringify(plan),
                                    reason,
                                    key,
                                    access.requestId(),
                                    actor,
                                    actor);
                    access.audit("reward.settlement.plan", "reward_settlement", id, reason);
                    return db.one(
                            "SELECT * FROM reward_settlements WHERE id=? AND is_deleted=0", id);
                });
    }

    /** 核实实际付款或全额抵扣结果；已付记录只允许完全一致的幂等确认。 */
    public ObjectNode result(long id, JsonNode body) {
        return db.tx(
                () -> {
                    Input.id(id);
                    Input.strict(body, "status", "paidAt", "proof", "reason");
                    String status = rawText(body, "status");
                    Input.require(
                            Arrays.asList("paid", "failed", "unknown", "cancelled")
                                    .contains(status),
                            "结算状态无效");
                    String paidAt = body.has("paidAt") ? actualTime(body, "paidAt") : null;
                    String proof = body.has("proof") ? Input.text(body, "proof", 1, 1000) : null;
                    String reason = reason(body);
                    ObjectNode settlement =
                            db.one(
                                    "SELECT * FROM reward_settlements WHERE id=? AND is_deleted=0",
                                    id);
                    if (settlement == null) throw notFound("结算不存在");
                    String previousStatus = s(settlement, "status");
                    if ("paid".equals(previousStatus)) {
                        requireConflict(
                                "paid".equals(status)
                                        && Objects.equals(nullableText(settlement, "proof"), proof)
                                        && Objects.equals(
                                                nullableText(settlement, "paid_at"), paidAt),
                                "结算已确认，不可覆盖付款依据");
                        return obj("ok", true);
                    }
                    requireConflict(!"cancelled".equals(previousStatus), "结算已取消");
                    requireConflict(
                            !("unknown".equals(previousStatus) && "cancelled".equals(status)),
                            "付款结果未明，须先核实失败或成功，不能取消后重复付款");
                    long actor = access.actor();
                    if ("paid".equals(status)) {
                        requireConflict(proof != null && paidAt != null, "需提供真实付款或全额抵扣核实时间及凭证");
                        JsonNode original = parse(s(settlement, "plan"));
                        List<Long> ids = new ArrayList<>();
                        for (JsonNode entry : original.path("rewards"))
                            ids.add(l(entry, "rewardId"));
                        ObjectNode fresh = planInside(l(settlement, "user_id"), ids);
                        requireConflict(samePlan(original, fresh), "奖励或欠款已变化，请核对实际付款，重新核算结算记录");
                        for (JsonNode entry : original.path("rewards"))
                            db.update(
                                    "UPDATE rewards SET settled_cents=settled_cents+?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) "
                                            + "WHERE id=? AND is_deleted=0",
                                    l(entry, "amountCents"),
                                    actor,
                                    l(entry, "rewardId"));
                        for (JsonNode entry : original.path("offsets"))
                            db.insert(
                                    "INSERT INTO reward_offsets(reversal_id,settlement_id,reward_id,amount_cents,create_user_id,update_user_id) "
                                            + "VALUES(?,?,?,?,?,?)",
                                    l(entry, "reversalId"),
                                    id,
                                    l(entry, "rewardId"),
                                    l(entry, "amountCents"),
                                    actor,
                                    actor);
                    }
                    db.update(
                            "UPDATE reward_settlements SET status=?,paid_at=?,proof=?,reason=?,request_id=?,update_user_id=?,"
                                    + "update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            status,
                            paidAt,
                            proof,
                            reason,
                            access.requestId(),
                            actor,
                            id);
                    access.audit(
                            "reward.settlement." + status,
                            "reward_settlement",
                            id,
                            reason,
                            "failed".equals(status) ? "failure" : "success");
                    return obj("ok", true);
                });
    }

    /** 登记真实归还的已付欠款；同一请求幂等，不得超收或抵扣其他人的奖励。 */
    public ObjectNode recover(JsonNode body) {
        return db.tx(
                () -> {
                    Input.strict(
                            body,
                            "reversalId",
                            "amountCents",
                            "receivedAt",
                            "proof",
                            "requestKey",
                            "reason");
                    long reversalId = entity(body, "reversalId"),
                            amount = money(body, "amountCents", true);
                    String receivedAt = actualTime(body, "receivedAt"),
                            proof = Input.text(body, "proof", 1, 1000);
                    String key = requestKey(body), reason = reason(body);
                    ObjectNode old =
                            db.one(
                                    "SELECT * FROM reward_recoveries WHERE request_key=? AND is_deleted=0",
                                    key);
                    if (old != null) {
                        requireConflict(
                                l(old, "reversal_id") == reversalId
                                        && l(old, "amount_cents") == amount
                                        && s(old, "received_at").equals(receivedAt)
                                        && s(old, "proof").equals(proof),
                                "请求编号已用于其他实收记录");
                        return obj("id", l(old, "id"));
                    }
                    ObjectNode owner =
                            db.one(
                                    "SELECT r.user_id FROM reward_reversals v JOIN rewards r ON r.id=v.reward_id AND r.is_deleted=0 "
                                            + "WHERE v.id=? AND v.is_deleted=0",
                                    reversalId);
                    if (owner == null) throw notFound("待收回记录不存在");
                    ObjectNode debt = null;
                    for (ObjectNode row : debtRows(l(owner, "user_id")))
                        if (l(row, "id") == reversalId) debt = row;
                    requireConflict(
                            debt != null && amount <= l(debt, "remainingCents"), "实收金额超过剩余待收回款");
                    long actor = access.actor();
                    long id =
                            db.insert(
                                    "INSERT INTO reward_recoveries(reversal_id,amount_cents,received_at,proof,reason,request_key,request_id,"
                                            + "create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?)",
                                    reversalId,
                                    amount,
                                    receivedAt,
                                    proof,
                                    reason,
                                    key,
                                    access.requestId(),
                                    actor,
                                    actor);
                    access.audit("reward.recovery.received", "reward_recovery", id, reason);
                    return obj("id", id);
                });
    }

    private ObjectNode settings() {
        ObjectNode row = db.one("SELECT value FROM reward_settings WHERE id=1 AND is_deleted=0");
        if (row == null) return null;
        JsonNode original = parse(s(row, "value"));
        return obj(
                "storeRewardCents",
                original.get("storeRewardCents"),
                "principalRewardCents",
                original.get("principalRewardCents"),
                "settlementDay",
                original.get("settlementDay"));
    }

    private ObjectNode reward(long id, Long owner) {
        ObjectNode row =
                owner == null
                        ? db.one("SELECT * FROM rewards WHERE id=? AND is_deleted=0", id)
                        : db.one(
                                "SELECT * FROM rewards WHERE id=? AND user_id=? AND is_deleted=0",
                                id,
                                owner);
        if (row == null) throw notFound("奖励不存在");
        return row;
    }

    private ObjectNode order(long id) {
        ObjectNode row = db.one("SELECT * FROM orders WHERE id=? AND is_deleted=0", id);
        if (row == null) throw notFound("订单不存在");
        return row;
    }

    /** 依据已付款快照逐人提供候选，登记仍由工作人员逐笔确认。 */
    private ObjectNode sourceOptionsInside(long orderId) {
        ObjectNode order = order(orderId);
        JsonNode original = parse(s(order, "snapshot"));
        ArrayNode candidates = arr();
        if ("package".equals(s(order, "kind")) && b(original, "initialVerified"))
            return obj(
                    "orderId",
                    orderId,
                    "orderNumber",
                    s(order, "number"),
                    "kind",
                    "onboarding",
                    "candidates",
                    candidates);
        if ("goods".equals(s(order, "kind"))
                && "D".equals(s(original, "identity"))
                && has(original, "salesAgent")) {
            candidates.add(retailSource(order, original));
        } else if ("package".equals(s(order, "kind")) && has(original, "inviterIdentity")) {
            requireConflict(
                    Arrays.asList("paid", "partially_refunded", "refunded")
                            .contains(s(order, "payment_state")),
                    "来源订单尚未核实有效付款");
            requireConflict("C".equals(s(original, "identity")), "主理人产品包奖励政策保持关闭");
            requireConflict(b(original, "onboardingApproved"), "原入驻资格尚未有效激活");
            long inviter = entity(original, "inviterId");
            String identity = s(original, "inviterIdentity");
            requireConflict("B".equals(identity) || "C".equals(identity), "原邀请身份快照无效");
            candidates.add(
                    onboardingCandidate(
                            order,
                            original,
                            inviter,
                            "B".equals(identity) ? 100000 : 50000,
                            "direct"));
            if ("C".equals(identity)) {
                long principal = entity(original, "principalId");
                requireConflict(principal != inviter, "邀请人和归属主理人不得重复登记为两位收益人");
                candidates.add(onboardingCandidate(order, original, principal, 50000, "principal"));
            }
        } else candidates.add(sourceInside(order));
        ObjectNode result = ((ObjectNode) candidates.get(0)).deepCopy();
        result.set("candidates", candidates);
        return result;
    }

    /** 按原确认身份生成一位收益人的固定奖励快照；不读取现时身份、归属或兼容 rewardCents。 */
    private ObjectNode onboardingCandidate(
            ObjectNode order, JsonNode original, long userId, long amount, String role) {
        requireConflict(
                userId != l(order, "user_id")
                        && db.one("SELECT id FROM users WHERE id=? AND is_deleted=0", userId)
                                != null,
                "奖励对象无效");
        ObjectNode evidence = ((ObjectNode) original).deepCopy();
        evidence.put("beneficiaryRole", role);
        evidence.put("rewardCents", amount);
        evidence.put("goodsCents", l(order, "goods_cents"));
        evidence.put("orderNumber", s(order, "number"));
        evidence.set("paymentMethod", order.get("payment_method"));
        Input.integer(evidence, "afterSalesDays", 0, 365);
        requireConflict(l(order, "goods_cents") > 0, "原产品包货款无效");
        return obj(
                "orderId",
                l(order, "id"),
                "orderNumber",
                s(order, "number"),
                "kind",
                "onboarding",
                "userId",
                userId,
                "amountCents",
                amount,
                "snapshot",
                evidence);
    }

    /** 游客本单已确认的销售归属按原普通商品货款计佣，运费及福利品排除。 */
    private ObjectNode retailSource(ObjectNode order, JsonNode original) {
        requireConflict(
                Arrays.asList("paid", "partially_refunded", "refunded")
                        .contains(s(order, "payment_state")),
                "来源订单尚未核实有效付款");
        requireConflict(b(original, "salesConfirmed"), "原订单缺少销售归属确认");
        JsonNode agent = original.get("salesAgent");
        long recipient = entity(agent, "id");
        requireConflict(
                recipient != l(order, "user_id")
                        && db.one("SELECT id FROM users WHERE id=? AND is_deleted=0", recipient)
                                != null,
                "零售收益人无效");
        String identity = s(agent, "identity");
        int percent = "B".equals(identity) ? 65 : "C".equals(identity) ? 30 : 0;
        requireConflict(percent > 0 && l(agent, "percent") == percent, "原销售身份及比例无效");
        ArrayNode lines = arr();
        long goods = 0;
        for (ObjectNode line :
                db.list(
                        "SELECT * FROM order_lines WHERE order_id=? AND is_deleted=0 ORDER BY id",
                        l(order, "id"))) {
            String type = s(line, "product_type");
            requireConflict("regular".equals(type) || "welfare".equals(type), "原商品类型依据无效");
            lines.add(
                    obj(
                            "lineId",
                            l(line, "id"),
                            "quantity",
                            l(line, "quantity"),
                            "unitCents",
                            l(line, "unit_cents"),
                            "productType",
                            type));
            if ("regular".equals(type)) goods += l(line, "unit_cents") * l(line, "quantity");
        }
        long amount = (goods * percent + 50) / 100;
        requireConflict(amount > 0 && amount <= MAX_MONEY, "无可登记的普通商品零售佣金");
        ObjectNode evidence =
                obj(
                        "identity",
                        "D",
                        "salesAgent",
                        agent,
                        "percent",
                        percent,
                        "lines",
                        lines,
                        "goodsCents",
                        l(order, "goods_cents"),
                        "commissionGoodsCents",
                        goods,
                        "afterSalesDays",
                        original.get("afterSalesDays"),
                        "orderNumber",
                        s(order, "number"));
        return obj(
                "orderId",
                l(order, "id"),
                "orderNumber",
                s(order, "number"),
                "kind",
                "retail",
                "userId",
                recipient,
                "amountCents",
                amount,
                "snapshot",
                evidence);
    }

    private ObjectNode sourceInside(ObjectNode order) {
        long orderId = l(order, "id");
        JsonNode original = parse(s(order, "snapshot"));
        requireConflict(
                Arrays.asList("paid", "partially_refunded", "refunded")
                        .contains(s(order, "payment_state")),
                "来源订单尚未核实有效付款");
        long userId, amount;
        ObjectNode evidence;
        boolean onboarding = "package".equals(s(order, "kind"));
        if (onboarding) {
            String identity = rawText(original, "identity");
            Input.require("B".equals(identity) || "C".equals(identity), "入驻身份依据无效");
            userId = entity(original, "inviterId");
            amount = money(original, "rewardCents", true);
            Input.require(
                    original.path("onboardingApproved").isBoolean()
                            && original.path("onboardingApproved").booleanValue(),
                    "入驻审批依据无效");
            evidence =
                    obj(
                            "identity",
                            identity,
                            "inviterId",
                            userId,
                            "rewardCents",
                            amount,
                            "afterSalesDays",
                            Input.integer(original, "afterSalesDays", 0, 365),
                            "onboardingApproved",
                            true,
                            "onboardingId",
                            entity(original, "onboardingId"));
            if (original.has("receiptId")) evidence.put("receiptId", entity(original, "receiptId"));
            if (original.has("principalId"))
                evidence.set(
                        "principalId",
                        original.get("principalId").isNull()
                                ? json(null)
                                : json(entity(original, "principalId")));
            if (original.has("afterSalesPolicy"))
                evidence.put("afterSalesPolicy", rawText(original, "afterSalesPolicy"));
        } else {
            requireConflict("C".equals(s(original, "identity")), "仅店主订货可登记归属主理人收益");
            userId = entity(original, "principalId");
            ArrayNode lines = arr();
            BigInteger sum = BigInteger.ZERO;
            for (ObjectNode line :
                    db.list(
                            "SELECT id,sku_id,quantity,unit_cents,principal_cents,product_type FROM order_lines "
                                    + "WHERE order_id=? AND is_deleted=0 ORDER BY id",
                            orderId)) {
                requireConflict(
                        has(line, "principal_cents")
                                && l(line, "unit_cents") >= l(line, "principal_cents"),
                        "原订单缺少有效主理人价差快照");
                lines.add(
                        obj(
                                "lineId",
                                l(line, "id"),
                                "skuId",
                                l(line, "sku_id"),
                                "quantity",
                                l(line, "quantity"),
                                "unitCents",
                                l(line, "unit_cents"),
                                "principalCents",
                                l(line, "principal_cents"),
                                "productType",
                                has(line, "product_type") ? s(line, "product_type") : "regular"));
                // 福利品保留退款对照行，但不计入订货收益。
                if ("welfare".equals(s(line, "product_type"))) continue;
                sum =
                        sum.add(
                                BigInteger.valueOf(
                                                l(line, "unit_cents") - l(line, "principal_cents"))
                                        .multiply(BigInteger.valueOf(l(line, "quantity"))));
            }
            requireConflict(lines.size() > 0, "原订单缺少有效主理人价差快照");
            Input.require(
                    sum.signum() > 0 && sum.compareTo(BigInteger.valueOf(MAX_MONEY)) <= 0,
                    "无可登记的普通商品订货收益或金额超出有效范围");
            amount = sum.longValue();
            evidence =
                    obj(
                            "identity",
                            "C",
                            "principalId",
                            userId,
                            "afterSalesDays",
                            original.get("afterSalesDays"),
                            "lines",
                            lines);
        }
        long days = Input.integer(evidence, "afterSalesDays", 0, 365);
        requireConflict(
                db.one("SELECT id FROM users WHERE id=? AND is_deleted=0", userId) != null
                        && userId != l(order, "user_id"),
                "奖励对象或来源归属无效");
        requireConflict(l(order, "goods_cents") > 0, "来源货款无效");
        evidence.put("orderNumber", s(order, "number"));
        evidence.put("goodsCents", l(order, "goods_cents"));
        evidence.put("afterSalesDays", days);
        evidence.set("paymentMethod", order.get("payment_method"));
        return obj(
                "orderId",
                orderId,
                "orderNumber",
                s(order, "number"),
                "kind",
                onboarding ? "onboarding" : "margin",
                "userId",
                userId,
                "amountCents",
                amount,
                "snapshot",
                evidence);
    }

    private List<ObjectNode> debtRows(long userId) {
        List<ObjectNode> rows =
                db.list(
                        "SELECT v.*,r.user_id,r.order_id,"
                                + "COALESCE((SELECT SUM(o.amount_cents) FROM reward_offsets o WHERE o.reversal_id=v.id AND o.is_deleted=0),0) AS offset_cents,"
                                + "COALESCE((SELECT SUM(c.amount_cents) FROM reward_recoveries c WHERE c.reversal_id=v.id AND c.is_deleted=0),0) AS recovered_cents "
                                + "FROM reward_reversals v JOIN rewards r ON r.id=v.reward_id AND r.is_deleted=0 "
                                + "WHERE r.user_id=? AND v.is_deleted=0 AND v.debt_cents>0 ORDER BY v.id",
                        userId);
        for (ObjectNode row : rows)
            row.put(
                    "remainingCents",
                    l(row, "debt_cents") - l(row, "offset_cents") - l(row, "recovered_cents"));
        return rows;
    }

    /** 按既有冲回先后顺序计算累计目标，避免乱序完成退款改变历史舍入结果。 */
    private ArrayNode reversalTargets(ObjectNode reward) {
        ArrayNode targets = arr();
        JsonNode snapshot = parse(s(reward, "snapshot"));
        long goods = 0, total = 0, retailGoods = 0;
        Map<Long, Long> quantities = new HashMap<>();
        Map<Long, JsonNode> basis = new HashMap<>();
        for (JsonNode line : snapshot.path("lines")) basis.put(l(line, "lineId"), line);
        for (ObjectNode refund :
                db.list(
                        "SELECT f.id,f.goods_cents,f.lines FROM refunds f "
                                + "LEFT JOIN reward_reversals v ON v.refund_id=f.id AND v.reward_id=? AND v.is_deleted=0 "
                                + "WHERE f.order_id=? AND f.status='success' AND f.is_deleted=0 "
                                + "ORDER BY CASE WHEN v.id IS NULL THEN 1 ELSE 0 END,v.id,f.id",
                        l(reward, "id"),
                        l(reward, "order_id"))) {
            requireConflict(integral(refund.get("goods_cents"), 0, MAX_SAFE), "退款货款数据无效，需核对来源");
            goods = safeSum(goods, l(refund, "goods_cents"), "累计退款货款超出范围");
            if ("retail".equals(s(reward, "kind"))) {
                JsonNode refundLines = parse(s(refund, "lines"));
                Input.require(refundLines.isArray(), "退款行依据必须为数组");
                for (JsonNode line : refundLines) {
                    long lineId = entity(line, "lineId"),
                            quantity = Input.integer(line, "quantity", 1, MAX_SAFE);
                    JsonNode original = basis.get(lineId);
                    requireConflict(
                            original != null
                                    && l(original, "unitCents") == money(line, "unitCents", false),
                            "退款行与原零售价格依据不符");
                    long count =
                            safeSum(quantities.getOrDefault(lineId, 0L), quantity, "累计退款数量超出原订单");
                    requireConflict(count <= l(original, "quantity"), "累计退款数量超出原订单");
                    quantities.put(lineId, count);
                    if ("regular".equals(s(original, "productType")))
                        retailGoods += l(original, "unitCents") * quantity;
                }
                requireConflict(retailGoods <= l(snapshot, "commissionGoodsCents"), "零售退款基数超出原货款");
                total =
                        retailGoods == l(snapshot, "commissionGoodsCents")
                                ? l(reward, "amount_cents")
                                : (retailGoods * l(snapshot, "percent") + 50) / 100;
            } else if ("onboarding".equals(s(reward, "kind"))) {
                long originalGoods = l(snapshot, "goodsCents");
                requireConflict(originalGoods > 0 && goods <= originalGoods, "累计退款货款超过原货款");
                // 累计比例四舍五入，全退时补齐尾差；乘积使用 BigInteger 避免浮点或 long 溢出。
                total =
                        goods == originalGoods
                                ? l(reward, "amount_cents")
                                : BigInteger.valueOf(l(reward, "amount_cents"))
                                        .multiply(BigInteger.valueOf(goods))
                                        .multiply(BigInteger.valueOf(2))
                                        .add(BigInteger.valueOf(originalGoods))
                                        .divide(
                                                BigInteger.valueOf(originalGoods)
                                                        .multiply(BigInteger.valueOf(2)))
                                        .longValueExact();
            } else {
                JsonNode lines = parse(s(refund, "lines"));
                Input.require(lines.isArray(), "退款行依据必须为数组");
                for (JsonNode line : lines) {
                    long lineId = entity(line, "lineId");
                    long quantity = Input.integer(line, "quantity", 1, MAX_SAFE);
                    long unit = money(line, "unitCents", false),
                            principal = money(line, "principalCents", false);
                    JsonNode original = basis.get(lineId);
                    requireConflict(
                            original != null
                                    && l(original, "unitCents") == unit
                                    && l(original, "principalCents") == principal,
                            "退款行与原奖励价格依据不符");
                    long count =
                            safeSum(quantities.getOrDefault(lineId, 0L), quantity, "累计退款数量超过原订单");
                    requireConflict(count <= l(original, "quantity"), "累计退款数量超过原订单");
                    quantities.put(lineId, count);
                    // 以原奖励来源行的类型排除福利品；不读取当前 SKU 类型或价格。
                    if ("welfare".equals(s(original, "productType"))) continue;
                    BigInteger next =
                            BigInteger.valueOf(total)
                                    .add(
                                            BigInteger.valueOf(unit - principal)
                                                    .multiply(BigInteger.valueOf(quantity)));
                    requireConflict(
                            next.signum() >= 0
                                    && next.compareTo(BigInteger.valueOf(l(reward, "amount_cents")))
                                            <= 0,
                            "累计冲回超过原奖励");
                    total = next.longValue();
                }
            }
            requireConflict(total <= l(reward, "amount_cents"), "累计冲回超过原奖励");
            targets.add(obj("refundId", l(refund, "id"), "target", total));
        }
        return targets;
    }

    private void reconcileInside(long orderId, long actor, String requestId) {
        db.assertTransaction();
        for (ObjectNode reward :
                db.list("SELECT * FROM rewards WHERE order_id=? AND is_deleted=0", orderId)) {
            long previous = 0, reversed = l(reward, "reversed_cents");
            for (JsonNode target : reversalTargets(reward)) {
                long delta = l(target, "target") - previous;
                previous = l(target, "target");
                ObjectNode old =
                        db.one(
                                "SELECT amount_cents FROM reward_reversals WHERE reward_id=? AND refund_id=? AND is_deleted=0",
                                l(reward, "id"),
                                l(target, "refundId"));
                if (old != null) {
                    requireConflict(l(old, "amount_cents") == delta, "退款历史被修改，需人工核查冲回依据");
                    continue;
                }
                long next = reversed + delta;
                long debt =
                        Math.max(0, l(reward, "settled_cents") - (l(reward, "amount_cents") - next))
                                - Math.max(
                                        0,
                                        l(reward, "settled_cents")
                                                - (l(reward, "amount_cents") - reversed));
                long id =
                        db.insert(
                                "INSERT INTO reward_reversals(reward_id,refund_id,amount_cents,debt_cents,reason,request_id,create_user_id,update_user_id) "
                                        + "VALUES(?,?,?,?,?,?,?,?)",
                                l(reward, "id"),
                                l(target, "refundId"),
                                delta,
                                debt,
                                "来源实际退款冲回；已付部分持续待收回",
                                requestId,
                                actor,
                                actor);
                db.update(
                        "UPDATE rewards SET reversed_cents=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                        next,
                        actor,
                        l(reward, "id"));
                // 使用调用方追踪信息，确保异步退款与奖励冲回可按同一请求编号追溯。
                db.insert(
                        "INSERT INTO audit_events(actor_kind,actor_id,action,object_type,object_id,result,reason,request_id,create_user_id,update_user_id) "
                                + "VALUES(?,?,'reward.reverse','reward_reversal',?,'success',?,?,?,?)",
                        actor == 0 ? "system" : "staff",
                        actor,
                        String.valueOf(id),
                        "订单"
                                + orderId
                                + " 退款"
                                + l(target, "refundId")
                                + " 冲回"
                                + delta
                                + "分 待收回"
                                + debt
                                + "分",
                        requestId,
                        actor,
                        actor);
                reversed = next;
            }
        }
    }

    private ObjectNode eligibility(ObjectNode reward) {
        ObjectNode order = order(l(reward, "order_id"));
        JsonNode snapshot = parse(s(reward, "snapshot"));
        if (!has(order, "received_at") || s(order, "received_at").isEmpty())
            return obj("eligible", false, "reason", "未确认收货");
        Instant until;
        try {
            if (!integral(snapshot.get("afterSalesDays"), 0, 365))
                return obj("eligible", false, "reason", "收货或售后期依据无效");
            until =
                    Instant.parse(s(order, "received_at"))
                            .plusSeconds(l(snapshot, "afterSalesDays") * 86400L);
        } catch (RuntimeException error) {
            return obj("eligible", false, "reason", "收货或售后期依据无效");
        }
        if (until.isAfter(Instant.now()))
            return obj(
                    "eligible", false, "reason", "尚在原售后期内", "eligibleAt", ISO_MILLIS.format(until));
        if (db.one(
                        "SELECT id FROM refunds WHERE order_id=? AND is_deleted=0 AND status NOT IN ('success','rejected') LIMIT 1",
                        l(order, "id"))
                != null) return obj("eligible", false, "reason", "存在未完售后或售后模块尚未接入");
        if (db.one(
                        "SELECT id FROM order_lines WHERE order_id=? AND is_deleted=0 AND refund_held>0 LIMIT 1",
                        l(order, "id"))
                != null) return obj("eligible", false, "reason", "商品仍在售后处理中");
        ArrayNode targets = reversalTargets(reward);
        long target = targets.size() == 0 ? 0 : l(targets.get(targets.size() - 1), "target");
        if (target != l(reward, "reversed_cents"))
            return obj("eligible", false, "reason", "实际退款尚未完成奖励冲回核对");
        if ("onboarding".equals(s(reward, "kind"))
                && !(snapshot.path("onboardingApproved").isBoolean()
                        && snapshot.path("onboardingApproved").booleanValue()))
            return obj("eligible", false, "reason", "入驻审批依据缺失");
        return obj(
                "eligible", true, "reason", "收货及原售后期已完成", "eligibleAt", ISO_MILLIS.format(until));
    }

    private ObjectNode view(ObjectNode reward, boolean admin) {
        ObjectNode eligibility = eligibility(reward);
        JsonNode snapshot = parse(s(reward, "snapshot"));
        long remaining = remaining(reward);
        ObjectNode result =
                obj(
                        "id",
                        l(reward, "id"),
                        "orderNumber",
                        snapshot.get("orderNumber"),
                        "kind",
                        s(reward, "kind"),
                        "targetIdentity",
                        "onboarding".equals(s(reward, "kind")) ? snapshot.get("identity") : null,
                        "amountCents",
                        l(reward, "amount_cents"),
                        "reversedCents",
                        l(reward, "reversed_cents"),
                        "settledCents",
                        l(reward, "settled_cents"),
                        "remainingCents",
                        remaining,
                        "createTime",
                        reward.get("create_time"));
        result.setAll(eligibility);
        result.put(
                "status",
                remaining > 0
                        ? (b(eligibility, "eligible") ? "eligible" : "not_due")
                        : (l(reward, "reversed_cents") == l(reward, "amount_cents")
                                ? "reversed"
                                : "settled"));
        if (admin) {
            result.put("userId", l(reward, "user_id"));
            result.put("orderId", l(reward, "order_id"));
            result.set("basis", snapshot);
        }
        return result;
    }

    private ObjectNode detailInside(ObjectNode reward, boolean admin) {
        long id = l(reward, "id"), userId = l(reward, "user_id");
        ObjectNode result = view(reward, admin);
        result.set(
                "reversals",
                json(
                        db.list(
                                "SELECT id,refund_id,amount_cents,debt_cents,create_time FROM reward_reversals "
                                        + "WHERE reward_id=? AND is_deleted=0 ORDER BY id",
                                id)));
        ArrayNode settlements = arr();
        for (ObjectNode row :
                db.list(
                        "SELECT s.id,s.month,s.payee,s.status,s.gross_cents,s.offset_cents,s.net_cents,s.paid_at,s.proof,s.plan "
                                + "FROM reward_settlements s WHERE s.user_id=? AND s.is_deleted=0 ORDER BY s.id DESC",
                        userId)) {
            JsonNode plan = parse(s(row, "plan"));
            for (JsonNode entry : plan.path("rewards"))
                if (l(entry, "rewardId") == id) {
                    row.set("plan", plan);
                    settlements.add(row);
                    break;
                }
        }
        result.set("settlements", settlements);
        ArrayNode debts = arr();
        for (ObjectNode row : debtRows(userId)) if (l(row, "reward_id") == id) debts.add(row);
        result.set("debts", debts);
        result.set(
                "offsets",
                json(
                        db.list(
                                "SELECT o.id,o.reversal_id,o.settlement_id,o.reward_id,o.amount_cents,o.create_time "
                                        + "FROM reward_offsets o JOIN reward_reversals v ON v.id=o.reversal_id AND v.is_deleted=0 "
                                        + "WHERE (v.reward_id=? OR o.reward_id=?) AND o.is_deleted=0 ORDER BY o.id",
                                id,
                                id)));
        result.set(
                "recoveries",
                json(
                        db.list(
                                "SELECT c.id,c.reversal_id,c.amount_cents,c.received_at,c.proof FROM reward_recoveries c "
                                        + "JOIN reward_reversals v ON v.id=c.reversal_id AND v.is_deleted=0 WHERE v.reward_id=? AND c.is_deleted=0 ORDER BY c.id",
                                id)));
        return result;
    }

    private ObjectNode planInside(long userId, List<Long> ids) {
        List<ObjectNode> debts = debtRows(userId);
        ArrayNode rewards = arr(), offsets = arr();
        long gross = 0, offset = 0;
        List<Long> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        for (Long id : sorted) {
            ObjectNode reward = reward(id, userId), eligible = eligibility(reward);
            requireConflict(b(eligible, "eligible"), s(eligible, "reason"));
            long amount = remaining(reward), left = amount;
            requireConflict(amount > 0, "奖励已结算或已全部冲回");
            gross = safeSum(gross, amount, "结算合计超出范围");
            for (ObjectNode debt : debts) {
                long take = Math.min(l(debt, "remainingCents"), left);
                if (take > 0) {
                    offsets.add(
                            obj("reversalId", l(debt, "id"), "rewardId", id, "amountCents", take));
                    debt.put("remainingCents", l(debt, "remainingCents") - take);
                    left -= take;
                    offset += take;
                }
            }
            rewards.add(obj("rewardId", id, "amountCents", amount));
        }
        return obj(
                "rewards",
                rewards,
                "offsets",
                offsets,
                "grossCents",
                gross,
                "offsetCents",
                offset,
                "netCents",
                gross - offset);
    }

    /** 比较计划数值及数组顺序，避免 JDBC/Jackson 整数节点宽度影响幂等判断。 */
    private static boolean samePlan(JsonNode original, JsonNode fresh) {
        return stringify(original).equals(stringify(fresh));
    }

    private static long remaining(JsonNode reward) {
        return Math.max(
                0,
                l(reward, "amount_cents")
                        - l(reward, "reversed_cents")
                        - l(reward, "settled_cents"));
    }

    private static List<Long> rewardIds(JsonNode body) {
        ArrayNode values = Input.array(body, "rewardIds", 1, 100);
        Set<Long> unique = new TreeSet<>();
        for (JsonNode value : values)
            Input.require(unique.add(entity(obj("id", value), "id")), "奖励不可重复");
        return new ArrayList<>(unique);
    }

    /** 路径编号接受原接口的数值字符串，禁止非安全整数。 */
    public static long pathId(String value) {
        return entity(obj("id", value), "id");
    }

    /** 保留原 entityId 的常用数值字符串转换，同时严格限制编号范围。 */
    private static long entity(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        Input.require(
                value != null
                        && !value.isNull()
                        && (value.isNumber() || value.isTextual() || value.isBoolean()),
                field + "必须为有效编号");
        try {
            BigDecimal decimal =
                    value.isBoolean()
                            ? (value.booleanValue() ? BigDecimal.ONE : BigDecimal.ZERO)
                            : new BigDecimal(value.asText().trim());
            return Input.id(decimal.longValueExact());
        } catch (ArithmeticException | NumberFormatException error) {
            throw invalid(field + "必须为有效编号");
        }
    }

    private static long money(JsonNode body, String field, boolean positive) {
        return Input.integer(body, field, positive ? 1 : 0, MAX_MONEY);
    }

    private static String reason(JsonNode body) {
        return Input.text(body, "reason", 1, 1000);
    }

    private static String requestKey(JsonNode body) {
        String value = rawText(body, "requestKey");
        Input.require(value.matches("[A-Za-z0-9_-]{12,100}"), "请求编号格式无效");
        return value;
    }

    private static String rawText(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        Input.require(value != null && value.isTextual(), field + "必须为字符串");
        return value.textValue();
    }

    private static String nullableText(JsonNode body, String field) {
        return has(body, field) ? s(body, field) : null;
    }

    /** 只接受原接口的 UTC ISO 日期时间，真实收付款时间不得在未来；保留原文本用于幂等比较。 */
    private static String actualTime(JsonNode body, String field) {
        String value = rawText(body, field);
        Input.require(
                value.matches(
                        "\\d{4}-\\d{2}-\\d{2}T(?:[01]\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d(?:\\.\\d+)?)?Z"),
                "实际时间格式无效");
        try {
            LocalDate.parse(value.substring(0, 10));
            String normalized = value.length() == 17 ? value.substring(0, 16) + ":00Z" : value;
            // 原 ISO 输入允许任意位小数；仅解析时截到 Java 的纳秒精度，存储仍保留原始凭据文本。
            normalized = normalized.replaceFirst("(\\.\\d{9})\\d+Z$", "$1Z");
            Input.require(!Instant.parse(normalized).isAfter(Instant.now()), "实际时间不能在未来");
        } catch (java.time.DateTimeException error) {
            throw invalid("实际时间格式无效");
        }
        return value;
    }

    private static boolean integral(JsonNode value, long minimum, long maximum) {
        if (value == null || !value.isNumber()) return false;
        try {
            long number = value.decimalValue().longValueExact();
            return number >= minimum && number <= maximum;
        } catch (ArithmeticException error) {
            return false;
        }
    }

    private static long safeSum(long left, long right, String message) {
        requireConflict(left >= 0 && right >= 0 && left <= MAX_SAFE - right, message);
        return left + right;
    }

    private static void requireConflict(boolean condition, String message) {
        if (!condition) throw conflict(message);
    }
}
