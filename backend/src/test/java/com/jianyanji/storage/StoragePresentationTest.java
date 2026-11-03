package com.jianyanji.storage;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 寄存展示回归：历史商品图、同规格赠品及旧快照不增加逐商品查询。 */
class StoragePresentationTest {
    @Test
    void pickupUsesHistoricalImageEvenWhenProductWasRemoved() {
        ObjectNode line = obj("id", 3, "sku_id", 8, "is_gift", 0, "quantity", 2,
                "lot_id", 7, "image", "", "source_snapshot", stringify(obj("lines", arr(
                        obj("skuId", 8, "name", "原成交精华", "skuName", "标准装",
                                "image", "/assets/original.png")))));
        Db db = mock(Db.class);
        when(db.list(startsWith("SELECT pl.*"), eq(9L)))
                .thenReturn(Collections.singletonList(line));
        ObjectNode result = new StorageService(db, mock(Access.class))
                .view(obj("id", 9, "address_snapshot", "{}"));
        assertEquals("/assets/original.png", s(result.path("lines").get(0), "image"));
        assertEquals("原成交精华", s(result.path("lines").get(0), "name"));
        assertFalse(result.path("lines").get(0).has("source_snapshot"));
        assertEquals(2, l(result, "storedGoodsQuantity"));
        verify(db).list(contains("p.image"), eq(9L));
        verifyNoMoreInteractions(db);
    }

    @Test
    void oldSnapshotWithoutImageRetainsCurrentProductImage() {
        Db db = mock(Db.class);
        when(db.list(startsWith("SELECT pl.*"), eq(9L)))
                .thenReturn(Collections.singletonList(obj("sku_id", 8, "quantity", 1,
                        "image", "/assets/current.png", "source_snapshot",
                        stringify(obj("lines", arr(obj("skuId", 8, "name", "旧名称")))))));
        ObjectNode result = new StorageService(db, mock(Access.class))
                .view(obj("id", 9, "address_snapshot", "{}"));
        assertEquals("/assets/current.png", s(result.path("lines").get(0), "image"));
        assertEquals("旧名称", s(result.path("lines").get(0), "name"));
    }

    @Test
    void pagedLotsUseGiftSnapshotImageAndPreserveQuantity() {
        ObjectNode row = obj("id", 7, "sku_id", 8, "is_gift", 1,
                "available_qty", 2, "expires_on", "2099-10-28", "image", "/assets/current.png",
                "source_snapshot", stringify(obj("lines", arr(
                        obj("skuId", 8, "isGift", false, "image", "/assets/main.png"),
                        obj("skuId", 8, "isGift", true, "name", "原成交赠品", "image", "/assets/gift.png")))));
        Db db = mock(Db.class, call -> {
            if (call.getMethod().getName().equals("one")) return obj("total", 1);
            if (call.getMethod().getName().equals("list")) {
                assertTrue(call.getArgument(0).toString().contains("p.image"));
                return Collections.singletonList(row);
            }
            return null;
        });
        JsonNode item = new StorageService(db, mock(Access.class))
                .lots(1L, Collections.emptyMap()).path("items").get(0);
        assertEquals("/assets/gift.png", s(item, "image"));
        assertEquals("原成交赠品", s(item, "name"));
        assertEquals(2, l(item, "available_qty"));
        assertEquals(2, mockingDetails(db).getInvocations().size());
    }
}
