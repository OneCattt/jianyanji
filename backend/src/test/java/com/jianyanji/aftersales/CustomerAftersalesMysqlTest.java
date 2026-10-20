package com.jianyanji.aftersales;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import com.jianyanji.orders.OrderService;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;

/** 真实MySQL本人受理金额、权限与并发占用；由主线程先统一迁移专用库，本类不运行Flyway。 */
@EnabledIfEnvironmentVariable(named = "PAYMENTS_TEST_MYSQL_URL", matches = ".+")
class CustomerAftersalesMysqlTest {
    /** 真实事务及持久化。 */
    private Db db;
    /** 真实会话审计上下文。 */
    private Access access;
    /** 用户在线受理服务。 */
    private CustomerAftersalesService service;
    /** 本轮独立用户编号。 */
    private long userId;
    /** 本轮独立分类编号。 */
    private long categoryId;
    /** 本轮独立商品编号。 */
    private long productId;
    /** 本轮独立规格编号。 */
    private long skuId;
    /** 本轮真实支付快照的订单编号。 */
    private long orderId;
    /** 本轮订单明细编号。 */
    private long lineId;
    /** 精确区分本轮记录的追踪号。 */
    private final String trace = "customer-aftersales-" + id();

    @BeforeEach
    void setup() {
        String url = System.getenv("PAYMENTS_TEST_MYSQL_URL");
        assertTrue(
                url.matches(
                        "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/java_rewards_test_[A-Za-z0-9_]+(?:\\?.*)?"),
                "仅使用本机专用隔离测试库");
        DriverManagerDataSource source =
                new DriverManagerDataSource(
                        url,
                        System.getenv().getOrDefault("PAYMENTS_TEST_MYSQL_USER", "root"),
                        System.getenv().getOrDefault("PAYMENTS_TEST_MYSQL_PASSWORD", ""));
        db = new Db(new JdbcTemplate(source), new DataSourceTransactionManager(source));
        access = new Access(db);
        OrderService orders = new OrderService(db, access, null, null);
        AftersalesService aftersales =
                new AftersalesService(db, access, orders, null, null, null, null);
        service = new CustomerAftersalesService(db, orders, aftersales);
        db.tx(
                () -> {
                    userId = db.insert("INSERT INTO users(openid) VALUES(?)", trace);
                    categoryId = db.insert("INSERT INTO categories(name) VALUES(?)", trace);
                    productId =
                            db.insert(
                                    "INSERT INTO products(category_id,name,subtitle,description,image,detail_image) VALUES(?,?,?,?,?,?)",
                                    categoryId,
                                    trace,
                                    "测试商品",
                                    "测试说明",
                                    "https://example.com/a.jpg",
                                    "https://example.com/a.jpg");
                    skuId =
                            db.insert(
                                    "INSERT INTO skus(product_id,name,unit,stock,active) VALUES(?,?,?,?,?)",
                                    productId,
                                    "测试规格",
                                    "件",
                                    10,
                                    1);
                    orderId =
                            db.insert(
                                    "INSERT INTO orders(number,user_id,payment_method,payment_state,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key) VALUES(?,?,?,?,?,?,?,?,?,?)",
                                    trace,
                                    userId,
                                    "points",
                                    "paid",
                                    7002,
                                    7002,
                                    0,
                                    stringify(
                                            obj(
                                                    "afterSalesDays",
                                                    7,
                                                    "afterSalesPolicy",
                                                    "原订单售后政策")),
                                    "2099-01-01T00:00:00Z",
                                    trace);
                    lineId =
                            db.insert(
                                    "INSERT INTO order_lines(order_id,sku_id,quantity,unit_cents) VALUES(?,?,?,?)",
                                    orderId,
                                    skuId,
                                    2,
                                    3501);
                });
    }

    private ObjectNode submit(ObjectNode body, long buyer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("requestId", trace);
        request.setAttribute("user", obj("id", buyer));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            return service.request(body, buyer);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void concurrentSameKeyOnlyHoldsTwoUnitsAndDifferentKeyCannotOverApply() throws Exception {
        ObjectNode body =
                obj(
                        "orderId",
                        orderId,
                        "items",
                        arr(obj("lineId", lineId, "quantity", 2)),
                        "reason",
                        "商品不适合",
                        "requestKey",
                        trace);
        assertThrows(BusinessException.class, () -> submit(body, userId + 1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<ObjectNode> first =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return submit(body, userId);
                            });
            Future<ObjectNode> second =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return submit(body, userId);
                            });
            ObjectNode refund = first.get(10, TimeUnit.SECONDS);
            assertEquals(l(refund, "id"), l(second.get(10, TimeUnit.SECONDS), "id"));
            assertEquals(7002, l(refund, "total_cents"));
            assertEquals("requested", s(refund, "status"));
            ObjectNode other = body.deepCopy();
            other.put("requestKey", trace + "-other");
            other.set("items", arr(obj("lineId", lineId, "quantity", 1)));
            assertThrows(BusinessException.class, () -> submit(other, userId));
            assertEquals(
                    2,
                    l(
                            db.one(
                                    "SELECT refund_held FROM order_lines WHERE id=? AND is_deleted=0",
                                    lineId),
                            "refund_held"));
            assertEquals(
                    1,
                    l(
                            db.one(
                                    "SELECT COUNT(*) AS n FROM refunds WHERE order_id=? AND is_deleted=0",
                                    orderId),
                            "n"));
        } finally {
            pool.shutdownNow();
        }
    }

    @AfterEach
    void cleanup() {
        if (db == null || userId == 0) return;
        db.tx(
                () -> {
                    for (String table : new String[] {"refunds", "order_lines"})
                        db.update(
                                "UPDATE "
                                        + table
                                        + " SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE order_id=? AND is_deleted=0",
                                userId,
                                orderId);
                    for (Object[] row :
                            new Object[][] {
                                {"orders", orderId},
                                {"skus", skuId},
                                {"products", productId},
                                {"categories", categoryId},
                                {"users", userId}
                            })
                        db.update(
                                "UPDATE "
                                        + row[0]
                                        + " SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                userId,
                                row[1]);
                    db.update(
                            "UPDATE audit_events SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE request_id=? AND is_deleted=0",
                            userId,
                            trace);
                });
    }
}
