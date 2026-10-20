package com.jianyanji.catalog;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.BusinessException;
import com.jianyanji.common.Db;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** 商品档价、公开字段和维护边界回归；数据库仅作显式测试替身。 */
class CatalogServiceTest {
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
