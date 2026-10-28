package com.jianyanji.storage;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import com.jianyanji.exports.ExportColumns;
import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 寄存对账Excel导出，分批覆盖全部筛选结果，不受前端已加载页和200条限制。 */
@RestController
public class StorageExportController {
    /** 权限及审计。 */
    private final Access access;
    /** 持久化查询。 */
    private final Db db;

    public StorageExportController(Db db, Access access) {
        this.db = db;
        this.access = access;
    }

    @GetMapping("/api/admin/exports/storage/{kind}")
    public ResponseEntity<StreamingResponseBody> export(
            @PathVariable String kind, @RequestParam Map<String, String> query) {
        access.staffAny("orders:manage", "finance:manage");
        Input.require(Arrays.asList("lots", "pickups", "consumption").contains(kind), "导出类型无效");
        access.audit("storage.export", "export", kind, "按完整筛选范围导出寄存对账Excel");
        Map<String, String> filters = new HashMap<>(query);
        filters.put("pageSize", "100");
        StreamingResponseBody output =
                stream -> {
                    SXSSFWorkbook workbook = new SXSSFWorkbook(100);
                    try {
                        Sheet sheet = workbook.createSheet("寄存对账");
                        String[] keys, labels;
                        if ("lots".equals(kind)) {
                            keys =
                                    new String[] {
                                        "user_id",
                                        "order_number",
                                        "name",
                                        "sku_name",
                                        "is_gift",
                                        "purchased_qty",
                                        "total_qty",
                                        "total_shipped_qty",
                                        "remaining_qty",
                                        "held_qty",
                                        "refunded_qty",
                                        "starts_on",
                                        "expires_on"
                                    };
                            labels =
                                    new String[] {
                                        "用户ID", "原订单号", "商品", "规格", "赠品标记", "购买总量", "累计寄存", "累计已发货", "剩余寄存",
                                        "申请占用", "已退寄存", "寄存日期", "到期日期"
                                    };
                        } else if ("pickups".equals(kind)) {
                            keys =
                                    new String[] {
                                        "number",
                                        "user_id",
                                        "status",
                                        "stored_goods_cents",
                                        "fee_cents",
                                        "fee_refunded",
                                        "carrier",
                                        "tracking",
                                        "create_time",
                                        "received_at"
                                    };
                            labels =
                                    new String[] {
                                        "提货单号",
                                        "用户ID",
                                        "状态",
                                        "原成交货款（元）",
                                        "运费（积分）",
                                        "运费已退",
                                        "快递",
                                        "单号",
                                        "申请时间",
                                        "收货时间"
                                    };
                        } else {
                            keys =
                                    new String[] {
                                        "id",
                                        "user_id",
                                        "target_identity",
                                        "total_qty",
                                        "consumed_qty",
                                        "consumption_confirmed",
                                        "consumption_note"
                                    };
                            labels =
                                    new String[] {
                                        "入驻记录ID", "用户ID", "档位编码", "总数量", "实际消耗", "人工确认", "确认依据"
                                    };
                        }
                        List<String> translated = new ArrayList<>();
                        for (int c = 0; c < keys.length; c++) {
                            translated.add(labels[c]);
                            if ("user_id".equals(keys[c])) translated.add("用户名称");
                        }
                        keys = ExportColumns.keys(keys);
                        labels = translated.toArray(new String[0]);
                        Row header = sheet.createRow(0);
                        for (int c = 0; c < labels.length; c++)
                            header.createCell(c).setCellValue(labels[c]);
                        int row = 1;
                        for (int page = 1; ; page++) {
                            filters.put("page", String.valueOf(page));
                            ObjectNode result;
                            if ("consumption".equals(kind))
                                result =
                                        new PageQuery(
                                                        filters,
                                                        "FROM onboardings a JOIN orders o ON o.id=a.order_id AND o.is_deleted=0 WHERE a.is_deleted=0 AND o.payment_state IN ('paid','partially_refunded','refunded')")
                                                .eq("userId", "a.user_id")
                                                .select(
                                                        db,
                                                        "a.*,(SELECT SUM(quantity) FROM order_lines WHERE order_id=a.order_id AND is_deleted=0) AS total_qty,(SELECT SUM(LEAST(received_qty,GREATEST(0,shipped_qty-refunded_qty+refunded_unshipped))) FROM order_lines WHERE order_id=a.order_id AND is_deleted=0) AS consumed_qty",
                                                        "a.id DESC",
                                                        r -> r);
                            else
                                result =
                                        "lots".equals(kind)
                                                ? new StorageService(db, access).lots(null, filters)
                                                : new StorageService(db, access)
                                                        .pickups(null, filters);
                            List<ObjectNode> batch = new ArrayList<>();
                            for (JsonNode item : result.path("items")) batch.add((ObjectNode) item);
                            ExportColumns.names(db, batch);
                            for (JsonNode item : batch) {
                                if (row == 1048576) {
                                    sheet =
                                            workbook.createSheet(
                                                    "寄存对账" + workbook.getNumberOfSheets());
                                    row = 0;
                                }
                                Row data = sheet.createRow(row++);
                                for (int c = 0; c < keys.length; c++){
                                    JsonNode value=item.get(keys[c]);
                                    boolean identifier=keys[c].equals("id")||keys[c].endsWith("_id");
                                    if(value!=null&&value.isNumber()&&!identifier){
                                        boolean money=keys[c].endsWith("_cents");
                                        org.apache.poi.ss.usermodel.Cell cell=data.createCell(c,CellType.NUMERIC);
                                        cell.setCellValue(money?value.asDouble()/100.0:value.asDouble());
                                    }else data.createCell(c,CellType.STRING).setCellValue(s(item,keys[c]));
                                }

                            }
                            if ((long) page * 100 >= l(result, "total")) break;
                        }
                        workbook.write(stream);
                    } finally {
                        workbook.close();
                        workbook.dispose();
                    }
                };
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"jianyanji-storage-" + kind + ".xlsx\"")
                .contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(output);
    }
}
