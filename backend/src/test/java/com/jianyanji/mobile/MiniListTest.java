package com.jianyanji.mobile;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import com.jianyanji.catalog.CatalogService;
import com.jianyanji.orders.OrderService;
import com.jianyanji.aftersales.AftersalesService;
import com.jianyanji.membership.MembershipService;
import com.jianyanji.rewards.RewardService;
import com.jianyanji.wallet.WalletService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证真实查询服务的分页边界、当前用户隔离、筛选及批量规格读取。 */
class MiniListTest {
    @Test
    void readingOrdersWithoutExpiredItemsDoesNotEnterGlobalWriteTransaction() {
        Db db=mock(Db.class);
        OrderService orders=new OrderService(db,mock(Access.class),mock(CatalogService.class),mock(com.jianyanji.payments.PaymentService.class));
        orders.expireForUser(7L);
        verify(db).one(startsWith("SELECT id FROM orders WHERE user_id=?"),eq(7L),anyString());
        verifyNoMoreInteractions(db);
    }

    private MiniListService service(Db db, OrderService orders) {
        return new MiniListService(db,new CatalogService(db,mock(Access.class)),orders,
                mock(AftersalesService.class),mock(MembershipService.class),mock(RewardService.class),mock(WalletService.class));
    }

    @Test
    void productsReadTwentyAndBatchSkusInOnlyTwoQueries() {
        List<String> queries=new ArrayList<>();
        List<Object[]> parameters=new ArrayList<>();
        Db db=mock(Db.class,call->{
            if(!call.getMethod().getName().equals("list"))return null;
            String sql=call.getArgument(0);
            queries.add(sql);parameters.add(Arrays.copyOfRange(call.getArguments(),1,call.getArguments().length));
            List<ObjectNode> rows=new ArrayList<>();
            if(sql.contains("FROM products")) {
                for(int i=1;i<=21;i++)rows.add(obj("id",i,"active",1));
            } else {
                for(int i=1;i<=20;i++)rows.add(obj("id",i,"product_id",i,"active",1,"stock",10,"reserved",0,"retail_cents",10000));
            }
            return rows;
        });
        ObjectNode result=service(db,mock(OrderService.class)).products(obj("identity","B"),1,20,3L,false);
        assertEquals(20,result.path("items").size());
        assertTrue(b(result,"hasMore"));assertEquals(2,l(result,"nextPage"));
        assertEquals(3500,l(result.path("items").get(0).path("skus").get(0),"priceCents"));
        assertEquals(2,queries.size());
        assertTrue(queries.get(0).contains("p.category_id=?"));
        assertArrayEquals(new Object[]{3L,21,0},parameters.get(0));
        assertTrue(queries.get(1).contains("product_id IN"));assertEquals(20,parameters.get(1).length);
    }

    @Test
    void featuredIsFilteredBeforeLimitAndSecondPageUsesDatabaseOffset() {
        List<Object[]> arguments=new ArrayList<>();List<String> queries=new ArrayList<>();
        Db db=mock(Db.class,call->{
            if(!call.getMethod().getName().equals("list"))return null;
            queries.add(call.getArgument(0));arguments.add(Arrays.copyOfRange(call.getArguments(),1,call.getArguments().length));return Collections.emptyList();
        });
        MiniListService service=service(db,mock(OrderService.class));
        service.products(null,1,1,null,true);
        assertTrue(queries.get(0).contains("p.featured=1"));
        assertArrayEquals(new Object[]{2,0},arguments.get(0));
        ObjectNode result=service.products(null,2,20,null,false);
        assertArrayEquals(new Object[]{21,20},arguments.get(1));
        assertFalse(b(result,"hasMore"));assertTrue(result.path("nextPage").isNull());
    }

    @Test
    void orderAndRefundFiltersAreAppliedBeforePaginationAndUserComesFromSession() {
        List<String> queries=new ArrayList<>();List<Object[]> arguments=new ArrayList<>();
        Db db=mock(Db.class,call->{
            if(!call.getMethod().getName().equals("list"))return null;
            queries.add(call.getArgument(0));arguments.add(Arrays.copyOfRange(call.getArguments(),1,call.getArguments().length));return Collections.emptyList();
        });
        OrderService orders=mock(OrderService.class);
        MiniListService service=service(db,orders);
        service.mine("orders",obj("id",7),2,20,2,null);
        verify(orders).expireForUser(7L);
        verify(orders,never()).expire();
        assertTrue(queries.get(0).contains("user_id=?"));
        assertTrue(queries.get(0).contains("status IN ('paid','awaiting_shipment')"));
        assertArrayEquals(new Object[]{7L,21,20},arguments.get(0));
        service.mine("refunds",obj("id",7),1,20,0,99L);
        assertTrue(queries.get(1).contains("o.user_id=?"));assertTrue(queries.get(1).contains("r.order_id=?"));
        assertArrayEquals(new Object[]{7L,99L,21,0},arguments.get(1));
    }

    @Test
    void emptyOrDeletedSelectedAddressFallsBackToOnlyOneDefaultAddress() {
        Db db=mock(Db.class);
        when(db.one("SELECT * FROM addresses WHERE user_id=? AND is_deleted=0 ORDER BY is_default DESC,id DESC LIMIT 1",7L)).thenReturn(obj("id",8));
        ObjectNode result=service(db,mock(OrderService.class)).address(7L,99L);
        assertEquals(8,l(result.path("address"),"id"));
        verify(db).one("SELECT * FROM addresses WHERE user_id=? AND id=? AND is_deleted=0",7L,99L);
    }

    @Test
    void defaultPageIsTwentyAndPrivateListsRequireAuthentication() throws Exception {
        MiniListService lists=mock(MiniListService.class);Access access=mock(Access.class);
        when(access.optionalUser()).thenReturn(null);
        when(lists.products(null,1,20,null,false)).thenReturn(obj("items",arr(),"hasMore",false,"nextPage",null));
        org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new MiniListController(lists,access)).setControllerAdvice(new ApiErrors(access)).build();
        mvc.perform(get("/api/mini/products")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        verify(lists).products(null,1,20,null,false);
        when(access.user()).thenThrow(new BusinessException(401,"UNAUTHORIZED","请先登录"));
        mvc.perform(get("/api/mini/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mini/cart/count")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mini/orders/counts")).andExpect(status().isUnauthorized());
        verifyNoMoreInteractions(lists);
    }

    @Test
    void badgeCountsOnlyUseAuthenticatedUserEvenWhenCallerSuppliesAnotherId() throws Exception {
        MiniListService lists=mock(MiniListService.class);Access access=mock(Access.class);
        when(access.user()).thenReturn(obj("id",7));
        when(lists.cartCount(7L)).thenReturn(obj("count",3));
        when(lists.orderCounts(7L)).thenReturn(obj("pending",1,"paid",2,"shipped",0,"completed",4));
        org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new MiniListController(lists,access)).build();
        mvc.perform(get("/api/mini/cart/count").param("userId","99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3));
        mvc.perform(get("/api/mini/orders/counts").param("userId","99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pending").value(1)).andExpect(jsonPath("$.paid").value(2));
        verify(lists).cartCount(7L);verify(lists).orderCounts(7L);verifyNoMoreInteractions(lists);
    }

    @Test
    void teamRejectsStoreAndExpiredPrincipal() throws Exception {
        MiniListService lists=mock(MiniListService.class);Access access=mock(Access.class);
        org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new MiniListController(lists,access)).setControllerAdvice(new ApiErrors(access)).build();
        for(ObjectNode user:Arrays.asList(obj("id",7,"identity","C"),obj("id",7,"identity","B","identity_valid_until","2020-01-01T00:00:00Z"))) {
            when(access.user()).thenReturn(user);
            mvc.perform(get("/api/mini/team/stores")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(lists);
    }

    @Test
    void invalidPaginationIsRejectedBeforeQueryAndLastPageHasNoNextPage() {
        Db db=mock(Db.class);MiniListService service=service(db,mock(OrderService.class));
        assertThrows(BusinessException.class,()->service.products(null,0,20,null,false));
        assertThrows(BusinessException.class,()->service.products(null,1,21,null,false));
        assertThrows(BusinessException.class,()->service.products(null,Integer.MAX_VALUE,20,null,false));
        verifyNoInteractions(db);
        List<ObjectNode> rows=new ArrayList<>();for(int i=0;i<20;i++)rows.add(obj("id",i));
        ObjectNode page=ListPage.result(rows,1,20);
        assertEquals(20,page.path("items").size());assertFalse(b(page,"hasMore"));assertTrue(page.path("nextPage").isNull());
    }
}
