package com.jianyanji.membership;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;

/** 各次资格来源、首批核验、退款恢复与品牌指定团队交接，所有修改处于同一本地事务。 */
public class QualificationService {
    /** 数据与事务访问器。 */
    private final Db db;
    /** 操作人及审计上下文。 */
    private final Access access;

    public QualificationService(Db db, Access access) {
        this.db = db;
        this.access = access;
    }

    /** 查询本人全部资格，不暴露其他人的核款与私人资料。 */
    public ArrayNode list(long userId) {
        return json(db.list(
                        "SELECT id,identity,source_kind,onboarding_id,active,valid_until,create_time FROM qualifications WHERE user_id=? AND is_deleted=0 ORDER BY id DESC",
                        userId))
                .deepCopy();
    }

    /** 首批存量核验入口；新购包必须走真实收款激活，不得以核验替代支付。 */
    public ObjectNode bootstrap(JsonNode body, long actor) {
        Input.strict(body, "userId", "identity", "principalId", "evidence", "reason");
        long userId = com.jianyanji.wallet.WalletService.entity(body, "userId");
        String identity = WalletChoice(body, "identity", "B", "C");
        String evidence = Input.text(body, "evidence", 1, 2000),
                reason = Input.text(body, "reason", 1, 2000);
        return db.tx(
                () -> {
                    ObjectNode user = user(userId);
                    if (!"D".equals(effectiveIdentity(user))
                            && !identity.equals(effectiveIdentity(user)))
                        throw conflict("已有其他有效代理资格，不得通过首批入口变更身份");
                    long principal =
                            "B".equals(identity)
                                    ? userId
                                    : com.jianyanji.wallet.WalletService.entity(
                                            body, "principalId");
                    if ("C".equals(identity)) requirePrincipal(principal);
                    if (db.one(
                                    "SELECT id FROM qualifications WHERE user_id=? AND active=1 AND is_deleted=0 LIMIT 1",
                                    userId)
                            != null) throw conflict("该用户已有有效资格来源，请先核对");
                    db.insert(
                            "INSERT INTO qualifications(user_id,identity,source_kind,principal_id,evidence,reason,create_user_id,update_user_id) VALUES(?,?,'existing',?,?,?,?,?)",
                            userId,
                            identity,
                            principal,
                            evidence,
                            reason,
                            actor,
                            actor);
                    db.update(
                            "UPDATE users SET identity=?,identity_valid_until=NULL,principal_id=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            identity,
                            principal,
                            actor,
                            userId);
                    access.audit(
                            "qualification.bootstrap", "user", userId, reason + "；依据：" + evidence);
                    return user(userId);
                });
    }

    /** 付款成功后登记该次真实产品包资格；同一申请重复到账仅复用原资格。 */
    public void activate(ObjectNode a, long actor) {
        db.assertTransaction();
        long uid = l(a, "user_id"), aid = l(a, "id");
        if (db.one("SELECT id FROM qualifications WHERE onboarding_id=? AND is_deleted=0", aid)
                == null)
            db.insert(
                    "INSERT INTO qualifications(user_id,identity,source_kind,onboarding_id,inviter_id,principal_id,evidence,reason,create_user_id,update_user_id) VALUES(?,?,'package',?,?,?,?,?,?,?)",
                    uid,
                    s(a, "target_identity"),
                    aid,
                    nullable(a, "inviter_id"),
                    nullable(a, "principal_id"),
                    "真实收款交付订单：" + l(a, "order_id"),
                    "到账后激活",
                    actor,
                    actor);
        db.update(
                "UPDATE users SET identity=?,identity_valid_until=NULL,inviter_id=?,principal_id=?,onboarding_id=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                s(a, "target_identity"),
                a.get("inviter_id") == null
                        ? null
                        : a.get("inviter_id").isNull() ? null : a.get("inviter_id").asLong(),
                a.get("principal_id") == null
                        ? null
                        : a.get("principal_id").isNull() ? null : a.get("principal_id").asLong(),
                aid,
                actor,
                uid);
    }

    /** 品牌方预先指定接管人；实际退款撤销资格时才执行团队交接。 */
    public ObjectNode prepareHandover(JsonNode body, long actor) {
        Input.strict(body, "userId", "principalId", "reason");
        long uid = com.jianyanji.wallet.WalletService.entity(body, "userId"),
                principal = com.jianyanji.wallet.WalletService.entity(body, "principalId");
        String reason = Input.text(body, "reason", 1, 2000);
        return db.tx(
                () -> {
                    if (uid == principal) throw conflict("接管人不能为原主理人");
                    if (!"B".equals(effectiveIdentity(user(uid)))) throw conflict("原对象不是有效主理人");
                    requirePrincipal(principal);
                    db.update(
                            "UPDATE team_handovers SET state='superseded',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE user_id=? AND state='prepared' AND is_deleted=0",
                            actor,
                            uid);
                    db.insert(
                            "INSERT INTO team_handovers(user_id,principal_id,reason,create_user_id,update_user_id) VALUES(?,?,?,?,?)",
                            uid,
                            principal,
                            reason,
                            actor,
                            actor);
                    access.audit("team.handover.prepare", "user", uid, reason + "；接管：" + principal);
                    return obj("userId", uid, "principalId", principal, "state", "prepared");
                });
    }

    /** 在外部退款前校验可执行的交接安排，避免实际资金已退而资格无法处理。 */
    public void assertRevocable(long userId) {
        if (db.one(
                        "SELECT id FROM users WHERE principal_id=? AND identity='C' AND is_deleted=0 LIMIT 1",
                        userId)
                == null) return;
        ObjectNode plan =
                db.one(
                        "SELECT * FROM team_handovers WHERE user_id=? AND state='prepared' AND is_deleted=0 ORDER BY id DESC LIMIT 1",
                        userId);
        if (plan == null) throw conflict("撤销主理人前须由品牌方指定有效接管主理人");
        requirePrincipal(l(plan, "principal_id"));
    }

    /** 仅本次资格撤销确实使用户失去主理人身份时，要求团队接管。 */
    public void assertSourceRevocable(ObjectNode source) {
        if (!"B".equals(s(source, "target_identity"))) return;
        long uid = l(source, "user_id");
        if (!"B".equals(effectiveIdentity(user(uid)))) return;
        if (db.one(
                        "SELECT id FROM qualifications WHERE user_id=? AND identity='B' AND active=1 AND (onboarding_id IS NULL OR onboarding_id<>?) AND (valid_until IS NULL OR valid_until>?) AND is_deleted=0 LIMIT 1",
                        uid,
                        l(source, "id"),
                        now())
                != null) return;
        assertRevocable(uid);
    }

    /** 只撤销本次取得的资格；恢复有效旧店主时沿用其原关系，历史交易从不改写。 */
    public void revoke(ObjectNode a, long actor, String reason) {
        db.assertTransaction();
        long uid = l(a, "user_id");
        ObjectNode prior = user(uid);
        db.update(
                "UPDATE qualifications SET active=0,reason=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE onboarding_id=? AND is_deleted=0",
                reason,
                actor,
                l(a, "id"));
        ObjectNode next =
                db.one(
                        "SELECT * FROM qualifications WHERE user_id=? AND active=1 AND (valid_until IS NULL OR valid_until>?) AND is_deleted=0 ORDER BY identity ASC,id DESC LIMIT 1",
                        uid,
                        now());
        String restored = next == null ? "D" : s(next, "identity");
        if ("B".equals(effectiveIdentity(prior)) && !"B".equals(restored)) {
            assertRevocable(uid);
            ObjectNode plan =
                    db.one(
                            "SELECT * FROM team_handovers WHERE user_id=? AND state='prepared' AND is_deleted=0 ORDER BY id DESC LIMIT 1",
                            uid);
            if (plan != null) {
                long target = l(plan, "principal_id");
                // 同时更新仍有效的店主资格，以便以后身份恢复时继续使用新归属。
                db.update(
                        "UPDATE qualifications q JOIN users u ON u.id=q.user_id AND u.is_deleted=0 SET q.principal_id=?,q.update_user_id=?,q.update_time=CURRENT_TIMESTAMP(3) WHERE u.principal_id=? AND u.identity='C' AND q.identity='C' AND q.active=1 AND q.is_deleted=0",
                        target,
                        actor,
                        uid);
                db.update(
                        "UPDATE users SET principal_id=?,relation_version=relation_version+1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE principal_id=? AND identity='C' AND is_deleted=0",
                        target,
                        actor,
                        uid);
                db.update(
                        "UPDATE team_handovers SET state='applied',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                        actor,
                        l(plan, "id"));
                access.auditSystem(
                        "staff",
                        actor,
                        "team.handover.apply",
                        "user",
                        uid,
                        reason + "；原主理人：" + uid + "；新主理人：" + target,
                        "success",
                        "qualification:" + l(a, "id"));
            }
        }
        if (has(prior, "onboarding_id") && l(prior, "onboarding_id") != l(a, "id")) return;
        db.update(
                "UPDATE users SET identity=?,identity_valid_until=?,onboarding_id=?,inviter_id=?,principal_id=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                restored,
                next == null ? null : nullable(next, "valid_until"),
                next == null ? null : nullable(next, "onboarding_id"),
                next == null ? null : nullable(next, "inviter_id"),
                next == null ? null : nullable(next, "principal_id"),
                actor,
                uid);
    }
    /** 读取有效用户行。 */
    private ObjectNode user(long id) {
        ObjectNode u = db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", id);
        if (u == null) throw notFound("用户不存在");
        return u;
    }
    /** 接管和店主归属均要求有效主理人。 */
    private void requirePrincipal(long id) {
        if (!"B".equals(effectiveIdentity(user(id)))) throw conflict("归属／接管对象必须是有效主理人");
    }
    /** 空数据库字段转换为可绑定参数，避免直接传入JsonNode。 */
    private static Object nullable(JsonNode n, String key) {
        return !has(n, key)
                ? null
                : n.get(key).isNumber() ? n.get(key).asLong() : n.get(key).asText();
    }
    /** 复用既有严格枚举校验。 */
    private static String WalletChoice(JsonNode b, String key, String... allowed) {
        return com.jianyanji.wallet.WalletService.choice(b, key, allowed);
    }
}
