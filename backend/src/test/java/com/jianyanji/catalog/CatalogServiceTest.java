package com.jianyanji.catalog;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 品牌资料、商品档价、公开字段和维护边界回归；数据库仅作显式测试替身。 */
class CatalogServiceTest {
    @Test
    void privacyFieldsMustBothBeSubmittedEvenWhenEmptyOrNull() {
        Db db = mock(Db.class);
        Access access = mock(Access.class);
        CatalogService service = new CatalogService(db, access);
        for (String field : new String[] {"privacyOperator", "privacyContact"}) {
            for (Object value : new Object[] {"隐私资料", "", null}) {
                ObjectNode body = validContent();
                body.set(field, json(value));
                BusinessException error =
                        assertThrows(BusinessException.class, () -> service.saveContent(body, 1L));
                assertEquals("运营主体全称与隐私联络方式需一并提交", error.getMessage());
            }
        }
        verifyNoInteractions(db, access);
    }

    @Test
    void privacyPairRejectsOnlyOneFilledValue() {
        Db db = mock(Db.class);
        Access access = mock(Access.class);
        CatalogService service = new CatalogService(db, access);
        for (String filled : new String[] {"privacyOperator", "privacyContact"}) {
            ObjectNode body = validContent();
            body.put("privacyOperator", " \t\n");
            body.put("privacyContact", " \t\n");
            body.put(filled, "已填写的隐私资料");
            BusinessException error =
                    assertThrows(BusinessException.class, () -> service.saveContent(body, 1L));
            assertEquals("运营主体全称与隐私联络方式需完整填写或全部留空", error.getMessage());
        }
        verifyNoInteractions(db, access);
    }

    @Test
    void privacyValuesRejectNullNonTextAndExcessLength() {
        Db db = mock(Db.class);
        Access access = mock(Access.class);
        CatalogService service = new CatalogService(db, access);
        for (String field : new String[] {"privacyOperator", "privacyContact"}) {
            int limit = "privacyOperator".equals(field) ? 100 : 300;
            String tooLong = String.join("", Collections.nCopies(limit + 1, "字"));
            for (Object value : new Object[] {null, 123, obj(), arr(), tooLong}) {
                ObjectNode body = validContent();
                body.put("privacyOperator", "运营有限公司");
                body.put("privacyContact", "privacy@example.com");
                body.set(field, json(value));
                BusinessException error =
                        assertThrows(BusinessException.class, () -> service.saveContent(body, 1L));
                assertTrue(error.getMessage().startsWith(field), error.getMessage());
            }
        }
        verifyNoInteractions(db, access);
    }

    @Test
    void explicitBlankPrivacyPairClearsExistingConfigurationAndCanBeRepeated() {
        Db db = transactionalDb();
        Access access = mock(Access.class);
        when(db.setting("content"))
                .thenReturn(obj("privacyOperator", "原运营有限公司", "privacyContact", "原联系方式"));
        CatalogService service = new CatalogService(db, access);
        ObjectNode body = validContent();
        body.put("privacyOperator", " \t\n");
        body.put("privacyContact", "");

        ObjectNode saved = service.saveContent(body, 1L);

        assertEquals("", saved.path("privacyOperator").textValue());
        assertEquals("", saved.path("privacyContact").textValue());
        assertEquals(saved, service.saveContent(body, 1L));
        verify(db, times(2)).putSetting("content", saved, 1L);
        verify(access, times(2)).audit("content.update", "content", "brand", "配置资料");
        verify(db, never()).setting("content");
    }

    @Test
    void omittedPrivacyFieldsAllowUnconfiguredOrLegacyContent() {
        for (ObjectNode current : new ObjectNode[] {null, obj("brandName", "旧品牌资料")}) {
            Db db = transactionalDb();
            when(db.setting("content")).thenReturn(current);
            CatalogService service = new CatalogService(db, mock(Access.class));

            ObjectNode saved = service.saveContent(validContent(), 1L);

            assertEquals("", saved.path("privacyOperator").textValue());
            assertEquals("", saved.path("privacyContact").textValue());
            verify(db).putSetting("content", saved, 1L);
        }
    }

    @Test
    void privacyPairIsTrimmedSavedAndIncludedInPublicContent() {
        Db db = transactionalDb();
        Access access = mock(Access.class);
        CatalogService service = new CatalogService(db, access);
        String operator = String.join("", Collections.nCopies(100, "司"));
        String contact = String.join("", Collections.nCopies(300, "联"));
        ObjectNode body = validContent();
        body.put("privacyOperator", " " + operator + " ");
        body.put("privacyContact", "\t" + contact + "\n");

        ObjectNode saved = service.saveContent(body, 1L);

        assertEquals(operator, s(saved, "privacyOperator"));
        assertEquals(contact, s(saved, "privacyContact"));
        verify(db).putSetting("content", saved, 1L);
        verify(access).audit("content.update", "content", "brand", "配置资料");
        verify(db, never()).setting("content");
        when(db.setting("content")).thenReturn(saved);
        ObjectNode publicView = service.publicContent();
        assertTrue(publicView.path("configured").asBoolean());
        assertEquals(saved, publicView.get("content"));
    }

    @Test
    void omittedPrivacyFieldsPreserveCurrentConfigurationInsideTransaction() {
        Db db = mock(Db.class);
        Access access = mock(Access.class);
        AtomicBoolean inTransaction = new AtomicBoolean();
        when(db.tx(org.mockito.ArgumentMatchers.<Supplier<ObjectNode>>any()))
                .thenAnswer(
                        call -> {
                            inTransaction.set(true);
                            try {
                                return ((Supplier<?>) call.getArgument(0)).get();
                            } finally {
                                inTransaction.set(false);
                            }
                        });
        when(db.setting("content"))
                .thenAnswer(
                        call -> {
                            assertTrue(inTransaction.get(), "必须在写事务内读取当前隐私配置");
                            return obj(
                                    "privacyOperator", "清欣简妍肌运营有限公司",
                                    "privacyContact", "privacy@example.com");
                        });
        CatalogService service = new CatalogService(db, access);

        ObjectNode saved = service.saveContent(validContent(), 1L);

        assertEquals("清欣简妍肌运营有限公司", s(saved, "privacyOperator"));
        assertEquals("privacy@example.com", s(saved, "privacyContact"));
        verify(db).setting("content");
        verify(db).putSetting("content", saved, 1L);
        verify(access).audit("content.update", "content", "brand", "配置资料");
    }

    /** 沿用服务测试的事务回调执行方式，不连接真实数据库。 */
    private Db transactionalDb() {
        Db db = mock(Db.class);
        when(db.tx(org.mockito.ArgumentMatchers.<Supplier<ObjectNode>>any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        return db;
    }

    /** 不含隐私新字段的旧版合法品牌资料请求。 */
    private ObjectNode validContent() {
        return obj(
                "brandName", "清欣简妍肌",
                "introduction", "品牌介绍",
                "heroTitle", "标题",
                "heroSubtitle", "副标题",
                "heroImage", "/assets/hero.png",
                "customerService", "",
                "purchaseNotes", "",
                "companyName", "",
                "bankName", "",
                "bankAccount", "",
                "transferNotes", "",
                "reason", "配置资料");
    }

    @Test
    void fixedRolePriceIgnoresLegacyManualPricesAndExpiredAgentUsesRetail() {
        CatalogService service = new CatalogService(mock(Db.class), mock(Access.class));
        ObjectNode sku = obj("retail_cents", 10000, "store_cents", null, "principal_cents", 6000);
        assertEquals(Long.valueOf(10000), service.rolePrice(sku, null));
        assertEquals(Long.valueOf(7000), service.rolePrice(sku, obj("identity", "C")));
        assertEquals(Long.valueOf(3500), service.rolePrice(sku, obj("identity", "B")));
        assertEquals(
                Long.valueOf(10000),
                service.rolePrice(
                        sku,
                        obj("identity", "B", "identity_valid_until", "2020-01-01T00:00:00.000Z")));
    }

    @Test
    void fractionsAndWelfareHaveOneAuthoritativePrice() {
        CatalogService service = new CatalogService(mock(Db.class), mock(Access.class));
        ObjectNode regular = obj("product_type", "regular", "retail_cents", 10001);
        assertEquals(Long.valueOf(10001), service.rolePrice(regular, null));
        assertEquals(Long.valueOf(7001), service.rolePrice(regular, obj("identity", "C")));
        assertEquals(Long.valueOf(3500), service.rolePrice(regular, obj("identity", "B")));
        ObjectNode welfare =
                obj("product_type", "welfare", "welfare_cents", 1234, "retail_cents", 10001);
        for (String identity : new String[] {"B", "C", "D"}) {
            assertEquals(Long.valueOf(1234), service.rolePrice(welfare, obj("identity", identity)));
        }
        welfare.putNull("welfare_cents");
        assertNull(service.rolePrice(welfare, obj("identity", "C")));
        regular.putNull("retail_cents");
        assertNull(service.rolePrice(regular, obj("identity", "B")));
        regular.put("retail_cents", -1);
        assertNull(service.rolePrice(regular, null));
        welfare.put("product_type", "unknown");
        assertNull(service.rolePrice(welfare, null));
    }

    @Test
    void publicStockExcludesReservationsAndDoesNotLeakAgentPrices() {
        Db db = mock(Db.class);
        when(db.list("SELECT * FROM skus WHERE product_id=? AND is_deleted=0 ORDER BY id", 1L))
                .thenReturn(
                        Collections.singletonList(
                                obj(
                                        "id",
                                        2,
                                        "name",
                                        "30ml",
                                        "unit",
                                        "瓶",
                                        "stock",
                                        10,
                                        "reserved",
                                        3,
                                        "active",
                                        1,
                                        "retail_cents",
                                        10000,
                                        "store_cents",
                                        8000,
                                        "principal_cents",
                                        6000)));
        CatalogService service = new CatalogService(db, mock(Access.class));
        ObjectNode product = obj("id", 1, "active", 1);
        ObjectNode publicView = service.productView(product, null, false);
        assertEquals(7, publicView.path("skus").get(0).path("stock").asInt());
        assertTrue(publicView.path("skus").get(0).path("available").asBoolean());
        assertFalse(publicView.path("skus").get(0).has("principalCents"));
        assertEquals(
                10,
                service.productView(product, null, true).path("skus").get(0).path("stock").asInt());
    }

    @Test
    void incompleteCompanyAccountFailsBeforePersistence() {
        Db db = mock(Db.class);
        CatalogService service = new CatalogService(db, mock(Access.class));
        ObjectNode content =
                obj(
                        "brandName",
                        "清欣简妍肌",
                        "introduction",
                        "品牌介绍",
                        "heroTitle",
                        "标题",
                        "heroSubtitle",
                        "副标题",
                        "heroImage",
                        "/assets/hero.png",
                        "customerService",
                        "",
                        "purchaseNotes",
                        "",
                        "companyName",
                        "只有户名",
                        "bankName",
                        "",
                        "bankAccount",
                        "",
                        "transferNotes",
                        "",
                        "reason",
                        "配置资料");
        assertThrows(BusinessException.class, () -> service.saveContent(content, 1));
        verifyNoInteractions(db);
    }

    @Test
    void manualIdentityPricesAndDuplicateSkuAreRejected() {
        Db db = mock(Db.class);
        CatalogService service = new CatalogService(db, mock(Access.class));
        ObjectNode sku =
                obj(
                        "id",
                        2,
                        "name",
                        "30ml",
                        "unit",
                        "瓶",
                        "retailCents",
                        10000,
                        "storeCents",
                        7000,
                        "principalCents",
                        8000,
                        "stock",
                        10,
                        "active",
                        true);
        ObjectNode product =
                obj(
                        "categoryId",
                        1,
                        "name",
                        "精华",
                        "subtitle",
                        "",
                        "description",
                        "说明",
                        "image",
                        "/assets/product.png",
                        "detailImage",
                        "https://example.com/detail.png",
                        "active",
                        true,
                        "featured",
                        false,
                        "skus",
                        arr(sku),
                        "reason",
                        "维护商品");
        assertThrows(BusinessException.class, () -> service.saveProduct(1L, product, 1));
        sku.remove("storeCents");
        sku.remove("principalCents");
        sku.put("productType", "regular");
        sku.putNull("welfareCents");
        product.set("skus", arr(sku, sku));
        assertThrows(BusinessException.class, () -> service.saveProduct(1L, product, 1));
        verifyNoInteractions(db);
    }
}
