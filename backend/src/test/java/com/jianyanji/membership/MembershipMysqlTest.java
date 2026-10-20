package com.jianyanji.membership;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.catalog.CatalogService;
import com.jianyanji.common.*;
import com.jianyanji.orders.OrderService;
import com.jianyanji.payments.*;
import com.jianyanji.wallet.WalletService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;

/** 隔离MySQL中验证V2邀请、真实订单、双渠道到账、全额升级与资格恢复；不模拟真实微信验收。 */
@EnabledIfEnvironmentVariable(named = "WALLET_TEST_MYSQL_URL", matches = ".+")
class MembershipMysqlTest {
    /** 独立测试库。 */
    private Db db;
    /** 产品包服务。 */
    private MembershipService membership;
    /** 实际支付本地账务服务，外部微信使用桩。 */
    private PaymentService payments;
    /** 本地积分钱包。 */
    private WalletService wallet;
    /** 测试商品规格。 */
    private long sku;
    /** 首批有效主理人。 */
    private long principal;
    /** 有效接管主理人。 */
    private long successor;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds =
                new DriverManagerDataSource(
                        System.getenv("WALLET_TEST_MYSQL_URL"),
                        System.getenv().getOrDefault("WALLET_TEST_MYSQL_USER", "root"),
                        System.getenv().getOrDefault("WALLET_TEST_MYSQL_PASSWORD", ""));
        db = new Db(new JdbcTemplate(ds), new DataSourceTransactionManager(ds));
        Access access = mock(Access.class);
        payments = new PaymentService(db, access, null, mock(WechatPayClient.class));
        OrderService orders = new OrderService(db, access, mock(CatalogService.class), payments);
        ReflectionTestUtils.setField(payments, "orders", orders);
        wallet = new WalletService(db, access, orders, payments);
        membership = new MembershipService(db, access, wallet, orders);
        ReflectionTestUtils.setField(payments, "membership", membership);
        db.tx(
                () -> {
                    long category =
                            db.insert("INSERT INTO categories(name,active) VALUES('V2产品包',1)");
                    long product =
                            db.insert(
                                    "INSERT INTO products(category_id,name,subtitle,description,image,detail_image,active) VALUES(?,'真实测试商品','','说明','','',1)",
                                    category);
                    sku =
                            db.insert(
                                    "INSERT INTO skus(product_id,name,unit,stock,reserved,active) VALUES(?,'测试规格','件',100,0,1)",
                                    product);
                    db.putSetting(
                            "shipping", obj("provinces", arr("浙江省"), "pendingMinutes", 30), 1);
                    db.putSetting("aftersales", obj("days", 7, "policy", repeat('售', 2000)), 1);
                });
        principal = newUser();
        successor = newUser();
        bootstrap(principal, "B", null);
        bootstrap(successor, "B", null);
        membership.updateSettings(
                obj(
                        "packages",
                        arr(productPackage("B"), productPackage("C")),
                        "reason",
                        repeat('设', 200)),
                1);
    }
    /** 新建不具有代理身份的独立测试用户与本人地址。 */
    private long newUser() {
        return db.tx(
                () -> {
                    long uid = db.insert("INSERT INTO users(openid) VALUES(?)", "v2-test-" + id());
                    db.insert(
                            "INSERT INTO addresses(user_id,recipient,phone,province,city,district,detail) VALUES(?,'本人','13800000000','浙江省','杭州市','西湖区','测试路')",
                            uid);
                    return uid;
                });
    }
    /** 授权存量核验，必须提供真实依据。 */
    private void bootstrap(long uid, String role, Long pid) {
        membership
                .qualifications()
                .bootstrap(
                        obj(
                                "userId",
                                uid,
                                "identity",
                                role,
                                "principalId",
                                pid,
                                "evidence",
                                "存量资格核验测试依据",
                                "reason",
                                "首批初始化"),
                        1);
    }
    /** 最大文本与固定实付货款、真实商品分摊。 */
    private ObjectNode productPackage(String role) {
        long amount = "B".equals(role) ? 1980000 : 298000;
        return obj(
                "targetIdentity",
                role,
                "name",
                repeat('名', 80),
                "amountCents",
                amount,
                "valueCents",
                amount + 100000,
                "selfUseLabel",
                repeat('自', 40),
                "benefits",
                repeat('权', 2000),
                "conditions",
                repeat('条', 2000),
                "agreement",
                repeat('约', 2000),
                "rightsImage",
                "/assets/test.png",
                "active",
                true,
                "lines",
                arr(obj("skuId", sku, "quantity", 1, "unitCents", amount)));
    }
    /** 提交前解析并明确确认邀请及归属。 */
    private ObjectNode apply(long uid, String role, long inviter) {
        ObjectNode rel = membership.invitations().candidate(null, inviter, role, uid);
        long address =
                l(db.one("SELECT id FROM addresses WHERE user_id=? AND is_deleted=0", uid), "id");
        return membership.apply(
                obj(
                        "targetIdentity",
                        role,
                        "addressId",
                        address,
                        "inviterId",
                        inviter,
                        "principalId",
                        rel.get("principalId"),
                        "relationshipsConfirmed",
                        true,
                        "conditionsMet",
                        true,
                        "agreementAccepted",
                        true,
                        "requestKey",
                        "application-" + id()),
                db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", uid));
    }
    /** 真实核款输入，产品包不发行积分。 */
    private ObjectNode receipt(long amount) {
        return obj(
                "sourceChannel",
                "bank",
                "accountRef",
                repeat('账', 200),
                "externalTransactionId",
                "bank-" + id(),
                "amountCents",
                amount,
                "receivedAt",
                "2020-01-01T00:00:00Z",
                "evidence",
                repeat('凭', 1000),
                "reason",
                repeat('款', 200),
                "verified",
                true);
    }
    /** 本次身份。 */
    private String role(long uid) {
        return s(db.one("SELECT identity FROM users WHERE id=? AND is_deleted=0", uid), "identity");
    }
    /** 写入渠道已成功事实后运行本地资格钩子，验证事务；不声称调用过真实银行或微信。 */
    private void refund(ObjectNode order, long amount, boolean full, String decision) {
        db.tx(
                () -> {
                    db.insert(
                            "INSERT INTO refunds(order_id,number,kind,status,goods_cents,shipping_cents,total_cents,`lines`,reason,policy_snapshot,request_key,request_id) VALUES(?,?,'package','success',?,0,?,'[]','实际退款测试','{}',?,'v2-test')",
                            l(order, "id"),
                            "refund-" + id(),
                            amount,
                            amount,
                            "refund-key-" + id());
                    membership.applyPackageRefundIdentity(
                            db.one(
                                    "SELECT * FROM orders WHERE id=? AND is_deleted=0",
                                    l(order, "id")),
                            full,
                            decision,
                            1,
                            repeat('退', 2000));
                });
    }

    @Test
    void transferActivatesOnceWithoutRewardConfigurationOrPoints() {
        long uid = newUser();
        ObjectNode a = apply(uid, "C", principal);
        assertEquals("D", role(uid));
        ObjectNode input = receipt(298000);
        membership.confirmReceipt(l(a, "id"), input, 1);
        membership.confirmReceipt(l(a, "id"), input, 1);
        ObjectNode approved = membership.applicationView(membership.application(l(a, "id"), uid));
        assertEquals("approved", s(approved, "status"));
        assertEquals("C", role(uid));
        assertEquals(0, l(wallet.walletView(uid), "balanceCents"));
        assertEquals(
                1,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM qualifications WHERE user_id=? AND is_deleted=0",
                                uid),
                        "n"));
        assertEquals(repeat('约', 2000), s(approved.get("package_snapshot"), "agreement"));
        assertEquals(
                1,
                l(
                        db.one(
                                "SELECT COUNT(*) AS n FROM payments WHERE order_id=? AND is_deleted=0",
                                l(approved, "order_id")),
                        "n"));
        ObjectNode order = (ObjectNode) approved.get("order");
        refund(order, 149000, false, "retain");
        assertEquals("C", role(uid));
        refund(order, 149000, true, null);
        assertEquals("D", role(uid));
    }

    @Test
    void wechatUpgradeRefundRestoresValidStoreAndHandsOverNewTeam() {
        long uid = newUser();
        ObjectNode first = apply(uid, "C", principal);
        membership.confirmReceipt(l(first, "id"), receipt(298000), 1);
        long oldChild = newUser();
        ObjectNode old = apply(oldChild, "C", uid);
        membership.confirmReceipt(l(old, "id"), receipt(298000), 1);
        ObjectNode upgrade = apply(uid, "B", principal);
        ObjectNode order = membership.preparePayment(l(upgrade, "id"), uid);
        assertEquals(1980000, l(order, "total_cents"));
        assertEquals("C", role(uid));
        db.tx(
                () -> {
                    db.update(
                            "UPDATE orders SET payment_method='wechat',payment_state='paying',update_user_id=1,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                            l(order, "id"));
                    payments.settlePaid(order, "wechat", "wx-test-" + id());
                });
        assertEquals("B", role(uid));
        assertEquals(
                principal,
                l(
                        db.one(
                                "SELECT principal_id FROM users WHERE id=? AND is_deleted=0",
                                oldChild),
                        "principal_id"));
        long child = newUser();
        ObjectNode newer = apply(child, "C", uid);
        membership.confirmReceipt(l(newer, "id"), receipt(298000), 1);
        assertEquals(
                uid,
                l(
                        db.one("SELECT principal_id FROM users WHERE id=? AND is_deleted=0", child),
                        "principal_id"));
        assertThrows(BusinessException.class, () -> membership.assertRefundRevocable(order));
        membership
                .qualifications()
                .prepareHandover(
                        obj("userId", uid, "principalId", successor, "reason", "退款前指定团队接管"), 1);
        refund(order, 1980000, true, null);
        assertEquals("C", role(uid));
        assertEquals("C", role(child));
        assertEquals(
                successor,
                l(
                        db.one("SELECT principal_id FROM users WHERE id=? AND is_deleted=0", child),
                        "principal_id"));
        assertEquals(uid, l(membership.application(l(newer, "id"), child), "principal_id"));
        assertEquals(0, l(wallet.walletView(uid), "balanceCents"));
    }

    @Test
    void refundOfUpgradeDoesNotRestoreRevokedOriginalQualification() {
        long uid = newUser();
        ObjectNode first = apply(uid, "C", principal);
        membership.confirmReceipt(l(first, "id"), receipt(298000), 1);
        ObjectNode original =
                (ObjectNode)
                        membership
                                .applicationView(membership.application(l(first, "id"), uid))
                                .get("order");
        ObjectNode upgrade = apply(uid, "B", principal);
        membership.confirmReceipt(l(upgrade, "id"), receipt(1980000), 1);
        ObjectNode upgraded =
                (ObjectNode)
                        membership
                                .applicationView(membership.application(l(upgrade, "id"), uid))
                                .get("order");
        refund(original, 298000, true, null);
        assertEquals("B", role(uid));
        refund(upgraded, 1980000, true, null);
        assertEquals("D", role(uid));
    }

    @Test
    void invalidInvitesAndChangedUnpaidRelationsRequireReconfirmation() {
        long uid = newUser(), store = newUser();
        bootstrap(store, "C", principal);
        assertThrows(
                BusinessException.class,
                () -> membership.invitations().candidate(null, store, "B", uid));
        assertThrows(
                BusinessException.class,
                () -> membership.invitations().candidate(null, null, "C", uid));
        ObjectNode a = apply(uid, "C", store);
        membership
                .invitations()
                .adjust(
                        obj(
                                "userId",
                                store,
                                "inviterId",
                                successor,
                                "principalId",
                                successor,
                                "reason",
                                "变更店主归属"),
                        1);
        assertThrows(
                BusinessException.class,
                () -> membership.confirmReceipt(l(a, "id"), receipt(298000), 1));
        membership.reconfirm(
                l(a, "id"),
                obj(
                        "inviterId",
                        store,
                        "principalId",
                        successor,
                        "relationshipsConfirmed",
                        true,
                        "conditionsMet",
                        true),
                uid);
        membership.confirmReceipt(l(a, "id"), receipt(298000), 1);
        assertEquals("C", role(uid));
        String code = s(membership.invitations().mine(store), "shareCode");
        assertEquals(
                store,
                l(membership.invitations().candidate(code, null, "C", newUser()), "inviterId"));
    }

    @Test
    void teamQueriesOnlyExposeOwnedStoreOrderFields() {
        long store = newUser();
        ObjectNode a = apply(store, "C", principal);
        membership.confirmReceipt(l(a, "id"), receipt(298000), 1);
        long other = newUser();
        ObjectNode b = apply(other, "C", successor);
        membership.confirmReceipt(l(b, "id"), receipt(298000), 1);
        db.tx(
                () -> {
                    for (long[] pair : new long[][] {{store, principal}, {other, successor}}) {
                        ObjectNode snapshot =
                                obj(
                                        "identity",
                                        "C",
                                        "principalId",
                                        pair[1],
                                        "lines",
                                        arr(obj("skuId", sku, "name", "允许商品", "quantity", 1)),
                                        "address",
                                        obj("phone", "13999999999", "detail", "不可向团队暴露"));
                        db.insert(
                                "INSERT INTO orders(number,user_id,status,payment_state,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key) VALUES(?,?,'paid','paid',7000,7000,0,?,?,?)",
                                "team-" + id(),
                                pair[0],
                                stringify(snapshot),
                                now(),
                                "team-order-" + id());
                    }
                });
        Access access = mock(Access.class);
        when(access.user())
                .thenReturn(db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", principal));
        TeamController team = new TeamController(db, access);
        String orders = stringify(team.orders());
        assertTrue(orders.contains("7000"));
        assertFalse(orders.contains("13999999999"));
        assertFalse(orders.contains("不可向团队暴露"));
        java.util.List<?> result = (java.util.List<?>) team.orders();
        assertEquals(1, result.size());
    }

    @Test
    void firstNewPrincipalStillRequiresRealPaymentAndDelivery() {
        long uid = newUser();
        long address =
                l(db.one("SELECT id FROM addresses WHERE user_id=? AND is_deleted=0", uid), "id");
        ObjectNode a =
                membership.initialApplication(
                        obj(
                                "userId",
                                uid,
                                "targetIdentity",
                                "B",
                                "addressId",
                                address,
                                "principalId",
                                null,
                                "agreementAccepted",
                                true,
                                "conditionsMet",
                                true,
                                "evidence",
                                "品牌确认的首批新入驻资格",
                                "reason",
                                "初始化第一批主理人",
                                "requestKey",
                                "initial-" + id()),
                        1);
        assertEquals("D", role(uid));
        assertEquals(0, membership.qualifications().list(uid).size());
        membership.confirmReceipt(l(a, "id"), receipt(1980000), 1);
        assertEquals("B", role(uid));
        ObjectNode approved = membership.application(l(a, "id"), uid);
        assertTrue(has(approved, "order_id"));
        assertTrue(has(approved, "receipt_id"));
        assertEquals(0, l(wallet.walletView(uid), "balanceCents"));
    }

    /** 构造中文边界文本。 */
    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }
}
