package com.jianyanji.storage;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.aftersales.*;
import com.jianyanji.catalog.*;
import com.jianyanji.common.*;
import com.jianyanji.membership.*;
import com.jianyanji.orders.*;
import com.jianyanji.payments.*;
import com.jianyanji.rewards.*;
import com.jianyanji.wallet.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;

/** 独立真实MySQL回归：覆盖全客户积分、原库存所有权、跨订单履约、退款和并发；不清表。 */
@EnabledIfEnvironmentVariable(
        named = "STORAGE_TEST_MYSQL_URL",
        matches = "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/java_rewards_test_.*")
class StorageMysqlTest {
    /** 独立库数据访问。 */
    private Db db;
    /** 模拟当前请求审计，不模拟业务数据。 */
    private Access access;
    /** 商品身份价。 */
    private CatalogService catalog;
    /** 真实报价与订单。 */
    private OrderService orders;
    /** 真实钱包与积分来源。 */
    private WalletService wallet;
    /** 真实身份开通。 */
    private MembershipService membership;
    /** 被测寄存服务。 */
    private StorageService storage;
    /** 真实售后业务。 */
    private AftersalesService aftersales;
    /** 独立用户、地址及规格。 */
    private long user, address, sku, principal;

    @BeforeAll
    static void quietJdbc() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework"))
                .setLevel(ch.qos.logback.classic.Level.WARN);
    }

    @BeforeEach
    void setup() {
        DriverManagerDataSource source =
                new DriverManagerDataSource(System.getenv("STORAGE_TEST_MYSQL_URL"), "root", "");
        db = new Db(new JdbcTemplate(source), new DataSourceTransactionManager(source));
        access = mock(Access.class);
        when(access.actor()).thenReturn(1L);
        when(access.requestId()).thenReturn("storage-test");
        catalog = new CatalogService(db, access);
        PaymentService payments = new PaymentService(db, access, null, mock(WechatPayClient.class));
        orders = new OrderService(db, access, catalog, payments);
        ReflectionTestUtils.setField(payments, "orders", orders);
        wallet = new WalletService(db, access, orders, payments);
        membership = new MembershipService(db, access, wallet, orders);
        ReflectionTestUtils.setField(payments, "membership", membership);
        ReflectionTestUtils.setField(payments, "wallet", wallet);
        storage = new StorageService(db, access);
        aftersales =
                new AftersalesService(
                        db,
                        access,
                        orders,
                        wallet,
                        membership,
                        new RewardService(db, access),
                        mock(WechatPayClient.class));
        db.tx(
                () -> {
                    user =
                            db.insert(
                                    "INSERT INTO users(openid,identity,nickname) VALUES(?,'D','寄存测试客户')",
                                    id());
                    principal =
                            db.insert(
                                    "INSERT INTO users(openid,identity,nickname) VALUES(?,'B','测试19800店主')",
                                    id());
                    address =
                            db.insert(
                                    "INSERT INTO addresses(user_id,recipient,phone,province,city,district,detail) VALUES(?,'测试客户','13800000000','浙江省','杭州','西湖','测试地址')",
                                    user);
                    long category =
                            db.insert("INSERT INTO categories(name,active) VALUES('寄存测试类目',1)");
                    long product =
                            db.insert(
                                    "INSERT INTO products(category_id,name,subtitle,description,image,detail_image,active) VALUES(?,'寄存测试商品','','说明','','',1)",
                                    category);
                    sku =
                            db.insert(
                                    "INSERT INTO skus(product_id,name,unit,retail_cents,store_cents,principal_cents,stock,reserved,active,storage_enabled) VALUES(?,'标准装','瓶',10000,7000,3500,1000,0,1,1)",
                                    product);
                    db.putSetting(
                            "shipping",
                            obj(
                                    "provinces",
                                    arr("浙江省"),
                                    "pendingMinutes",
                                    30,
                                    "feeCents",
                                    1000,
                                    "instructions",
                                    "品牌仓配送"),
                            1);
                    db.putSetting("aftersales", obj("days", 7, "policy", "测试售后政策"), 1);
                });
    }

    private ObjectNode buyer() {
        return db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", user);
    }

    private long balance() {
        return new PointsService(db).available(user);
    }

    private ObjectNode credit(long amount, String key) {
        return wallet.confirmReceipt(
                obj(
                        "userId",
                        user,
                        "purpose",
                        "points",
                        "sourceChannel",
                        "wechat_company",
                        "accountRef",
                        "总部微信",
                        "externalTransactionId",
                        key,
                        "amountCents",
                        amount,
                        "receivedAt",
                        "2026-01-01T00:00:00Z",
                        "evidence",
                        "人工核实凭证",
                        "reason",
                        "测试充值",
                        "verified",
                        true),
                1);
    }

    private ObjectNode create(long quantity, long ship, JsonNode stored) {
        ObjectNode quote =
                orders.quote(
                        obj(
                                "addressId",
                                address,
                                "items",
                                arr(obj("skuId", sku, "quantity", quantity, "shipQuantity", ship)),
                                "storedItems",
                                stored),
                        buyer());
        return orders.create(
                obj("quoteId", l(quote, "id"), "requestKey", "order-" + id()), buyer());
    }

    private ObjectNode purchase(long quantity, long ship) {
        ObjectNode order = create(quantity, ship, arr());
        return wallet.payWithPoints(user, l(order, "id"));
    }

    private long lot(long order) {
        return l(
                db.one("SELECT id FROM storage_lots WHERE order_id=? AND is_deleted=0", order),
                "id");
    }

    private ObjectNode submit(JsonNode lines) {
        return storage.submit(
                obj("addressId", address, "storedItems", lines, "requestKey", "pickup-" + id()),
                buyer(),
                false,
                user);
    }

    private void ship(long pickup) {
        storage.review(pickup, obj("approve", true, "reason", "核实提货"), 1);
        storage.ship(pickup, obj("carrier", "测试快递", "tracking", "TEST123456", "reason", "实际发货"), 1);
    }

    @Test
    void storageStatusFollowsActualParcelsAndFiltersBeforePagination() {
        credit(100000, id());
        ObjectNode order = purchase(3, 0);
        long orderId = l(order, "id");
        assertEquals("stored", s(orders.view(orders.owned(orderId, user)), "fulfillmentState"));
        com.jianyanji.mobile.MiniListService lists =
                new com.jianyanji.mobile.MiniListService(db, catalog, orders, aftersales, membership, null, wallet);
        assertEquals(0, lists.mine("orders", buyer(), 1, 1, 2, null).path("items").size());
        assertEquals(orderId, l(lists.mine("orders", buyer(), 1, 1, 5, null).path("items").get(0), "id"));
        assertEquals(0, l(lists.orderCounts(user), "paid"));
        ObjectNode pickup = submit(arr(obj("lotId", lot(orderId), "quantity", 1)));
        assertEquals("awaiting_shipment", s(orders.view(orders.owned(orderId, user)), "fulfillmentState"));
        assertEquals(1, l(lists.orderCounts(user), "paid"));
        ship(l(pickup, "id"));
        assertEquals("shipped", s(orders.view(orders.owned(orderId, user)), "fulfillmentState"));
        storage.receive(l(pickup, "id"), user);
        ObjectNode stored = orders.view(orders.owned(orderId, user));
        assertEquals("stored", s(stored, "fulfillmentState"));
        assertEquals(2, l(stored, "storageRemainingQty"));
        assertEquals(0, lists.mine("orders", buyer(), 1, 1, 3, null).path("items").size());
        assertEquals(0, l(lists.orderCounts(user), "shipped"));
    }

    @Test
    void cancellingCombinedDeliveryKeepsNewGoodsAvailableForOrdinaryShipping() {
        credit(100000, id());
        ObjectNode first = purchase(3, 0);
        long lotId = lot(l(first, "id"));
        ObjectNode fresh = create(2, 1, arr(obj("lotId", lotId, "quantity", 1)));
        long orderId = l(fresh, "id");
        wallet.payWithPoints(user, orderId);
        ObjectNode pickup = db.one("SELECT * FROM pickup_orders WHERE purchase_order_id=? AND is_deleted=0", orderId);
        ObjectNode cancelled = storage.cancel(l(pickup, "id"), user, user, "取消合并配送", false);
        assertEquals(1, l(cancelled, "newGoodsQuantity"));
        assertEquals(1, l(cancelled, "storedGoodsQuantity"));
        assertEquals(0, l(db.one("SELECT held_qty FROM storage_lots WHERE id=?", lotId), "held_qty"));
        FulfillmentService fulfillment = new FulfillmentService(db, orders, access);
        ObjectNode line = fulfillment.shippableLines(orderId).get(0);
        assertEquals(1, l(line, "remaining"));
        assertEquals(1, l(orders.view(orders.owned(orderId, user)), "directShipQuantity"));
        storage.cancel(l(pickup, "id"), user, user, "重复取消", false);
        fulfillment.ship(orderId, obj("carrier", "测试快递", "tracking", "CANCEL1234", "reason", "继续配送已购新货", "requestKey", "ship-" + id(), "lines", arr(obj("lineId", l(line, "id"), "quantity", 1))), 1);
        assertEquals(1, l(db.one("SELECT shipped_qty FROM order_lines WHERE id=?", l(line, "id")), "shipped_qty"));
        assertEquals(1, l(db.one("SELECT remaining_qty FROM storage_lots WHERE order_id=?", orderId), "remaining_qty"));
    }

    @Test
    void ordinaryCustomerRechargeIdempotentAndStorageOwnership() {
        String key = id();
        credit(50000, key);
        credit(50000, key);
        assertEquals(50000, balance());
        ObjectNode paid = purchase(3, 1);
        assertEquals(20000, balance());
        assertEquals(997, l(db.one("SELECT stock FROM skus WHERE id=?", sku), "stock"));
        assertEquals(
                2,
                l(
                        db.one("SELECT * FROM storage_lots WHERE order_id=?", l(paid, "id")),
                        "remaining_qty"));
        wallet.payWithPoints(user, l(paid, "id"));
        assertEquals(20000, balance());
        assertEquals(
                1,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM storage_lots WHERE order_id=?",
                                l(paid, "id")),
                        "n"));
    }

    @Test
    void insufficientPointsRollsBackEveryDeductionAndDoesNotCreateStorage() {
        credit(10000, id());
        ObjectNode order = create(3, 0, arr());
        assertThrows(BusinessException.class, () -> wallet.payWithPoints(user, l(order, "id")));
        assertEquals(10000, balance());
        assertNull(db.one("SELECT * FROM storage_lots WHERE order_id=?", l(order, "id")));
        assertEquals(1000, l(db.one("SELECT stock FROM skus WHERE id=?", sku), "stock"));
    }

    @Test
    void mergedPickupShipsOnceAndReceivingUpdatesBothOriginalOrders() {
        credit(100000, id());
        ObjectNode a = purchase(3, 0), b = purchase(2, 0);
        ObjectNode p =
                submit(
                        arr(
                                obj("lotId", lot(l(a, "id")), "quantity", 2),
                                obj("lotId", lot(l(b, "id")), "quantity", 1)));
        long id = l(p, "id");
        ship(id);
        long shipmentId =
                l(
                        db.one(
                                "SELECT id FROM shipments WHERE pickup_id=? ORDER BY id LIMIT 1",
                                id),
                        "id");
        new FulfillmentService(db, orders, access)
                .correct(
                        shipmentId,
                        obj("carrier", "顺丰", "tracking", "FIXED1234", "reason", "更正合并包裹物流"),
                        1);
        assertEquals("FIXED1234", s(storage.owned(id, null), "tracking"));
        assertEquals(
                2,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM shipments WHERE pickup_id=? AND tracking='FIXED1234'",
                                id),
                        "n"));
        assertEquals(
                2,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM shipment_corrections c JOIN shipments s ON s.id=c.shipment_id WHERE s.pickup_id=?",
                                id),
                        "n"));
        storage.receive(id, user);
        storage.receive(id, user);
        assertEquals(995, l(db.one("SELECT stock FROM skus WHERE id=?", sku), "stock"));
        assertEquals(
                2,
                l(
                        db.one("SELECT received_qty FROM order_lines WHERE order_id=?", l(a, "id")),
                        "received_qty"));
        assertEquals(
                1,
                l(
                        db.one("SELECT received_qty FROM order_lines WHERE order_id=?", l(b, "id")),
                        "received_qty"));
        assertEquals(
                2, l(db.one("SELECT COUNT(*) AS n FROM shipments WHERE pickup_id=?", id), "n"));
    }

    @Test
    void pickupFeeCancellationReturnsOnlyFeeAndRestoresOriginalBatch() {
        credit(100000, id());
        ObjectNode o = purchase(3, 0);
        db.tx(() -> db.update("UPDATE users SET identity='B' WHERE id=?", user));
        long before = balance();
        ObjectNode p = submit(arr(obj("lotId", lot(l(o, "id")), "quantity", 1)));
        assertEquals(before - 1000, balance());
        storage.cancel(l(p, "id"), user, user, "取消提货", false);
        storage.cancel(l(p, "id"), user, user, "重复取消", false);
        assertEquals(before, balance());
        ObjectNode lot = db.one("SELECT * FROM storage_lots WHERE order_id=?", l(o, "id"));
        assertEquals(3, l(lot, "remaining_qty"));
        assertEquals(0, l(lot, "held_qty"));
    }

    @Test
    void concurrentApplicationsCannotOverdrawStorage() {
        credit(50000, id());
        ObjectNode order = purchase(2, 0);
        long lot = lot(l(order, "id"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> calls =
                    Arrays.asList(
                            () -> {
                                try {
                                    submit(arr(obj("lotId", lot, "quantity", 2)));
                                    return true;
                                } catch (BusinessException e) {
                                    return false;
                                }
                            },
                            () -> {
                                try {
                                    submit(arr(obj("lotId", lot, "quantity", 2)));
                                    return true;
                                } catch (BusinessException e) {
                                    return false;
                                }
                            });
            int successes = 0;
            for (Future<Boolean> result : pool.invokeAll(calls)) if (result.get()) successes++;
            assertEquals(1, successes);
            assertEquals(2, l(db.one("SELECT * FROM storage_lots WHERE id=?", lot), "held_qty"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredUserCannotApplyButStaffCanExtend() {
        credit(30000, id());
        ObjectNode order = purchase(2, 0);
        long lot = lot(l(order, "id"));
        db.tx(() -> db.update("UPDATE storage_lots SET expires_on='2020-01-01' WHERE id=?", lot));
        assertThrows(BusinessException.class, () -> submit(arr(obj("lotId", lot, "quantity", 1))));
        storage.extend(
                lot,
                obj(
                        "expiresOn",
                        LocalDate.now(StorageService.ZONE).plusDays(30).toString(),
                        "reason",
                        "人工批准延期"),
                1);
        assertEquals("review", s(submit(arr(obj("lotId", lot, "quantity", 1))), "status"));
    }

    @Test
    void combinedPurchaseExpiryReleasesStorageAndUsesOriginalValuation() {
        credit(200000, id());
        ObjectNode first = purchase(2, 0);
        db.tx(() -> db.update("UPDATE users SET identity='B' WHERE id=?", user));
        ObjectNode quote =
                orders.quote(
                        obj(
                                "addressId",
                                address,
                                "items",
                                arr(obj("skuId", sku, "quantity", 1, "shipQuantity", 1)),
                                "storedItems",
                                arr(obj("lotId", lot(l(first, "id")), "quantity", 1))),
                        buyer());
        assertEquals(1000, l(quote, "shippingCents"));
        ObjectNode order =
                orders.create(
                        obj("quoteId", l(quote, "id"), "requestKey", "combined-" + id()), buyer());
        assertEquals(
                1,
                l(
                        db.one(
                                "SELECT held_qty FROM storage_lots WHERE order_id=?",
                                l(first, "id")),
                        "held_qty"));
        orders.cancel(l(order, "id"), user);
        assertEquals(
                0,
                l(
                        db.one(
                                "SELECT held_qty FROM storage_lots WHERE order_id=?",
                                l(first, "id")),
                        "held_qty"));
    }

    @Test
    void storageRefundReturnsOriginalPointsAndCannotRacePickup() {
        credit(50000, id());
        ObjectNode order = purchase(3, 0);
        long line = l(order.path("lines").get(0), "id"), lot = lot(l(order, "id"));
        ObjectNode pickup = submit(arr(obj("lotId", lot, "quantity", 3)));
        ObjectNode command =
                obj(
                        "orderId",
                        l(order, "id"),
                        "items",
                        arr(obj("lineId", line, "quantity", 1)),
                        "shippingCents",
                        0,
                        "reason",
                        "未提货退款",
                        "requestKey",
                        "refund-" + id());
        assertThrows(
                BusinessException.class,
                () -> aftersales.request(command, obj("id", 1, "permissions", "[]")));
        storage.cancel(l(pickup, "id"), user, user, "取消后退款", false);
        ObjectNode refund = aftersales.request(command, obj("id", 1, "permissions", "[]"));
        aftersales.review(
                l(refund, "id"), obj("approve", true, "identityDecision", null, "reason", "批准退款"));
        aftersales.points(l(refund, "id"), obj("reason", "原积分退回"));
        assertEquals(30000, balance());
        assertEquals(
                2,
                l(
                        db.one("SELECT remaining_qty FROM storage_lots WHERE id=?", lot),
                        "remaining_qty"));
    }

    @Test
    void independentPricesAndFixedPackagePointsActivation() {
        credit(1000000, id());
        assertEquals(
                10000, catalog.rolePrice(db.one("SELECT * FROM skus WHERE id=?", sku), buyer()));
        ObjectNode packages = obj("packages", arr(pack("B"), pack("C")), "reason", "配置固定产品包");
        membership.updateSettings(packages, 1);
        ObjectNode relation = membership.invitations().candidate(null, principal, "C", user);
        ObjectNode app =
                membership.apply(
                        obj(
                                "targetIdentity",
                                "C",
                                "addressId",
                                address,
                                "inviterId",
                                principal,
                                "principalId",
                                relation.get("principalId"),
                                "relationshipsConfirmed",
                                true,
                                "conditionsMet",
                                true,
                                "agreementAccepted",
                                true,
                                "deliveryMode",
                                "store",
                                "requestKey",
                                "application-" + id()),
                        buyer());
        ObjectNode order = membership.preparePayment(l(app, "id"), user);
        wallet.payWithPoints(user, l(order, "id"));
        assertEquals("C", s(buyer(), "identity"));
        assertEquals(702000, balance());
        assertEquals(
                7000, catalog.rolePrice(db.one("SELECT * FROM skus WHERE id=?", sku), buyer()));
        assertEquals(
                2,
                l(
                        db.one(
                                "SELECT remaining_qty FROM storage_lots WHERE order_id=?",
                                l(order, "id")),
                        "remaining_qty"));
        wallet.payWithPoints(user, l(order, "id"));
        assertEquals(702000, balance());
    }

    @Test
    void purePickupWithoutFeeBalanceRollsBackApplicationAndHold() {
        credit(20000, id());
        ObjectNode order = purchase(2, 0);
        long lot = lot(l(order, "id"));
        db.tx(() -> db.update("UPDATE users SET identity='B' WHERE id=?", user));
        assertThrows(BusinessException.class, () -> submit(arr(obj("lotId", lot, "quantity", 1))));
        assertEquals(0, balance());
        assertEquals(0, l(db.one("SELECT held_qty FROM storage_lots WHERE id=?", lot), "held_qty"));
        assertNull(
                db.one(
                        "SELECT * FROM pickup_orders WHERE user_id=? AND purchase_order_id IS NULL",
                        user));
    }

    @Test
    void normalShipmentCannotBypassStoredOwnership() {
        credit(20000, id());
        ObjectNode order = purchase(2, 0);
        long line = l(order.path("lines").get(0), "id");
        FulfillmentService fulfillment = new FulfillmentService(db, orders, access);
        assertThrows(
                BusinessException.class,
                () ->
                        fulfillment.ship(
                                l(order, "id"),
                                obj(
                                        "carrier",
                                        "测试快递",
                                        "tracking",
                                        "TEST9999",
                                        "requestKey",
                                        "shipment-" + id(),
                                        "reason",
                                        "尝试直接发寄存",
                                        "lines",
                                        arr(obj("lineId", line, "quantity", 1))),
                                1));
        assertEquals(
                0,
                l(db.one("SELECT shipped_qty FROM order_lines WHERE id=?", line), "shipped_qty"));
    }

    @Test
    void exportCoversMoreThanTwoHundredRowsAndWritesRealWorkbook() throws Exception {
        credit(10000, id());
        ObjectNode order = purchase(1, 0);
        long orderId = l(order, "id");
        db.tx(
                () -> {
                    for (int i = 0; i < 205; i++) {
                        long line =
                                db.insert(
                                        "INSERT INTO order_lines(order_id,sku_id,quantity,unit_cents,storage_qty) VALUES(?,?,1,100,1)",
                                        orderId,
                                        sku);
                        db.insert(
                                "INSERT INTO storage_lots(user_id,order_id,line_id,sku_id,total_qty,remaining_qty,unit_cents,starts_on,expires_on) VALUES(?,?,?,?,1,1,100,'2026-10-27','2027-10-27')",
                                user,
                                orderId,
                                line,
                                sku);
                    }
                });
        StorageExportController export = new StorageExportController(db, access);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        export.export("lots", Collections.singletonMap("userId", String.valueOf(user)))
                .getBody()
                .writeTo(output);
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook book =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                        new java.io.ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(206, book.getSheetAt(0).getLastRowNum());
            assertEquals("用户ID", book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test
    void reminderScanDeduplicatesAndRetainsStationMessageWithoutWechat() {
        credit(10000, id());
        ObjectNode order = purchase(1, 0);
        long lot = lot(l(order, "id"));
        db.tx(
                () -> {
                    db.update(
                            "UPDATE storage_lots SET expires_on=? WHERE id=?",
                            LocalDate.now(StorageService.ZONE).plusDays(30).toString(),
                            lot);
                    db.putSetting(
                            "storage-reminders",
                            obj(
                                    "enabled",
                                    true,
                                    "days",
                                    arr(30, 7),
                                    "expiryTemplateId",
                                    "",
                                    "rejectedTemplateId",
                                    ""),
                            1);
                });
        StorageReminderController reminder = new StorageReminderController(db, access, "", "");
        reminder.tick();
        reminder.tick();
        assertEquals(
                1,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM storage_messages WHERE lot_id=? AND is_deleted=0",
                                lot),
                        "n"));
        assertEquals(
                "unavailable",
                s(db.one("SELECT * FROM storage_messages WHERE lot_id=?", lot), "delivery_state"));
    }

    @Test
    void receivedRewardIsPartialAndRefundOfPaidPartCreatesRecoverableDebt() {
        credit(100000, id());
        db.tx(
                () ->
                        db.update(
                                "UPDATE users SET identity='C',principal_id=? WHERE id=?",
                                principal,
                                user));
        ObjectNode order = purchase(3, 0);
        long orderId = l(order, "id"),
                lineId = l(order.path("lines").get(0), "id"),
                lot = lot(orderId);
        ObjectNode pickup = submit(arr(obj("lotId", lot, "quantity", 1)));
        ship(l(pickup, "id"));
        storage.receive(l(pickup, "id"), user);
        RewardService rewards = new RewardService(db, access);
        ObjectNode reward =
                rewards.register(
                        obj(
                                "orderId",
                                orderId,
                                "userId",
                                principal,
                                "amountCents",
                                10500,
                                "reason",
                                "登记原订货收益"));
        long rewardId = l(reward, "id");
        assertEquals(3500, l(reward, "availableCents"));
        db.tx(() -> db.update("UPDATE rewards SET settled_cents=3500 WHERE id=?", rewardId));
        ObjectNode refund =
                aftersales.request(
                        obj(
                                "orderId",
                                orderId,
                                "items",
                                arr(obj("lineId", lineId, "quantity", 1, "unshippedQuantity", 0)),
                                "shippingCents",
                                0,
                                "reason",
                                "退回已签收货品",
                                "requestKey",
                                "signed-refund-" + id()),
                        obj("id", 1, "permissions", "[]"));
        aftersales.review(
                l(refund, "id"), obj("approve", true, "identityDecision", null, "reason", "批准退货"));
        aftersales.inspect(
                l(refund, "id"),
                obj(
                        "returnedAt",
                        Instant.now().minusSeconds(1).toString(),
                        "inspection",
                        "实际收回货品",
                        "restock",
                        true,
                        "reason",
                        "验收通过"));
        aftersales.points(l(refund, "id"), obj("reason", "原积分退回"));
        assertEquals(
                3500,
                l(
                        db.one(
                                "SELECT debt_cents FROM reward_reversals WHERE reward_id=?",
                                rewardId),
                        "debt_cents"));
        assertEquals("C", s(buyer(), "identity"));
        ObjectNode second = submit(arr(obj("lotId", lot, "quantity", 2)));
        ship(l(second, "id"));
        storage.receive(l(second, "id"), user);
        assertEquals(7000, l(rewards.detail(rewardId, null, true), "availableCents"));
    }

    @Test
    void giftRefundMustInspectActualReturnedGiftAndNeverClosesIdentity() {
        credit(200000, id());
        db.tx(
                () ->
                        db.update(
                                "UPDATE users SET identity='C',principal_id=? WHERE id=?",
                                principal,
                                user));
        OfferService offers = new OfferService(db, access);
        offers.save(
                obj(
                        "offers",
                        arr(
                                obj(
                                        "id",
                                        99,
                                        "name",
                                        "买十赠二",
                                        "amountCents",
                                        100000,
                                        "active",
                                        true,
                                        "lines",
                                        arr(
                                                obj(
                                                        "skuId",
                                                        sku,
                                                        "quantity",
                                                        10,
                                                        "unitCents",
                                                        10000,
                                                        "isGift",
                                                        false),
                                                obj(
                                                        "skuId",
                                                        sku,
                                                        "quantity",
                                                        2,
                                                        "unitCents",
                                                        0,
                                                        "isGift",
                                                        true)))),
                        "reason",
                        "买赠配置"));
        ObjectNode quote =
                orders.quote(
                        obj(
                                "addressId",
                                address,
                                "offerId",
                                99,
                                "offerQuantity",
                                1,
                                "offerDelivery",
                                "store"),
                        buyer());
        ObjectNode created =
                orders.create(
                        obj("quoteId", l(quote, "id"), "requestKey", "gift-order-" + id()),
                        buyer());
        ObjectNode order = wallet.payWithPoints(user, l(created, "id"));
        long orderId = l(order, "id");
        ObjectNode
                main = db.one("SELECT * FROM order_lines WHERE order_id=? AND is_gift=0", orderId),
                gift = db.one("SELECT * FROM order_lines WHERE order_id=? AND is_gift=1", orderId);
        long giftLot =
                l(db.one("SELECT id FROM storage_lots WHERE line_id=?", l(gift, "id")), "id");
        ObjectNode pickup = submit(arr(obj("lotId", giftLot, "quantity", 2)));
        ship(l(pickup, "id"));
        storage.receive(l(pickup, "id"), user);
        ObjectNode refund =
                aftersales.request(
                        obj(
                                "orderId",
                                orderId,
                                "items",
                                arr(obj("lineId", l(main, "id"), "quantity", 5)),
                                "shippingCents",
                                0,
                                "reason",
                                "部分买赠退款",
                                "requestKey",
                                "gift-refund-" + id()),
                        obj("id", 1, "permissions", "[]"));
        aftersales.review(
                l(refund, "id"),
                obj(
                        "approve",
                        true,
                        "identityDecision",
                        null,
                        "giftDecision",
                        "退五件主品并收回一件已发赠品",
                        "giftItems",
                        arr(obj("lineId", l(gift, "id"), "quantity", 1)),
                        "reason",
                        "人工核定"));
        assertThrows(
                BusinessException.class,
                () -> aftersales.points(l(refund, "id"), obj("reason", "未收回赠品前尝试退款")));
        aftersales.inspect(
                l(refund, "id"),
                obj(
                        "returnedAt",
                        Instant.now().minusSeconds(1).toString(),
                        "inspection",
                        "赠品实收1件",
                        "restock",
                        true,
                        "reason",
                        "赠品验收"));
        aftersales.points(l(refund, "id"), obj("reason", "原积分退款"));
        assertEquals(150000, balance());
        assertEquals(994, l(db.one("SELECT stock FROM skus WHERE id=?", sku), "stock"));
        assertEquals("C", s(buyer(), "identity"));
    }

    @Test
    void combinedCancellationFeeCannotBeRefundedAgainThroughGoodsAftersales() {
        credit(100000, id());
        ObjectNode first = purchase(2, 0);
        db.tx(() -> db.update("UPDATE users SET identity='B' WHERE id=?", user));
        ObjectNode order = create(1, 1, arr(obj("lotId", lot(l(first, "id")), "quantity", 1)));
        wallet.payWithPoints(user, l(order, "id"));
        ObjectNode pickup =
                db.one("SELECT * FROM pickup_orders WHERE purchase_order_id=?", l(order, "id"));
        assertEquals(1000, l(pickup, "fee_cents"));
        storage.cancel(l(pickup, "id"), user, user, "取消合并配送", false);
        ObjectNode shipping =
                obj(
                        "orderId",
                        l(order, "id"),
                        "items",
                        arr(),
                        "shippingCents",
                        1000,
                        "reason",
                        "尝试重复退运费",
                        "requestKey",
                        "shipping-refund-" + id());
        assertThrows(
                BusinessException.class,
                () -> aftersales.request(shipping, obj("id", 1, "permissions", "[]")));
        ObjectNode paid = orders.view(orders.owned(l(order, "id"), user));
        long line = l(paid.path("lines").get(0), "id");
        ObjectNode refund =
                aftersales.request(
                        obj(
                                "orderId",
                                l(order, "id"),
                                "items",
                                arr(obj("lineId", line, "quantity", 1)),
                                "shippingCents",
                                0,
                                "reason",
                                "退还新购商品",
                                "requestKey",
                                "goods-refund-" + id()),
                        obj("id", 1, "permissions", "[]"));
        aftersales.review(
                l(refund, "id"),
                obj("approve", true, "identityDecision", null, "reason", "批准货款退款"));
        aftersales.points(l(refund, "id"), obj("reason", "退回原积分"));
        assertEquals(80000, balance());
        assertEquals(998, l(db.one("SELECT stock FROM skus WHERE id=?", sku), "stock"));
    }

    @Test
    void miniPaginationAndOwnerFilterCoverAllStoredRows() {
        credit(10000, id());
        ObjectNode order = purchase(1, 0);
        long orderId = l(order, "id");
        db.tx(
                () -> {
                    for (int i = 0; i < 22; i++) {
                        long line =
                                db.insert(
                                        "INSERT INTO order_lines(order_id,sku_id,quantity,unit_cents,storage_qty) VALUES(?,?,1,100,1)",
                                        orderId,
                                        sku);
                        db.insert(
                                "INSERT INTO storage_lots(user_id,order_id,line_id,sku_id,total_qty,remaining_qty,unit_cents,starts_on,expires_on) VALUES(?,?,?,?,1,1,100,'2026-10-27','2027-10-27')",
                                user,
                                orderId,
                                line,
                                sku);
                    }
                });
        when(access.user()).thenReturn(buyer());
        StorageController controller = new StorageController(db, access);
        Map<String, String> query = new HashMap<>();
        query.put("limit", "20");
        query.put("userId", String.valueOf(principal));
        ObjectNode first = (ObjectNode) controller.mine(query);
        assertEquals(20, first.path("items").size());
        assertTrue(b(first, "hasMore"));
        assertEquals(2, l(first, "nextPage"));
        assertEquals(23, l(first, "total"));
        query.put("page", "2");
        ObjectNode second = (ObjectNode) controller.mine(query);
        assertEquals(3, second.path("items").size());
        assertFalse(b(second, "hasMore"));
    }

    @Test
    void independentPriceMetadataPreservesSoldStockAndRejectsStaleAdjustment() {
        credit(50000, id());
        purchase(3, 0);
        ObjectNode original =
                db.one(
                        "SELECT s.*,p.category_id FROM skus s JOIN products p ON p.id=s.product_id WHERE s.id=?",
                        sku);
        ObjectNode skuInput =
                obj(
                        "id",
                        sku,
                        "name",
                        "标准装",
                        "unit",
                        "瓶",
                        "retailCents",
                        10000,
                        "storeCents",
                        8000,
                        "principalCents",
                        3600,
                        "productType",
                        "regular",
                        "welfareCents",
                        null,
                        "active",
                        true);
        ObjectNode input =
                obj(
                        "categoryId",
                        l(original, "category_id"),
                        "name",
                        "独立价格商品",
                        "subtitle",
                        "",
                        "description",
                        "维护商品资料",
                        "image",
                        "/assets/test.png",
                        "detailImage",
                        "/assets/test.png",
                        "active",
                        true,
                        "featured",
                        false,
                        "skus",
                        arr(skuInput),
                        "reason",
                        "仅修改价格资料");
        catalog.saveProduct(l(original, "product_id"), input, 1);
        ObjectNode updated = db.one("SELECT * FROM skus WHERE id=?", sku);
        assertEquals(997, l(updated, "stock"));
        assertEquals(8000, catalog.rolePrice(updated, obj("identity", "C")));
        assertEquals(3600, catalog.rolePrice(updated, obj("identity", "B")));
        skuInput.put("stock", 1000);
        skuInput.put("expectedStock", 1000);
        input.set("skus", arr(skuInput));
        assertThrows(
                BusinessException.class,
                () -> catalog.saveProduct(l(original, "product_id"), input, 1));
        assertEquals(997, l(db.one("SELECT stock FROM skus WHERE id=?", sku), "stock"));
        assertThrows(
                BusinessException.class,
                () ->
                        catalog.removeProduct(
                                l(original, "product_id"), obj("reason", "尝试移除有寄存货品的商品"), 1));
    }

    @Test
    void independentIdentityClosureRequiresTeamHandoverAndIsIdempotent() {
        db.tx(() -> db.update("UPDATE users SET identity='B' WHERE id=?", user));
        long child =
                db.tx(
                        () ->
                                db.insert(
                                        "INSERT INTO users(openid,identity,principal_id) VALUES(?,'C',?)",
                                        id(),
                                        user));
        IdentityAdjustmentController controller = new IdentityAdjustmentController(db, access);
        ObjectNode body =
                obj(
                        "identity",
                        "D",
                        "reason",
                        "人工关闭资格",
                        "evidence",
                        "品牌方核验记录",
                        "requestKey",
                        "identity-close-" + id());
        assertThrows(BusinessException.class, () -> controller.adjust(user, body));
        assertEquals("B", s(buyer(), "identity"));
        membership
                .qualifications()
                .prepareHandover(
                        obj("userId", user, "principalId", principal, "reason", "指定有效接管人"), 1);
        controller.adjust(user, body);
        controller.adjust(user, body);
        assertEquals("D", s(buyer(), "identity"));
        assertEquals(
                principal,
                l(db.one("SELECT principal_id FROM users WHERE id=?", child), "principal_id"));
        assertEquals(
                1,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM qualification_actions WHERE user_id=?",
                                user),
                        "n"));
    }

    @Test
    void archivedProductDoesNotHideHistoricalPickupOrStorage() {
        credit(50000, id());
        ObjectNode o = purchase(2, 0);
        ObjectNode pickup = submit(arr(obj("lotId", lot(l(o, "id")), "quantity", 2)));
        ship(l(pickup, "id"));
        storage.receive(l(pickup, "id"), user);
        db.tx(
                () -> {
                    db.update(
                            "UPDATE skus SET is_deleted=1,update_time=CURRENT_TIMESTAMP(3) WHERE id=?",
                            sku);
                    db.update(
                            "UPDATE products SET is_deleted=1,update_time=CURRENT_TIMESTAMP(3) WHERE id=(SELECT product_id FROM skus WHERE id=?)",
                            sku);
                });
        assertEquals(1, storage.view(storage.owned(l(pickup, "id"), user)).path("lines").size());
        ObjectNode page = storage.lots(user, Collections.emptyMap());
        assertEquals(1, page.path("items").size());
        assertEquals("寄存测试商品", s(page.path("items").get(0), "name"));
    }

    private ObjectNode pack(String role) {
        long amount = "B".equals(role) ? 1980000 : 298000;
        return obj(
                "targetIdentity",
                role,
                "name",
                role + "固定包",
                "amountCents",
                amount,
                "valueCents",
                amount,
                "selfUseLabel",
                "固定货品",
                "benefits",
                "身份价",
                "conditions",
                "有效邀请",
                "agreement",
                "同意条款",
                "rightsImage",
                "",
                "active",
                true,
                "lines",
                arr(obj("skuId", sku, "quantity", 2, "unitCents", amount / 2)));
    }

    @Test
    void freightBoundaryAndCalendarExpiry() {
        assertEquals(1000, StorageService.shippingFee("B", 99999, 99999, true));
        assertEquals(0, StorageService.shippingFee("B", 100000, 1, true));
        assertEquals(0, StorageService.shippingFee("B", 0, 100000, true));
        assertEquals(0, StorageService.shippingFee("C", 0, 1, true));
        assertEquals(0, StorageService.shippingFee("D", 0, 1, true));
        assertEquals(0, StorageService.shippingFee("B", 1, 0, false));
        assertEquals(
                LocalDate.of(2025, 2, 28),
                StorageService.expiresOn(Instant.parse("2024-02-29T00:00:00Z")));
    }
}
