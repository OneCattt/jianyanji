package com.jianyanji.mobile;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import com.jianyanji.catalog.CatalogService;
import com.jianyanji.orders.OrderService;
import com.jianyanji.aftersales.AftersalesService;
import com.jianyanji.membership.MembershipService;
import com.jianyanji.rewards.RewardService;
import com.jianyanji.wallet.WalletService;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** 小程序列表查询，数据库只取当前页，统计数据独立于已加载页计算。 */
@Service
public class MiniListService {
    /** 绑定参数的数据库读取。 */
    private final Db db;
    /** 统一商品与身份价格展示。 */
    private final CatalogService catalog;
    /** 订单视图与失效订单处理。 */
    private final OrderService orders;
    /** 售后隐私字段过滤。 */
    private final AftersalesService aftersales;
    /** 入驻快照视图。 */
    private final MembershipService membership;
    /** 奖励展示与统计。 */
    private final RewardService rewards;
    /** 积分统计与公司收款配置。 */
    private final WalletService wallet;

    public MiniListService(Db db, CatalogService catalog, OrderService orders,
            AftersalesService aftersales, MembershipService membership, RewardService rewards, WalletService wallet) {
        this.db=db; this.catalog=catalog; this.orders=orders; this.aftersales=aftersales;
        this.membership=membership; this.rewards=rewards; this.wallet=wallet;
    }

    /** 只转换当前页；额外一条仅用于判断是否还有数据。 */
    private ObjectNode page(String sql, List<Object> args, int page, int limit, Function<ObjectNode,ObjectNode> view) {
        int offset=ListPage.offset(page,limit);
        List<Object> parameters=new ArrayList<>(args);
        parameters.add(limit+1); parameters.add(offset);
        List<ObjectNode> rows=db.list(sql+" LIMIT ? OFFSET ?",parameters.toArray());
        ObjectNode result=ListPage.result(rows,page,limit);
        com.fasterxml.jackson.databind.node.ArrayNode items=arr();
        for (JsonNode row: result.path("items")) items.add(view.apply((ObjectNode)row));
        result.set("items",items);
        return result;
    }

    /** 商品列表批量读取本页规格，首页精选在 SQL 中筛选且只读取一条。 */
    public ObjectNode products(JsonNode user,int page,int limit,Long categoryId,boolean featured) {
        String sql="SELECT p.id,p.category_id,p.name,p.subtitle,p.image,p.active,p.featured,p.version FROM products p JOIN categories c ON c.id=p.category_id AND c.is_deleted=0 AND c.active=1 WHERE p.is_deleted=0 AND p.active=1";
        List<Object> args=new ArrayList<>();
        if(categoryId!=null) {sql+=" AND p.category_id=?";args.add(Input.id(categoryId));}
        if(featured) sql+=" AND p.featured=1";
        ObjectNode result=page(sql+" ORDER BY p.id DESC",args,page,limit,Function.identity());
        List<Long> ids=new ArrayList<>();
        for(JsonNode item:result.path("items")) ids.add(l(item,"id"));
        Map<Long,List<ObjectNode>> grouped=new HashMap<>();
        if(!ids.isEmpty()) {
            String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
            for(ObjectNode sku:db.list("SELECT * FROM skus WHERE product_id IN ("+placeholders+") AND is_deleted=0 ORDER BY id",ids.toArray()))
                grouped.computeIfAbsent(l(sku,"product_id"),key->new ArrayList<>()).add(sku);
        }
        com.fasterxml.jackson.databind.node.ArrayNode items=arr();
        for(JsonNode item:result.path("items")) {
            ObjectNode value=catalog.productView((ObjectNode)item,user,false,grouped.getOrDefault(l(item,"id"),Collections.emptyList()));
            value.remove(Arrays.asList("description","detailImage"));
            items.add(value);
        }
        result.set("items",items);
        result.put("identity",user==null?"D":effectiveIdentity(user));
        return result;
    }

    /** 本人业务列表；筛选先于分页执行，不能在前端只过滤已读到的一页。 */
    public ObjectNode mine(String kind,JsonNode user,int page,int limit,int tab,Long orderId) {
        long uid=l(user,"id");
        List<Object> args=new ArrayList<>(Collections.singletonList(uid));
        String sql; Function<ObjectNode,ObjectNode> view=Function.identity();
        switch(kind) {
            case "orders":
                Input.require(tab>=0&&tab<=4,"订单筛选无效");
                orders.expireForUser(uid);
                sql="SELECT * FROM orders WHERE user_id=? AND is_deleted=0";
                sql+=new String[]{""," AND status='pending'"," AND status IN ('paid','awaiting_shipment')"," AND status='shipped'"," AND status='completed'"}[tab];
                sql+=" ORDER BY id DESC"; view=orders::view; break;
            case "refunds":
                sql="SELECT r.* FROM refunds r JOIN orders o ON o.id=r.order_id AND o.is_deleted=0 WHERE o.user_id=? AND r.is_deleted=0";
                if(orderId!=null) {sql+=" AND r.order_id=?";args.add(Input.id(orderId));}
                sql+=" ORDER BY r.id DESC";view=aftersales::view;break;
            case "cash-refunds": sql="SELECT * FROM cash_refunds WHERE user_id=? AND is_deleted=0 ORDER BY id DESC";break;
            case "addresses": sql="SELECT * FROM addresses WHERE user_id=? AND is_deleted=0 ORDER BY is_default DESC,id DESC";break;
            case "onboardings": sql="SELECT * FROM onboardings WHERE user_id=? AND is_deleted=0 ORDER BY id DESC";view=membership::applicationView;break;
            case "qualifications": sql="SELECT id,identity,source_kind,onboarding_id,active,valid_until,create_time FROM qualifications WHERE user_id=? AND is_deleted=0 ORDER BY id DESC";break;
            case "cart":
                ObjectNode cart=page(cartSql()+" ORDER BY c.id",args,page,limit,row->cartItem(row,user));
                cart.setAll(cartSummary(user));return cart;
            default: throw new BusinessException(404,"NOT_FOUND","列表不存在");
        }
        return page(sql,args,page,limit,view);
    }

    /** 按本人地址编号读取；未指定时只读取默认地址，已删除或失效选择回到默认地址。 */
    public ObjectNode address(long uid,Long id) {
        ObjectNode row=id==null?null:db.one("SELECT * FROM addresses WHERE user_id=? AND id=? AND is_deleted=0",uid,Input.id(id));
        if(row==null) row=db.one("SELECT * FROM addresses WHERE user_id=? AND is_deleted=0 ORDER BY is_default DESC,id DESC LIMIT 1",uid);
        return obj("address",row);
    }

    private String cartSql() {
        return "SELECT c.id,c.sku_id AS skuId,c.quantity,c.selected,p.name,p.image,s.name AS skuName,s.unit,s.stock-s.reserved AS stock,s.retail_cents,s.product_type,s.welfare_cents,s.active,p.active AS product_active,p.is_deleted AS product_deleted,s.is_deleted AS sku_deleted,ct.active AS category_active,ct.is_deleted AS category_deleted FROM cart_items c JOIN skus s ON s.id=c.sku_id JOIN products p ON p.id=s.product_id JOIN categories ct ON ct.id=p.category_id WHERE c.user_id=? AND c.is_deleted=0";
    }

    private ObjectNode cartItem(ObjectNode row,JsonNode user) {
        Long price=catalog.rolePrice(row,user);
        return obj("id",row.get("id"),"skuId",row.get("skuId"),"quantity",row.get("quantity"),"selected",b(row,"selected"),
                "name",row.get("name"),"image",row.get("image"),"skuName",row.get("skuName"),"unit",row.get("unit"),"stock",row.get("stock"),
                "productType",catalog.productType(row),"priceCents",price,"available",
                !b(row,"product_deleted")&&!b(row,"sku_deleted")&&!b(row,"category_deleted")&&b(row,"active")&&b(row,"product_active")&&b(row,"category_active")&&price!=null&&l(row,"stock")>=l(row,"quantity"));
    }

    /** 购物袋统计覆盖全部已选项，包括尚未滚动加载的商品。 */
    private ObjectNode cartSummary(JsonNode user) {
        String identity=effectiveIdentity(user);
        int percent="B".equals(identity)?35:"C".equals(identity)?70:100;
        String price="CASE WHEN s.product_type='welfare' THEN s.welfare_cents ELSE FLOOR((s.retail_cents*"+percent+"+50)/100) END";
        return db.one("SELECT COUNT(*) AS kinds,COALESCE(SUM(c.quantity),0) AS pieces,COALESCE(SUM(("+price+")*c.quantity),0) AS totalCents FROM cart_items c JOIN skus s ON s.id=c.sku_id WHERE c.user_id=? AND c.selected=1 AND c.is_deleted=0",l(user,"id"));
    }

    /** 结算时按需读取全部已选商品编号及数量，避免只结算已展示的一页。 */
    public ObjectNode selection(JsonNode user) {
        com.fasterxml.jackson.databind.node.ArrayNode items=arr();
        for(ObjectNode row:db.list(cartSql()+" AND c.selected=1 ORDER BY c.id",l(user,"id"))) {
            Input.require(b(cartItem(row,user),"available"),"已选商品暂不可购买，请检查库存或联系品牌方");
            items.add(obj("skuId",row.get("skuId"),"quantity",row.get("quantity")));
        }
        Input.require(items.size()>0,"请选择商品后结算");
        return obj("items",items);
    }

    /** 钱包首屏只读汇总和收款配置，不读取积分来源批次和历史明细。 */
    public ObjectNode wallet(long uid) { return wallet.summary(uid); }

    /** 积分明细按类型在数据库筛选。 */
    public ObjectNode ledger(long uid,int page,int limit,String kind) {
        Input.require(Arrays.asList("all","credit","spend","order_refund","cash_refund","correction").contains(kind),"积分类型无效");
        String sql="SELECT l.*,o.number AS order_number,r.external_transaction_id FROM wallet_ledger l JOIN company_receipts r ON r.id=l.receipt_id AND r.is_deleted=0 LEFT JOIN orders o ON o.id=l.order_id AND o.is_deleted=0 WHERE l.user_id=? AND l.is_deleted=0";
        List<Object> args=new ArrayList<>(Collections.singletonList(uid));
        if(!"all".equals(kind)){sql+=" AND l.kind=?";args.add(kind);}
        return page(sql+" ORDER BY l.id DESC",args,page,limit,Function.identity());
    }

    public ObjectNode rewards(long uid,int page,int limit,int tab) {return rewards.ownPage(uid,page,limit,tab);}

    /** 奖励详情各历史分区按需分页，归属由奖励服务再次核验。 */
    public ObjectNode rewardDetail(long id,long uid,int page,int limit,String parts) {
        return rewards.ownDetailPage(id,uid,page,limit,parts);
    }

    /** 团队三个分区各自分页，权限由控制器以当前有效身份校验。 */
    public ObjectNode team(long uid,String kind,int page,int limit) {
        if("stores".equals(kind)) return page("SELECT id,nickname,identity FROM users WHERE principal_id=? AND identity='C' AND (identity_valid_until IS NULL OR identity_valid_until>?) AND is_deleted=0 ORDER BY id DESC",Arrays.asList(uid,now()),page,limit,Function.identity());
        if("principals".equals(kind)) return page("SELECT a.id AS application_id,u.id,u.nickname,a.status FROM onboardings a JOIN users u ON u.id=a.user_id AND u.is_deleted=0 WHERE a.inviter_id=? AND a.target_identity='B' AND a.is_deleted=0 ORDER BY a.id DESC",Collections.singletonList(uid),page,limit,Function.identity());
        return page("SELECT o.id,o.number,o.goods_cents,o.status,u.nickname AS store_name,JSON_EXTRACT(o.snapshot,'$.lines') AS `lines`,COALESCE(r.amount_cents,0) AS reward_cents FROM orders o JOIN users u ON u.id=o.user_id AND u.is_deleted=0 LEFT JOIN (SELECT order_id,SUM(amount_cents-reversed_cents) AS amount_cents FROM rewards WHERE user_id=? AND kind='margin' AND is_deleted=0 GROUP BY order_id) r ON r.order_id=o.id WHERE o.kind='goods' AND o.payment_state='paid' AND JSON_UNQUOTE(JSON_EXTRACT(o.snapshot,'$.identity'))='C' AND JSON_UNQUOTE(JSON_EXTRACT(o.snapshot,'$.principalId'))=? AND o.is_deleted=0 ORDER BY o.id DESC",Arrays.asList(uid,String.valueOf(uid)),page,limit,Function.identity());
    }
}
