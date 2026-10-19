package com.jianyanji.wallet;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import com.jianyanji.orders.OrderService;
import com.jianyanji.payments.PaymentService;
import com.jianyanji.payments.WechatPayClient;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 使用独立MySQL测试库验证真实事务、原批次账务和并发资金互斥；需显式设置测试库URL。 */
@EnabledIfEnvironmentVariable(named = "WALLET_TEST_MYSQL_URL", matches = ".+")
class WalletMysqlTest {
    /** 真实数据库事务组件。 */
    private Db db;
    /** 被测钱包服务。 */
    private WalletService wallet;
    /** 被测现金退款服务。 */
    private CashRefundService cash;
    /** 独立样例用户编号，每个用例新建。 */
    private long userId;
    /** 样例商品规格编号。 */
    private long skuId;

    @BeforeEach
    void setup() {
        DriverManagerDataSource source =
                new DriverManagerDataSource(
                        System.getenv("WALLET_TEST_MYSQL_URL"),
                        System.getenv().getOrDefault("WALLET_TEST_MYSQL_USER", "root"),
                        System.getenv().getOrDefault("WALLET_TEST_MYSQL_PASSWORD", ""));
        db = new Db(new JdbcTemplate(source), new DataSourceTransactionManager(source));
        Access access = mock(Access.class);
        OrderService orders = mock(OrderService.class);
        when(orders.owned(anyLong(), nullable(Long.class)))
                .thenAnswer(
                        i -> {
                            long orderId = i.getArgument(0);
                            Long owner = i.getArgument(1);
                            ObjectNode order =
                                    db.one(
                                            "SELECT * FROM orders WHERE id=? AND is_deleted=0",
                                            orderId);
                            if (order == null || (owner != null && l(order, "user_id") != owner))
                                throw BusinessException.notFound("订单不存在");
                            return order;
                        });
        when(orders.view(any(ObjectNode.class))).thenAnswer(i -> i.getArgument(0));
        PaymentService payments =
                new PaymentService(db, access, orders, mock(WechatPayClient.class));
        wallet = new WalletService(db, access, orders, payments);
        cash = new CashRefundService(db, access, wallet);
        db.tx(
                () -> {
                    userId =
                            db.insert(
                                    "INSERT INTO users(openid,identity) VALUES(?,'C')",
                                    "wallet-test-" + id());
                    long category =
                            db.insert("INSERT INTO categories(name,active) VALUES('测试类目',1)");
                    long product =
                            db.insert(
                                    "INSERT INTO products(category_id,name,subtitle,description,image,detail_image,active) VALUES(?,'事务测试商品','','测试','','',1)",
                                    category);
                    skuId =
                            db.insert(
                                    "INSERT INTO skus(product_id,name,unit,stock,reserved,active) VALUES(?,'测试规格','件',100,10,1)",
                                    product);
                });
    }

    /** 为当前用户登记一笔真实来源清晰的样例收款。 */
    private long credit(long amount) {
        return l(
                wallet.confirmReceipt(
                        obj(
                                "userId",
                                userId,
                                "purpose",
                                "points",
                                "sourceChannel",
                                "bank",
                                "accountRef",
                                "测试账户",
                                "externalTransactionId",
                                id(),
                                "amountCents",
                                amount,
                                "receivedAt",
                                "2020-01-01T00:00:00Z",
                                "evidence",
                                "测试到账凭证",
                                "reason",
                                "测试核实到账",
                                "verified",
                                true),
                        1),
                "id");
    }

    /** 创建持有实物预占的待付款订单，不引入报价及地址模块。 */
    private long order(long amount) {
        return db.tx(
                () -> {
                    long orderId =
                            db.insert(
                                    "INSERT INTO orders(number,user_id,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key) VALUES(?,?,?,?,0,?,?,?)",
                                    "TEST" + id(),
                                    userId,
                                    amount,
                                    amount,
                                    "{\"identity\":\"C\"}",
                                    "2999-01-01T00:00:00Z",
                                    id());
                    db.insert(
                            "INSERT INTO order_lines(order_id,sku_id,quantity,unit_cents) VALUES(?,?,1,?)",
                            orderId,
                            skuId,
                            amount);
                    return orderId;
                });
    }

    /** 余额退款请求内容，使用独立请求编号。 */
    private ObjectNode reserveBody(long receiptId, long amount) {
        return obj(
                "userId",
                userId,
                "receiptId",
                receiptId,
                "amountCents",
                amount,
                "reason",
                "原未用积分退款",
                "requestKey",
                id());
    }

    /** 两个独立线程同时竞争数据库业务锁，均在十秒内完成。 */
    private List<Integer> compete(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Integer> a = executor.submit(() -> outcome(barrier, first));
            Future<Integer> b = executor.submit(() -> outcome(barrier, second));
            Integer[] result = {a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)};
            Arrays.sort(result);
            return Arrays.asList(result);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 返回真实业务状态；未预期的SQL错误直接让用例失败。 */
    private int outcome(CyclicBarrier barrier, Callable<?> work) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            work.call();
            return 200;
        } catch (BusinessException error) {
            return error.getStatus();
        }
    }

    @Test
    void concurrentReceiptRegistrationCreditsOnce() throws Exception {
        ObjectNode request =
                obj(
                        "userId",
                        userId,
                        "purpose",
                        "points",
                        "sourceChannel",
                        "bank",
                        "accountRef",
                        "测试账户",
                        "externalTransactionId",
                        id(),
                        "amountCents",
                        100000,
                        "receivedAt",
                        "2020-01-01T00:00:00Z",
                        "evidence",
                        "到账凭证",
                        "reason",
                        "核款",
                        "verified",
                        true);
        assertEquals(
                Arrays.asList(200, 409),
                compete(
                        () -> wallet.confirmReceipt(request, 1),
                        () -> wallet.confirmReceipt(request, 1)));
        assertEquals(100000, l(wallet.walletView(userId), "balanceCents"));
        assertEquals(1, wallet.walletView(userId).get("lots").size());
    }

    @Test
    void cashRefundAndPointsPaymentCannotReuseSameBalance() throws Exception {
        long receiptId = credit(100000), orderId = order(50000);
        assertEquals(
                Arrays.asList(200, 409),
                compete(
                        () -> cash.reserve(reserveBody(receiptId, 60000), 1, "并发现金"),
                        () -> wallet.payWithPoints(userId, orderId)));
        ObjectNode view = wallet.walletView(userId);
        assertTrue(l(view, "availableCents") == 40000 || l(view, "availableCents") == 50000);
        assertTrue(l(view, "heldCents") == 0 || l(view, "heldCents") == 60000);
    }

    @Test
    void stockFailureRollsBackPointsAllocationsAndLedger() {
        credit(100000);
        long orderId = order(50000);
        db.tx(
                () ->
                        db.update(
                                "UPDATE skus SET reserved=0,update_user_id=1,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                skuId));
        assertEquals(
                "STOCK_SETTLEMENT_CONFLICT",
                assertThrows(BusinessException.class, () -> wallet.payWithPoints(userId, orderId))
                        .getCode());
        assertEquals(100000, l(wallet.walletView(userId), "balanceCents"));
        assertEquals(
                0,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM wallet_spend_allocations WHERE order_id=? AND is_deleted=0",
                                orderId),
                        "n"));
        assertEquals(
                0,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM payments WHERE order_id=? AND is_deleted=0",
                                orderId),
                        "n"));
        assertEquals(
                0,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM wallet_ledger WHERE order_id=? AND is_deleted=0",
                                orderId),
                        "n"));
    }

    @Test
    void crossLotOrderRefundReturnsToOriginalCashSource() {
        long first = credit(5000), second = credit(20000), orderId = order(11000);
        wallet.payWithPoints(userId, orderId);
        db.tx(() -> wallet.restoreOrderPoints(orderId, 8, 7000, 1, "退积分"));
        db.tx(() -> wallet.restoreOrderPoints(orderId, 8, 7000, 1, "重复退积分"));
        assertEquals(
                5000,
                l(
                        db.one(
                                "SELECT remaining_cents FROM wallet_lots WHERE receipt_id=? AND is_deleted=0",
                                first),
                        "remaining_cents"));
        assertEquals(
                16000,
                l(
                        db.one(
                                "SELECT remaining_cents FROM wallet_lots WHERE receipt_id=? AND is_deleted=0",
                                second),
                        "remaining_cents"));
        assertThrows(
                BusinessException.class,
                () -> db.tx(() -> wallet.restoreOrderPoints(orderId, 9, 4001, 1, "超退")));
        assertThrows(
                BusinessException.class, () -> cash.reserve(reserveBody(first, 5001), 1, "现金超退"));
        assertEquals(5000, l(cash.reserve(reserveBody(first, 5000), 1, "合法现金"), "amount_cents"));
    }

    @Test
    void concurrentCashCompletionDebitsOnce() throws Exception {
        long receipt = credit(100000);
        ObjectNode refund = cash.reserve(reserveBody(receipt, 60000), 1, "申请退款");
        ObjectNode resolution =
                obj(
                        "outcome",
                        "success",
                        "recipient",
                        "原付款人",
                        "paidAt",
                        "2020-01-01T00:00:00Z",
                        "proof",
                        "银行核查凭证",
                        "confirmed",
                        true,
                        "confirmedNoPayment",
                        false,
                        "reason",
                        "核实实际退款");
        long refundId = l(refund, "id");
        assertEquals(
                Arrays.asList(200, 200),
                compete(
                        () -> cash.resolve(refundId, resolution, 1, "确认一"),
                        () -> cash.resolve(refundId, resolution, 1, "确认二")));
        assertEquals(40000, l(wallet.walletView(userId), "balanceCents"));
        assertEquals(0, l(wallet.walletView(userId), "heldCents"));
        assertEquals(
                60000,
                l(
                        db.one(
                                "SELECT cash_refunded_cents FROM company_receipts WHERE id=? AND is_deleted=0",
                                receipt),
                        "cash_refunded_cents"));
        assertEquals(
                1,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM wallet_ledger WHERE user_id=? AND kind='cash_refund' AND is_deleted=0",
                                userId),
                        "n"));
    }

    @Test
    void maximumReceiptFieldsRoundTripWithoutTruncation() {
        String account = repeat('账', 200), transaction = repeat('流', 168) + id();
        String evidence = repeat('证', 1000), reason = repeat('因', 200);
        ObjectNode request =
                obj(
                        "userId",
                        userId,
                        "purpose",
                        "points",
                        "sourceChannel",
                        "bank",
                        "accountRef",
                        account,
                        "externalTransactionId",
                        transaction,
                        "amountCents",
                        100000,
                        "receivedAt",
                        "2020-01-01T00:00:00Z",
                        "evidence",
                        evidence,
                        "reason",
                        reason,
                        "verified",
                        true);
        long receiptId = l(wallet.confirmReceipt(request, 1), "id");
        ObjectNode row =
                db.one("SELECT * FROM company_receipts WHERE id=? AND is_deleted=0", receiptId);
        assertEquals(account, s(row, "account_ref"));
        assertEquals(transaction, s(row, "external_transaction_id"));
        assertEquals(evidence, s(row, "evidence"));
        assertEquals(reason, s(row, "reason"));
        assertEquals(1, wallet.receipts(null, null, transaction).size());
        assertEquals(
                400,
                assertThrows(
                                BusinessException.class,
                                () ->
                                        wallet.confirmReceipt(
                                                request.deepCopy().put("accountRef", account + "多"),
                                                1))
                        .getStatus());
        assertEquals(
                400,
                assertThrows(
                                BusinessException.class,
                                () ->
                                        wallet.confirmReceipt(
                                                request.deepCopy()
                                                        .put(
                                                                "externalTransactionId",
                                                                transaction + "多"),
                                                1))
                        .getStatus());
    }

    @Test
    void failedCashRefundPreservesUnboundedNodePaidAtInput() {
        long receiptId = credit(100000);
        ObjectNode refund = cash.reserve(reserveBody(receiptId, 60000), 1, "退款字段边界");
        String paidAt = repeat('时', 3000),
                recipient = repeat('人', 100),
                proof = repeat('证', 1000),
                reason = repeat('因', 200);
        ObjectNode result =
                cash.resolve(
                        l(refund, "id"),
                        obj(
                                "outcome",
                                "failed",
                                "recipient",
                                recipient,
                                "paidAt",
                                paidAt,
                                "proof",
                                proof,
                                "confirmed",
                                false,
                                "confirmedNoPayment",
                                true,
                                "reason",
                                reason),
                        1,
                        "失败释放占用");
        assertEquals(paidAt, s(result, "paid_at"));
        assertEquals(recipient, s(result, "recipient"));
        assertEquals(proof, s(result, "proof"));
        assertEquals(reason, s(result, "last_error"));
        assertEquals(100000, l(wallet.walletView(userId), "availableCents"));
        assertEquals(
                0,
                l(
                        db.one(
                                "SELECT cash_refunded_cents FROM company_receipts WHERE id=? AND is_deleted=0",
                                receiptId),
                        "cash_refunded_cents"));
    }

    /** 生成中文边界样例，同时验证utf8mb4字符长度与完整存储。 */
    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
