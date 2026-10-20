package com.jianyanji.membership;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import com.jianyanji.orders.OrderService;
import com.jianyanji.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 产品包分摊约束、独立审批及成功退款身份处理回归。 */
class MembershipServiceTest {
    /** 数据库边界桩。 */
    private Db db;
    /** 订单服务桩。 */
    private OrderService orders;
    /** 被测产品包服务。 */
    private MembershipService membership;
    /** 已支付的原公司转款产品包订单。 */
    private ObjectNode order;

    @BeforeEach
    void setup() {
        db =
                mock(
                        Db.class,
                        i ->
                                "update".equals(i.getMethod().getName())
                                        ? 1
                                        : RETURNS_DEFAULTS.answer(i));
        orders = mock(OrderService.class);
        membership =
                new MembershipService(db, mock(Access.class), mock(WalletService.class), orders);
        order =
                obj(
                        "id",
                        30,
                        "user_id",
                        7,
                        "kind",
                        "package",
                        "payment_method",
                        "offline",
                        "snapshot",
                        "{\"onboardingApproved\":true}");
        doAnswer(
                        i -> {
                            ((Runnable) i.getArgument(0)).run();
                            return null;
                        })
                .when(db)
                .tx(org.mockito.ArgumentMatchers.<Runnable>any());
    }

    /** 产品包货款与明细分摊总额一致的配置。 */
    private ObjectNode productPackage(String target) {
        return obj(
                "targetIdentity",
                target,
                "name",
                "入驻产品包",
                "amountCents",
                "B".equals(target) ? 1980000 : 298000,
                "valueCents",
                30000,
                "selfUseLabel",
                "自用体验",
                "benefits",
                "身份权益",
                "conditions",
                "入驻条件",
                "agreement",
                "入驻协议",
                "rightsImage",
                "/assets/rights.png",
                "active",
                true,
                "lines",
                arr(
                        obj(
                                "skuId",
                                11,
                                "quantity",
                                1,
                                "unitCents",
                                "B".equals(target) ? 1980000 : 298000)));
    }

    @Test
    void settingsRequireBothIdentitiesAndExactAllocation() {
        ObjectNode b = productPackage("B"), c = productPackage("C");
        assertEquals(
                2, membership.validateSettings(obj("packages", arr(b, c))).get("packages").size());
        assertThrows(
                BusinessException.class,
                () -> membership.validateSettings(obj("packages", arr(b, b))));
        assertThrows(
                BusinessException.class,
                () ->
                        membership.validateSettings(
                                obj("packages", arr(b, c.deepCopy().put("amountCents", 20001)))));
        assertThrows(
                BusinessException.class,
                () ->
                        membership.validateSettings(
                                obj("packages", arr(b, c.deepCopy().put("active", "true")))));
        ObjectNode duplicate = c.deepCopy();
        duplicate.set(
                "lines",
                arr(
                        obj("skuId", 11, "quantity", 1, "unitCents", 10000),
                        obj("skuId", 11, "quantity", 1, "unitCents", 10000)));
        assertThrows(
                BusinessException.class,
                () -> membership.validateSettings(obj("packages", arr(b, duplicate))));
    }

    @Test
    void approvalRequiresConfirmedReceiptAndDeliveryOrder() {
        when(db.one(startsWith("SELECT * FROM onboardings WHERE id="), eq(9L)))
                .thenReturn(obj("id", 9, "status", "submitted", "user_id", 7));
        ObjectNode request =
                obj(
                        "decision",
                        "approved",
                        "inviterId",
                        null,
                        "principalId",
                        1,
                        "conditionsMet",
                        true,
                        "relationshipsConfirmed",
                        true,
                        "reason",
                        "核实资格");
        assertEquals(
                409,
                assertThrows(BusinessException.class, () -> membership.review(9, request, 99))
                        .getStatus());
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "update".equals(i.getMethod().getName())));
    }

    /** 为实际退款核对准备原申请、原收款与累计成功退款事实。 */
    private void refundFacts(long actual, long original, long previouslyRefunded) {
        when(db.one(startsWith("SELECT * FROM onboardings WHERE order_id="), eq(30L), eq(7L)))
                .thenReturn(obj("id", 9, "user_id", 7, "receipt_id", 21, "status", "approved"));
        when(db.one(startsWith("SELECT * FROM company_receipts"), eq(21L), eq(9L), eq(7L)))
                .thenReturn(
                        obj("amount_cents", original, "cash_refunded_cents", previouslyRefunded));
        when(db.one(startsWith("SELECT COALESCE(SUM(total_cents)"), eq(30L)))
                .thenReturn(obj("total", actual));
        when(db.one(startsWith("SELECT identity,identity_valid_until"), eq(7L)))
                .thenReturn(obj("identity", "B", "onboarding_id", 999));
        when(db.one(startsWith("SELECT * FROM users WHERE id="), eq(7L)))
                .thenReturn(obj("identity", "B", "onboarding_id", 999));
    }

    @Test
    void fullRefundRevokesOnlyThisOnboardingAndPreservesLaterIdentity() {
        refundFacts(20000, 20000, 0);
        membership.applyPackageRefundIdentity(order, true, null, 99, "产品包全退");
        verify(db, never()).update(startsWith("UPDATE users"), any(Object[].class));
        verify(db)
                .insert(
                        startsWith("INSERT INTO onboarding_identity_events"),
                        eq(9L),
                        eq(30L),
                        eq("approved"),
                        eq("revoke"),
                        eq(1),
                        eq(20000L),
                        anyString(),
                        eq("产品包全退"),
                        eq(99L),
                        eq(99L));
    }

    @Test
    void partialRefundRequiresDecisionAndRetainDoesNotUpdateUser() {
        refundFacts(10000, 20000, 0);
        assertThrows(
                BusinessException.class,
                () -> membership.applyPackageRefundIdentity(order, false, null, 99, "部分退款"));
        membership.applyPackageRefundIdentity(order, false, "retain", 99, "部分退款符合资格");
        verify(db, never()).update(startsWith("UPDATE users"), any(Object[].class));
        verify(db)
                .update(
                        startsWith("UPDATE company_receipts"),
                        eq(10000L),
                        eq(99L),
                        eq(21L),
                        eq(10000L));
    }

    @Test
    void refundMustMatchOriginalReceiptAndActualSuccess() {
        refundFacts(20001, 20000, 0);
        assertThrows(
                BusinessException.class,
                () -> membership.applyPackageRefundIdentity(order, true, null, 99, "超额退款"));
        refundFacts(10000, 20000, 11000);
        assertThrows(
                BusinessException.class,
                () -> membership.applyPackageRefundIdentity(order, false, "retain", 99, "累计倒退"));
        refundFacts(10000, 20000, 0);
        assertThrows(
                BusinessException.class,
                () -> membership.applyPackageRefundIdentity(order, true, null, 99, "全退金额不足"));
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "update".equals(i.getMethod().getName())));
    }

    @Test
    void repeatedIdentityDecisionIsIdempotentAndCannotBeChanged() {
        refundFacts(10000, 20000, 10000);
        when(db.one(startsWith("SELECT decision,full_refund"), eq(9L), eq(10000L)))
                .thenReturn(obj("decision", "retain", "full_refund", 0));
        membership.applyPackageRefundIdentity(order, false, "retain", 99, "重复通知");
        assertThrows(
                BusinessException.class,
                () -> membership.applyPackageRefundIdentity(order, false, "revoke", 99, "更改结论"));
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "update".equals(i.getMethod().getName())));
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "insert".equals(i.getMethod().getName())));
    }

    @Test
    void refundIdentityCannotRunOutsideTransaction() {
        doThrow(new IllegalStateException("须在退款成功事务内")).when(db).assertTransaction();
        assertThrows(
                IllegalStateException.class,
                () -> membership.applyPackageRefundIdentity(order, true, null, 99, "退款"));
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .noneMatch(i -> "one".equals(i.getMethod().getName())));
    }
}
