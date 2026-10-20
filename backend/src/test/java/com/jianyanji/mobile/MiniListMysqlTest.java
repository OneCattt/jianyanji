package com.jianyanji.mobile;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import com.jianyanji.catalog.CatalogService;
import com.jianyanji.orders.OrderService;
import com.jianyanji.aftersales.AftersalesService;
import com.jianyanji.membership.MembershipService;
import com.jianyanji.rewards.RewardService;
import com.jianyanji.wallet.WalletService;
import com.jianyanji.payments.PaymentService;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/** 只在显式指定的本机隔离库验证真实 MySQL 分页、金额汇总、混合奖励及地址选择。 */
@EnabledIfEnvironmentVariable(named="MINI_LIST_TEST_MYSQL_URL",matches=".+")
class MiniListMysqlTest {
    @Test
    void fortyOneRowsAreReadAsTwentyTwentyOneWithGlobalTotalsAndPrivateScope() {
        String url=System.getenv("MINI_LIST_TEST_MYSQL_URL");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/java_rewards_test_mini_[A-Za-z0-9_]+(?:\\?.*)?"));
        DriverManagerDataSource source=new DriverManagerDataSource(url,"root","");
        Flyway.configure().dataSource(source).load().migrate();
        Db db=new Db(new JdbcTemplate(source),new DataSourceTransactionManager(source));
        Access access=mock(Access.class);
        CatalogService catalog=new CatalogService(db,access);
        OrderService orders=new OrderService(db,access,catalog,mock(PaymentService.class));
        WalletService wallet=new WalletService(db,access,orders,mock(PaymentService.class));
        RewardService rewards=new RewardService(db,access);
        MiniListService lists=new MiniListService(db,catalog,orders,mock(AftersalesService.class),mock(MembershipService.class),rewards,wallet);
        String run="mini-"+id();
        long principal=db.insert("INSERT INTO users(openid,nickname,identity) VALUES(?,?,'B')",run+"-principal","分页主理人");
        long uid=db.insert("INSERT INTO users(openid,nickname,identity,principal_id) VALUES(?,?,'C',?)",run,"分页店主",principal);
        long other=db.insert("INSERT INTO users(openid,nickname) VALUES(?,?)",run+"-other","隔离用户");
        long category=db.insert("INSERT INTO categories(name,active,sort) VALUES(?,1,0)",run);
        ObjectNode user=obj("id",uid,"identity","C"),owner=obj("id",principal,"identity","B");
        List<Long> skus=new ArrayList<>(),rewardIds=new ArrayList<>();
        for(int i=0;i<41;i++) {
            long product=db.insert("INSERT INTO products(category_id,name,subtitle,description,image,detail_image,active,featured) VALUES(?,?,'说明','说明','/a.png','/a.png',1,?)",category,run+i,i==0?1:0);
            long sku=db.insert("INSERT INTO skus(product_id,name,unit,retail_cents,stock,active) VALUES(?,'规格','瓶',10000,99,1)",product);
            skus.add(sku);
            db.insert("INSERT INTO cart_items(user_id,sku_id,quantity,selected) VALUES(?,?,1,1)",uid,sku);
            long order=db.insert("INSERT INTO orders(number,user_id,status,payment_state,payment_method,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key) VALUES(?,?,?,'paid','points',7000,7000,0,?,'2099-01-01T00:00:00.000Z',?)",
                    run+"-order-"+i,uid,i<21?"paid":"shipped",stringify(obj("identity","C","principalId",principal,"lines",arr())),run+"-request-"+i);
            rewardIds.add(db.insert("INSERT INTO rewards(order_id,user_id,kind,amount_cents,snapshot,reason,request_id) VALUES(?,?,'margin',100,?,'隔离分页验证',?)",order,principal,stringify(obj("orderNumber",run+"-order-"+i)),run));
        }
        long hidden=db.insert("INSERT INTO products(category_id,name,subtitle,description,image,detail_image,active,is_deleted) VALUES(?,'已删除','说明','说明','/a.png','/a.png',1,1)",category);
        Set<Long> productIds=new HashSet<>();
        for(int page=1;page<=3;page++) {
            ObjectNode result=lists.products(user,page,20,category,false);
            assertEquals(page<3?20:1,result.path("items").size());
            assertEquals(page<3,b(result,"hasMore"));
            for(JsonNode item:result.path("items")) {
                assertTrue(productIds.add(l(item,"id")));assertNotEquals(hidden,l(item,"id"));
                assertEquals(7000,l(item.path("skus").get(0),"priceCents"));
            }
        }
        assertEquals(41,productIds.size());
        assertEquals(1,lists.products(user,1,1,category,true).path("items").size());
        ObjectNode cart=lists.mine("cart",user,1,20,0,null);
        assertEquals(20,cart.path("items").size());assertEquals(41,l(cart,"kinds"));assertEquals(287000,l(cart,"totalCents"));
        assertEquals(41,lists.selection(user).path("items").size());
        assertEquals(0,lists.mine("cart",obj("id",other,"identity","D"),1,20,0,null).path("items").size());
        assertEquals(1,lists.mine("orders",user,2,20,2,null).path("items").size(),"筛选后的二十一笔待发货订单应分两页");
        assertEquals(0,lists.mine("orders",obj("id",other),1,20,0,null).path("items").size());
        long chosen=0;
        for(int i=0;i<25;i++) chosen=db.insert("INSERT INTO addresses(user_id,recipient,phone,province,city,district,detail,is_default) VALUES(?,'收货人','13800138000','省','市','区',?,?)",uid,"地址"+i,i==0?1:0);
        assertEquals(20,lists.mine("addresses",user,1,20,0,null).path("items").size());
        assertEquals(chosen,l(lists.address(uid,chosen).path("address"),"id"));
        assertTrue(lists.address(other,chosen).path("address").isNull());
        long receipt=db.insert("INSERT INTO company_receipts(user_id,purpose,source_channel,account_ref,external_transaction_id,amount_cents,received_at,evidence,reason,confirmed_by,confirmed_at) VALUES(?,'points','bank','隔离账户',?,4100,?,'隔离凭证','隔离测试',0,?)",uid,run,now(),now());
        long lot=db.insert("INSERT INTO wallet_lots(user_id,receipt_id,issued_cents,remaining_cents) VALUES(?,?,4100,4100)",uid,receipt);
        for(int i=0;i<41;i++) db.insert("INSERT INTO wallet_ledger(user_id,receipt_id,lot_id,kind,delta_cents,reference,idempotency_key,reason) VALUES(?,?,?,'credit',100,'隔离来源',?,'隔离分页')",uid,receipt,lot,run+i);
        assertEquals(4100,l(lists.wallet(uid),"availableCents"));assertFalse(lists.wallet(uid).has("ledger"));
        assertEquals(20,lists.ledger(uid,1,20,"credit").path("items").size());
        assertEquals(1,lists.ledger(uid,3,20,"credit").path("items").size());
        assertEquals(0,lists.ledger(uid,1,20,"spend").path("items").size());
        for(int i=0;i<2;i++) {
            db.insert("INSERT INTO reward_reversals(reward_id,refund_id,amount_cents,debt_cents,reason,request_id) VALUES(?,?,20,0,'隔离冲回',?)",rewardIds.get(i),i+1,run);
            db.update("UPDATE rewards SET reversed_cents=20,update_user_id=0,update_time=CURRENT_TIMESTAMP(3) WHERE id=?",rewardIds.get(i));
        }
        Set<String> events=new HashSet<>();
        for(int page=1;page<=3;page++) {
            ObjectNode result=lists.rewards(principal,page,20,0);
            assertEquals(page<3?20:3,result.path("items").size());
            for(JsonNode item:result.path("items")) assertTrue(events.add(s(item,"eventKey")),"混合奖励不能重复或遗漏");
            if(page==1)assertEquals(4060,l(result.path("summary"),"pendingCents"));
        }
        assertEquals(43,events.size());assertEquals(2,lists.rewards(principal,1,20,3).path("items").size());
        assertEquals(0,lists.rewards(other,1,20,0).path("items").size());
        long detailId=rewardIds.get(0);
        for(int i=0;i<40;i++)
            db.insert("INSERT INTO reward_reversals(reward_id,refund_id,amount_cents,debt_cents,reason,request_id) VALUES(?,?,1,0,'隔离详情分页',?)",detailId,i+3,run);
        db.update("UPDATE rewards SET reversed_cents=60,update_user_id=0,update_time=CURRENT_TIMESTAMP(3) WHERE id=?",detailId);
        long settlement=db.insert("INSERT INTO reward_settlements(user_id,month,payee,status,gross_cents,offset_cents,net_cents,plan,reason,request_key,request_id) VALUES(?,'2026-10','隔离收款人','cancelled',40,0,40,?,'隔离详情',?,?)",
                principal,stringify(obj("rewards",arr(obj("rewardId",detailId,"amountCents",40)))),run+"-settlement",run);
        db.insert("INSERT INTO reward_settlements(user_id,month,payee,status,gross_cents,offset_cents,net_cents,plan,reason,request_key,request_id) VALUES(?,'2026-10','其他奖励收款人','cancelled',100,0,100,?,'隔离详情',?,?)",
                principal,stringify(obj("rewards",arr(obj("rewardId",rewardIds.get(1),"amountCents",100)))),run+"-settlement-other",run);
        Set<Long> reversalIds=new HashSet<>();
        for(int page=1;page<=3;page++) {
            ObjectNode detail=lists.rewardDetail(detailId,principal,page,20,page==1?"reversals,settlements,debts,offsets,recoveries":"reversals");
            JsonNode history=detail.path("pages").path("reversals");
            assertEquals(page<3?20:1,history.path("items").size());
            assertEquals(page<3,history.path("hasMore").asBoolean());
            for(JsonNode reversal:history.path("items"))assertTrue(reversalIds.add(l(reversal,"id")));
            if(page==1) {
                assertEquals(detailId,l(detail.path("item"),"id"));
                assertEquals(40,l(detail.path("item"),"remainingCents"));
                assertEquals(1,detail.path("pages").path("settlements").path("items").size());
                assertEquals(settlement,l(detail.path("pages").path("settlements").path("items").get(0),"id"));
            } else {
                assertFalse(detail.has("item"));
                assertEquals(1,detail.path("pages").size());
            }
        }
        assertEquals(41,reversalIds.size());
        assertThrows(BusinessException.class,()->lists.rewardDetail(detailId,other,1,20,"reversals"));
        assertEquals(1,lists.team(principal,"stores",1,20).path("items").size());
        assertEquals(20,lists.team(principal,"orders",1,20).path("items").size());
        assertEquals(0,lists.team(principal,"principals",1,20).path("items").size());
        for(String kind:Arrays.asList("refunds","cash-refunds","onboardings","qualifications"))
            assertEquals(0,lists.mine(kind,user,1,20,0,null).path("items").size());
        long expired=0,foreignExpired=0,unknown=0;
        for(int i=0;i<3;i++) {
            long order=db.insert("INSERT INTO orders(number,user_id,status,payment_state,payment_method,total_cents,goods_cents,shipping_cents,snapshot,expires_at,request_key) VALUES(?,?,'pending',?,'points',100,100,0,'{}','2020-01-01T00:00:00.000Z',?)",
                    run+"-expired-"+i,i==1?other:uid,i==2?"unknown":"unpaid",run+"-expired-request-"+i);
            if(i==0)expired=order;else if(i==1)foreignExpired=order;else unknown=order;
        }
        lists.mine("orders",user,1,20,0,null);
        assertEquals("closed",s(orders.owned(expired,uid),"status"));
        assertEquals("pending",s(orders.owned(foreignExpired,other),"status"));
        assertEquals("pending",s(orders.owned(unknown,uid),"status"));
    }
}
