package com.jianyanji.membership;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import org.springframework.web.bind.annotation.*;

/** 主理人团队查询只选择允许的字段，不通过订单完整视图间接泄露收货与资金资料。 */
@RestController
public class TeamController {
    /** 数据访问器。 */
    private final Db db;
    /** 当前请求身份。 */
    private final Access access;

    public TeamController(Db db, Access access) {
        this.db = db;
        this.access = access;
    }
    /** 本人主理人身份与有效期独立校验。 */
    private long principal() {
        ObjectNode u = access.user();
        if (!"B".equals(effectiveIdentity(u)))
            throw new BusinessException(403, "FORBIDDEN", "仅有效主理人可查看本人团队");
        return l(u, "id");
    }

    @GetMapping("/api/team")
    public Object list() {
        long uid = principal();
        return obj(
                "stores",
                db.list(
                        "SELECT id,nickname,identity FROM users WHERE principal_id=? AND identity='C' AND (identity_valid_until IS NULL OR identity_valid_until>?) AND is_deleted=0 ORDER BY id DESC LIMIT 200",
                        uid,
                        now()),
                "principals",
                db.list(
                        "SELECT a.id AS application_id,u.id,u.nickname,a.status FROM onboardings a JOIN users u ON u.id=a.user_id AND u.is_deleted=0 WHERE a.inviter_id=? AND a.target_identity='B' AND a.is_deleted=0 ORDER BY a.id DESC LIMIT 200",
                        uid));
    }

    @GetMapping("/api/team/orders")
    public Object orders() {
        long uid = principal();
        return db.list(
                "SELECT o.id,o.number,o.goods_cents,o.status,u.nickname AS store_name,JSON_EXTRACT(o.snapshot,'$.lines') AS `lines`,COALESCE(r.amount_cents,0) AS reward_cents FROM orders o JOIN users u ON u.id=o.user_id AND u.is_deleted=0 LEFT JOIN (SELECT order_id,SUM(amount_cents-reversed_cents) AS amount_cents FROM rewards WHERE user_id=? AND kind='margin' AND is_deleted=0 GROUP BY order_id) r ON r.order_id=o.id WHERE o.kind='goods' AND o.payment_state='paid' AND JSON_UNQUOTE(JSON_EXTRACT(o.snapshot,'$.identity'))='C' AND JSON_UNQUOTE(JSON_EXTRACT(o.snapshot,'$.principalId'))=? AND o.is_deleted=0 ORDER BY o.id DESC LIMIT 100",
                uid,
                String.valueOf(uid));
    }
}
