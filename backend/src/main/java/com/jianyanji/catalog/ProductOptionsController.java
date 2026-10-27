package com.jianyanji.catalog;

import com.jianyanji.common.Access;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 后台商品快捷选择；只读名称和来源数量，不开放商品编辑或资金数据。 */
@RestController
public class ProductOptionsController {
    /** 商品与原交易来源选项查询。 */
    private final ProductOptionsService options;
    /** 当前后台身份和业务权限。 */
    private final Access access;

    public ProductOptionsController(ProductOptionsService options, Access access) {
        this.options = options;
        this.access = access;
    }

    /** 商品、规格可跨业务复用；原订单和寄存批次仅允许运营履约人员查询。 */
    @GetMapping("/api/admin/product-options")
    public Object options(@RequestParam Map<String, String> query) {
        String kind = query.getOrDefault("kind", "sku");
        if ("orderLine".equals(kind) || "lot".equals(kind)) access.staff("orders:manage");
        else access.staffAny("catalog:manage", "onboarding:manage", "content:manage", "orders:manage", "finance:manage");
        return options.page(query);
    }
}
