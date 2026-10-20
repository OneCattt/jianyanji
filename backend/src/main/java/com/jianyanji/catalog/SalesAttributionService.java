package com.jianyanji.catalog;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Db;
import com.jianyanji.common.Input;
import org.springframework.stereotype.Service;

/** 销售分享只提供候选；每笔游客零售订单经本人确认后冻结身份和佣金比例。 */
@Service
public class SalesAttributionService {
    /** 实际代理身份及随机分享码数据源。 */
    private final Db db;

    public SalesAttributionService(Db db) {
        this.db = db;
    }

    /** 仅公开有效分享代理的必要展示信息，不读取入驻邀请关系。 */
    public ObjectNode candidate(String code) {
        Input.require(code != null && code.matches("[A-Za-z0-9_-]{12,64}"), "销售分享码无效");
        ObjectNode agent = db.one("SELECT * FROM users WHERE share_code=? AND is_deleted=0", code);
        if (agent == null) throw conflict("销售代理不存在或分享已失效");
        String identity = effectiveIdentity(agent);
        if (!"B".equals(identity) && !"C".equals(identity)) throw conflict("销售代理资格已失效");
        return obj(
                "id",
                l(agent, "id"),
                "name",
                s(agent, "nickname"),
                "identity",
                identity,
                "percent",
                "B".equals(identity) ? 65 : 30,
                "shareCode",
                code);
    }
}
