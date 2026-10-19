package com.jianyanji.rewards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.jianyanji.common.Access;
import org.springframework.web.bind.annotation.*;

/** 奖励后台管理及代理本人查询接口，权限与原 Node 路由保持一致。 */
@RestController
public class RewardController {
    /** 奖励业务服务，所有业务读写均在数据库事务内完成。 */
    private final RewardService rewards;
    /** 当前请求身份、权限及审计上下文。 */
    private final Access access;

    public RewardController(RewardService rewards, Access access) {
        this.rewards = rewards;
        this.access = access;
    }

    @GetMapping("/api/admin/reward-settings")
    public Object settings() {
        access.staff("rewards:manage");
        JsonNode value = rewards.settingsView();
        return value == null ? NullNode.getInstance() : value;
    }

    @PutMapping("/api/admin/reward-settings")
    public Object settings(@RequestBody JsonNode body) {
        access.staff("rewards:manage");
        return rewards.saveSettings(body);
    }

    @GetMapping("/api/admin/rewards/source/{id}")
    public Object source(@PathVariable("id") String id) {
        access.staff("rewards:manage");
        return rewards.source(RewardService.pathId(id));
    }

    @PostMapping("/api/admin/rewards")
    public Object register(@RequestBody JsonNode body) {
        access.staff("rewards:manage");
        return rewards.register(body);
    }

    @GetMapping("/api/admin/rewards")
    public Object list() {
        access.staff("rewards:manage");
        return rewards.adminRewards();
    }

    @GetMapping("/api/admin/rewards/{id}")
    public Object detail(@PathVariable("id") String id) {
        access.staff("rewards:manage");
        return rewards.detail(RewardService.pathId(id), null, true);
    }

    @PostMapping("/api/admin/rewards/{id}/reconcile")
    public Object reconcile(@PathVariable("id") String id, @RequestBody JsonNode body) {
        access.staff("rewards:manage");
        return rewards.reconcile(RewardService.pathId(id), body);
    }

    @GetMapping("/api/rewards")
    public Object ownRewards() {
        return rewards.ownRewards(com.jianyanji.common.J.l(access.user(), "id"));
    }

    @GetMapping("/api/rewards/{id}")
    public Object ownDetail(@PathVariable("id") String id) {
        long userId = com.jianyanji.common.J.l(access.user(), "id");
        return rewards.detail(RewardService.pathId(id), userId, false);
    }

    @GetMapping("/api/admin/reward-debts")
    public Object debts() {
        access.staff("rewards:manage");
        return rewards.debts();
    }

    @GetMapping("/api/admin/reward-settlements")
    public Object settlements() {
        access.staff("rewards:manage");
        return rewards.settlements();
    }

    @PostMapping("/api/admin/reward-settlements")
    public Object plan(@RequestBody JsonNode body) {
        access.staff("rewards:manage");
        return rewards.plan(body);
    }

    @PostMapping("/api/admin/reward-settlements/{id}/result")
    public Object result(@PathVariable("id") String id, @RequestBody JsonNode body) {
        access.staff("rewards:manage");
        return rewards.result(RewardService.pathId(id), body);
    }

    @PostMapping("/api/admin/reward-recoveries")
    public Object recover(@RequestBody JsonNode body) {
        access.staff("rewards:manage");
        return rewards.recover(body);
    }
}
