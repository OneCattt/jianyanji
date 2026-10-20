package com.jianyanji.exports;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 资金及奖励白名单导出，沿用原查询权限和必要业务筛选，不包含凭证或完整账户资料。 */
@RestController
public class FinanceExportController {
    /** 只读业务查询及导出审计所需的数据访问。 */
    private final Db db;
    /** 原后台功能权限与操作留痕。 */
    private final Access access;

    public FinanceExportController(Db db, Access access) {
        this.db = db;
        this.access = access;
    }

    /** 每页最多200条；points须指定用户，payments须指定原订单，beforeId可逐页继续导出。 */
    @GetMapping("/api/admin/exports/finance/{kind}")
    public ResponseEntity<byte[]> export(
            @PathVariable String kind,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Long beforeId) {
        String table, columns, permission, owner = "user_id";
        switch (kind) {
            case "receipts":
                table = "company_receipts";
                permission = "finance:manage";
                columns =
                        "id,user_id,purpose,onboarding_id,order_id,amount_cents,cash_refunded_cents,cash_held_cents,received_at,confirmed_at";
                break;
            case "payments":
                table = "payments";
                permission = "orders:manage";
                owner = null;
                columns = "id,order_id,method,transaction_id,cents,paid_at";
                break;
            case "refunds":
                table = "refunds";
                permission = "orders:manage";
                owner = null;
                columns =
                        "id,order_id,number,status,goods_cents,shipping_cents,total_cents,create_time";
                break;
            case "points":
                table = "wallet_ledger";
                permission = "finance:manage";
                columns =
                        "id,user_id,receipt_id,lot_id,order_id,kind,delta_cents,reference,create_time";
                break;
            case "cash-refunds":
                table = "cash_refunds";
                permission = "finance:manage";
                columns = "id,user_id,receipt_id,lot_id,status,amount_cents,create_time";
                break;
            case "rewards":
                table = "rewards";
                permission = "rewards:manage";
                columns =
                        "id,order_id,user_id,kind,amount_cents,reversed_cents,settled_cents,create_time";
                break;
            case "settlements":
                table = "reward_settlements";
                permission = "rewards:manage";
                columns =
                        "id,user_id,month,payee,status,gross_cents,offset_cents,net_cents,paid_at";
                break;
            case "reversals":
                table = "reward_reversals";
                permission = "rewards:manage";
                owner = null;
                columns = "id,reward_id,refund_id,amount_cents,debt_cents,create_time";
                break;
            case "offsets":
                table = "reward_offsets";
                permission = "rewards:manage";
                owner = null;
                columns = "id,reversal_id,settlement_id,reward_id,amount_cents,create_time";
                break;
            case "recoveries":
                table = "reward_recoveries";
                permission = "rewards:manage";
                owner = null;
                columns = "id,reversal_id,amount_cents,received_at,create_time";
                break;
            default:
                throw BusinessException.notFound("财务导出类型不存在");
        }
        access.staff(permission);
        if (userId != null) Input.id(userId);
        if (orderId != null) Input.id(orderId);
        if (beforeId != null) Input.id(beforeId);
        Input.require(!"points".equals(kind) || userId != null, "积分导出须指定原查询用户");
        Input.require(!"payments".equals(kind) || orderId != null, "支付导出须指定原查询订单");
        Input.require(
                orderId == null
                        || Arrays.asList("payments", "refunds", "points", "rewards").contains(kind),
                "此类型不支持订单筛选");
        StringBuilder sql = new StringBuilder("SELECT ");
        String[] keys = columns.split(",");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sql.append(',');
            sql.append("t.`").append(keys[i]).append('`');
        }
        sql.append(" FROM ").append(table).append(" t WHERE t.is_deleted=0");
        List<Object> args = new ArrayList<>();
        if (userId != null) {
            if (owner != null) sql.append(" AND t.user_id=?");
            else if (Arrays.asList("payments", "refunds").contains(kind))
                sql.append(
                        " AND t.order_id IN (SELECT id FROM orders WHERE user_id=? AND is_deleted=0)");
            else if ("recoveries".equals(kind))
                sql.append(
                        " AND t.reversal_id IN (SELECT v.id FROM reward_reversals v JOIN rewards r ON r.id=v.reward_id AND r.is_deleted=0 WHERE v.is_deleted=0 AND r.user_id=?)");
            else
                sql.append(
                        " AND t.reward_id IN (SELECT id FROM rewards WHERE user_id=? AND is_deleted=0)");
            args.add(userId);
        }
        if (orderId != null) {
            sql.append(" AND t.order_id=?");
            args.add(orderId);
        }
        if (beforeId != null) {
            sql.append(" AND t.id<?");
            args.add(beforeId);
        }
        List<ObjectNode> rows =
                db.list(sql.append(" ORDER BY t.id DESC LIMIT 200").toString(), args.toArray());
        StringBuilder csv = new StringBuilder("\uFEFF");
        append(csv, Arrays.asList(keys));
        for (ObjectNode row : rows) {
            List<String> values = new ArrayList<>();
            for (String key : keys) values.add(s(row, key));
            append(csv, values);
        }
        access.audit(
                "finance.export",
                "export",
                kind,
                "按原查询权限导出；金额单位分；userId="
                        + userId
                        + " orderId="
                        + orderId
                        + " beforeId="
                        + beforeId
                        + " rows="
                        + rows.size());
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"jianyanji-finance-" + kind + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 引号和换行按CSV转义；公式前缀转为纯文本，避免表格执行业务文本。 */
    private static void append(StringBuilder csv, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) csv.append(',');
            String value = values.get(i);
            if (value.matches("(?s)^\\s*[=+@-].*")) value = "'" + value;
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
