package com.jianyanji.aftersales;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.catalog.CatalogService;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import com.jianyanji.membership.MembershipService;
import com.jianyanji.orders.FulfillmentService;
import com.jianyanji.orders.OrderService;
import com.jianyanji.payments.PaymentService;
import com.jianyanji.payments.WechatPayClient;
import com.jianyanji.rewards.RewardService;
import com.jianyanji.wallet.WalletService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 真实 MySQL 支付与售后生命周期：真实 Db、会话审计及全部业务服务，仅微信网络使用显式测试渠道。 仅设置 PAYMENTS_TEST_MYSQL_URL 时运行，允许本机
 * java_rewards_test_ 专用库及原 3308 隔离库。 首个用例执行前自动应用 Flyway 迁移；统一验收默认使用 java_rewards_test_integration。
 */
@EnabledIfEnvironmentVariable(named = "PAYMENTS_TEST_MYSQL_URL", matches = ".+")
class PaymentsAftersalesMysqlTest {
    /** 本类共用的真实数据源，校验专用库后才初始化并执行迁移。 */
    private static DriverManagerDataSource source;
    /** 本轮独立服务容器，不启动应用、定时器或演示数据。 */
    private AnnotationConfigApplicationContext context;
    /** 实际 MySQL 事务及 SQL 访问组件。 */
    private Db db;
    /** 真实请求审计与身份上下文。 */
    private Access access;
    /** 真实报价、下单和履约聚合服务。 */
    private OrderService orders;
    /** 真实支付结算、租约和核对服务。 */
    private PaymentService payments;
    /** 真实公司核款及来源批次积分服务。 */
    private WalletService wallet;
    /** 真实发货、验收和收货服务。 */
    private FulfillmentService fulfillment;
    /** 真实售后和退款补偿服务。 */
    private AftersalesService aftersales;
    /** 真实奖励登记与退款冲回服务。 */
    private RewardService rewards;
    /** 唯一替换的外部微信渠道，不替换任何数据库或领域服务。 */
    private TestWechatChannel channel;
    /** 独立店主用户编号。 */
    private long userId;
    /** 独立归属主理人编号。 */
    private long principalId;
    /** 独立后台工作人员编号。 */
    private long staffId;
    /** 独立测试类目编号。 */
    private long categoryId;
    /** 独立测试商品编号。 */
    private long productId;
    /** 独立规格编号，初始实物库存二十件。 */
    private long skuId;
    /** 通过真实地址服务创建的收货地址。 */
    private long addressId;
    /** 当前测试店主数据库行。 */
    private ObjectNode user;
    /** 当前测试后台人员数据库行。 */
    private ObjectNode staff;
    /** 待恢复的共享配置原始行，缺失时为 null。 */
    private final Map<String, ObjectNode> previousSettings = new LinkedHashMap<>();
    /** 此用例所有主线程和并发线程的诊断前缀，用于精确逻辑清理。 */
    private final String tracePrefix = "payments-mysql-" + id() + "-";
    /** 并发请求编号序列。 */
    private final AtomicInteger requestSequence = new AtomicInteger();

    /** URL 环境门控继续保留，不设置时整类跳过；只对明确允许的隔离库执行迁移。 */
    @BeforeAll
    static void migrateIsolatedDatabase() {
        String url = System.getenv("PAYMENTS_TEST_MYSQL_URL");
        assertTrue(
                url != null
                        && (url.matches(
                                        "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/java_rewards_test_[A-Za-z0-9_]+(?:\\?.*)?")
                                || url.matches(
                                        "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):3308/jianyanji_schema_check(?:\\?.*)?")),
                "仅允许本机 java_rewards_test_ 专用库或原 3308 jianyanji_schema_check 隔离库");
        source =
                new DriverManagerDataSource(
                        url,
                        System.getenv().getOrDefault("PAYMENTS_TEST_MYSQL_USER", "root"),
                        System.getenv().getOrDefault("PAYMENTS_TEST_MYSQL_PASSWORD", ""));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void setup() {
        db = new Db(new JdbcTemplate(source), new DataSourceTransactionManager(source));
        channel = new TestWechatChannel();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(Db.class, () -> db);
        context.registerBean(WechatPayClient.class, () -> channel);
        context.register(
                Access.class,
                CatalogService.class,
                OrderService.class,
                PaymentService.class,
                WalletService.class,
                MembershipService.class,
                RewardService.class,
                FulfillmentService.class,
                AftersalesService.class);
        context.refresh();
        access = context.getBean(Access.class);
        orders = context.getBean(OrderService.class);
        payments = context.getBean(PaymentService.class);
        wallet = context.getBean(WalletService.class);
        fulfillment = context.getBean(FulfillmentService.class);
        aftersales = context.getBean(AftersalesService.class);
        rewards = context.getBean(RewardService.class);
        db.tx(
                () -> {
                    staffId =
                            db.insert(
                                    "INSERT INTO staff(username,display_name,password_hash,permissions) VALUES(?,?,?,?)",
                                    tracePrefix,
                                    "支付售后隔离测试",
                                    "测试专用禁止登录",
                                    stringify(
                                            arr(
                                                    "orders:manage",
                                                    "finance:manage",
                                                    "rewards:manage")));
                    principalId =
                            db.insert(
                                    "INSERT INTO users(openid,identity,create_user_id,update_user_id) VALUES(?,'B',?,?)",
                                    tracePrefix + "principal",
                                    staffId,
                                    staffId);
                    userId =
                            db.insert(
                                    "INSERT INTO users(openid,identity,principal_id,create_user_id,update_user_id) VALUES(?,'C',?,?,?)",
                                    tracePrefix + "store",
                                    principalId,
                                    staffId,
                                    staffId);
                    categoryId =
                            db.insert(
                                    "INSERT INTO categories(name,active,create_user_id,update_user_id) VALUES(?,1,?,?)",
                                    tracePrefix,
                                    staffId,
                                    staffId);
                    productId =
                            db.insert(
                                    "INSERT INTO products(category_id,name,subtitle,description,image,detail_image,active,create_user_id,update_user_id) VALUES(?,?,'','真实生命周期测试','','',1,?,?)",
                                    categoryId,
                                    tracePrefix,
                                    staffId,
                                    staffId);
                    skuId =
                            db.insert(
                                    "INSERT INTO skus(product_id,name,unit,retail_cents,store_cents,principal_cents,stock,reserved,active,create_user_id,update_user_id) VALUES(?,'测试规格','件',12000,8400,4200,20,0,1,?,?)",
                                    productId,
                                    staffId,
                                    staffId);
                    for (String key : new String[] {"shipping", "aftersales"})
                        previousSettings.put(
                                key,
                                db.one(
                                        "SELECT * FROM settings WHERE `key`=? AND is_deleted=0",
                                        key));
                    db.putSetting(
                            "shipping",
                            obj(
                                    "provinces",
                                    arr("测试省"),
                                    "feeCents",
                                    1000,
                                    "instructions",
                                    "隔离测试配送",
                                    "pendingMinutes",
                                    30),
                            staffId);
                    db.putSetting(
                            "aftersales", obj("days", 30, "policy", "按购买快照办理实际退货退款"), staffId);
                });
        user = db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", userId);
        staff = db.one("SELECT * FROM staff WHERE id=? AND is_deleted=0", staffId);
        attachRequest(true);
        addressId =
                l(
                        orders.saveAddress(
                                null,
                                obj(
                                        "recipient",
                                        "测试收货人",
                                        "phone",
                                        "13800138000",
                                        "province",
                                        "测试省",
                                        "city",
                                        "测试市",
                                        "district",
                                        "测试区",
                                        "detail",
                                        "隔离库测试地址",
                                        "isDefault",
                                        true),
                                userId),
                        "id");
    }

    /** 不物理删除，不接触既有业务行；本用例按唯一用户、商品与追踪前缀精确逻辑清理。 */
    @AfterEach
    void cleanup() {
        try {
            if (db != null && staffId != 0)
                db.tx(
                        () -> {
                            for (Map.Entry<String, ObjectNode> entry :
                                    previousSettings.entrySet()) {
                                ObjectNode old = entry.getValue();
                                if (old == null)
                                    db.update(
                                            "UPDATE settings SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE `key`=? AND is_deleted=0 AND create_user_id=?",
                                            staffId,
                                            entry.getKey(),
                                            staffId);
                                else
                                    db.update(
                                            "UPDATE settings SET value=?,version=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                            s(old, "value"),
                                            l(old, "version"),
                                            l(old, "update_user_id"),
                                            l(old, "id"));
                            }
                            String ownOrders = "SELECT id FROM orders WHERE user_id=" + userId;
                            String ownRefunds =
                                    "SELECT id FROM refunds WHERE order_id IN (" + ownOrders + ")";
                            String ownRewards =
                                    "SELECT id FROM rewards WHERE order_id IN (" + ownOrders + ")";
                            logical("reward_reversals", "reward_id IN (" + ownRewards + ")");
                            logical("rewards", "order_id IN (" + ownOrders + ")");
                            logical("returned_inventory", "refund_id IN (" + ownRefunds + ")");
                            logical("refunds", "order_id IN (" + ownOrders + ")");
                            logical(
                                    "shipment_corrections",
                                    "shipment_id IN (SELECT id FROM shipments WHERE order_id IN ("
                                            + ownOrders
                                            + "))");
                            for (String table :
                                    new String[] {
                                        "shipments",
                                        "payment_events",
                                        "payment_attempts",
                                        "payments",
                                        "order_lines"
                                    }) logical(table, "order_id IN (" + ownOrders + ")");
                            for (String table :
                                    new String[] {
                                        "wallet_ledger",
                                        "wallet_spend_allocations",
                                        "wallet_lots",
                                        "company_receipts",
                                        "addresses",
                                        "quotes",
                                        "orders"
                                    }) logical(table, "user_id=" + userId);
                            logical("skus", "id=" + skuId);
                            logical("products", "id=" + productId);
                            logical("categories", "id=" + categoryId);
                            logical("users", "id IN (" + userId + "," + principalId + ")");
                            logical("staff", "id=" + staffId);
                            db.update(
                                    "UPDATE audit_events SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE request_id LIKE ? AND is_deleted=0",
                                    staffId,
                                    tracePrefix + "%");
                        });
        } finally {
            RequestContextHolder.resetRequestAttributes();
            if (context != null) context.close();
        }
    }

    /** 仅使用测试内部固定表名和新生成数字主键构造清理条件。 */
    private void logical(String table, String predicate) {
        db.update(
                "UPDATE `"
                        + table
                        + "` SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE is_deleted=0 AND "
                        + predicate,
                staffId);
    }

    /** 使用真实 Access 与请求属性，后台和用户操作各有独立 requestId。 */
    private void attachRequest(boolean admin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("requestId", tracePrefix + requestSequence.incrementAndGet());
        request.setAttribute(admin ? "staff" : "user", admin ? staff : user);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** 并发线程安装真实审计上下文，结束时释放线程绑定。 */
    private ObjectNode inRequest(Supplier<ObjectNode> action) {
        attachRequest(true);
        try {
            return action.get();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /** 按真实核款流程发放两个来源批次，分别为 25000 分和 75000 分。 */
    private void credit() {
        for (long cents : new long[] {25000, 75000})
            wallet.confirmReceipt(
                    obj(
                            "userId",
                            userId,
                            "purpose",
                            "points",
                            "sourceChannel",
                            "bank",
                            "accountRef",
                            tracePrefix,
                            "externalTransactionId",
                            id(),
                            "amountCents",
                            cents,
                            "receivedAt",
                            "2020-01-01T00:00:00Z",
                            "evidence",
                            "隔离测试到账依据",
                            "reason",
                            "测试核实公司到账",
                            "verified",
                            true),
                    staffId);
    }

    /** 真正报价并下单四件店主价商品：货款 33600 分（零售价12000分的7折）、运费 1000 分。 */
    private ObjectNode createOrder() {
        attachRequest(false);
        ObjectNode quote =
                orders.quote(
                        obj(
                                "addressId",
                                addressId,
                                "items",
                                arr(obj("skuId", skuId, "quantity", 4))),
                        user);
        ObjectNode body = obj("quoteId", l(quote, "id"), "requestKey", id());
        ObjectNode order = orders.create(body, user);
        assertEquals(l(order, "id"), l(orders.create(body, user), "id"));
        assertEquals(20, sku("stock"));
        assertEquals(4, sku("reserved"));
        assertEquals(34600, l(order, "total_cents"));
        return order;
    }

    /** 积分支付并登记真实归属主理人价差奖励，便于核对退款补偿。 */
    private ObjectNode paidPointsOrder() {
        credit();
        ObjectNode order = createOrder();
        long orderId = l(order, "id");
        ObjectNode paid = wallet.payWithPoints(userId, orderId);
        assertEquals("paid", s(paid, "status"));
        assertEquals(65400, balance());
        assertEquals(16, sku("stock"));
        assertEquals(0, sku("reserved"));
        wallet.payWithPoints(userId, orderId);
        assertEquals(65400, balance());
        assertEquals(16, sku("stock"));
        attachRequest(true);
        registerReward(orderId);
        return paid;
    }

    /** 实际登记订单价差奖励，不直接插入奖励表。 */
    private long registerReward(long orderId) {
        return l(
                rewards.register(
                        obj("orderId", orderId, "amountCents", 16800, "reason", "核对四件商品原成交价差")),
                "id");
    }

    private ObjectNode refundBody(long orderId, long lineId, long quantity, long shipping) {
        return obj(
                "orderId",
                orderId,
                "items",
                quantity == 0 ? arr() : arr(obj("lineId", lineId, "quantity", quantity)),
                "shippingCents",
                shipping,
                "reason",
                "按原商品办理售后",
                "requestKey",
                id());
    }

    private ObjectNode requestRefund(ObjectNode body) {
        attachRequest(true);
        return aftersales.request(body, staff);
    }

    private void approve(long refundId) {
        aftersales.review(
                refundId, obj("approve", true, "identityDecision", null, "reason", "核对退款依据"));
    }

    private ObjectNode ship(long orderId, long lineId, long quantity) {
        return fulfillment.ship(
                orderId,
                obj(
                        "carrier",
                        "测试物流",
                        "tracking",
                        "TEST-" + id(),
                        "lines",
                        arr(obj("lineId", lineId, "quantity", quantity)),
                        "requestKey",
                        id(),
                        "reason",
                        "按实际数量出库"),
                staffId);
    }

    private long lineId(long orderId) {
        return l(
                db.one("SELECT id FROM order_lines WHERE order_id=? AND is_deleted=0", orderId),
                "id");
    }

    private ObjectNode line(long orderId) {
        return db.one("SELECT * FROM order_lines WHERE order_id=? AND is_deleted=0", orderId);
    }

    private long sku(String field) {
        return l(
                db.one("SELECT stock,reserved FROM skus WHERE id=? AND is_deleted=0", skuId),
                field);
    }

    private long balance() {
        return l(wallet.walletView(userId), "balanceCents");
    }

    private long count(String sql, Object... args) {
        return l(db.one(sql, args), "n");
    }

    private ObjectNode reason() {
        return obj("reason", "核对原支付并办理退款");
    }

    private void assertRefundAccounting(long orderId, long refundedCents, long reversedCents) {
        assertEquals(
                refundedCents,
                l(
                        db.one(
                                "SELECT COALESCE(SUM(delta_cents),0) AS n FROM wallet_ledger WHERE order_id=? AND kind='order_refund' AND is_deleted=0",
                                orderId),
                        "n"));
        assertEquals(
                reversedCents,
                l(
                        db.one(
                                "SELECT reversed_cents FROM rewards WHERE order_id=? AND is_deleted=0",
                                orderId),
                        "reversed_cents"));
    }

    @Test
    void pointsPaymentPartialShipmentReturnedGoodsRefundAndReceiptAreIdempotent() {
        ObjectNode order = paidPointsOrder();
        long orderId = l(order, "id"), lineId = lineId(orderId);
        ObjectNode shipment = ship(orderId, lineId, 3);
        ObjectNode body = refundBody(orderId, lineId, 2, 0), refund = requestRefund(body);
        long refundId = l(refund, "id");
        assertEquals(refundId, l(requestRefund(body), "id"));
        assertEquals(2, l(line(orderId), "refund_held"));
        assertEquals(1, l(line(orderId), "held_unshipped"));
        assertEquals(1, l(refund.path("lines").get(0), "returnQuantity"));
        approve(refundId);
        assertThrows(BusinessException.class, () -> aftersales.points(refundId, reason()));
        assertThrows(
                BusinessException.class,
                () -> fulfillment.receive(orderId, l(shipment, "id"), userId));
        assertEquals(65400, balance());
        assertRefundAccounting(orderId, 0, 0);
        ObjectNode inspection =
                obj(
                        "returnedAt",
                        Instant.now().minusSeconds(1).toString(),
                        "inspection",
                        "实退一件可入库",
                        "restock",
                        true,
                        "reason",
                        "核对退货实物");
        aftersales.inspect(refundId, inspection);
        assertThrows(BusinessException.class, () -> aftersales.inspect(refundId, inspection));
        assertEquals(17, sku("stock"));
        assertEquals("success", s(aftersales.points(refundId, reason()), "status"));
        aftersales.points(refundId, reason());
        db.tx(() -> rewards.reconcileReversals(orderId, staffId, access.requestId()));
        assertEquals(82200, balance());
        assertRefundAccounting(orderId, 16800, 8400);
        assertEquals(2, l(line(orderId), "refunded_qty"));
        assertEquals(1, l(line(orderId), "refunded_unshipped"));
        assertEquals(0, l(line(orderId), "refund_held"));
        assertEquals(0, l(line(orderId), "held_unshipped"));
        assertEquals(0, l(fulfillment.shippableLines(orderId).get(0), "remaining"));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) AS n FROM returned_inventory WHERE refund_id=? AND is_deleted=0",
                        refundId));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) AS n FROM reward_reversals WHERE refund_id=? AND is_deleted=0",
                        refundId));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) AS n FROM payments WHERE order_id=? AND is_deleted=0",
                        orderId));
        JsonNode lots = wallet.walletView(userId).path("lots");
        assertEquals(16800, l(lots.get(0), "remaining_cents"));
        assertEquals(65400, l(lots.get(1), "remaining_cents"));
        assertEquals(0, l(wallet.walletView(principalId), "balanceCents"));
        attachRequest(false);
        assertEquals(
                "completed", s(fulfillment.receive(orderId, l(shipment, "id"), userId), "status"));
        fulfillment.receive(orderId, l(shipment, "id"), userId);
        assertEquals(2, l(line(orderId), "received_qty"));
        assertEquals(17, sku("stock"));
    }

    @Test
    void unshippedRefundDoesNotRestockAndRemainingGoodsCanComplete() {
        ObjectNode order = paidPointsOrder();
        long orderId = l(order, "id"), lineId = lineId(orderId);
        ObjectNode refund = requestRefund(refundBody(orderId, lineId, 1, 0));
        long refundId = l(refund, "id");
        approve(refundId);
        aftersales.points(refundId, reason());
        assertEquals(16, sku("stock"));
        assertEquals(73800, balance());
        assertEquals(1, l(line(orderId), "refunded_unshipped"));
        assertEquals(3, l(fulfillment.shippableLines(orderId).get(0), "remaining"));
        assertRefundAccounting(orderId, 8400, 4200);
        ObjectNode shipment = ship(orderId, lineId, 3);
        attachRequest(false);
        assertEquals(
                "completed", s(fulfillment.receive(orderId, l(shipment, "id"), userId), "status"));
        assertEquals(3, l(line(orderId), "received_qty"));
    }

    @Test
    void rewardCompensationFailureRollsBackPointsQuantityAndRefundThenCanRetry() {
        ObjectNode order = paidPointsOrder();
        long orderId = l(order, "id");
        ObjectNode refund = requestRefund(refundBody(orderId, lineId(orderId), 1, 0));
        long refundId = l(refund, "id");
        approve(refundId);
        String originalLines = s(aftersales.getRefund(refundId), "lines");
        JsonNode corrupted = parse(originalLines);
        ((ObjectNode) corrupted.get(0)).put("principalCents", 7000);
        db.tx(
                () ->
                        db.update(
                                "UPDATE refunds SET `lines`=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                stringify(corrupted),
                                staffId,
                                refundId));
        assertThrows(BusinessException.class, () -> aftersales.points(refundId, reason()));
        assertEquals("approved", s(aftersales.getRefund(refundId), "status"));
        assertEquals(65400, balance());
        assertEquals(1, l(line(orderId), "refund_held"));
        assertEquals(0, l(line(orderId), "refunded_qty"));
        assertRefundAccounting(orderId, 0, 0);
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) AS n FROM reward_reversals WHERE refund_id=? AND is_deleted=0",
                        refundId));
        db.tx(
                () ->
                        db.update(
                                "UPDATE refunds SET `lines`=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                originalLines,
                                staffId,
                                refundId));
        aftersales.points(refundId, reason());
        aftersales.points(refundId, reason());
        assertEquals(73800, balance());
        assertRefundAccounting(orderId, 8400, 4200);
    }

    @Test
    void concurrentSameRequestAndPointsRefundOnlyCreateOneFinancialResult() throws Exception {
        ObjectNode order = paidPointsOrder();
        long orderId = l(order, "id");
        ObjectNode body = refundBody(orderId, lineId(orderId), 1, 0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier requestBarrier = new CyclicBarrier(2);
            Future<ObjectNode> a =
                    executor.submit(
                            () -> {
                                requestBarrier.await(5, TimeUnit.SECONDS);
                                return inRequest(() -> aftersales.request(body, staff));
                            });
            Future<ObjectNode> b =
                    executor.submit(
                            () -> {
                                requestBarrier.await(5, TimeUnit.SECONDS);
                                return inRequest(() -> aftersales.request(body, staff));
                            });
            long refundId = l(a.get(10, TimeUnit.SECONDS), "id");
            assertEquals(refundId, l(b.get(10, TimeUnit.SECONDS), "id"));
            approve(refundId);
            CyclicBarrier finishBarrier = new CyclicBarrier(2);
            Future<ObjectNode> first =
                    executor.submit(
                            () -> {
                                finishBarrier.await(5, TimeUnit.SECONDS);
                                return inRequest(() -> aftersales.points(refundId, reason()));
                            });
            Future<ObjectNode> second =
                    executor.submit(
                            () -> {
                                finishBarrier.await(5, TimeUnit.SECONDS);
                                return inRequest(() -> aftersales.points(refundId, reason()));
                            });
            assertEquals("success", s(first.get(10, TimeUnit.SECONDS), "status"));
            assertEquals("success", s(second.get(10, TimeUnit.SECONDS), "status"));
            assertEquals(73800, balance());
            assertRefundAccounting(orderId, 8400, 4200);
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) AS n FROM wallet_ledger WHERE order_id=? AND kind='order_refund' AND is_deleted=0",
                            orderId));
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) AS n FROM reward_reversals WHERE refund_id=? AND is_deleted=0",
                            refundId));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shippingLimitFailureRollsBackHeldQuantityAndRejectionReleasesOnlyItsReservation() {
        ObjectNode order = paidPointsOrder();
        long orderId = l(order, "id"), lineId = lineId(orderId);
        ObjectNode accepted = requestRefund(refundBody(orderId, lineId, 1, 1000));
        assertThrows(
                BusinessException.class, () -> requestRefund(refundBody(orderId, lineId, 1, 1)));
        assertEquals(1, l(line(orderId), "refund_held"));
        assertEquals(1, l(line(orderId), "held_unshipped"));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) AS n FROM refunds WHERE order_id=? AND is_deleted=0",
                        orderId));
        aftersales.review(
                l(accepted, "id"),
                obj("approve", false, "identityDecision", null, "reason", "核对后驳回"));
        assertEquals(0, l(line(orderId), "refund_held"));
        assertEquals(4, l(fulfillment.shippableLines(orderId).get(0), "remaining"));
        assertEquals(65400, balance());
        assertRefundAccounting(orderId, 0, 0);
    }

    /** 使用真实支付发起和服务端查单结算，不直接插入或修改收款凭据。 */
    private ObjectNode paidWechatOrder() {
        ObjectNode order = createOrder();
        long orderId = l(order, "id");
        payments.start(orderId, user);
        ObjectNode attempt =
                db.one("SELECT * FROM payment_attempts WHERE order_id=? AND is_deleted=0", orderId);
        channel.paid(s(attempt, "number"));
        payments.query(orderId, userId);
        assertEquals(16, sku("stock"));
        assertEquals(0, sku("reserved"));
        attachRequest(true);
        registerReward(orderId);
        return orders.owned(orderId, null);
    }

    @Test
    void wechatTimeoutKeepsOriginalRefundAndQueryCompletesRealCompensationOnce() {
        credit();
        ObjectNode order = paidWechatOrder();
        long orderId = l(order, "id");
        ObjectNode refund = requestRefund(refundBody(orderId, lineId(orderId), 1, 0));
        long refundId = l(refund, "id");
        approve(refundId);
        channel.refundFailure = new BusinessException(502, "CHANNEL_ERROR", "测试模拟渠道已经退款但响应超时");
        ObjectNode unknown = aftersales.channel(refundId, reason(), false);
        assertEquals("unknown", s(unknown, "status"));
        assertTrue(s(unknown, "last_error").contains(s(order, "number")));
        assertTrue(s(unknown, "last_error").contains(tracePrefix));
        assertEquals(1, l(line(orderId), "refund_held"));
        assertEquals(0, l(line(orderId), "refunded_qty"));
        assertEquals(100000, balance());
        assertRefundAccounting(orderId, 0, 0);
        assertThrows(BusinessException.class, () -> aftersales.channel(refundId, reason(), false));
        assertEquals("success", s(aftersales.channel(refundId, reason(), true), "status"));
        aftersales.channel(refundId, reason(), true);
        assertEquals(1, channel.refundCalls.get());
        assertEquals(1, channel.refundQueries.get());
        assertEquals(1, l(line(orderId), "refunded_qty"));
        assertEquals(0, l(line(orderId), "refund_held"));
        assertEquals(100000, balance());
        assertRefundAccounting(orderId, 0, 4200);
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) AS n FROM reward_reversals WHERE refund_id=? AND is_deleted=0",
                        refundId));
        assertFalse(has(aftersales.getRefund(refundId), "channel_token"));
    }

    @Test
    void wrongWechatAmountCannotSettleAndVerifiedQueryRecoversWithoutDuplicateStock() {
        ObjectNode order = createOrder();
        long orderId = l(order, "id");
        payments.start(orderId, user);
        ObjectNode attempt =
                db.one("SELECT * FROM payment_attempts WHERE order_id=? AND is_deleted=0", orderId);
        String number = s(attempt, "number");
        channel.paid(number);
        ((ObjectNode) channel.trades.get(number).get("amount")).put("total", 34599);
        assertEquals(
                "PAYMENT_MISMATCH",
                assertThrows(BusinessException.class, () -> payments.query(orderId, userId))
                        .getCode());
        assertEquals("unknown", s(orders.owned(orderId, null), "payment_state"));
        assertEquals(20, sku("stock"));
        assertEquals(4, sku("reserved"));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) AS n FROM payments WHERE order_id=? AND is_deleted=0",
                        orderId));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) AS n FROM payment_events WHERE order_id=? AND result='failure' AND request_id LIKE ? AND is_deleted=0",
                        orderId,
                        tracePrefix + "%"));
        ((ObjectNode) channel.trades.get(number).get("amount")).put("total", 34600);
        payments.query(orderId, userId);
        payments.query(orderId, userId);
        assertEquals("paid", s(orders.owned(orderId, null), "payment_state"));
        assertEquals(16, sku("stock"));
        assertEquals(0, sku("reserved"));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) AS n FROM payments WHERE order_id=? AND is_deleted=0",
                        orderId));
        assertTrue(
                has(
                        db.one(
                                "SELECT verified_trade FROM payment_attempts WHERE order_id=? AND is_deleted=0",
                                orderId),
                        "verified_trade"));
    }

    @Test
    void liveRefundLeasePreventsConcurrentQueryWhileNetworkIsOutsideTransaction() throws Exception {
        ObjectNode order = paidWechatOrder();
        long orderId = l(order, "id");
        long refundId = l(requestRefund(refundBody(orderId, lineId(orderId), 1, 0)), "id");
        approve(refundId);
        channel.refundEntered = new CountDownLatch(1);
        channel.refundContinue = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ObjectNode> first =
                    executor.submit(
                            () -> inRequest(() -> aftersales.channel(refundId, reason(), false)));
            assertTrue(channel.refundEntered.await(5, TimeUnit.SECONDS));
            assertTrue(has(aftersales.getRefund(refundId), "channel_token"));
            assertThrows(
                    BusinessException.class, () -> aftersales.channel(refundId, reason(), true));
            assertEquals(0, channel.refundQueries.get());
            channel.refundContinue.countDown();
            assertEquals("success", s(first.get(10, TimeUnit.SECONDS), "status"));
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) AS n FROM reward_reversals WHERE refund_id=? AND is_deleted=0",
                            refundId));
        } finally {
            channel.refundContinue.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void expiredLeaseCanQueryOriginalRefundAndLateFailureCannotOverwriteSuccess() throws Exception {
        ObjectNode order = paidWechatOrder();
        long orderId = l(order, "id");
        long refundId = l(requestRefund(refundBody(orderId, lineId(orderId), 1, 0)), "id");
        approve(refundId);
        channel.refundEntered = new CountDownLatch(1);
        channel.refundContinue = new CountDownLatch(1);
        channel.refundFailure = new BusinessException(502, "CHANNEL_ERROR", "旧请求迟到的超时响应");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ObjectNode> first =
                    executor.submit(
                            () -> inRequest(() -> aftersales.channel(refundId, reason(), false)));
            assertTrue(channel.refundEntered.await(5, TimeUnit.SECONDS));
            db.tx(
                    () ->
                            db.update(
                                    "UPDATE refunds SET channel_until=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    Instant.now().minusSeconds(1).toString(),
                                    staffId,
                                    refundId));
            assertEquals("success", s(aftersales.channel(refundId, reason(), true), "status"));
            channel.refundContinue.countDown();
            assertEquals("success", s(first.get(10, TimeUnit.SECONDS), "status"));
            ObjectNode stored = aftersales.getRefund(refundId);
            assertEquals("success", s(stored, "status"));
            assertEquals("", s(stored, "last_error"));
            assertFalse(has(stored, "channel_token"));
            assertEquals(1, l(line(orderId), "refunded_qty"));
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) AS n FROM reward_reversals WHERE refund_id=? AND is_deleted=0",
                            refundId));
            assertRefundAccounting(orderId, 0, 4200);
        } finally {
            channel.refundContinue.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** 显式测试渠道：只模拟微信网络，不替换真实金额核对、持久租约、事务及跨域补偿。 */
    private static class TestWechatChannel extends WechatPayClient {
        /** 渠道交易样本，商户、应用、付款人和金额由真实支付发起参数生成。 */
        private final Map<String, ObjectNode> trades = new ConcurrentHashMap<>();
        /** 持久退款单号对应的渠道退款事实，用于模拟响应丢失后查单。 */
        private final Map<String, ObjectNode> refunds = new ConcurrentHashMap<>();
        /** 发起退款调用次数。 */
        private final AtomicInteger refundCalls = new AtomicInteger();
        /** 查询原退款单调用次数。 */
        private final AtomicInteger refundQueries = new AtomicInteger();
        /** 可空，模拟本次发起退款响应异常；查询仍可核实已保存的渠道事实。 */
        private volatile RuntimeException refundFailure;
        /** 可空，并发用例的网络进入信号。 */
        private volatile CountDownLatch refundEntered;
        /** 可空，并发用例的网络返回信号，最多等待十秒。 */
        private volatile CountDownLatch refundContinue;

        TestWechatChannel() {
            super(new MockEnvironment());
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public String appId() {
            return "payments-mysql-app";
        }

        @Override
        public String mchId() {
            return "payments-mysql-mch";
        }

        @Override
        public void requireConfigured() {
            /* 本类仅存在于测试包，生产渠道配置校验保持真实实现。 */
        }

        private void outsideTransaction() {
            assertFalse(
                    TransactionSynchronizationManager.isActualTransactionActive(), "微信网络必须位于事务外");
        }

        @Override
        public JsonNode create(JsonNode input) {
            outsideTransaction();
            String number = s(input, "number");
            trades.put(
                    number,
                    obj(
                            "out_trade_no",
                            number,
                            "mchid",
                            mchId(),
                            "appid",
                            appId(),
                            "trade_state",
                            "NOTPAY",
                            "amount",
                            obj("total", l(input, "cents"), "currency", "CNY"),
                            "payer",
                            obj("openid", s(input, "openid"))));
            return obj(
                    "timeStamp",
                    "1",
                    "nonceStr",
                    "test",
                    "package",
                    "prepay_id=" + number,
                    "signType",
                    "RSA",
                    "paySign",
                    "测试渠道参数");
        }

        void paid(String number) {
            trades.get(number).put("trade_state", "SUCCESS").put("transaction_id", "wx-" + number);
        }

        @Override
        public JsonNode query(String number) {
            outsideTransaction();
            return trades.get(number).deepCopy();
        }

        @Override
        public void close(String number) {
            outsideTransaction();
            trades.get(number).put("trade_state", "CLOSED");
        }

        @Override
        public JsonNode refund(JsonNode input) {
            outsideTransaction();
            refundCalls.incrementAndGet();
            ObjectNode result =
                    obj(
                            "status",
                            "success",
                            "outRefundNo",
                            s(input, "outRefundNo"),
                            "outTradeNo",
                            s(input, "outTradeNo"),
                            "totalCents",
                            l(input, "totalCents"),
                            "refundCents",
                            l(input, "refundCents"),
                            "refundId",
                            "wx-" + s(input, "outRefundNo"));
            refunds.put(s(input, "outRefundNo"), result);
            if (refundEntered != null) refundEntered.countDown();
            if (refundContinue != null)
                try {
                    assertTrue(refundContinue.await(10, TimeUnit.SECONDS), "测试网络等待超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试网络等待被中断", e);
                }
            if (refundFailure != null) throw refundFailure;
            return result.deepCopy();
        }

        @Override
        public JsonNode queryRefund(String number) {
            outsideTransaction();
            refundQueries.incrementAndGet();
            return refunds.get(number).deepCopy();
        }
    }
}
