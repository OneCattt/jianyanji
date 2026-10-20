package com.jianyanji.rewards;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Db;
import com.jianyanji.payments.WechatPayClient;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 真实 MySQL、Spring HTTP 链和模拟微信渠道的资金奖励合同验证。 默认跳过；只允许显式启用且库名以 java_rewards_test_ 开头的独立测试库，不监听任何端口。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "jianyanji.bootstrap-admin=false",
            "jianyanji.seed-demo=false",
            "jianyanji.production=false",
            "payments.reconcile-delay-ms=3600000"
        })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "JAVA_REWARDS_TEST_ISOLATED_DB", matches = "1")
class RewardsFundsContractTest {
    /** 真实应用的 HTTP 分发链，保留全局过滤器、会话、权限及异常响应。 */
    @Autowired private MockMvc mvc;
    /** 测试数据和时间回溯仅使用已验证的隔离库连接。 */
    @Autowired private Db db;
    /** 跨模块冲回钩子，用于检验外层事务回滚及乱序退款。 */
    @Autowired private RewardService rewards;
    /** 唯一替换的外部依赖；支付、钱包、售后及奖励服务均为真实实现。 */
    @MockBean private WechatPayClient wechat;
    /** 本次测试专属数据前缀，不覆盖已有用户、商品或订单。 */
    private String runId;
    /** 独立测试工作人员编号，拥有财务、订单及奖励权限。 */
    private long staffId;
    /** 本次工作人员会话原文，仅用于测试 HTTP cookie。 */
    private String staffToken;
    /** 奖励归属主理人编号。 */
    private long ownerId;
    /** 另一位主理人编号，用于验证跨人隔离。 */
    private long otherId;
    /** 实际购买商品的店主编号，与奖励收款人不同。 */
    private long buyerId;
    /** 奖励归属人的测试会话。 */
    private String ownerToken;
    /** 另一位主理人的测试会话。 */
    private String otherToken;
    /** 店主购买者会话，用于真实报价、下单与支付。 */
    private String buyerToken;
    /** 本次独立商品规格编号，零售价 10000 分，固定店主价 7000 分、主理人价 3500 分。 */
    private long skuId;
    /** 购买者本人收货地址编号。 */
    private long addressId;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        String url = System.getenv("REWARDS_TEST_JDBC_URL");
        if (!"1".equals(System.getenv("JAVA_REWARDS_TEST_ISOLATED_DB"))
                || url == null
                || !url.matches(
                        "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/java_rewards_test_[A-Za-z0-9_]+(?:\\?.*)?")) {
            throw new IllegalStateException("奖励验收只允许显式指定本机 java_rewards_test_ 独立库，拒绝加载默认业务数据库");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> environment("REWARDS_TEST_MYSQL_USER"));
        properties.add(
                "spring.datasource.password", () -> environment("REWARDS_TEST_MYSQL_PASSWORD"));
    }

    private static String environment(String key) {
        String value = System.getenv(key);
        if (value == null) throw new IllegalStateException("隔离库连接参数缺失：" + key);
        return value;
    }

    @BeforeEach
    void prepareIndependentData() throws Exception {
        runId = "java-rewards-" + UUID.randomUUID().toString().replace("-", "");
        // 数据库名二次核实后才创建测试数据，避免连接参数与实际选库不一致。
        assertTrue(s(db.one("SELECT DATABASE() AS name"), "name").startsWith("java_rewards_test_"));
        db.tx(
                () -> {
                    staffId =
                            db.insert(
                                    "INSERT INTO staff(username,display_name,password_hash,permissions,enabled) VALUES(?,?,?,?,1)",
                                    runId,
                                    "独立资金验收工作人员",
                                    "不用于登录的隔离测试摘要",
                                    stringify(
                                            arr(
                                                    "rewards:manage",
                                                    "orders:manage",
                                                    "finance:manage",
                                                    "catalog:manage")));
                    staffToken = session("staff", staffId);
                    ownerId = user("owner", "B", null);
                    otherId = user("other", "B", null);
                    buyerId = user("buyer", "C", ownerId);
                    ownerToken = session("user", ownerId);
                    otherToken = session("user", otherId);
                    buyerToken = session("user", buyerId);
                    long category =
                            db.insert("INSERT INTO categories(name,active) VALUES(?,1)", runId);
                    long product =
                            db.insert(
                                    "INSERT INTO products(category_id,name,subtitle,description,image,detail_image,active) VALUES(?,?,?,?,?,?,1)",
                                    category,
                                    runId,
                                    "独立资金验收",
                                    "仅用于测试数据库",
                                    "/assets/test.png",
                                    "/assets/test.png");
                    skuId =
                            db.insert(
                                    "INSERT INTO skus(product_id,name,unit,retail_cents,store_cents,principal_cents,stock,active) VALUES(?,?,?,10000,10000,8000,1000,1)",
                                    product,
                                    "独立验收规格",
                                    "瓶");
                });
        when(wechat.configured()).thenReturn(true);
        when(wechat.appId()).thenReturn("isolated-app");
        when(wechat.mchId()).thenReturn("isolated-mch");
        when(wechat.create(any(JsonNode.class)))
                .thenReturn(
                        obj(
                                "timeStamp",
                                "1",
                                "nonceStr",
                                runId,
                                "package",
                                "prepay_id=isolated",
                                "signType",
                                "RSA",
                                "paySign",
                                "仅测试渠道"));
        when(wechat.query(anyString())).thenAnswer(call -> trade(call.getArgument(0)));
        when(wechat.refund(any(JsonNode.class)))
                .thenAnswer(
                        call -> {
                            JsonNode input = call.getArgument(0);
                            return obj(
                                    "status",
                                    "success",
                                    "outTradeNo",
                                    s(input, "outTradeNo"),
                                    "outRefundNo",
                                    s(input, "outRefundNo"),
                                    "totalCents",
                                    l(input, "totalCents"),
                                    "refundCents",
                                    l(input, "refundCents"),
                                    "refundId",
                                    "isolated-refund-" + id());
                        });
        admin(
                "PUT",
                "/reward-settings",
                obj(
                        "storeRewardCents",
                        20000,
                        "principalRewardCents",
                        50000,
                        "settlementDay",
                        20,
                        "reason",
                        runId),
                200);
        admin(
                "PUT",
                "/shipping-settings",
                obj(
                        "provinces",
                        arr("浙江省"),
                        "feeCents",
                        1000,
                        "instructions",
                        "独立测试配送",
                        "pendingMinutes",
                        30,
                        "reason",
                        runId),
                200);
        admin(
                "PUT",
                "/aftersales-settings",
                obj("days", 7, "policy", "冻结七天售后期", "reason", runId),
                200);
        addressId =
                l(
                        call(
                                "POST",
                                "/api/addresses",
                                obj(
                                        "recipient",
                                        "独立测试店主",
                                        "phone",
                                        "13800000000",
                                        "province",
                                        "浙江省",
                                        "city",
                                        "杭州市",
                                        "district",
                                        "西湖区",
                                        "detail",
                                        "独立测试地址",
                                        "isDefault",
                                        true),
                                buyerToken,
                                200),
                        "id");
    }

    @Test
    void wechatPointsRefundOffsetAndRecoveryKeepFundsOwnership() throws Exception {
        long order = order(5);
        call("POST", "/api/orders/" + order + "/pay/wechat", null, buyerToken, 200);
        call("POST", "/api/orders/" + order + "/payment/query", null, buyerToken, 200);
        call("POST", "/api/orders/" + order + "/payment/query", null, buyerToken, 200);
        assertEquals(1, count("payments", "order_id", order), "重复查询不能重复入账");
        assertEquals(
                36000,
                l(
                        db.one(
                                "SELECT cents FROM payments WHERE order_id=? AND is_deleted=0",
                                order),
                        "cents"));
        assertEquals(0, count("wallet_lots", "user_id", buyerId), "微信商品实付不得重复生成订货积分");
        received(order, 0);
        long reward = register(order, 17500);
        assertFalse(b(admin("GET", "/rewards/" + reward, null, 200), "eligible"));
        plan(reward, "2001-01", 409);
        received(order, 8);
        assertTrue(b(admin("GET", "/rewards/" + reward, null, 200), "eligible"));
        call("GET", "/api/rewards/" + reward, null, otherToken, 404);
        JsonNode own = call("GET", "/api/rewards/" + reward, null, ownerToken, 200);
        assertFalse(own.has("basis"));
        JsonNode paid = plan(reward, "2001-01", 200);
        paid(l(paid, "id"), 200);
        paid(l(paid, "id"), 200);

        long refund = refund(order, 5);
        JsonNode refunded =
                admin("POST", "/refunds/" + refund + "/wechat", obj("reason", runId), 200);
        assertEquals("success", s(refunded, "status"));
        ArgumentCaptor<JsonNode> channelInput = ArgumentCaptor.forClass(JsonNode.class);
        verify(wechat).refund(channelInput.capture());
        assertEquals(36000, l(channelInput.getValue(), "totalCents"), "原渠道总额必须包含原运费");
        assertEquals(35000, l(channelInput.getValue(), "refundCents"), "本次仅退货款，不退运费");
        assertEquals(17500, l(admin("GET", "/rewards/" + reward, null, 200), "reversedCents"));
        long firstDebt = l(debtFor(reward), "id");
        assertEquals(17500, l(debtFor(reward), "remainingCents"));

        JsonNode receipt =
                obj(
                        "userId",
                        buyerId,
                        "purpose",
                        "points",
                        "sourceChannel",
                        "bank",
                        "accountRef",
                        runId,
                        "externalTransactionId",
                        id(),
                        "amountCents",
                        100000,
                        "receivedAt",
                        Instant.now().minusSeconds(60).toString(),
                        "evidence",
                        "隔离验收银行流水",
                        "verified",
                        true,
                        "reason",
                        runId);
        admin("POST", "/receipts", receipt, 200);
        long pointsOrder = order(4);
        call("POST", "/api/orders/" + pointsOrder + "/pay/points", null, buyerToken, 200);
        assertEquals(71000, l(call("GET", "/api/wallet", null, buyerToken, 200), "balanceCents"));
        received(pointsOrder, 8);
        long nextReward = register(pointsOrder, 14000);
        JsonNode stale = plan(nextReward, "2001-02", 200);
        assertEquals(14000, l(stale, "offset_cents"));
        assertEquals(0, l(stale, "net_cents"));
        JsonNode recovery = recovery(firstDebt, 6000);
        ExecutorService recoveryWorkers = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> firstRecovery =
                    recoveryWorkers.submit(
                            () -> admin("POST", "/reward-recoveries", recovery, 200));
            Future<JsonNode> repeatedRecovery =
                    recoveryWorkers.submit(
                            () -> admin("POST", "/reward-recoveries", recovery, 200));
            assertEquals(
                    l(firstRecovery.get(20, TimeUnit.SECONDS), "id"),
                    l(repeatedRecovery.get(20, TimeUnit.SECONDS), "id"),
                    "两个并发实收请求只能形成一笔真实归还记录");
        } finally {
            recoveryWorkers.shutdownNow();
        }
        paid(l(stale, "id"), 409);
        result(l(stale, "id"), "unknown", 200);
        result(l(stale, "id"), "cancelled", 409);
        result(l(stale, "id"), "failed", 200);
        result(l(stale, "id"), "cancelled", 200);
        JsonNode fresh = plan(nextReward, "2001-02", 200);
        assertEquals(11500, l(fresh, "offset_cents"));
        assertEquals(2500, l(fresh, "net_cents"));
        paid(l(fresh, "id"), 200);
        assertEquals(0, l(debtFor(reward), "remainingCents"));

        long pointsRefund = refund(pointsOrder, 1);
        admin("POST", "/refunds/" + pointsRefund + "/points", obj("reason", runId), 200);
        admin("POST", "/refunds/" + pointsRefund + "/points", obj("reason", runId), 200);
        assertEquals(
                78000,
                l(call("GET", "/api/wallet", null, buyerToken, 200), "balanceCents"),
                "重复积分退款只能恢复一次原批次");
        assertEquals(3500, l(admin("GET", "/rewards/" + nextReward, null, 200), "reversedCents"));
        assertEquals(3500, l(debtFor(nextReward), "remainingCents"));
        admin("POST", "/reward-recoveries", recovery(l(debtFor(nextReward), "id"), 3500), 200);
        admin("POST", "/reward-recoveries", recovery(firstDebt, 1), 409);
        assertEquals(
                0,
                l(call("GET", "/api/rewards", null, ownerToken, 200).path("summary"), "debtCents"));
        assertEquals(
                0,
                l(call("GET", "/api/wallet", null, ownerToken, 200), "balanceCents"),
                "奖励抵扣不得改收款人的订货积分");
        assertEquals(
                20000,
                l(
                        db.one(
                                "SELECT SUM(net_cents) AS cents FROM reward_settlements WHERE user_id=? AND status='paid' AND is_deleted=0",
                                ownerId),
                        "cents"));
        assertEquals(
                9500,
                l(
                        db.one(
                                "SELECT SUM(c.amount_cents) AS cents FROM reward_recoveries c JOIN reward_reversals v ON v.id=c.reversal_id AND v.is_deleted=0 "
                                        + "JOIN rewards r ON r.id=v.reward_id AND r.is_deleted=0 WHERE r.user_id=? AND c.is_deleted=0",
                                ownerId),
                        "cents"));
    }

    @Test
    void historicalMarginSnapshotAndRefundBeforeRegistrationRemainTraceable() throws Exception {
        long order = order(5);
        call("POST", "/api/orders/" + order + "/pay/wechat", null, buyerToken, 200);
        call("POST", "/api/orders/" + order + "/payment/query", null, buyerToken, 200);
        received(order, 8);
        ObjectNode line =
                db.one(
                        "SELECT id FROM order_lines WHERE order_id=? AND sku_id=? AND is_deleted=0",
                        order,
                        skuId);
        ObjectNode refundLine =
                obj(
                        "lineId",
                        l(line, "id"),
                        "quantity",
                        1,
                        "unitCents",
                        7000,
                        "principalCents",
                        3500);
        long refund =
                db.tx(
                        () -> {
                            // 独立夹具模拟迁移时已存在的成功退款；奖励稍后人工登记，也必须追溯冲回。
                            db.update(
                                    "UPDATE skus SET principal_cents=7000,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    staffId,
                                    skuId);
                            return db.insert(
                                    "INSERT INTO refunds(order_id,number,kind,status,goods_cents,shipping_cents,total_cents,`lines`,reason,policy_snapshot,request_key,request_id) "
                                            + "VALUES(?,?,'goods','success',7000,0,7000,?,?,'{}',?,?)",
                                    order,
                                    runId + "-historical-refund",
                                    stringify(arr(refundLine)),
                                    runId,
                                    id(),
                                    runId);
                        });
        JsonNode source = admin("GET", "/rewards/source/" + order, null, 200);
        assertEquals(17500, l(source, "amountCents"));
        assertEquals(3500, l(source.path("snapshot").path("lines").get(0), "principalCents"));
        admin(
                "POST",
                "/rewards",
                obj("orderId", order, "amountCents", 15000, "reason", runId),
                409);
        long reward = register(order, 17500);
        JsonNode detail = admin("GET", "/rewards/" + reward, null, 200);
        assertEquals(3500, l(detail, "reversedCents"));
        assertEquals(14000, l(detail, "remainingCents"));
        assertEquals(0, detail.path("debts").size());
        assertEquals(1, detail.path("reversals").size());
        admin("POST", "/rewards/" + reward + "/reconcile", obj("reason", runId), 200);
        refundLine.put("unitCents", 10001);
        db.tx(
                () -> {
                    db.update(
                            "UPDATE refunds SET `lines`=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            stringify(arr(refundLine)),
                            staffId,
                            refund);
                });
        admin("POST", "/rewards/" + reward + "/reconcile", obj("reason", runId), 409);
        assertEquals(
                3500,
                l(
                        db.one(
                                "SELECT reversed_cents FROM rewards WHERE id=? AND is_deleted=0",
                                reward),
                        "reversed_cents"));
        assertEquals(1, count("reward_reversals", "reward_id", reward));
    }

    @Test
    void packageRoundingOutOfOrderRefundAndOuterRollbackKeepHistoricalReversals() throws Exception {
        long order =
                db.tx(
                        () -> {
                            ObjectNode snapshot =
                                    obj(
                                            "identity",
                                            "C",
                                            "inviterId",
                                            ownerId,
                                            "rewardCents",
                                            1,
                                            "afterSalesDays",
                                            7,
                                            "onboardingApproved",
                                            true,
                                            "onboardingId",
                                            1);
                            return db.insert(
                                    "INSERT INTO orders(number,user_id,kind,status,payment_method,payment_state,total_cents,goods_cents,shipping_cents,"
                                            + "snapshot,expires_at,received_at,request_key) VALUES(?,?,'package','completed','offline','paid',6,6,0,?,?,?,?)",
                                    runId + "-package",
                                    buyerId,
                                    stringify(snapshot),
                                    Instant.now().plusSeconds(3600).toString(),
                                    Instant.now().minusSeconds(8 * 86400L).toString(),
                                    id());
                        });
        long reward = register(order, 1);
        long early = sourceRefund(order, 1, "processing");
        sourceRefund(order, 3, "success");
        assertThrows(
                IllegalStateException.class,
                () ->
                        db.tx(
                                () -> {
                                    rewards.reconcileReversals(order, staffId, runId);
                                    throw new IllegalStateException("验证外层失败全部回滚");
                                }));
        assertEquals(0, count("reward_reversals", "reward_id", reward));
        assertEquals(0, l(admin("GET", "/rewards/" + reward, null, 200), "reversedCents"));
        rewards.reconcileReversals(order, staffId, runId);
        assertEquals(1, l(admin("GET", "/rewards/" + reward, null, 200), "reversedCents"));
        db.tx(
                () -> {
                    db.update(
                            "UPDATE refunds SET status='success',update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            staffId,
                            early);
                });
        rewards.reconcileReversals(order, staffId, runId);
        sourceRefund(order, 2, "success");
        rewards.reconcileReversals(order, staffId, runId);
        rewards.reconcileReversals(order, staffId, runId);
        JsonNode detail = admin("GET", "/rewards/" + reward, null, 200);
        assertEquals(1, l(detail, "reversedCents"));
        assertEquals(3, detail.path("reversals").size(), "零增量也保留成功退款的冲回证据");
        assertEquals(0, detail.path("debts").size(), "未付款奖励不得凭空产生待追回款");
    }

    @Test
    void v2MixedWelfarePriceSnapshotSurvivesPriceIdentityAndOwnerChanges() throws Exception {
        long welfare =
                db.tx(
                        () -> {
                            db.update(
                                    "UPDATE skus SET retail_cents=10001 WHERE id=? AND is_deleted=0",
                                    skuId);
                            return db.insert(
                                    "INSERT INTO skus(product_id,name,unit,product_type,welfare_cents,stock,active) "
                                            + "SELECT product_id,'福利规格','件','welfare',1234,100,1 FROM skus WHERE id=? AND is_deleted=0",
                                    skuId);
                        });
        JsonNode items =
                arr(obj("skuId", skuId, "quantity", 3), obj("skuId", welfare, "quantity", 2));
        JsonNode quote =
                call(
                        "POST",
                        "/api/checkout/quote",
                        obj("addressId", addressId, "items", items),
                        buyerToken,
                        200);
        assertEquals(7001, l(quote.path("lines").get(0), "unitCents"));
        assertEquals(3500, l(quote.path("lines").get(0), "principalCents"));
        assertEquals(1234, l(quote.path("lines").get(1), "unitCents"));
        assertEquals(24471, l(quote, "totalCents"));
        long order =
                l(
                        call(
                                "POST",
                                "/api/orders",
                                obj("quoteId", l(quote, "id"), "requestKey", id()),
                                buyerToken,
                                200),
                        "id");
        call("POST", "/api/orders/" + order + "/pay/wechat", null, buyerToken, 200);
        call("POST", "/api/orders/" + order + "/payment/query", null, buyerToken, 200);
        assertEquals(
                24471,
                l(
                        db.one(
                                "SELECT cents FROM payments WHERE order_id=? AND is_deleted=0",
                                order),
                        "cents"));
        // 已付款后改现价、类型、身份及归属；核算只能使用原订单依据。
        db.tx(
                () -> {
                    db.update(
                            "UPDATE skus SET retail_cents=99000,product_type='welfare',welfare_cents=99 WHERE id=? AND is_deleted=0",
                            skuId);
                    db.update(
                            "UPDATE skus SET product_type='regular',retail_cents=99999 WHERE id=? AND is_deleted=0",
                            welfare);
                    db.update(
                            "UPDATE users SET identity='B',principal_id=? WHERE id=? AND is_deleted=0",
                            otherId,
                            buyerId);
                });
        JsonNode source = admin("GET", "/rewards/source/" + order, null, 200);
        assertEquals(10503, l(source, "amountCents"));
        assertEquals(ownerId, l(source, "userId"));
        long reward = register(order, 10503);
        admin(
                "POST",
                "/rewards",
                obj("orderId", order, "amountCents", 10503, "reason", runId),
                409);
        ObjectNode welfareLine =
                db.one(
                        "SELECT * FROM order_lines WHERE order_id=? AND sku_id=? AND is_deleted=0",
                        order,
                        welfare);
        JsonNode refund =
                admin(
                        "POST",
                        "/refunds",
                        obj(
                                "orderId",
                                order,
                                "items",
                                arr(obj("lineId", l(welfareLine, "id"), "quantity", 2)),
                                "shippingCents",
                                0,
                                "reason",
                                runId,
                                "requestKey",
                                id()),
                        200);
        long refundId = l(refund, "id");
        admin(
                "POST",
                "/refunds/" + refundId + "/review",
                obj("approve", true, "identityDecision", null, "reason", runId),
                200);
        admin("POST", "/refunds/" + refundId + "/wechat", obj("reason", runId), 200);
        assertEquals(0, l(admin("GET", "/rewards/" + reward, null, 200), "reversedCents"));
        long first = refund(order, 1);
        admin("POST", "/refunds/" + first + "/wechat", obj("reason", runId), 200);
        assertEquals(3501, l(admin("GET", "/rewards/" + reward, null, 200), "reversedCents"));
        long remaining = refund(order, 2);
        admin("POST", "/refunds/" + remaining + "/wechat", obj("reason", runId), 200);
        admin("POST", "/refunds/" + remaining + "/wechat", obj("reason", runId), 200);
        JsonNode detail = admin("GET", "/rewards/" + reward, null, 200);
        assertEquals(10503, l(detail, "reversedCents"));
        assertEquals(3, detail.path("reversals").size());
        assertEquals(ownerId, l(detail, "userId"));
        assertEquals(0, detail.path("debts").size());
    }

    @Test
    void v2PointsAndWechatUseSamePriceAndWelfareCannotGenerateMargin() throws Exception {
        long welfare =
                db.tx(
                        () -> {
                            db.update(
                                    "UPDATE skus SET retail_cents=10001 WHERE id=? AND is_deleted=0",
                                    skuId);
                            return db.insert(
                                    "INSERT INTO skus(product_id,name,unit,product_type,welfare_cents,stock,active) "
                                            + "SELECT product_id,'混合福利','件','welfare',1234,100,1 FROM skus WHERE id=? AND is_deleted=0",
                                    skuId);
                        });
        admin(
                "POST",
                "/receipts",
                obj(
                        "userId",
                        buyerId,
                        "purpose",
                        "points",
                        "sourceChannel",
                        "bank",
                        "accountRef",
                        runId,
                        "externalTransactionId",
                        id(),
                        "amountCents",
                        100000,
                        "receivedAt",
                        Instant.now().minusSeconds(60).toString(),
                        "evidence",
                        "独立测试收款",
                        "verified",
                        true,
                        "reason",
                        runId),
                200);
        JsonNode items =
                arr(obj("skuId", skuId, "quantity", 3), obj("skuId", welfare, "quantity", 2));
        long pointsOrder = orderItems(items), wechatOrder = orderItems(items);
        call("POST", "/api/orders/" + pointsOrder + "/pay/points", null, buyerToken, 200);
        call("POST", "/api/orders/" + wechatOrder + "/pay/wechat", null, buyerToken, 200);
        call("POST", "/api/orders/" + wechatOrder + "/payment/query", null, buyerToken, 200);
        for (long value : new long[] {pointsOrder, wechatOrder}) {
            assertEquals(
                    24471,
                    l(
                            db.one(
                                    "SELECT cents FROM payments WHERE order_id=? AND is_deleted=0",
                                    value),
                            "cents"));
            assertEquals(
                    10503, l(admin("GET", "/rewards/source/" + value, null, 200), "amountCents"));
        }
        assertEquals(75529, l(call("GET", "/api/wallet", null, buyerToken, 200), "balanceCents"));
        long reward = register(pointsOrder, 10503);
        long refund = refund(pointsOrder, 1);
        admin("POST", "/refunds/" + refund + "/points", obj("reason", runId), 200);
        assertEquals(3501, l(admin("GET", "/rewards/" + reward, null, 200), "reversedCents"));
        assertEquals(82530, l(call("GET", "/api/wallet", null, buyerToken, 200), "balanceCents"));
        db.tx(
                () ->
                        db.update(
                                "UPDATE skus SET product_type='welfare',welfare_cents=1234 WHERE id=? AND is_deleted=0",
                                skuId));
        long welfareOrder = order(1);
        call("POST", "/api/orders/" + welfareOrder + "/pay/points", null, buyerToken, 200);
        admin("GET", "/rewards/source/" + welfareOrder, null, 400);
        admin(
                "POST",
                "/rewards",
                obj("orderId", welfareOrder, "amountCents", 1, "reason", runId),
                400);
    }

    @Test
    void v2CatalogAndQuoteRejectUnauthorizedOrClientSuppliedPrices() throws Exception {
        long category =
                l(
                        db.one(
                                "SELECT p.category_id FROM products p JOIN skus s ON s.product_id=p.id WHERE s.id=? AND s.is_deleted=0 AND p.is_deleted=0",
                                skuId),
                        "category_id");
        JsonNode product =
                obj(
                        "categoryId",
                        category,
                        "name",
                        "福利测试",
                        "subtitle",
                        "",
                        "description",
                        "真实测试商品",
                        "image",
                        "/assets/test.png",
                        "detailImage",
                        "/assets/test.png",
                        "active",
                        true,
                        "featured",
                        false,
                        "reason",
                        runId,
                        "skus",
                        arr(
                                obj(
                                        "name",
                                        "福利",
                                        "unit",
                                        "件",
                                        "productType",
                                        "welfare",
                                        "retailCents",
                                        null,
                                        "welfareCents",
                                        1234,
                                        "stock",
                                        10,
                                        "active",
                                        true)));
        call("POST", "/api/admin/products", product, buyerToken, 401);
        String restricted =
                db.tx(
                        () -> {
                            long staff =
                                    db.insert(
                                            "INSERT INTO staff(username,display_name,password_hash,permissions) VALUES(?,?,?,'[\"content:manage\"]')",
                                            id(),
                                            "无商品权限",
                                            "无登录密码");
                            return session("staff", staff);
                        });
        call("POST", "/api/admin/products", product, "staff:" + restricted, 403);
        long productId = l(admin("POST", "/products", product, 200), "id");
        JsonNode view = call("GET", "/api/products/" + productId, null, buyerToken, 200);
        assertEquals(1234, l(view.path("skus").get(0), "priceCents"));
        assertEquals("welfare", s(view.path("skus").get(0), "productType"));
        ((ObjectNode) product.path("skus").get(0)).put("storeCents", 1);
        admin("PUT", "/products/" + productId, product, 400);
        call(
                "POST",
                "/api/checkout/quote",
                obj(
                        "addressId",
                        addressId,
                        "items",
                        arr(obj("skuId", skuId, "quantity", 1, "unitCents", 1))),
                buyerToken,
                400);
    }

    @Test
    void legacyOrdersKeepOriginalManualPricesAfterV2() throws Exception {
        long order =
                db.tx(
                        () -> {
                            long value =
                                    db.insert(
                                            "INSERT INTO orders(number,user_id,status,payment_state,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key) "
                                                    + "VALUES(?,?,'paid','paid',24000,24000,0,?,?,?)",
                                            id(),
                                            buyerId,
                                            stringify(
                                                    obj(
                                                            "identity",
                                                            "C",
                                                            "principalId",
                                                            ownerId,
                                                            "afterSalesDays",
                                                            7)),
                                            Instant.now().plusSeconds(3600).toString(),
                                            id());
                            db.insert(
                                    "INSERT INTO order_lines(order_id,sku_id,quantity,unit_cents,principal_cents) VALUES(?,?,3,8000,6000)",
                                    value,
                                    skuId);
                            db.update(
                                    "UPDATE skus SET product_type='welfare',welfare_cents=1,retail_cents=10001 WHERE id=? AND is_deleted=0",
                                    skuId);
                            return value;
                        });
        JsonNode source = admin("GET", "/rewards/source/" + order, null, 200);
        assertEquals(6000, l(source, "amountCents"));
        assertEquals(8000, l(source.path("snapshot").path("lines").get(0), "unitCents"));
        assertEquals(6000, l(source.path("snapshot").path("lines").get(0), "principalCents"));
    }

    @Test
    void unpaidPointsOrderRequiresReconfirmationAfterPrincipalOrIdentityChanges() throws Exception {
        long order = order(1);
        db.tx(
                () ->
                        db.update(
                                "UPDATE users SET principal_id=? WHERE id=? AND is_deleted=0",
                                otherId,
                                buyerId));
        JsonNode denied =
                call("POST", "/api/orders/" + order + "/pay/points", null, buyerToken, 409);
        assertEquals("ORDER_RELATIONS_CHANGED", s(denied, "code"));
        assertEquals(0, count("payments", "order_id", order));
        db.tx(
                () ->
                        db.update(
                                "UPDATE users SET identity='D',principal_id=NULL WHERE id=? AND is_deleted=0",
                                buyerId));
        assertEquals(
                "ORDER_RELATIONS_CHANGED",
                s(
                        call("POST", "/api/orders/" + order + "/pay/points", null, buyerToken, 409),
                        "code"));
        JsonNode original = call("GET", "/api/orders/" + order, null, buyerToken, 200);
        assertEquals(ownerId, l(original.path("snapshot"), "principalId"));
        assertEquals("C", s(original.path("snapshot"), "identity"));
    }

    @Test
    void v2OnboardingRecipientsRegisterAndRefundSeparatelyWithoutUsingLegacyAmount()
            throws Exception {
        long order = packageSource("C", "C", otherId, ownerId);
        JsonNode source = admin("GET", "/rewards/source/" + order, null, 200);
        assertEquals(2, source.path("candidates").size());
        assertEquals(50000, l(source.path("candidates").get(0), "amountCents"));
        assertEquals(50000, l(source.path("candidates").get(1), "amountCents"));
        assertEquals(0, count("rewards", "order_id", order));
        admin(
                "POST",
                "/rewards",
                obj("orderId", order, "amountCents", 50000, "reason", runId),
                400);
        long direct =
                l(
                        admin(
                                "POST",
                                "/rewards",
                                obj(
                                        "orderId",
                                        order,
                                        "userId",
                                        otherId,
                                        "amountCents",
                                        50000,
                                        "reason",
                                        runId),
                                200),
                        "id");
        long principal =
                l(
                        admin(
                                "POST",
                                "/rewards",
                                obj(
                                        "orderId",
                                        order,
                                        "userId",
                                        ownerId,
                                        "amountCents",
                                        50000,
                                        "reason",
                                        runId),
                                200),
                        "id");
        admin(
                "POST",
                "/rewards",
                obj("orderId", order, "userId", ownerId, "amountCents", 50000, "reason", runId),
                409);
        call("GET", "/api/rewards/" + direct, null, ownerToken, 404);
        assertFalse(b(admin("GET", "/rewards/" + principal, null, 200), "eligible"));
        received(order, 8);
        JsonNode paid = plan(principal, "2002-01", 200);
        paid(l(paid, "id"), 200);
        sourceRefund(order, 149000, "success");
        rewards.reconcileReversals(order, staffId, runId);
        assertEquals(25000, l(admin("GET", "/rewards/" + direct, null, 200), "reversedCents"));
        assertEquals(25000, l(admin("GET", "/rewards/" + principal, null, 200), "reversedCents"));
        assertEquals(25000, l(debtFor(principal), "remainingCents"));
        assertEquals(0, admin("GET", "/rewards/" + direct, null, 200).path("debts").size());
        sourceRefund(order, 149000, "success");
        rewards.reconcileReversals(order, staffId, runId);
        rewards.reconcileReversals(order, staffId, runId);
        assertEquals(50000, l(admin("GET", "/rewards/" + direct, null, 200), "reversedCents"));
        assertEquals(
                50000,
                l(
                        db.one(
                                "SELECT SUM(debt_cents) AS cents FROM reward_reversals WHERE reward_id=? AND is_deleted=0",
                                principal),
                        "cents"));
        assertEquals(0, l(call("GET", "/api/wallet", null, ownerToken, 200), "balanceCents"));
        long bInvite = packageSource("C", "B", ownerId, ownerId);
        JsonNode single = admin("GET", "/rewards/source/" + bInvite, null, 200);
        assertEquals(1, single.path("candidates").size());
        assertEquals(100000, l(single, "amountCents"));
        long bPackage = packageSource("B", "B", ownerId, buyerId);
        admin("GET", "/rewards/source/" + bPackage, null, 409);
    }

    @Test
    void initialVerifiedPackageHasNoInviterAndNoRewardCandidate() throws Exception {
        long order = packageSource("C", "C", otherId, ownerId);
        ObjectNode snapshot =
                (ObjectNode)
                        parse(
                                s(
                                        db.one(
                                                "SELECT snapshot FROM orders WHERE id=? AND is_deleted=0",
                                                order),
                                        "snapshot"));
        snapshot.put("initialVerified", true);
        snapshot.put("initialEvidence", "首批新代理真实申请依据归档");
        snapshot.putNull("inviterId");
        snapshot.remove("inviterIdentity");
        db.tx(
                () ->
                        db.update(
                                "UPDATE orders SET snapshot=? WHERE id=? AND is_deleted=0",
                                stringify(snapshot),
                                order));
        assertEquals(
                0, admin("GET", "/rewards/source/" + order, null, 200).path("candidates").size());
        admin(
                "POST",
                "/rewards",
                obj("orderId", order, "userId", ownerId, "amountCents", 100000, "reason", runId),
                409);
        assertEquals(0, count("rewards", "order_id", order));
    }

    @Test
    void financeExportsUseRealPermissionsFiltersAndAuditWithoutEvidence() throws Exception {
        credit(buyerId, 100000);
        credit(otherId, 50000);
        long order = order(1);
        call("POST", "/api/orders/" + order + "/pay/points", null, buyerToken, 200);
        for (String kind :
                new String[] {
                    "receipts",
                    "points",
                    "cash-refunds",
                    "payments",
                    "refunds",
                    "rewards",
                    "settlements",
                    "reversals",
                    "offsets",
                    "recoveries"
                }) {
            String path = "/api/admin/exports/finance/" + kind;
            MockHttpServletRequestBuilder request =
                    get(path)
                            .cookie(new Cookie("jyj_admin", staffToken))
                            .param("userId", String.valueOf(buyerId));
            if ("payments".equals(kind)) request.param("orderId", String.valueOf(order));
            MvcResult result = mvc.perform(request).andReturn();
            assertEquals(
                    200,
                    result.getResponse().getStatus(),
                    kind + ": " + result.getResponse().getContentAsString());
            String csv =
                    result.getResponse()
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(csv.startsWith("\uFEFF"), kind);
            assertFalse(
                    csv.contains("\"proof\"")
                            || csv.contains("\"evidence\"")
                            || csv.contains("\"account_ref\""));
        }
        assertEquals(
                10,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM audit_events WHERE actor_id=? AND action='finance.export' AND is_deleted=0",
                                staffId),
                        "n"));
        long hidden =
                l(
                        db.one(
                                "SELECT id FROM wallet_ledger WHERE user_id=? AND is_deleted=0 ORDER BY id LIMIT 1",
                                buyerId),
                        "id");
        db.tx(
                () ->
                        db.update(
                                "UPDATE wallet_ledger SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                staffId,
                                hidden));
        String csv =
                mvc.perform(
                                get("/api/admin/exports/finance/points")
                                        .cookie(new Cookie("jyj_admin", staffToken))
                                        .param("userId", String.valueOf(buyerId)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertEquals(2, lines.length);
        assertFalse(lines[1].startsWith("\"" + hidden + "\","));
        assertEquals(String.valueOf(buyerId), lines[1].split(",")[1].replace("\"", ""));
        call("GET", "/api/admin/exports/finance/rewards", null, buyerToken, 401);
        String restricted =
                db.tx(
                        () -> {
                            long staff =
                                    db.insert(
                                            "INSERT INTO staff(username,display_name,password_hash,permissions) VALUES(?,?,?,'[\"orders:manage\"]')",
                                            id(),
                                            "仅订单权限",
                                            "无登录密码");
                            return session("staff", staff);
                        });
        call(
                "GET",
                "/api/admin/exports/finance/points?userId=" + buyerId,
                null,
                "staff:" + restricted,
                403);
        call("GET", "/api/admin/exports/finance/rewards", null, "staff:" + restricted, 403);
    }

    /** 已付款来源夹具，不替代主线程产品包真实核款、交付及资格激活验收。 */
    private long packageSource(
            String target, String inviterIdentity, long inviter, long principal) {
        return db.tx(
                () ->
                        db.insert(
                                "INSERT INTO orders(number,user_id,kind,status,payment_method,payment_state,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key) VALUES(?,?,'package','paid','offline','paid',298000,298000,0,?,?,?)",
                                id(),
                                buyerId,
                                stringify(
                                        obj(
                                                "identity",
                                                target,
                                                "inviterIdentity",
                                                inviterIdentity,
                                                "inviterId",
                                                inviter,
                                                "principalId",
                                                principal,
                                                "rewardCents",
                                                999999,
                                                "afterSalesDays",
                                                7,
                                                "onboardingApproved",
                                                true,
                                                "onboardingId",
                                                1)),
                                Instant.now().plusSeconds(3600).toString(),
                                id()));
    }

    @Test
    void v2RetailOrdersConfirmDifferentAgentsExcludeWelfareAndRoundCumulativeRefunds()
            throws Exception {
        long welfare =
                db.tx(
                        () -> {
                            db.update(
                                    "UPDATE users SET identity='D',inviter_id=?,principal_id=NULL WHERE id=? AND is_deleted=0",
                                    ownerId,
                                    buyerId);
                            db.update(
                                    "UPDATE users SET identity='C',share_code=? WHERE id=? AND is_deleted=0",
                                    runId + "-sale-c",
                                    otherId);
                            db.update(
                                    "UPDATE users SET share_code=? WHERE id=? AND is_deleted=0",
                                    runId + "-sale-b",
                                    ownerId);
                            db.update(
                                    "UPDATE skus SET retail_cents=10001 WHERE id=? AND is_deleted=0",
                                    skuId);
                            return db.insert(
                                    "INSERT INTO skus(product_id,name,unit,product_type,welfare_cents,stock,active) SELECT product_id,'福利','件','welfare',1234,100,1 FROM skus WHERE id=? AND is_deleted=0",
                                    skuId);
                        });
        JsonNode items =
                arr(obj("skuId", skuId, "quantity", 3), obj("skuId", welfare, "quantity", 1));
        call(
                "POST",
                "/api/checkout/quote",
                obj(
                        "addressId",
                        addressId,
                        "items",
                        items,
                        "salesCode",
                        runId + "-sale-c",
                        "salesConfirmed",
                        false),
                buyerToken,
                400);
        long cOrder = salesOrder(items, runId + "-sale-c");
        long bOrder = salesOrder(items, runId + "-sale-b");
        long organic = order(1);
        for (long order : new long[] {cOrder, bOrder, organic}) {
            call("POST", "/api/orders/" + order + "/pay/wechat", null, buyerToken, 200);
            call("POST", "/api/orders/" + order + "/payment/query", null, buyerToken, 200);
        }
        JsonNode c = admin("GET", "/rewards/source/" + cOrder, null, 200),
                b = admin("GET", "/rewards/source/" + bOrder, null, 200);
        assertEquals("retail", s(c, "kind"));
        assertEquals(otherId, l(c, "userId"));
        assertEquals(9001, l(c, "amountCents"));
        assertEquals(ownerId, l(b, "userId"));
        assertEquals(19502, l(b, "amountCents"));
        admin("GET", "/rewards/source/" + organic, null, 409);
        long reward = register(cOrder, 9001);
        assertEquals(1, count("rewards", "order_id", cOrder));
        db.tx(
                () -> {
                    db.update(
                            "UPDATE users SET identity='B',principal_id=? WHERE id=? AND is_deleted=0",
                            ownerId,
                            otherId);
                    db.update(
                            "UPDATE skus SET retail_cents=123,product_type='welfare',welfare_cents=1 WHERE id=? AND is_deleted=0",
                            skuId);
                });
        long first = refund(cOrder, 1);
        admin("POST", "/refunds/" + first + "/wechat", obj("reason", runId), 200);
        assertEquals(3000, l(admin("GET", "/rewards/" + reward, null, 200), "reversedCents"));
        long second = refund(cOrder, 1);
        admin("POST", "/refunds/" + second + "/wechat", obj("reason", runId), 200);
        assertEquals(6001, l(admin("GET", "/rewards/" + reward, null, 200), "reversedCents"));
        long third = refund(cOrder, 1);
        admin("POST", "/refunds/" + third + "/wechat", obj("reason", runId), 200);
        assertEquals(9001, l(admin("GET", "/rewards/" + reward, null, 200), "reversedCents"));
        assertEquals(otherId, l(admin("GET", "/rewards/" + reward, null, 200), "userId"));
        db.tx(
                () ->
                        db.update(
                                "UPDATE users SET identity='C',principal_id=? WHERE id=? AND is_deleted=0",
                                ownerId,
                                buyerId));
        call(
                "POST",
                "/api/checkout/quote",
                obj(
                        "addressId",
                        addressId,
                        "items",
                        items,
                        "salesCode",
                        runId + "-sale-c",
                        "salesConfirmed",
                        true),
                buyerToken,
                409);
    }

    private long salesOrder(JsonNode items, String code) throws Exception {
        JsonNode quote =
                call(
                        "POST",
                        "/api/checkout/quote",
                        obj(
                                "addressId",
                                addressId,
                                "items",
                                items,
                                "salesCode",
                                code,
                                "salesConfirmed",
                                true),
                        buyerToken,
                        200);
        return l(
                call(
                        "POST",
                        "/api/orders",
                        obj("quoteId", l(quote, "id"), "requestKey", id()),
                        buyerToken,
                        200),
                "id");
    }

    /** 模拟已核验到账的隔离测试收款，通过真实业务接口生成积分及流水。 */
    private void credit(long userId, long cents) throws Exception {
        admin(
                "POST",
                "/receipts",
                obj(
                        "userId",
                        userId,
                        "purpose",
                        "points",
                        "sourceChannel",
                        "bank",
                        "accountRef",
                        runId,
                        "externalTransactionId",
                        id(),
                        "amountCents",
                        cents,
                        "receivedAt",
                        Instant.now().minusSeconds(60).toString(),
                        "evidence",
                        "隔离测试到账记录",
                        "verified",
                        true,
                        "reason",
                        runId),
                200);
    }

    private long user(String suffix, String identity, Long principal) {
        return db.insert(
                "INSERT INTO users(openid,nickname,identity,principal_id) VALUES(?,?,?,?)",
                runId + "-" + suffix,
                "隔离资金验收",
                identity,
                principal);
    }

    private String session(String kind, long subject) {
        String token = id();
        db.insert(
                "INSERT INTO sessions(token_hash,kind,subject_id,expires_at) VALUES(?,?,?,?)",
                Access.hash(token),
                kind,
                subject,
                Instant.now().plusSeconds(3600).toString());
        return token;
    }

    private JsonNode trade(String number) {
        ObjectNode attempt =
                db.one("SELECT * FROM payment_attempts WHERE number=? AND is_deleted=0", number);
        assertNotNull(attempt);
        return obj(
                "out_trade_no",
                number,
                "mchid",
                "isolated-mch",
                "appid",
                "isolated-app",
                "trade_state",
                "SUCCESS",
                "transaction_id",
                "isolated-wx-" + number,
                "amount",
                obj("total", l(attempt, "cents"), "currency", "CNY"),
                "payer",
                obj("openid", s(attempt, "openid")));
    }

    private long order(int quantity) throws Exception {
        return orderItems(arr(obj("skuId", skuId, "quantity", quantity)));
    }

    /** 真实结算与下单入口；仅使用 SKU 和数量，价格由服务端冻结。 */
    private long orderItems(JsonNode items) throws Exception {
        JsonNode quote =
                call(
                        "POST",
                        "/api/checkout/quote",
                        obj("addressId", addressId, "items", items),
                        buyerToken,
                        200);
        return l(
                call(
                        "POST",
                        "/api/orders",
                        obj("quoteId", l(quote, "id"), "requestKey", id()),
                        buyerToken,
                        200),
                "id");
    }

    private void received(long order, int daysAgo) {
        String receivedAt = Instant.now().minusSeconds(daysAgo * 86400L).toString();
        db.tx(
                () -> {
                    // 只回溯本次购买者、本次商品的订单，时间修改不进入生产业务代码。
                    assertEquals(
                            1,
                            db.update(
                                    "UPDATE orders SET status='completed',received_at=?,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) "
                                            + "WHERE id=? AND user_id=? AND is_deleted=0",
                                    receivedAt,
                                    staffId,
                                    order,
                                    buyerId));
                    db.update(
                            "UPDATE order_lines SET shipped_qty=quantity,received_qty=quantity,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) "
                                    + "WHERE order_id=? AND sku_id=? AND is_deleted=0",
                            staffId,
                            order,
                            skuId);
                });
    }

    private long register(long order, long cents) throws Exception {
        return l(
                admin(
                        "POST",
                        "/rewards",
                        obj("orderId", order, "amountCents", cents, "reason", runId),
                        200),
                "id");
    }

    private JsonNode plan(long reward, String month, int status) throws Exception {
        return admin(
                "POST",
                "/reward-settlements",
                obj(
                        "userId",
                        ownerId,
                        "month",
                        month,
                        "rewardIds",
                        arr(reward),
                        "payee",
                        runId,
                        "requestKey",
                        id(),
                        "reason",
                        runId),
                status);
    }

    private void paid(long settlement, int status) throws Exception {
        admin(
                "POST",
                "/reward-settlements/" + settlement + "/result",
                obj(
                        "status",
                        "paid",
                        "paidAt",
                        "2001-01-20T08:00:00.000Z",
                        "proof",
                        runId,
                        "reason",
                        runId),
                status);
    }

    private void result(long settlement, String result, int status) throws Exception {
        admin(
                "POST",
                "/reward-settlements/" + settlement + "/result",
                obj("status", result, "reason", runId),
                status);
    }

    private long refund(long order, int quantity) throws Exception {
        ObjectNode line =
                db.one(
                        "SELECT id FROM order_lines WHERE order_id=? AND sku_id=? AND is_deleted=0",
                        order,
                        skuId);
        JsonNode refund =
                admin(
                        "POST",
                        "/refunds",
                        obj(
                                "orderId",
                                order,
                                "items",
                                arr(obj("lineId", l(line, "id"), "quantity", quantity)),
                                "shippingCents",
                                0,
                                "reason",
                                runId,
                                "requestKey",
                                id(),
                                "exceptionReason",
                                "隔离验收超期售后"),
                        200);
        long id = l(refund, "id");
        admin(
                "POST",
                "/refunds/" + id + "/review",
                obj("approve", true, "identityDecision", null, "reason", runId),
                200);
        admin(
                "POST",
                "/refunds/" + id + "/inspect",
                obj(
                        "returnedAt",
                        Instant.now().minusSeconds(60).toString(),
                        "inspection",
                        runId,
                        "restock",
                        false,
                        "reason",
                        runId),
                200);
        return id;
    }

    private long sourceRefund(long order, long cents, String status) {
        return db.tx(
                () ->
                        db.insert(
                                "INSERT INTO refunds(order_id,number,kind,status,goods_cents,shipping_cents,total_cents,`lines`,reason,policy_snapshot,request_key,request_id) "
                                        + "VALUES(?,?,'package',?,?,0,?,'[]',?,'{}',?,?)",
                                order,
                                runId + "-" + id(),
                                status,
                                cents,
                                cents,
                                runId,
                                id(),
                                runId));
    }

    private JsonNode debtFor(long reward) throws Exception {
        JsonNode detail = admin("GET", "/rewards/" + reward, null, 200);
        assertEquals(1, detail.path("debts").size());
        return detail.path("debts").get(0);
    }

    private ObjectNode recovery(long reversalId, long cents) {
        return obj(
                "reversalId",
                reversalId,
                "amountCents",
                cents,
                "receivedAt",
                Instant.now().minusSeconds(60).toString(),
                "proof",
                runId,
                "requestKey",
                id(),
                "reason",
                runId);
    }

    private long count(String table, String column, long value) {
        // 表与字段均为本测试方法中的固定常量，不接受外部输入。
        return l(
                db.one(
                        "SELECT COUNT(*) AS total FROM "
                                + table
                                + " WHERE "
                                + column
                                + "=? AND is_deleted=0",
                        value),
                "total");
    }

    private JsonNode admin(String method, String path, JsonNode body, int status) throws Exception {
        return call(method, "/api/admin" + path, body, null, status);
    }

    private JsonNode call(String method, String path, JsonNode body, String token, int expected)
            throws Exception {
        MockHttpServletRequestBuilder request =
                "GET".equals(method) ? get(path) : "PUT".equals(method) ? put(path) : post(path);
        request.header("Host", "localhost").header("Origin", "http://localhost");
        if (token == null) request.cookie(new Cookie("jyj_admin", staffToken));
        else if (token.startsWith("staff:"))
            request.cookie(new Cookie("jyj_admin", token.substring(6)));
        else request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(stringify(body));
        MvcResult result = mvc.perform(request).andReturn();
        String text =
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(expected, result.getResponse().getStatus(), method + " " + path + " " + text);
        assertFalse(text.isEmpty(), "接口必须返回 JSON，包括空值 null");
        return parse(text);
    }
}
