package com.jianyanji.orders;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.catalog.CatalogService;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import com.jianyanji.payments.PaymentService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 真实 MySQL 8 回归：核心数据库和三个领域服务均为真实实现，支付和请求上下文不连接外部渠道。 */
@EnabledIfEnvironmentVariable(named = "ORDERS_TEST_MYSQL_URL", matches = ".+")
class OrdersMysqlTest {
    /** 真实 JDBC 与 REQUIRED 事务组件，每个工作线程使用独立数据库连接。 */
    private Db db;
    /** 真实商品服务。 */
    private CatalogService catalog;
    /** 真实报价和订单服务。 */
    private OrderService orders;
    /** 真实履约服务。 */
    private FulfillmentService fulfillment;
    /** 仅替代 HTTP 操作人与审计，不替代领域结果。 */
    private Access access;
    /** 测试前原有的共享经营配置，结束后恢复，不物理删除。 */
    private final Map<String, ObjectNode> previousSettings = new LinkedHashMap<>();
    /** 本用例独立用户，作为所有样例行的创建人。 */
    private ObjectNode user;
    /** 本用例用户编号。 */
    private long userId;
    /** 本用例分类编号。 */
    private long categoryId;
    /** 本用例商品编号。 */
    private long productId;
    /** 本用例规格编号。 */
    private long skuId;
    /** 本用例本人收货地址编号。 */
    private long addressId;

    @BeforeEach
    void setup() {
        String url = System.getenv("ORDERS_TEST_MYSQL_URL");
        boolean legacyTestDatabase =
                url.matches(
                        "^jdbc:mysql://(127\\.0\\.0\\.1|localhost):3308/jianyanji_schema_check(?:\\?.*)?$");
        boolean dedicatedTestDatabase =
                url.matches(
                        "^jdbc:mysql://(127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?/java_rewards_test_[A-Za-z0-9_]+(?:\\?.*)?$");
        assertTrue(
                legacyTestDatabase || dedicatedTestDatabase,
                "只允许本机 java_rewards_test_ 专用库或原 3308/jianyanji_schema_check 隔离库");
        DriverManagerDataSource source =
                new DriverManagerDataSource(
                        url,
                        System.getenv().getOrDefault("ORDERS_TEST_MYSQL_USER", "root"),
                        System.getenv().getOrDefault("ORDERS_TEST_MYSQL_PASSWORD", ""));
        db = new Db(new JdbcTemplate(source), new DataSourceTransactionManager(source));
        ObjectNode database = db.one("SELECT DATABASE() AS db_name,VERSION() AS mysql_version");
        String databaseName = s(database, "db_name");
        assertTrue(
                (dedicatedTestDatabase && databaseName.matches("java_rewards_test_[A-Za-z0-9_]+"))
                        || (legacyTestDatabase && "jianyanji_schema_check".equals(databaseName)),
                "实际连接库必须仍为已授权的专用测试库");
        assertTrue(s(database, "mysql_version").startsWith("8.0."));
        access = mock(Access.class);
        when(access.requestId()).thenReturn("orders-mysql-" + id());
        catalog = new CatalogService(db, access);
        orders = new OrderService(db, access, catalog, mock(PaymentService.class));
        fulfillment = new FulfillmentService(db, orders, access);
        db.tx(
                () -> {
                    userId =
                            db.insert(
                                    "INSERT INTO users(openid,nickname,create_user_id,update_user_id) VALUES(?,?,0,0)",
                                    "orders-mysql-" + id(),
                                    "订单回归独立用户");
                    user = db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", userId);
                    for (String key : Arrays.asList("shipping", "aftersales", "content")) {
                        previousSettings.put(
                                key,
                                db.one(
                                        "SELECT * FROM settings WHERE `key`=? AND is_deleted=0",
                                        key));
                    }
                    orders.saveShipping(shipping(1000), userId);
                    db.putSetting("aftersales", obj("days", 7, "policy", "成交时原售后政策"), userId);
                    categoryId =
                            l(
                                    catalog.saveCategory(
                                            null,
                                            obj(
                                                    "name", "订单回归类目", "sort", 1, "active", true,
                                                    "reason", "准备隔离样例"),
                                            userId),
                                    "id");
                    productId =
                            l(
                                    catalog.saveProduct(null, productBody(null, 10, 10000), userId),
                                    "id");
                    skuId =
                            l(
                                    db.one(
                                            "SELECT id FROM skus WHERE product_id=? AND is_deleted=0",
                                            productId),
                                    "id");
                    addressId = l(orders.saveAddress(null, address("原地址1号", true), userId), "id");
                });
    }

    /** 只逻辑移除本用例创建的数据；共享设置恢复原值，不改其他用例数据或结构。 */
    @AfterEach
    void cleanup() {
        if (db == null || userId == 0) return;
        db.tx(
                () -> {
                    for (Map.Entry<String, ObjectNode> entry : previousSettings.entrySet()) {
                        ObjectNode old = entry.getValue();
                        if (old == null)
                            db.update(
                                    "UPDATE settings SET is_deleted=1,update_user_id=0,update_time=CURRENT_TIMESTAMP(3) WHERE `key`=? AND create_user_id=? AND is_deleted=0",
                                    entry.getKey(),
                                    userId);
                        else
                            db.update(
                                    "UPDATE settings SET value=?,version=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    s(old, "value"),
                                    l(old, "version"),
                                    l(old, "update_user_id"),
                                    l(old, "id"));
                    }
                    for (String table :
                            Arrays.asList(
                                    "shipment_corrections",
                                    "shipments",
                                    "refunds",
                                    "order_lines",
                                    "orders",
                                    "quotes",
                                    "cart_items",
                                    "addresses",
                                    "skus",
                                    "products",
                                    "categories")) {
                        db.update(
                                "UPDATE "
                                        + table
                                        + " SET is_deleted=1,update_user_id=0,update_time=CURRENT_TIMESTAMP(3) WHERE create_user_id=? AND is_deleted=0",
                                userId);
                    }
                    db.update(
                            "UPDATE users SET is_deleted=1,update_user_id=0,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            userId);
                });
    }

    /** 配送设置请求，金额单位分。 */
    private ObjectNode shipping(long fee) {
        return obj(
                "provinces",
                arr("浙江省"),
                "feeCents",
                fee,
                "instructions",
                "测试品牌仓配送",
                "pendingMinutes",
                30,
                "reason",
                "测试配送设置");
    }

    /** 收货地址请求。 */
    private ObjectNode address(String detail, boolean primary) {
        return obj(
                "recipient",
                "测试收货人",
                "phone",
                "13800000000",
                "province",
                "浙江省",
                "city",
                "杭州市",
                "district",
                "西湖区",
                "detail",
                detail,
                "isDefault",
                primary);
    }

    /** 完整商品请求；规格编号为空时新建，否则维护当前规格。 */
    private ObjectNode productBody(Long existingSku, long stock, long retail) {
        ObjectNode sku =
                obj(
                        "name",
                        "30ml",
                        "unit",
                        "瓶",
                        "retailCents",
                        retail,
                        "productType",
                        "regular",
                        "welfareCents",
                        null,
                        "stock",
                        stock,
                        "active",
                        true);
        if (existingSku != null) {
            sku.put("id", existingSku);
            // 已有规格默认只维护资料，库存调整由用例显式提交新值及原值。
            sku.remove("stock");
        }
        return obj(
                "categoryId",
                categoryId,
                "name",
                "隔离回归精华",
                "subtitle",
                "测试",
                "description",
                "真实 MySQL 回归商品",
                "image",
                "/assets/test.png",
                "detailImage",
                "https://example.com/detail.png",
                "active",
                true,
                "featured",
                true,
                "skus",
                arr(sku),
                "reason",
                "测试商品维护");
    }

    /** 通过真实服务生成报价。 */
    private ObjectNode quote(long quantity) {
        return orders.quote(
                obj(
                        "addressId",
                        addressId,
                        "items",
                        arr(obj("skuId", skuId, "quantity", quantity))),
                user);
    }

    /** 通过真实报价与建单 SQL 创建待支付订单。 */
    private ObjectNode create(long quantity) {
        return orders.create(obj("quoteId", l(quote(quantity), "id"), "requestKey", id()), user);
    }

    @Test
    void creatingOrderRemovesOnlyItsCartItemsAndRetryKeepsNewAdditions() {
        long otherProduct = l(catalog.saveProduct(null, productBody(null, 10, 10000), userId), "id");
        long otherSku = l(db.one("SELECT id FROM skus WHERE product_id=? AND is_deleted=0", otherProduct), "id");
        long otherUser = db.insert(
                "INSERT INTO users(openid,nickname,create_user_id,update_user_id) VALUES(?,?,?,?)",
                "cart-other-" + id(), "其他购物车用户", userId, userId);
        try {
            orders.saveCart(skuId, obj("quantity", 2, "selected", true), user);
            orders.saveCart(otherSku, obj("quantity", 1, "selected", false), user);
            db.insert("INSERT INTO cart_items(user_id,sku_id,quantity,selected,create_user_id,update_user_id) VALUES(?,?,1,1,?,?)",
                    otherUser, skuId, userId, userId);
            long cartId = l(db.one("SELECT id FROM cart_items WHERE user_id=? AND sku_id=? AND is_deleted=0", userId, skuId), "id");
            ObjectNode body = obj("quoteId", l(quote(2), "id"), "requestKey", id());

            ObjectNode order = orders.create(body, user);

            assertEquals(1, orders.cart(user).path("items").size(), "下单后只保留未购买商品");
            assertEquals(otherSku, orders.cart(user).path("items").get(0).path("skuId").asLong());
            ObjectNode removed = db.one("SELECT * FROM cart_items WHERE id=?", cartId);
            assertEquals(1, l(removed, "is_deleted"));
            assertEquals(userId, l(removed, "update_user_id"));
            assertNotNull(db.one("SELECT id FROM cart_items WHERE user_id=? AND sku_id=? AND is_deleted=0", otherUser, skuId));

            orders.saveCart(skuId, obj("quantity", 1, "selected", true), user);
            assertEquals(l(order, "id"), l(orders.create(body, user), "id"));
            assertEquals(2, orders.cart(user).path("items").size(), "重试原订单不能清掉后来重新加购的商品");
        } finally {
            db.update("UPDATE cart_items SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE user_id=? AND is_deleted=0", userId, otherUser);
            db.update("UPDATE users SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0", userId, otherUser);
        }
    }

    @Test
    void failedOrderRollsBackCartRemovalAndInventoryReservation() {
        orders.saveCart(skuId, obj("quantity", 2, "selected", true), user);
        ObjectNode body = obj("quoteId", l(quote(2), "id"), "requestKey", id());
        doThrow(new IllegalStateException("模拟订单审计写入失败"))
                .when(access).audit(eq("order.create"), eq("order"), anyLong(), anyString());

        assertThrows(IllegalStateException.class, () -> orders.create(body, user));

        assertEquals(1, orders.cart(user).path("items").size());
        assertEquals(2, orders.cart(user).path("items").get(0).path("quantity").asInt());
        assertEquals(0, l(db.one("SELECT reserved FROM skus WHERE id=?", skuId), "reserved"));
        assertNull(db.one("SELECT id FROM orders WHERE user_id=? AND request_key=? AND is_deleted=0", userId, s(body, "requestKey")));
    }

    /** 只准备已付款库存前置事实以验证履约；不调用或声称验证支付结算、渠道到账。 */
    private ObjectNode paidOrder(long quantity) {
        ObjectNode order = create(quantity);
        db.tx(
                () -> {
                    assertEquals(
                            1,
                            db.update(
                                    "UPDATE skus SET stock=stock-?,reserved=reserved-?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0 AND reserved>=?",
                                    quantity,
                                    quantity,
                                    userId,
                                    skuId,
                                    quantity));
                    db.update(
                            "UPDATE orders SET status='paid',payment_state='paid',payment_method='wechat',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            userId,
                            l(order, "id"));
                });
        return orders.view(orders.owned(l(order, "id"), userId));
    }

    /** 发货请求，按本次创建的订单明细编号。 */
    private ObjectNode shipment(long lineId, long quantity) {
        return obj(
                "carrier",
                "顺丰",
                "tracking",
                "SF" + id(),
                "lines",
                arr(obj("lineId", lineId, "quantity", quantity)),
                "requestKey",
                id(),
                "reason",
                "测试实际分包出库");
    }

    /** 准备明确的售后状态前置事实，退款金额与本订单单价一致；不模拟渠道退款结果。 */
    private long refund(long orderId, long lineId, long quantity, String state) {
        return db.tx(
                () ->
                        db.insert(
                                "INSERT INTO refunds(order_id,number,kind,status,goods_cents,shipping_cents,total_cents,`lines`,reason,policy_snapshot,request_key,request_id,create_user_id,update_user_id) VALUES(?,?,?,?,?,0,?,?,?,?,?,?,?,?)",
                                orderId,
                                "RFTEST" + id(),
                                "refund",
                                state,
                                quantity * 10000,
                                quantity * 10000,
                                stringify(arr(obj("lineId", lineId, "quantity", quantity))),
                                "测试售后占用前置条件",
                                stringify(obj("days", 7, "policy", "成交时原售后政策")),
                                id(),
                                "orders-mysql",
                                userId,
                                userId));
    }

    /** 最多五个线程同时起跑，SQL 异常不转业务结果，直接让测试失败。 */
    private List<ObjectNode> compete(List<Callable<ObjectNode>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CyclicBarrier start = new CyclicBarrier(calls.size());
        List<Future<ObjectNode>> futures = new ArrayList<>();
        try {
            for (Callable<ObjectNode> call : calls)
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await(5, TimeUnit.SECONDS);
                                    try {
                                        return obj("status", 200, "order", call.call());
                                    } catch (BusinessException exception) {
                                        return obj(
                                                "status",
                                                exception.getStatus(),
                                                "code",
                                                exception.getCode(),
                                                "message",
                                                exception.getMessage());
                                    }
                                }));
            List<ObjectNode> result = new ArrayList<>();
            for (Future<ObjectNode> future : futures) result.add(future.get(20, TimeUnit.SECONDS));
            return result;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "并发工作线程必须全部结束后再清理样例");
        }
    }

    /** 读取当前规格，断言数量来自真实数据库。 */
    private ObjectNode sku() {
        return db.one("SELECT * FROM skus WHERE id=? AND is_deleted=0", skuId);
    }

    @Test
    void catalogContentAddressAndCartExecuteRealSqlAndAllowReaddingRemovedItems() {
        ObjectNode content =
                obj(
                        "brandName",
                        "清欣简妍肌测试",
                        "introduction",
                        "回归介绍",
                        "heroTitle",
                        "标题",
                        "heroSubtitle",
                        "副标题",
                        "heroImage",
                        "/assets/hero.png",
                        "customerService",
                        "客服",
                        "purchaseNotes",
                        "购买说明",
                        "companyName",
                        "",
                        "bankName",
                        "",
                        "bankAccount",
                        "",
                        "transferNotes",
                        "",
                        "reason",
                        "隔离内容回归");
        catalog.saveContent(content, userId);
        assertEquals("回归介绍", catalog.publicContent().path("content").path("introduction").asText());
        assertEquals(
                10000,
                catalog.product(productId, user).path("skus").get(0).path("priceCents").asInt());
        assertTrue(catalog.products(categoryId, user).path("items").size() > 0);
        orders.saveCart(skuId, obj("quantity", 2, "selected", true), user);
        orders.saveCart(skuId, obj("quantity", 3, "selected", false), user);
        assertEquals(1, orders.cart(user).path("items").size());
        assertEquals(3, orders.cart(user).path("items").get(0).path("quantity").asInt());
        orders.removeCart(skuId, userId);
        orders.saveCart(skuId, obj("quantity", 1, "selected", true), user);
        assertEquals(1, orders.cart(user).path("items").size());
        long nextAddress = l(orders.saveAddress(null, address("新默认地址", true), userId), "id");
        assertEquals(
                1,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM addresses WHERE user_id=? AND is_default=1 AND is_deleted=0",
                                userId),
                        "n"));
        orders.removeAddress(nextAddress, userId);
        assertEquals(1, orders.addresses(userId).size());
        assertEquals(
                404,
                assertThrows(
                                BusinessException.class,
                                () ->
                                        orders.saveAddress(
                                                addressId, address("越权", true), userId + 1000000))
                        .getStatus());
        catalog.saveCategory(
                categoryId,
                obj("name", "停售类目", "sort", 1, "active", false, "reason", "停售测试"),
                userId);
        assertTrue(catalog.products(categoryId, user).path("items").isEmpty());
        assertFalse(orders.cart(user).path("items").get(0).path("available").asBoolean());
        catalog.removeProduct(productId, obj("reason", "测试逻辑删除"), userId);
        assertEquals(
                1,
                l(db.one("SELECT is_deleted FROM products WHERE id=?", productId), "is_deleted"));
    }

    /** 验证 V3 扩容后，两个图片字段达到原接口1000字符上限时新增和更新均不会截断。 */
    @Test
    void thousandCharacterProductImagesPersistAndReadBackOnCreateAndUpdate() {
        String prefix = "https://example.com/images/";
        char[] imagePath = new char[1000 - prefix.length()];
        char[] detailPath = new char[1000 - prefix.length()];
        Arrays.fill(imagePath, 'a');
        Arrays.fill(detailPath, 'b');
        String image = prefix + new String(imagePath);
        String detailImage = prefix + new String(detailPath);
        assertEquals(1000, image.length());
        assertEquals(1000, detailImage.length());

        ObjectNode body = productBody(null, 10, 10000);
        body.put("image", image);
        body.put("detailImage", detailImage);
        long createdId = l(catalog.saveProduct(null, body, userId), "id");
        ObjectNode created = catalog.product(createdId, user);
        assertEquals(image, s(created, "image"), "新增后的列表图必须完整回读1000字符");
        assertEquals(detailImage, s(created, "detailImage"), "新增后的详情图必须完整回读1000字符");

        long createdSku = created.path("skus").get(0).path("id").asLong();
        ObjectNode updatedBody = productBody(createdSku, 10, 10000);
        updatedBody.put("image", detailImage);
        updatedBody.put("detailImage", image);
        catalog.saveProduct(createdId, updatedBody, userId);
        ObjectNode updated = catalog.product(createdId, user);
        assertEquals(detailImage, s(updated, "image"), "更新后的列表图必须完整回读新值");
        assertEquals(image, s(updated, "detailImage"), "更新后的详情图必须完整回读新值");
    }

    @Test
    void expiredQuoteDoesNotReserveAndExpiryReleasesOnlyConfirmedUnpaidOrdersOnce() {
        ObjectNode oldQuote = quote(2);
        db.tx(
                () ->
                        db.update(
                                "UPDATE quotes SET expires_at='2020-01-01T00:00:00.000Z',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                userId,
                                l(oldQuote, "id")));
        assertEquals(
                "确认信息已过期，请重新结算",
                assertThrows(
                                BusinessException.class,
                                () ->
                                        orders.create(
                                                obj(
                                                        "quoteId",
                                                        l(oldQuote, "id"),
                                                        "requestKey",
                                                        id()),
                                                user))
                        .getMessage());
        assertEquals(0, l(sku(), "reserved"));
        ObjectNode unpaid = create(2), processing = create(3);
        db.tx(
                () -> {
                    db.update(
                            "UPDATE orders SET expires_at='2020-01-01T00:00:00.000Z',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE user_id=? AND is_deleted=0",
                            userId,
                            userId);
                    db.update(
                            "UPDATE orders SET payment_state='processing',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            userId,
                            l(processing, "id"));
                });
        orders.expire();
        orders.expire();
        assertEquals("closed", s(orders.owned(l(unpaid, "id"), userId), "status"));
        assertEquals("pending", s(orders.owned(l(processing, "id"), userId), "status"));
        assertFalse(orders.release(unpaid));
        assertEquals(3, l(sku(), "reserved"));
    }

    @Test
    void
            quotePolicyChangesRequireReconfirmationAndOrderKeepsOriginalPolicyAddressAndPrincipalPrice() {
        db.tx(
                () ->
                        db.update(
                                "UPDATE users SET identity='C',principal_id=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                userId,
                                userId,
                                userId));
        user = db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", userId);
        ObjectNode obsolete = quote(2);
        orders.saveShipping(shipping(2000), userId);
        assertEquals(
                "QUOTE_CHANGED",
                assertThrows(
                                BusinessException.class,
                                () ->
                                        orders.create(
                                                obj(
                                                        "quoteId",
                                                        l(obsolete, "id"),
                                                        "requestKey",
                                                        id()),
                                                user))
                        .getCode());
        ObjectNode acceptedQuote = quote(2);
        String key = id();
        ObjectNode order =
                orders.create(obj("quoteId", l(acceptedQuote, "id"), "requestKey", key), user);
        db.tx(() -> db.putSetting("aftersales", obj("days", 30, "policy", "后来更新的售后政策"), userId));
        orders.saveAddress(addressId, address("已变更地址", true), userId);
        ObjectNode product = productBody(skuId, 10, 11000);
        ((ObjectNode) product.path("skus").get(0)).put("productType", "welfare");
        ((ObjectNode) product.path("skus").get(0)).put("welfareCents", 1234);
        catalog.saveProduct(productId, product, userId);
        db.tx(
                () ->
                        db.update(
                                "UPDATE users SET identity='D',principal_id=NULL,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                userId,
                                userId));
        ObjectNode historical = orders.detail(l(order, "id"), userId);
        JsonNode snapshot = historical.path("snapshot");
        assertEquals("C", snapshot.path("identity").asText());
        assertEquals(userId, snapshot.path("principalId").asLong());
        assertEquals(3500, snapshot.path("lines").get(0).path("principalCents").asLong());
        assertEquals(7000, snapshot.path("lines").get(0).path("unitCents").asLong());
        assertEquals(7, snapshot.path("afterSalesDays").asInt());
        assertEquals("成交时原售后政策", snapshot.path("afterSalesPolicy").asText());
        assertEquals("原地址1号", snapshot.path("address").path("detail").asText());
        assertEquals(2000, snapshot.path("shippingCents").asInt());
        assertEquals(
                l(order, "id"),
                l(
                        orders.create(
                                obj("quoteId", l(acceptedQuote, "id"), "requestKey", key), user),
                        "id"));
    }

    @Test
    void fiveConcurrentOrdersCannotReserveMoreThanRealStock() throws Exception {
        List<Callable<ObjectNode>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long quoteId = l(quote(3), "id");
            String key = id();
            calls.add(() -> orders.create(obj("quoteId", quoteId, "requestKey", key), user));
        }
        List<ObjectNode> result = compete(calls);
        assertEquals(3, result.stream().filter(row -> l(row, "status") == 200).count());
        assertEquals(
                2,
                result.stream()
                        .filter(
                                row ->
                                        l(row, "status") == 409
                                                && s(row, "message").equals("商品库存不足"))
                        .count());
        assertEquals(9, l(sku(), "reserved"));
        assertEquals(10, l(sku(), "stock"));
        assertEquals(
                3,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM orders WHERE user_id=? AND is_deleted=0",
                                userId),
                        "n"));
        assertEquals(
                9,
                l(
                        db.one(
                                "SELECT SUM(quantity) AS n FROM order_lines WHERE create_user_id=? AND is_deleted=0",
                                userId),
                        "n"));
    }

    @Test
    void fiveConcurrentReplaysCreateOneOrderAndReserveOnce() throws Exception {
        long quoteId = l(quote(4), "id");
        String key = id();
        ObjectNode body = obj("quoteId", quoteId, "requestKey", key);
        List<Callable<ObjectNode>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) calls.add(() -> orders.create(body, user));
        List<ObjectNode> result = compete(calls);
        assertTrue(result.stream().allMatch(row -> l(row, "status") == 200));
        assertEquals(1, result.stream().map(row -> l(row.path("order"), "id")).distinct().count());
        assertEquals(4, l(sku(), "reserved"));
        assertEquals(
                1,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM orders WHERE user_id=? AND is_deleted=0",
                                userId),
                        "n"));
        long anotherQuote = l(quote(1), "id");
        assertEquals(
                "请求编号已用于其他订单",
                assertThrows(
                                BusinessException.class,
                                () ->
                                        orders.create(
                                                obj("quoteId", anotherQuote, "requestKey", key),
                                                user))
                        .getMessage());
    }

    @Test
    void failureAfterOrderAndLineInsertionRollsBackHeaderLinesAndReservations() {
        long quoteId = l(quote(3), "id");
        String key = id();
        doThrow(new IllegalStateException("显式注入末尾审计失败以验证事务回滚"))
                .when(access)
                .audit(eq("order.create"), eq("order"), any(), anyString());
        assertThrows(
                IllegalStateException.class,
                () -> orders.create(obj("quoteId", quoteId, "requestKey", key), user));
        assertEquals(0, l(sku(), "reserved"));
        assertEquals(
                0,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM orders WHERE user_id=? AND is_deleted=0",
                                userId),
                        "n"));
        assertEquals(
                0,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM order_lines WHERE create_user_id=? AND is_deleted=0",
                                userId),
                        "n"));
        doNothing().when(access).audit(eq("order.create"), eq("order"), any(), anyString());
        assertEquals(
                3,
                orders.create(obj("quoteId", quoteId, "requestKey", key), user)
                        .path("lines")
                        .get(0)
                        .path("quantity")
                        .asInt());
        assertEquals(3, l(sku(), "reserved"));
    }

    @Test
    void reservedSkuMaintenanceFailureRollsBackProductUpdate() {
        create(3);
        ObjectNode before =
                db.one("SELECT name,version FROM products WHERE id=? AND is_deleted=0", productId);
        ObjectNode product = productBody(skuId, 2, 11000);
        ((ObjectNode) product.path("skus").get(0)).put("stock", 2).put("expectedStock", 10);
        product.put("name", "必须回滚的改名");
        assertEquals(
                "库存不能低于待付款占用数量",
                assertThrows(
                                BusinessException.class,
                                () -> catalog.saveProduct(productId, product, userId))
                        .getMessage());
        assertEquals(
                before,
                db.one("SELECT name,version FROM products WHERE id=? AND is_deleted=0", productId));
        assertEquals(3, l(sku(), "reserved"));
        assertThrows(
                BusinessException.class,
                () -> catalog.removeProduct(productId, obj("reason", "测试不可移除"), userId));
    }

    /** 使用真实结算入口扣除库存，验证编辑页与销售交错时的库存一致性。 */
    private void settleStock(long quantity) {
        ObjectNode order = create(quantity);
        PaymentService realPayments = new PaymentService(db, access, orders,
                mock(com.jianyanji.payments.WechatPayClient.class));
        db.tx(() -> realPayments.settlePaid(order, "offline", "stock-regression:" + id()));
    }

    @Test
    void editingProductDetailsPreservesStockSoldAfterEditorOpened() {
        ObjectNode editor = productBody(skuId, 10, 10000);
        editor.put("name", "只修改资料");
        settleStock(3);

        catalog.saveProduct(productId, editor, userId);

        assertEquals(7, l(sku(), "stock"));
        assertEquals("只修改资料", s(db.one("SELECT name FROM products WHERE id=?", productId), "name"));
    }

    @Test
    void staleStockAdjustmentAndLegacyEditorCannotRestoreSoldStock() {
        ObjectNode before = db.one("SELECT name,version FROM products WHERE id=?", productId);
        ObjectNode editor = productBody(skuId, 10, 10000);
        editor.put("name", "应当回滚的修改");
        ObjectNode editedSku = (ObjectNode) editor.path("skus").get(0);
        settleStock(3);

        editedSku.put("stock", 10);
        assertEquals("STOCK_CHANGED", assertThrows(BusinessException.class,
                () -> catalog.saveProduct(productId, editor, userId)).getCode());
        editedSku.put("stock", 12).put("expectedStock", 10);
        assertEquals("STOCK_CHANGED", assertThrows(BusinessException.class,
                () -> catalog.saveProduct(productId, editor, userId)).getCode());
        assertEquals(7, l(sku(), "stock"));
        assertEquals(before, db.one("SELECT name,version FROM products WHERE id=?", productId));
    }

    @Test
    void stockAdjustmentWithCurrentValueSucceedsWithoutChangingReservations() {
        create(3);
        ObjectNode editor = productBody(skuId, 10, 10000);
        ((ObjectNode) editor.path("skus").get(0)).put("stock", 12).put("expectedStock", 10);

        catalog.saveProduct(productId, editor, userId);

        assertEquals(12, l(sku(), "stock"));
        assertEquals(3, l(sku(), "reserved"));
    }

    @Test
    void concurrentStockAdjustmentsFromSameOriginalValueCannotOverwriteEachOther() throws Exception {
        ObjectNode first = productBody(skuId, 10, 10000);
        ObjectNode second = first.deepCopy();
        ((ObjectNode) first.path("skus").get(0)).put("stock", 12).put("expectedStock", 10);
        ((ObjectNode) second.path("skus").get(0)).put("stock", 15).put("expectedStock", 10);

        List<ObjectNode> results = compete(Arrays.asList(
                () -> catalog.saveProduct(productId, first, userId),
                () -> catalog.saveProduct(productId, second, userId)));

        assertEquals(1, results.stream().filter(row -> l(row, "status") == 200).count());
        assertEquals(1, results.stream().filter(row -> l(row, "status") == 409).count());
        assertTrue(l(sku(), "stock") == 12 || l(sku(), "stock") == 15);
    }

    @Test
    void partialShipmentRefundHoldsAndPartialReceiptUseOnlyRemainingEffectiveQuantity() {
        ObjectNode order = paidOrder(6);
        long orderId = l(order, "id"), lineId = order.path("lines").get(0).path("id").asLong();
        long parcelA = l(fulfillment.ship(orderId, shipment(lineId, 2), userId), "id");
        long parcelB = l(fulfillment.ship(orderId, shipment(lineId, 1), userId), "id");
        long refundId = refund(orderId, lineId, 2, "requested");
        db.tx(
                () ->
                        db.update(
                                "UPDATE order_lines SET refund_held=2,held_unshipped=2,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                userId,
                                lineId));
        assertEquals(1, l(fulfillment.shippableLines(orderId).get(0), "remaining"));
        assertThrows(
                BusinessException.class,
                () -> fulfillment.ship(orderId, shipment(lineId, 2), userId));
        assertThrows(BusinessException.class, () -> fulfillment.receive(orderId, parcelA, userId));
        db.tx(
                () -> {
                    db.update(
                            "UPDATE refunds SET status='success',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            userId,
                            refundId);
                    db.update(
                            "UPDATE order_lines SET refunded_qty=2,refunded_unshipped=2,refund_held=0,held_unshipped=0,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            userId,
                            lineId);
                    db.update(
                            "UPDATE orders SET payment_state='partially_refunded',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            userId,
                            orderId);
                });
        long parcelC = l(fulfillment.ship(orderId, shipment(lineId, 1), userId), "id");
        assertEquals(0, l(fulfillment.shippableLines(orderId).get(0), "remaining"));
        assertEquals("shipped", s(fulfillment.receive(orderId, parcelA, userId), "status"));
        fulfillment.receive(orderId, parcelA, userId);
        assertEquals("shipped", s(fulfillment.receive(orderId, parcelC, userId), "status"));
        ObjectNode completed = fulfillment.receive(orderId, parcelB, userId);
        assertEquals("completed", s(completed, "status"));
        assertTrue(has(completed, "received_at"));
        assertEquals(4, completed.path("lines").get(0).path("received_qty").asInt());
        assertEquals(4, completed.path("lines").get(0).path("shipped_qty").asInt());
        assertEquals(3, completed.path("shipments").size());
    }

    @Test
    void everyUnresolvedRefundStateBlocksReceiptEvenWithoutQuantityHolds() {
        ObjectNode order = paidOrder(2);
        long orderId = l(order, "id"), lineId = order.path("lines").get(0).path("id").asLong();
        long parcelId = l(fulfillment.ship(orderId, shipment(lineId, 2), userId), "id");
        long refundId = refund(orderId, lineId, 1, "requested");
        for (String state :
                Arrays.asList("requested", "approved", "processing", "unknown", "failed")) {
            db.tx(
                    () ->
                            db.update(
                                    "UPDATE refunds SET status=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    state,
                                    userId,
                                    refundId));
            assertEquals(
                    "订单尚有未处理售后，请处理完成后确认收货",
                    assertThrows(
                                    BusinessException.class,
                                    () -> fulfillment.receive(orderId, parcelId, userId))
                            .getMessage(),
                    state);
        }
        assertEquals(
                0,
                l(
                        db.one(
                                "SELECT received_qty FROM order_lines WHERE id=? AND is_deleted=0",
                                lineId),
                        "received_qty"));
        db.tx(
                () ->
                        db.update(
                                "UPDATE refunds SET status='rejected',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                userId,
                                refundId));
        assertEquals("completed", s(fulfillment.receive(orderId, parcelId, userId), "status"));
    }

    @Test
    void shippedRefundBeforeReceiptMustNotCountUnshippedRemainderAsReceived() {
        ObjectNode order = paidOrder(6);
        long orderId = l(order, "id"), lineId = order.path("lines").get(0).path("id").asLong();
        long parcelId = l(fulfillment.ship(orderId, shipment(lineId, 4), userId), "id");
        // 售后先分配未发货数量：第一笔占用两件未发货商品，第二笔才可分配到两件已发货商品。
        // 第二笔验货退款成功、第一笔被驳回后，仍有两件未发货且没有未决售后，符合现有分配规则。
        long heldRefund = refund(orderId, lineId, 2, "requested");
        db.tx(
                () ->
                        db.update(
                                "UPDATE order_lines SET refund_held=2,held_unshipped=2,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                userId,
                                lineId));
        long returnedRefund = refund(orderId, lineId, 2, "approved");
        db.tx(
                () -> {
                    db.update(
                            "UPDATE refunds SET status='success',returned_at=?,completed_at=?,inspection='测试退货两件验收完成',`lines`=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            now(),
                            now(),
                            stringify(
                                    arr(
                                            obj(
                                                    "lineId",
                                                    lineId,
                                                    "skuId",
                                                    skuId,
                                                    "quantity",
                                                    2,
                                                    "unitCents",
                                                    10000,
                                                    "unshippedQuantity",
                                                    0,
                                                    "returnQuantity",
                                                    2))),
                            userId,
                            returnedRefund);
                    db.update(
                            "UPDATE refunds SET status='rejected',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            userId,
                            heldRefund);
                    db.update(
                            "UPDATE order_lines SET refunded_qty=2,refunded_unshipped=0,refund_held=0,held_unshipped=0,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            userId,
                            lineId);
                    db.update(
                            "UPDATE orders SET payment_state='partially_refunded',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            userId,
                            orderId);
                });
        assertEquals(
                2,
                l(fulfillment.shippableLines(orderId).get(0), "remaining"),
                "已发货退款不应再次减少仍未发出的两件");
        ObjectNode received = fulfillment.receive(orderId, parcelId, userId);
        assertAll(
                "六件中发出四件、其中退款两件后确认该包裹，应只计两件有效收货且还有两件待发",
                () ->
                        assertEquals(
                                2,
                                received.path("lines").get(0).path("received_qty").asLong(),
                                "未发出的两件不得计为收货"),
                () -> assertEquals("shipped", s(received, "status"), "尚有两件未发货，不得提前完成订单"),
                () -> assertFalse(has(received, "received_at"), "尚未完成时不得写订单完成收货时间"));
        long lastParcel = l(fulfillment.ship(orderId, shipment(lineId, 2), userId), "id");
        ObjectNode completed = fulfillment.receive(orderId, lastParcel, userId);
        assertEquals("completed", s(completed, "status"));
        assertEquals(4, completed.path("lines").get(0).path("received_qty").asLong());
    }

    @Test
    void receivedThenRefundedItemsCannotCompleteWhileValidItemsRemainUnshipped() {
        ObjectNode order = paidOrder(6);
        long orderId = l(order, "id"), lineId = order.path("lines").get(0).path("id").asLong();
        long parcelId = l(fulfillment.ship(orderId, shipment(lineId, 4), userId), "id");
        assertEquals("shipped", s(fulfillment.receive(orderId, parcelId, userId), "status"));
        // 已收货后的两件退货不改写累计历史收货；模拟已完成验收、无未决占用的售后最终事实。
        refund(orderId, lineId, 2, "success");
        db.tx(
                () -> {
                    db.update(
                            "UPDATE order_lines SET refunded_qty=2,refunded_unshipped=0,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            userId,
                            lineId);
                    db.update(
                            "UPDATE orders SET payment_state='partially_refunded',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            userId,
                            orderId);
                });
        orders.refreshFulfillment(orderId);
        ObjectNode waiting = orders.owned(orderId, userId);
        assertEquals("shipped", s(waiting, "status"));
        assertFalse(has(waiting, "received_at"));
        assertEquals(2, l(fulfillment.shippableLines(orderId).get(0), "remaining"));
        long lastParcel = l(fulfillment.ship(orderId, shipment(lineId, 2), userId), "id");
        assertEquals("completed", s(fulfillment.receive(orderId, lastParcel, userId), "status"));
    }

    @Test
    void fullyRefundedWithoutAnyActualReceiptCannotBecomeCompleted() {
        ObjectNode order = paidOrder(2);
        long orderId = l(order, "id"), lineId = order.path("lines").get(0).path("id").asLong();
        refund(orderId, lineId, 2, "success");
        db.tx(
                () -> {
                    db.update(
                            "UPDATE order_lines SET refunded_qty=2,refunded_unshipped=2,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            userId,
                            lineId);
                    db.update(
                            "UPDATE orders SET payment_state='partially_refunded',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            userId,
                            orderId);
                });
        orders.refreshFulfillment(orderId);
        ObjectNode refreshed = orders.owned(orderId, userId);
        assertNotEquals("completed", s(refreshed, "status"));
        assertFalse(has(refreshed, "received_at"));
        assertEquals(0, l(fulfillment.shippableLines(orderId).get(0), "remaining"));
    }

    @Test
    void invalidLaterShipmentLineRollsBackEarlierLineAndLogisticsCorrectionKeepsHistory() {
        ObjectNode order = paidOrder(3);
        long orderId = l(order, "id"), lineId = order.path("lines").get(0).path("id").asLong();
        ObjectNode invalid = shipment(lineId, 1);
        invalid.withArray("lines").add(obj("lineId", 9007199254740991L, "quantity", 1));
        assertThrows(BusinessException.class, () -> fulfillment.ship(orderId, invalid, userId));
        assertEquals(
                0,
                l(
                        db.one(
                                "SELECT shipped_qty FROM order_lines WHERE id=? AND is_deleted=0",
                                lineId),
                        "shipped_qty"));
        assertEquals(
                0,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM shipments WHERE order_id=? AND is_deleted=0",
                                orderId),
                        "n"));
        assertEquals("paid", s(orders.owned(orderId, userId), "status"));
        ObjectNode body = shipment(lineId, 2);
        ObjectNode parcel = fulfillment.ship(orderId, body, userId);
        long parcelId = l(parcel, "id");
        assertEquals(parcelId, l(fulfillment.ship(orderId, body, userId), "id"));
        assertEquals(
                404,
                assertThrows(
                                BusinessException.class,
                                () -> fulfillment.receive(orderId, parcelId, userId + 1000000))
                        .getStatus());
        fulfillment.correct(
                parcelId,
                obj("carrier", "新物流", "tracking", "CORRECT1234", "reason", "原物流录入错误"),
                userId);
        ObjectNode correction =
                (ObjectNode) fulfillment.fulfillment(orderId).path("corrections").get(0);
        assertEquals(s(body, "tracking"), s(correction, "old_tracking"));
        assertEquals("CORRECT1234", s(correction, "tracking"));
        assertEquals("原物流录入错误", s(correction, "reason"));
        assertFalse(s(correction, "request_id").isEmpty());
        ObjectNode view = orders.view(orders.owned(orderId, userId));
        assertEquals(2, view.path("lines").get(0).path("shipped_qty").asInt());
        assertEquals("CORRECT1234", view.path("shipments").get(0).path("tracking").asText());
        assertTrue(view.path("shipments").get(0).path("lines").isArray());
    }
}
