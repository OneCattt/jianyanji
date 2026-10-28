package com.jianyanji.identity;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Db;
import java.util.*;

/** 后台用户名称只读批量查询；已删除用户不再返回昵称，历史编号仍由调用方保留。 */
public final class UserNames {
    /** 只读数据访问。 */
    private final Db db;

    public UserNames(Db db) { this.db = db; }

    /** 一批业务记录仅查询一次；只返回用户编号及昵称，不包含联系方式或资金信息。 */
    public Map<Long, String> find(Collection<Long> ids) {
        Set<Long> unique = new LinkedHashSet<>(ids);
        unique.remove(null);
        unique.remove(0L);
        Map<Long, String> names = new HashMap<>();
        if (unique.isEmpty()) return names;
        String slots = String.join(",", Collections.nCopies(unique.size(), "?"));
        for (ObjectNode row : db.list("SELECT id,nickname FROM users WHERE is_deleted=0 AND id IN (" + slots + ")", unique.toArray()))
            names.put(l(row, "id"), s(row, "nickname"));
        return names;
    }
}
