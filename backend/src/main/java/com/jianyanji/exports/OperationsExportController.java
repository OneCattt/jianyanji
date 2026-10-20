package com.jianyanji.exports;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 运营导出独立执行与业务查询一致的权限及字段白名单，不导出私人资金和凭证。 */
@RestController
public class OperationsExportController {
    /** 数据查询组件。 */
    private final Db db;
    /** 后台权限及操作审计。 */
    private final Access access;

    public OperationsExportController(Db db, Access access) {
        this.db = db;
        this.access = access;
    }

    @GetMapping("/api/admin/exports/operations/{kind}")
    public ResponseEntity<byte[]> export(@PathVariable String kind) {
        String sql, permission;
        String[] keys;
        switch (kind) {
            case "qualifications":
                permission = "onboarding:manage";
                sql =
                        "SELECT q.id,q.user_id,u.nickname,q.identity,q.source_kind,q.onboarding_id,q.active,q.principal_id,q.create_time FROM qualifications q JOIN users u ON u.id=q.user_id AND u.is_deleted=0 WHERE q.is_deleted=0 ORDER BY q.id DESC LIMIT 200";
                keys =
                        new String[] {
                            "id",
                            "user_id",
                            "nickname",
                            "identity",
                            "source_kind",
                            "onboarding_id",
                            "active",
                            "principal_id",
                            "create_time"
                        };
                break;
            case "orders":
                permission = "orders:manage";
                sql =
                        "SELECT id,number,user_id,kind,status,goods_cents,shipping_cents,payment_state FROM orders WHERE is_deleted=0 ORDER BY id DESC LIMIT 200";
                keys =
                        new String[] {
                            "id",
                            "number",
                            "user_id",
                            "kind",
                            "status",
                            "goods_cents",
                            "shipping_cents",
                            "payment_state"
                        };
                break;
            case "aftersales":
                permission = "orders:manage";
                sql =
                        "SELECT id,order_id,status,reason,total_cents,identity_decision FROM refunds WHERE is_deleted=0 ORDER BY id DESC LIMIT 200";
                keys =
                        new String[] {
                            "id", "order_id", "status", "reason", "total_cents", "identity_decision"
                        };
                break;
            default:
                throw BusinessException.notFound("导出类型不存在");
        }
        access.staff(permission);
        List<ObjectNode> rows = db.list(sql);
        StringBuilder csv = new StringBuilder("\uFEFF");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) csv.append(',');
            csv.append(cell(keys[i]));
        }
        csv.append("\r\n");
        for (ObjectNode row : rows) {
            for (int i = 0; i < keys.length; i++) {
                if (i > 0) csv.append(',');
                csv.append(cell(s(row, keys[i])));
            }
            csv.append("\r\n");
        }
        access.audit(
                "operations.export", "export", kind, "按查询权限导出最近200条以内记录，金额单位分；行数=" + rows.size());
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"jianyanji-" + kind + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }
    /** CSV保留换行引号；对表格公式前缀转为文本，避免打开后执行不可信内容。 */
    private static String cell(String value) {
        if (value.matches("(?s)^[\\s]*[=+@-].*")) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
