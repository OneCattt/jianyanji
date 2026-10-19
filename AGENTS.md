# 项目工程规范

## Frontend Verification

- 前端项目存在 `npm run build:dev` 时，窄范围改动优先运行相关 lint、类型检查或测试，不必每次执行完整构建。
- 仅在用户明确要求或改动具有广泛构建风险时执行完整构建，并提前说明原因。

## Implementation Simplicity And Performance

- 设计与实现应聚焦明确需求和常见业务路径，不为极少数假设场景堆叠大量分支、兼容逻辑或备选方案；确需处理的边缘场景应有明确业务依据。
- 不得以增加兜底能力为由引入冗长的后端计算、重复查询或调用、多层前端回退；优先保持主链路简单高效，避免明显增加接口耗时和前端响应时间。

## Document Rendering Verification

- 生成或修改 DOCX 时仍需执行一次渲染检查；若仅 LibreOffice 等无头渲染环境出现中文字体缺失，而用户已确认本地 Word 显示正常，应将其视为渲染器环境限制，不再反复更换字体、重建文档或延长排查。
- 遇到上述情况，继续完成分页、图片、内容结构、可访问性和文件完整性检查，并在交付时简要说明渲染环境限制，不得声称无头渲染已完整通过。

## Java Toolchain

- 所有 Java 项目统一使用 JDK 8；运行 Maven、Gradle、测试、编译或其他 Java 工具前，必须显式确认并使用 Java 8，不得沿用系统默认的更高版本 JDK。

## Code Conventions

- 代码注释和日志以中文为主；技术关键字、字段名、状态码、类名、方法名及 trace/request ID 可保留英文。
- 新增或修改 Java 实体、DTO、VO、请求/响应类及持久化模型时，类和相关字段必须有完整中文 Javadoc；涉及枚举值、状态码、单位、格式或可空性时须说明约束。

## Spring Boot Diagnosability

- 本节仅适用于新增或修改的外部集成、异步/定时任务、上传、批处理、支付、医保/监管接口、同步及重试流程；普通 CRUD 无需仅为诊断新增持久化。
- 使用 trace/request ID 和业务键记录结构化错误上下文，确保失败可通过生产日志追踪并定位。
- 仅在需要重试或人工补偿时持久化失败与重试状态；批处理须逐条判定，失败项不得计为成功，需补偿时应可查询。
- 交付时按需说明诊断覆盖、验证结果及运行假设。

## MySQL Audit And Logical Deletion

- 新建 MySQL 表统一使用以下审计字段，不创建 `create_user_name`、`update_user_name`：

```sql
`id` BIGINT NOT NULL COMMENT '主键',
`update_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '修改用户ID',
`create_user_id` BIGINT UNSIGNED DEFAULT '0' COMMENT '新增用户ID',
`create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
`update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '修改时间',
`is_deleted` TINYINT UNSIGNED NOT NULL DEFAULT '0' COMMENT '删除标记 0：未删除 1：删除'
```

- 业务数据只允许逻辑删除，禁止 `DELETE`、`TRUNCATE` 及会物理删除数据的 ORM 方法；统一设置 `is_deleted=1`，同时维护 `update_user_id`、`update_time`，正常查询必须过滤已删除数据。
- 逻辑删除涉及唯一索引时，须设计可重复写入方案（如清空可空幂等键或将删除标记纳入唯一键），不得依赖物理删除解决冲突。
- 表缺少逻辑删除字段时，先补充字段及数据库迁移，再实现删除功能。

## Agent skills

### Issue tracker

发布规格、创建或读取任务时，使用本地 Markdown 跟踪器。
先读取 docs/agents/issue-tracker.md。

### Triage labels

设置任务状态时，使用默认五个标签。
先读取 docs/agents/triage-labels.md。

### Domain docs

采用 single-context。
探索业务代码或编写规格前，按 docs/agents/domain.md 读取术语与相关决策。
