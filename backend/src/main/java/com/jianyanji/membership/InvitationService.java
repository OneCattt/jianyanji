package com.jianyanji.membership;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;

/** 分享仅提供候选；提交时由服务端校验资格及归属并锁定明确确认的关系。 */
public class InvitationService {
    /** 业务事务与有效记录查询。 */
    private final Db db;
    /** 请求审计组件。 */
    private final Access access;

    public InvitationService(Db db, Access access) {
        this.db = db;
        this.access = access;
    }

    /** 本人生成稳定随机分享码，并返回自己可见的代理关系。 */
    public ObjectNode mine(long uid) {
        return db.tx(
                () -> {
                    ObjectNode u = user(uid);
                    if (!in(effectiveIdentity(u), "B", "C"))
                        throw new BusinessException(403, "FORBIDDEN", "当前身份没有代理邀请权限");
                    if (!has(u, "share_code")) {
                        String code = id().replace("-", "");
                        db.update(
                                "UPDATE users SET share_code=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                code,
                                uid,
                                uid);
                        u.put("share_code", code);
                    }
                    return obj(
                            "shareCode",
                            s(u, "share_code"),
                            "inviterId",
                            u.get("inviter_id"),
                            "principalId",
                            u.get("principal_id"),
                            "identity",
                            effectiveIdentity(u));
                });
    }

    /** 输入分享码或候选编号，返回最小必要资料；最终提交还会重新核验。 */
    public ObjectNode candidate(String code, Long inviterId, String target, long applicant) {
        if (!in(target, "B", "C")) throw conflict("目标身份无效");
        ObjectNode inviter =
                code != null && !code.isEmpty()
                        ? db.one("SELECT * FROM users WHERE share_code=? AND is_deleted=0", code)
                        : inviterId == null ? null : user(inviterId);
        if (inviter == null) throw conflict("请通过有效代理邀请申请入驻");
        long inviterValue = l(inviter, "id");
        String identity = effectiveIdentity(inviter);
        if (inviterValue == applicant) throw conflict("不能邀请自己");
        if (!in(identity, "B", "C") || ("B".equals(target) && !"B".equals(identity)))
            throw conflict("主理人入驻须由有效主理人邀请，店主入驻须有效代理邀请");
        long principal =
                "B".equals(target)
                        ? applicant
                        : "B".equals(identity) ? inviterValue : l(inviter, "principal_id");
        if ("C".equals(target) && !"B".equals(effectiveIdentity(user(principal))))
            throw conflict("邀请人的归属主理人资格无效");
        return obj(
                "inviterId",
                inviterValue,
                "inviterName",
                s(inviter, "nickname"),
                "inviterIdentity",
                identity,
                "inviterRelationVersion",
                l(inviter, "relation_version"),
                "principalId",
                principal,
                "principalName",
                "B".equals(target) ? "本人（激活后）" : s(user(principal), "nickname"));
    }

    /** 授权关系调整，只影响后续业务；待付款来源标记为须本人重新确认。 */
    public ObjectNode adjust(JsonNode body, long actor) {
        Input.strict(body, "userId", "inviterId", "principalId", "reason");
        long uid = com.jianyanji.wallet.WalletService.entity(body, "userId"),
                inviter = com.jianyanji.wallet.WalletService.entity(body, "inviterId"),
                principal = com.jianyanji.wallet.WalletService.entity(body, "principalId");
        String reason = Input.text(body, "reason", 1, 2000);
        return db.tx(
                () -> {
                    ObjectNode u = user(uid);
                    // 与预支付创建共用业务事务锁，未决交易核清前不能使原确认失效。
                    if (db.one(
                                    "SELECT p.id FROM payment_attempts p JOIN orders o ON o.id=p.order_id AND o.is_deleted=0 WHERE p.is_deleted=0 AND p.state NOT IN ('closed','success') AND (o.user_id=? OR o.id IN (SELECT a.order_id FROM onboardings a WHERE a.inviter_id=? AND a.status='submitted' AND a.is_deleted=0)) LIMIT 1",
                                    uid,
                                    uid)
                            != null) throw conflict("存在受本次关系调整影响的未决微信交易，请先核清并关闭原交易");
                    ObjectNode rel =
                            candidate(
                                    null,
                                    inviter,
                                    "B".equals(effectiveIdentity(u)) ? "B" : "C",
                                    uid);
                    if (l(rel, "principalId") != principal) throw conflict("归属须与有效邀请关系一致");
                    db.update(
                            "UPDATE users SET inviter_id=?,principal_id=?,relation_version=relation_version+1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            inviter,
                            principal,
                            actor,
                            uid);
                    db.update(
                            "UPDATE qualifications SET inviter_id=?,principal_id=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE user_id=? AND identity=? AND active=1 AND is_deleted=0",
                            inviter,
                            principal,
                            actor,
                            uid,
                            effectiveIdentity(u));
                    db.update(
                            "UPDATE onboardings SET relation_confirmed=0,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE status='submitted' AND is_deleted=0 AND (user_id=? OR inviter_id=?)",
                            actor,
                            uid,
                            uid);
                    access.audit(
                            "relation.adjust",
                            "user",
                            uid,
                            reason
                                    + "；原邀请="
                                    + s(u, "inviter_id")
                                    + "，原归属="
                                    + s(u, "principal_id")
                                    + "；新邀请="
                                    + inviter
                                    + "，新归属="
                                    + principal);
                    return obj("ok", true, "userId", uid);
                });
    }
    /** 严格读取未删除用户。 */
    private ObjectNode user(long uid) {
        ObjectNode u = db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", uid);
        if (u == null) throw notFound("代理不存在");
        return u;
    }
    /** 校验固定枚举集合。 */
    private static boolean in(String value, String... values) {
        return java.util.Arrays.asList(values).contains(value);
    }
}
