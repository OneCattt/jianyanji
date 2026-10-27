package com.jianyanji.storage;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 本人寄存及后台管理入口；身份只取已认证会话，后台写入逐项校验权限。 */
@RestController
public class StorageController {
    /** 当前会话、权限及审计。 */
    private final Access access;
    /** 持久化与事务。 */
    private final Db db;
    /** 寄存业务，复用同一数据库事务。 */
    private final StorageService storage;

    public StorageController(Db db, Access access) {
        this.db = db;
        this.access = access;
        this.storage = new StorageService(db, access);
    }

    private Map<String, String> page(Map<String, String> q) {
        Map<String, String> v = new HashMap<>(q);
        if (v.containsKey("limit")) v.put("pageSize", v.get("limit"));
        return v;
    }

    /** 小程序分页约定：明确下一页和是否还有数据，不能只返回后台分页字段。 */
    private ObjectNode mini(ObjectNode result) {
        long page = l(result, "page"), size = l(result, "pageSize");
        boolean more = page * size < l(result, "total");
        result.put("hasMore", more);
        result.set("nextPage", more ? json(page + 1) : json(null));
        return result;
    }

    @GetMapping("/api/mini/storage")
    public Object mine(@RequestParam Map<String, String> q) {
        return mini(storage.lots(l(access.user(), "id"), page(q)));
    }

    @GetMapping("/api/mini/pickups")
    public Object pickups(@RequestParam Map<String, String> q) {
        return mini(storage.pickups(l(access.user(), "id"), page(q)));
    }

    @GetMapping("/api/pickups/{id}")
    public Object pickup(@PathVariable long id) {
        return storage.view(storage.owned(Input.id(id), l(access.user(), "id")));
    }

    @PostMapping("/api/pickups/quote")
    public Object quote(@RequestBody JsonNode body) {
        return storage.quote(body, access.user(), false);
    }

    @PostMapping("/api/pickups")
    public Object submit(@RequestBody JsonNode body) {
        ObjectNode user = access.user();
        return storage.submit(body, user, false, l(user, "id"));
    }

    @PostMapping("/api/pickups/{id}/cancel")
    public Object cancel(@PathVariable long id) {
        long uid = l(access.user(), "id");
        return storage.cancel(Input.id(id), uid, uid, "用户取消未发货提货申请", false);
    }

    @PostMapping("/api/pickups/{id}/receive")
    public Object receive(@PathVariable long id) {
        return storage.receive(Input.id(id), l(access.user(), "id"));
    }

    @GetMapping("/api/admin/storage")
    public Object admin(@RequestParam Map<String, String> q) {
        access.staffAny("orders:manage", "finance:manage");
        return storage.lots(null, page(q));
    }

    @GetMapping("/api/admin/pickups")
    public Object adminPickups(@RequestParam Map<String, String> q) {
        access.staffAny("orders:manage", "finance:manage");
        return storage.pickups(null, page(q));
    }

    @GetMapping("/api/admin/pickups/{id}")
    public Object adminPickup(@PathVariable long id) {
        access.staffAny("orders:manage", "finance:manage");
        return storage.view(storage.owned(Input.id(id), null));
    }

    @PostMapping("/api/admin/pickups")
    public Object manual(@RequestBody JsonNode body) {
        long actor = l(access.staff("orders:manage"), "id");
        long uid = Input.integer(body, "userId", 1, 9007199254740991L);
        ObjectNode user = db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", uid);
        if (user == null) throw BusinessException.notFound("用户不存在");
        return storage.submit(body, user, true, actor);
    }

    @PostMapping("/api/admin/pickups/{id}/review")
    public Object review(@PathVariable long id, @RequestBody JsonNode body) {
        return storage.review(Input.id(id), body, l(access.staff("orders:manage"), "id"));
    }

    @PostMapping("/api/admin/pickups/{id}/ship")
    public Object ship(@PathVariable long id, @RequestBody JsonNode body) {
        return storage.ship(Input.id(id), body, l(access.staff("orders:manage"), "id"));
    }

    /** 已发货退货的运费由财务独立审核退回，和商品货款退款分账，幂等退原积分批次。 */
    @PostMapping("/api/admin/pickups/{id}/refund-fee")
    public Object refundFee(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("finance:manage"), "id");
        Input.strict(body, "reason");
        String reason = Input.text(body, "reason", 1, 2000);
        return db.tx(
                () -> {
                    ObjectNode pickup = storage.owned(Input.id(id), null);
                    Input.require(
                            Arrays.asList("shipped", "received").contains(s(pickup, "status")),
                            "未发货运费请通过取消提货退回");
                    new com.jianyanji.wallet.PointsService(db).refundFee(pickup, actor);
                    access.audit("pickup.refund-fee", "pickup", id, reason);
                    return storage.view(storage.owned(id, null));
                });
    }

    @PostMapping("/api/admin/pickups/{id}/cancel")
    public Object cancelAdmin(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("orders:manage"), "id");
        Input.strict(body, "reason");
        return storage.cancel(
                Input.id(id), null, actor, Input.text(body, "reason", 1, 2000), false);
    }

    @PostMapping("/api/admin/storage/{id}/extend")
    public Object extend(@PathVariable long id, @RequestBody JsonNode body) {
        return storage.extend(Input.id(id), body, l(access.staff("orders:manage"), "id"));
    }

    @PostMapping("/api/admin/order-lines/{id}/storage")
    public Object convert(@PathVariable long id, @RequestBody JsonNode body) {
        return storage.importLine(Input.id(id), body, l(access.staff("orders:manage"), "id"));
    }

    @GetMapping("/api/mini/storage-messages")
    public Object messages(@RequestParam Map<String, String> q) {
        long uid = l(access.user(), "id");
        Map<String, String> values = page(q);
        values.put("userId", String.valueOf(uid));
        return new PageQuery(values, "FROM storage_messages WHERE is_deleted=0")
                .eq("userId", "user_id")
                .select(db, "id,title,body,create_time", "id DESC", r -> r);
    }

    @GetMapping("/api/mini/package-consumption")
    public Object consumption() {
        return progress(l(access.user(), "id"), Collections.emptyMap());
    }

    @GetMapping("/api/admin/package-consumption")
    public Object adminConsumption(@RequestParam Map<String, String> q) {
        access.staffAny("onboarding:manage", "orders:manage");
        return progress(null, q);
    }

    private ObjectNode progress(Long uid, Map<String, String> filters) {
        Map<String, String> q = new HashMap<>(filters);
        if (uid != null) q.put("userId", String.valueOf(uid));
        return new PageQuery(
                        q,
                        "FROM onboardings a JOIN orders o ON o.id=a.order_id AND o.is_deleted=0 WHERE a.is_deleted=0 AND o.payment_state IN ('paid','partially_refunded','refunded')")
                .eq("userId", "a.user_id")
                .select(
                        db,
                        "a.id,a.user_id,a.target_identity,a.consumption_confirmed,a.consumption_note,a.order_id,(SELECT COALESCE(SUM(quantity),0) FROM order_lines WHERE order_id=a.order_id AND is_deleted=0) AS total_qty,(SELECT COALESCE(SUM(LEAST(received_qty,GREATEST(0,shipped_qty-refunded_qty+refunded_unshipped))),0) FROM order_lines WHERE order_id=a.order_id AND is_deleted=0) AS consumed_qty",
                        "a.id DESC",
                        r -> r);
    }

    @PostMapping("/api/admin/onboardings/{id}/consumption")
    public Object confirmConsumption(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("onboarding:manage"), "id");
        Input.strict(body, "confirmed", "reason");
        boolean confirmed = Input.bool(body, "confirmed");
        String reason = Input.text(body, "reason", 1, 2000);
        return db.tx(
                () -> {
                    if (db.update(
                                    "UPDATE onboardings SET consumption_confirmed=?,consumption_note=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    confirmed ? 1 : 0,
                                    reason,
                                    actor,
                                    Input.id(id))
                            != 1) throw BusinessException.notFound("权益包记录不存在");
                    access.audit("onboarding.consumption", "onboarding", id, reason);
                    return obj("ok", true);
                });
    }
}
