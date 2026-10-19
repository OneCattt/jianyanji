package com.jianyanji.membership;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.catalog.CatalogService;
import com.jianyanji.common.Access;
import com.jianyanji.common.Db;
import com.jianyanji.orders.OrderService;
import com.jianyanji.payments.PaymentService;
import com.jianyanji.wallet.WalletService;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 使用V2独立MySQL测试库验证最长产品包内容、资格快照及退款原因的完整业务存储。 */
@EnabledIfEnvironmentVariable(named = "WALLET_TEST_MYSQL_URL", matches = ".+")
class MembershipMysqlTest {
    @Test
    void maximumPackageFieldsSurviveApplicationApprovalAndRefund() {
        DriverManagerDataSource source =
                new DriverManagerDataSource(
                        System.getenv("WALLET_TEST_MYSQL_URL"),
                        System.getenv().getOrDefault("WALLET_TEST_MYSQL_USER", "root"),
                        System.getenv().getOrDefault("WALLET_TEST_MYSQL_PASSWORD", ""));
        Db db = new Db(new JdbcTemplate(source), new DataSourceTransactionManager(source));
        Access access = mock(Access.class);
        PaymentService payments = mock(PaymentService.class);
        OrderService orders = new OrderService(db, access, mock(CatalogService.class), payments);
        WalletService wallet = new WalletService(db, access, orders, payments);
        MembershipService membership = new MembershipService(db, access, wallet, orders);
        ObjectNode fixture =
                db.tx(
                        () -> {
                            long user =
                                    db.insert(
                                            "INSERT INTO users(openid,identity) VALUES(?,'D')",
                                            "membership-test-" + id());
                            long principal =
                                    db.insert(
                                            "INSERT INTO users(openid,identity) VALUES(?,'B')",
                                            "membership-principal-" + id());
                            long category =
                                    db.insert(
                                            "INSERT INTO categories(name,active) VALUES('产品包类目',1)");
                            long product =
                                    db.insert(
                                            "INSERT INTO products(category_id,name,subtitle,description,image,detail_image,active) VALUES(?,'实物产品包商品','','测试','','',1)",
                                            category);
                            long sku =
                                    db.insert(
                                            "INSERT INTO skus(product_id,name,unit,stock,reserved,active) VALUES(?,'产品包规格','件',10,3,1)",
                                            product);
                            long address =
                                    db.insert(
                                            "INSERT INTO addresses(user_id,recipient,phone,province,city,district,detail) VALUES(?,'收件人','13800000000','浙江省','杭州市','西湖区','测试路1号')",
                                            user);
                            db.putSetting("shipping", obj("provinces", arr("浙江省")), 1);
                            db.putSetting(
                                    "aftersales", obj("days", 7, "policy", repeat('售', 2000)), 1);
                            db.update(
                                    "INSERT INTO reward_settings(id,value,create_user_id,update_user_id) VALUES(1,?,1,1) ON DUPLICATE KEY UPDATE value=VALUES(value),is_deleted=0,update_user_id=1,update_time=CURRENT_TIMESTAMP(3)",
                                    stringify(
                                            obj(
                                                    "storeRewardCents",
                                                    2000,
                                                    "principalRewardCents",
                                                    5000,
                                                    "settlementDay",
                                                    15)));
                            return obj(
                                    "user",
                                    user,
                                    "principal",
                                    principal,
                                    "sku",
                                    sku,
                                    "address",
                                    address);
                        });
        long user = l(fixture, "user"), sku = l(fixture, "sku");
        ObjectNode settings =
                membership.updateSettings(
                        obj(
                                "packages",
                                arr(productPackage("B", sku), productPackage("C", sku)),
                                "reason",
                                repeat('设', 200)),
                        1);
        assertEquals(repeat('约', 2000), s(settings.get("packages").get(0), "agreement"));
        ObjectNode application =
                membership.apply(
                        obj(
                                "targetIdentity",
                                "C",
                                "addressId",
                                l(fixture, "address"),
                                "inviterId",
                                null,
                                "agreementAccepted",
                                true,
                                "requestKey",
                                repeat('申', 68) + id()),
                        db.one("SELECT * FROM users WHERE id=? AND is_deleted=0", user));
        long applicationId = l(application, "id");
        membership.confirmReceipt(
                applicationId,
                obj(
                        "sourceChannel",
                        "bank",
                        "accountRef",
                        repeat('账', 200),
                        "externalTransactionId",
                        repeat('流', 168) + id(),
                        "amountCents",
                        20000,
                        "receivedAt",
                        "2020-01-01T00:00:00Z",
                        "evidence",
                        repeat('凭', 1000),
                        "reason",
                        repeat('款', 200),
                        "verified",
                        true),
                1);
        assertEquals(
                "D",
                s(
                        db.one("SELECT identity FROM users WHERE id=? AND is_deleted=0", user),
                        "identity"));
        assertEquals(0, l(wallet.walletView(user), "balanceCents"));
        assertEquals(
                8, l(db.one("SELECT stock FROM skus WHERE id=? AND is_deleted=0", sku), "stock"));
        assertEquals(
                3,
                l(
                        db.one("SELECT reserved FROM skus WHERE id=? AND is_deleted=0", sku),
                        "reserved"));
        String reviewReason = repeat('批', 200);
        ObjectNode approved =
                membership.review(
                        applicationId,
                        obj(
                                "decision",
                                "approved",
                                "inviterId",
                                null,
                                "principalId",
                                l(fixture, "principal"),
                                "relationshipsConfirmed",
                                true,
                                "conditionsMet",
                                true,
                                "reason",
                                reviewReason),
                        1);
        assertEquals(reviewReason, s(approved, "review_reason"));
        assertEquals(repeat('条', 2000), s(approved.get("qualification_snapshot"), "conditions"));
        assertEquals(
                repeat('售', 2000), s(approved.get("qualification_snapshot"), "afterSalesPolicy"));
        assertTrue(stringify(approved.get("qualification_snapshot")).length() > 4000);
        ObjectNode order = orders.owned(l(approved, "order_id"), user);
        String refundReason = repeat('退', 2000);
        db.tx(
                () -> {
                    db.insert(
                            "INSERT INTO refunds(order_id,number,kind,status,goods_cents,shipping_cents,total_cents,`lines`,reason,policy_snapshot,request_key,request_id) VALUES(?,?,'package','success',20000,0,20000,'[]',?,'{}',?,?)",
                            l(order, "id"),
                            "RF" + id(),
                            "实际退款事实",
                            id(),
                            "产品包容量回归");
                    membership.applyPackageRefundIdentity(order, true, null, 1, refundReason);
                });
        assertEquals(
                refundReason,
                s(
                        db.one(
                                "SELECT review_reason FROM onboardings WHERE id=? AND is_deleted=0",
                                applicationId),
                        "review_reason"));
        assertEquals(
                refundReason,
                s(
                        db.one(
                                "SELECT reason FROM onboarding_identity_events WHERE onboarding_id=? AND is_deleted=0",
                                applicationId),
                        "reason"));
        assertEquals(
                "D",
                s(
                        db.one("SELECT identity FROM users WHERE id=? AND is_deleted=0", user),
                        "identity"));
        assertEquals(
                20000,
                l(
                        db.one(
                                "SELECT cash_refunded_cents FROM company_receipts WHERE id=? AND is_deleted=0",
                                l(approved, "receipt_id")),
                        "cash_refunded_cents"));
    }

    /** 生成达到Node各项文字长度上限的合法产品包。 */
    private static ObjectNode productPackage(String target, long sku) {
        return obj(
                "targetIdentity",
                target,
                "name",
                repeat('包', 80),
                "amountCents",
                20000,
                "valueCents",
                30000,
                "selfUseLabel",
                repeat('用', 40),
                "benefits",
                repeat('益', 2000),
                "conditions",
                repeat('条', 2000),
                "agreement",
                repeat('约', 2000),
                "rightsImage",
                "https://" + repeat('a', 992),
                "active",
                true,
                "lines",
                arr(obj("skuId", sku, "quantity", 2, "unitCents", 10000)));
    }

    /** 按字符生成中文输入边界样例。 */
    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
