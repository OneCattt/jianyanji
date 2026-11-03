# 2026-11-03 后端发布

用户授权提交、推送、打包并部署本次后端改动。邀请资格及归属改为在取得业务事务锁后重验；寄存/提货查询增加商品图并优先保留成交快照。升级回归验证C另购完整B包、原C资格和订单保留、重复支付只扣一次。

发布沿用提交SHA作为版本标识，不修改既有0.1.0软件版本。范围仅后端，后台前端沿用当前线上版本；其他未提交页面和资料保留，小程序不上传、不提审、不发布。远端落后本地的4个已有提交随main一并推送。

## 验证及边界

- 显式JDK 1.8.0_452，Maven verify 208项中193通过、15个条件环境测试跳过，无失败或错误。
- 定向InvitationGuardTest、InvitationGuardMysqlTest、MembershipMysqlTest、StoragePresentationTest、StorageMysqlTest合计38项通过，其中32项真实MySQL验证。使用本机3308的独立java_rewards_test_backend_release_20261103数据库，未操作正式业务库。
- 扩大全套集成283项：18个断言失败、17个错误、5项跳过。发布前HEAD干净源码基线275项出现完全相同的35个失败，无新增失败；不能将本次发布描述为全套集成通过。旧用例仍假定直接货款核销、折扣、运费或旧报价结构，另有并发充值断言。报告保存于artifacts/backend-release-20261103/，未扩大本次修改去调整旧规则用例。
- 干净源码最终构建、产物及实际部署结果在完成后追加。本次无新增迁移，V1～V15必须与线上哈希一致，无配置变化。
- 真实有效邀请、真实积分购买、微信真机和小程序页面仍需人工验收。后端部署不代表尚未发布的小程序界面已经上线。

## 备份与回退

使用scripts/deploy-backend-update.py：维护期间停止服务，先备份数据库、旧JAR、后台产物和配置，核对资金汇总及价格，再原子替换JAR；失败仅恢复旧JAR及发布清单，不自动恢复或覆盖业务库。没有数据库结构变化，应用可单独回退。数据恢复需先停写、保全当前数据并对照一致备份及发布后交易制定恢复方案，不能直接整库回灌。

## 实际发布结果

- 上海时间2026-11-03 **19:37:54**发布成功，实际构建和线上后端提交`0329f9ba66451e0037fe1a4317071e96d7f0d436`；业务提交为`6e41e1b`（邀请事务核验与升级测试）、`47ce480`（寄存历史图片）。代码及此前本地4个提交均已推送origin/main。
- 干净源码位于`artifacts/backend-release-0329f9b/source`，从上述提交通过git archive导出。最终JDK8 Maven verify为208项/193通过/15跳过；在同一份干净源码上额外执行38项定向单元及MySQL verify全部通过，并生成最终JAR。扩大集成的35个既有失败与基线比对结果保留，不作为通过项。
- 发布包`artifacts/jianyanji-backend-update-0329f9b.tar.gz`，SHA-256 `ffa94f478e8c21a059c27db118aba1794d27dcd3a05dd72b37b8e088276ff42a`；JAR SHA-256 `1a4f5a1f420fac79c55ef73195f5b4ba68a14340f89ba7adacbf2f7e884f66fd`。
- 服务器发布目录`/opt/jianyanji/releases/backend-update-0329f9b`，备份`/var/backups/jianyanji/20261103T193738+0800-backend-update-0329f9b`，含数据库、旧应用、配置及业务行数/资金/三档价格核对记录。数据库压缩备份完整性检查通过。无迁移、无配置变更。
- 部署后再次独立核对所有发布清单文件哈希、业务表行数、资金汇总及SKU三档价格，均一致；V1～V15无失败或变化。应用与Nginx均active，API维护标记已解除，生产模式devAuth=false。
- 服务器通过本机Nginx及真实域名证书检查后台、公开context及匿名权限隔离；本机直接访问公网`https://api.jianyanji.cn/api/public/context`返回200且devAuth=false。未创建真实客户或执行正式积分交易。
- 后台前端仍为`b2e99437523f7ef0d8a726a56c2110426d110386`，小程序未上传、未提审、未发布，其他工作区未提交改动保留。前述文档中本次后端“待部署”的说明由本记录替代；小程序页面和真实业务验收仍待人工完成。

证据：[产物清单](../../artifacts/backend-release-0329f9b/bundle.json)、[发布结果](../../artifacts/backend-release-0329f9b/deployment-result.json)、[独立发布后核对](../../artifacts/backend-release-0329f9b/production-postcheck.json)、[公网HTTPS检查](../../artifacts/backend-release-0329f9b/public-https-result.json)、[扩大集成基线对照](../../artifacts/backend-release-20261103/baseline-comparison.json)。这些本机证据与产物保存在已忽略artifacts目录，不提交数据库、密钥或备份。

如需应用回退，维护并停服务后，从上述备份application.tar.gz恢复旧JAR及release.json，再启动并检查健康/Flyway。后台前端和数据库无需回退。本次无数据库变更，不自动回灌业务库；数据恢复条件见前节。
