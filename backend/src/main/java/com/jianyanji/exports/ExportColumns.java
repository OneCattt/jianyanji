package com.jianyanji.exports;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Db;
import com.jianyanji.identity.UserNames;
import java.util.*;

/** 导出字段使用中文表头，用户编号相邻补充昵称；数值及金额单位保留原导出契约。 */
public final class ExportColumns {
    /** 原始列名与中文含义，金额类CSV统一保留分单位。 */
    private static final Map<String, String> LABELS = new HashMap<>();
    /** 用户关系编号与名称列映射，不把订单、收款等业务编号当作用户。 */
    private static final Map<String, String> USER_KEYS = new LinkedHashMap<>();
    static {
        USER_KEYS.put("user_id", "user_name");
        USER_KEYS.put("principal_id", "principal_name");
        String[][] entries = {
            {"id","记录编号"},{"user_id","用户编号"},{"user_name","用户名称"},{"nickname","用户名称"},
            {"principal_id","归属19800店主编号"},{"principal_name","归属19800店主名称"},
            {"identity","身份编码"},{"source_kind","资格来源"},{"onboarding_id","入驻申请编号"},{"active","是否有效"},
            {"create_time","创建时间"},{"number","业务单号"},{"kind","业务类型"},{"status","处理状态"},{"reason","处理原因"},
            {"purpose","收款用途"},{"order_id","原订单编号"},{"payment_state","支付状态"},{"identity_decision","身份处理方式"},
            {"goods_cents","商品金额（分）"},{"shipping_cents","运费金额（分）"},{"total_cents","合计金额（分）"},
            {"amount_cents","金额（分）"},{"cash_refunded_cents","已退现金（分）"},{"cash_held_cents","待退占用现金（分）"},
            {"received_at","实际到账时间"},{"confirmed_at","核款确认时间"},{"method","支付方式"},{"transaction_id","支付流水号"},
            {"cents","支付金额（分）"},{"paid_at","实际付款时间"},{"receipt_id","原收款编号"},{"lot_id","积分来源批次编号"},
            {"delta_cents","积分变动（百分之一积分）"},{"reference","业务依据"},{"reversed_cents","已冲回金额（分）"},{"settled_cents","已结算金额（分）"},
            {"month","结算月份"},{"payee","实际收款人"},{"gross_cents","应结算金额（分）"},{"offset_cents","抵扣金额（分）"},{"net_cents","实付金额（分）"},
            {"reward_id","原奖励编号"},{"refund_id","退款编号"},{"debt_cents","待追回金额（分）"},{"reversal_id","奖励冲回编号"},{"settlement_id","结算单编号"}
        };
        for (String[] entry : entries) LABELS.put(entry[0], entry[1]);
    }
    private ExportColumns() {}

    /** 只在用户编号旁加名称列；已有昵称列时不重复。 */
    public static String[] keys(String[] original) {
        List<String> result = new ArrayList<>();
        for (String key : original) {
            result.add(key);
            if (USER_KEYS.containsKey(key) && !("user_id".equals(key) && Arrays.asList(original).contains("nickname"))) result.add(USER_KEYS.get(key));
        }
        return result.toArray(new String[0]);
    }

    /** 对每批导出记录一次读取所有涉及用户，跨分页导出仍覆盖完整范围。 */
    public static void names(Db db, List<ObjectNode> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ObjectNode row : rows) for (String key : USER_KEYS.keySet()) if (l(row, key) > 0) ids.add(l(row, key));
        Map<Long, String> names = new UserNames(db).find(ids);
        for (ObjectNode row : rows) USER_KEYS.forEach((key, column) -> {
            long id = l(row, key);
            String name = names.get(id);
            row.put(column, id <= 0 ? "—" : name == null ? "资料不可用" : name.trim().isEmpty() ? "未填写昵称" : name);
        });
    }

    /** 表头必须有明确中文含义，防止新增字段悄然导出技术列名。 */
    public static String label(String key) {
        String value = LABELS.get(key);
        if (value == null) throw new IllegalArgumentException("导出字段缺少中文表头：" + key);
        return value;
    }
}
