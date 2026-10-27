package com.jianyanji.storage;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import com.jianyanji.membership.QualificationService;
import java.util.Arrays;
import org.springframework.web.bind.annotation.*;

/** 身份独立开通、变更和关闭；退款不调用此入口，主理人降级仍须完成团队交接。 */
@RestController
public class IdentityAdjustmentController {
    /** 事务数据访问。 */
    private final Db db;
    /** 后台权限与审计。 */
    private final Access access;

    public IdentityAdjustmentController(Db db, Access access) {
        this.db = db;
        this.access = access;
    }

    @PostMapping("/api/admin/users/{id}/identity")
    public Object adjust(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("onboarding:manage"), "id");
        Input.id(id);
        Input.strict(body, "identity", "principalId", "reason", "evidence", "requestKey");
        String target = Input.text(body, "identity", 1, 1),
                reason = Input.text(body, "reason", 1, 2000),
                evidence = Input.text(body, "evidence", 1, 2000),
                key = Input.text(body, "requestKey", 8, 191);
        Input.require(Arrays.asList("D", "C", "B").contains(target), "身份编码无效");
        return db.tx(
                () -> {
                    ObjectNode prior =
                            db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", id);
                    if (prior == null) throw notFound("用户不存在");
                    ObjectNode old =
                            db.one(
                                    "SELECT * FROM qualification_actions WHERE user_id=? AND request_key=? AND is_deleted=0",
                                    id,
                                    key);
                    if (old != null) {
                        if (!s(parse(s(old, "snapshot")), "request").equals(stringify(body)))
                            throw conflict("身份操作编号已用于其他内容");
                        return publicUser(prior);
                    }
                    long principal =
                            "B".equals(target)
                                    ? id
                                    : "C".equals(target)
                                            ? Input.integer(
                                                    body, "principalId", 1, 9007199254740991L)
                                            : 0;
                    if ("C".equals(target)) {
                        ObjectNode owner =
                                db.one(
                                        "SELECT * FROM users WHERE id=? AND is_deleted=0",
                                        principal);
                        if (owner == null
                                || !"B".equals(effectiveIdentity(owner))
                                || principal == id) throw conflict("2980店主须指定有效19800店主归属");
                    }
                    QualificationService qualifications = new QualificationService(db, access);
                    if ("B".equals(effectiveIdentity(prior)) && !"B".equals(target))
                        qualifications.assertRevocable(id);
                    db.update(
                            "UPDATE qualifications SET active=0,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE user_id=? AND active=1 AND is_deleted=0",
                            actor,
                            id);
                    if ("B".equals(effectiveIdentity(prior)) && !"B".equals(target))
                        qualifications.revoke(
                                obj(
                                        "id",
                                        has(prior, "onboarding_id") ? l(prior, "onboarding_id") : 0,
                                        "user_id",
                                        id),
                                actor,
                                reason);
                    if (!"D".equals(target))
                        db.insert(
                                "INSERT INTO qualifications(user_id,identity,source_kind,principal_id,evidence,reason,create_user_id,update_user_id) VALUES(?,?,'existing',?,?,?,?,?)",
                                id,
                                target,
                                principal,
                                evidence,
                                reason,
                                actor,
                                actor);
                    db.update(
                            "UPDATE users SET identity=?,identity_valid_until=NULL,onboarding_id=NULL,principal_id=?,relation_version=relation_version+1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            target,
                            principal == 0 ? null : principal,
                            actor,
                            id);
                    db.insert(
                            "INSERT INTO qualification_actions(user_id,request_key,snapshot,create_user_id,update_user_id) VALUES(?,?,?,?,?)",
                            id,
                            key,
                            stringify(
                                    obj(
                                            "before",
                                            publicUser(prior),
                                            "target",
                                            target,
                                            "reason",
                                            reason,
                                            "evidence",
                                            evidence,
                                            "request",
                                            stringify(body))),
                            actor,
                            actor);
                    access.audit(
                            "qualification.adjust",
                            "user",
                            id,
                            "原身份=" + s(prior, "identity") + "；目标=" + target + "；" + reason);
                    return publicUser(
                            db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", id));
                });
    }
}
