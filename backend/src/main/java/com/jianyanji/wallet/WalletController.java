package com.jianyanji.wallet;

import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.Access;
import com.jianyanji.common.Input;
import org.springframework.web.bind.annotation.*;

/** 保留公司收款、积分钱包及整单积分支付的原有API与权限。 */
@RestController
public class WalletController {
    /** 当前会话及权限校验。 */
    private final Access access;
    /** 收款与积分业务服务。 */
    private final WalletService wallet;

    public WalletController(Access access, WalletService wallet) {
        this.access = access;
        this.wallet = wallet;
    }

    @GetMapping("/api/wallet")
    public Object view() {
        ObjectNode user = access.user();
        ObjectNode result = wallet.walletView(l(user, "id"));
        result.set("transfer", wallet.companyTransfer());
        return result;
    }

    @GetMapping("/api/wallet/transfer")
    public Object transfer() {
        access.user();
        return wallet.companyTransfer();
    }

    @GetMapping("/api/admin/receipts")
    public Object receipts(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String receiptId,
            @RequestParam(required = false) String externalTransactionId,
            @RequestParam java.util.Map<String, String> query) {
        access.staff("finance:manage");
        return query.containsKey("page")
                ? wallet.adminPage(query)
                : wallet.receipts(userId, receiptId, externalTransactionId);
    }

    @PostMapping("/api/admin/receipts")
    public Object confirmReceipt(@RequestBody JsonNode body) {
        long actor = l(access.staff("finance:manage"), "id");
        return wallet.confirmReceipt(body, actor);
    }

    @GetMapping("/api/admin/wallets/{id}")
    public Object adminView(
            @PathVariable long id, @RequestParam(defaultValue = "true") boolean includeLedger) {
        access.staff("finance:manage");
        return wallet.walletView(Input.id(id), includeLedger);
    }

    @GetMapping("/api/admin/wallets/{id}/ledger")
    public Object ledger(@PathVariable long id, @RequestParam java.util.Map<String, String> query) {
        access.staff("finance:manage");
        return wallet.ledgerPage(Input.id(id), query);
    }

    @PostMapping("/api/admin/wallet-lots/{id}/correct")
    public Object correct(@PathVariable long id, @RequestBody JsonNode body) {
        long actor = l(access.staff("finance:manage"), "id");
        return wallet.correct(Input.id(id), body, actor);
    }

    @PostMapping("/api/orders/{id}/pay/points")
    public Object pay(@PathVariable long id) {
        long userId = l(access.user(), "id");
        return obj(
                "order",
                wallet.payWithPoints(userId, Input.id(id)),
                "wallet",
                wallet.walletView(userId));
    }
}
