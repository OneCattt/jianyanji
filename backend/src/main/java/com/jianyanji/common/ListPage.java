package com.jianyanji.common;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 小程序列表分页：默认二十条，多查一条判定下一页，不额外执行总数查询。 */
public final class ListPage {
    private ListPage() {}

    /** 校验页码及页大小；页码从一开始，每页一至二十条。 */
    public static int offset(int page, int limit) {
        Input.require(page >= 1 && limit >= 1 && limit <= 20, "页码须大于零，每页数量须为1至20");
        long offset = (long) (page - 1) * limit;
        Input.require(offset <= Integer.MAX_VALUE, "页码超出范围");
        return (int) offset;
    }

    /** rows 最多为 limit+1 条；响应 items 最多 limit 条，末页 nextPage 为 null。 */
    public static ObjectNode result(List<ObjectNode> rows, int page, int limit) {
        offset(page, limit);
        boolean more = rows.size() > limit;
        return obj("items", rows.subList(0, Math.min(rows.size(), limit)),
                "hasMore", more, "nextPage", more ? page + 1 : null);
    }
}
