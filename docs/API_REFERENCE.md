# Content Publisher REST API 参考

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 文档基线 | 2026-08-10 |
| API 前缀 | `/api/v1` |
| 实现入口 | `publisher-web/.../controller/*Controller.java` |
| 请求模型 | `publisher-web/.../dto/*Request.java` |
| 响应模型 | `publisher-web/.../dto/*Response.java` |
| 错误映射 | `GlobalExceptionHandler.java` |

本文档描述当前代码中已经存在的 REST API。新增、删除或修改 Controller、DTO、角色要求、错误码或状态码时，必须在同一变更中更新本文档。

产品长期采用纯个人模式。Portal 已使用 `READY` 和本人内容确认；REST 仍保留多租户、Viewer/Editor/Admin、`approve/reject`、`APPROVED/REJECTED` 与旧错误码作为历史兼容合同。人工平台配置和个人确认通过 Session + CSRF 的 Portal 表单实现，不新增 `/api/v1` 路径。

## 2. 通用协议

### 2.1 认证模式

- `JWT`：使用 `Authorization: Bearer <token>`；Token 至少包含主体、租户和角色。
- `LOCAL`：使用服务端 Session；登录入口为 `/login`，REST API 复用当前会话。
- `DISABLED`：仅用于受控本地开发，所有请求使用默认租户和默认主体。

匿名只允许访问 `/actuator/health/**` 和 `/actuator/info`。其他 Actuator 端点仅 Admin 可访问。

### 2.2 角色

| 角色 | 说明 |
|---|---|
| Viewer | 读取项目、文章、任务、发布记录和监控数据 |
| Editor | Viewer 权限，加上内容创建、编辑、发布、取消任务和失败发布重试 |
| Admin | Editor 权限，加上兼容 REST 审核、渠道管理、软删除、恢复和 AI 设置 |

### 2.3 租户

- 客户端不能提交 `tenantId`。
- 服务端从 JWT Claim 或本地用户读取租户。
- 所有业务查询同时使用资源 ID 与租户 ID。
- 跨租户资源统一返回 404，不暴露资源是否存在。

### 2.4 幂等

下列接口要求 `Idempotency-Key`：

- 项目导入。
- Git、主题和网站三类文章生成。
- 渠道账号创建。
- 单渠道发布、批量发布。
- 兼容的失败发布重试接口。
- 单个或批量失败任务重放。

键格式为 8–128 位字母、数字、点、下划线、冒号或连字符。同租户同键同请求返回原资源；同键不同请求返回 `409 IDEMPOTENCY_KEY_CONFLICT`。

### 2.5 时间、ID 与内容类型

- ID 使用 UUID。
- 时间使用 UTC ISO-8601，例如 `2026-08-10T10:30:00Z`。
- 请求和响应默认使用 `application/json`。
- 异步提交成功返回 `202 Accepted`；项目导入、文章生成和单渠道发布返回 `Location: /api/v1/jobs/{jobId}`。

## 3. 项目与内容来源

| 方法 | 路径 | 角色 | 幂等 | 结果 |
|---|---|---|---|---|
| POST | `/api/v1/projects/imports` | Editor/Admin | 是 | 202，Git 导入任务 |
| GET | `/api/v1/projects/{projectId}` | Viewer/Editor/Admin | 否 | 项目详情 |
| POST | `/api/v1/projects/{projectId}/articles` | Editor/Admin | 是 | 202，Git 项目文章生成任务 |
| POST | `/api/v1/articles/topic-generations` | Editor/Admin | 是 | 202，主题文章生成任务 |
| POST | `/api/v1/articles/website-generations` | Editor/Admin | 是 | 202，网站推荐文章生成任务 |

### 3.1 项目导入请求

`POST /api/v1/projects/imports`：

| 字段 | 类型 | 约束 |
|---|---|---|
| `gitUrl` | string | 必填，最长 2048，仅允许安全的 HTTPS Git 地址 |
| `branch` | string | 可选，最长 255；为空时使用远程默认分支 |

### 3.2 通用生成策略

三类文章生成都包含：

| 字段 | 约束 |
|---|---|
| `language` | 必填，最长 20 |
| `tone` | 必填，最长 50 |
| `minCharacters` | 200–3000 |
| `maxCharacters` | 200–3000，且不小于最小长度 |
| `maxKeywords` | 1–30 |
| `excludedKeywords` | 最多 100 项，每项最长 100 |
| `requiredSections` | 最多 20 项，每项最长 100 |

Git 生成额外使用 `requiredKeywords`，最多 30 项。主题和网站生成把来源关键词作为必选关键词。

### 3.3 主题生成请求

| 字段 | 约束 |
|---|---|
| `topic` | 必填，最长 300 |
| `description` | 必填，最长 4000 |
| `audience` | 必填，最长 500 |
| `articleType` | `TUTORIAL`、`KNOWLEDGE_GUIDE`、`BEST_PRACTICES`、`TROUBLESHOOTING`、`CONCEPT_EXPLAINER` |
| `knowledgeLevel` | `BEGINNER`、`INTERMEDIATE`、`ADVANCED`、`MIXED` |
| `keywords` | 最多 30 项 |
| `referenceNotes` | 最长 10000；属于不可信参考材料 |

### 3.4 网站生成请求

| 字段 | 约束 |
|---|---|
| `websiteUrl` | 必填，最长 2048，必须是 HTTPS |
| `recommendationAngle` | 必填，最长 2000 |
| `audience` | 必填，最长 500 |
| `keywords` | 最多 30 项 |

网站内容获取受 SSRF、重定向、响应体和文本长度限制。

## 4. 文章与版本

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/api/v1/articles/{articleId}` | Viewer/Editor/Admin | 查询当前文章 |
| GET | `/api/v1/articles/{articleId}/versions` | Viewer/Editor/Admin | 查询不可变版本 |
| GET | `/api/v1/articles/{articleId}/draft` | Viewer/Editor/Admin | 查询当前主体的服务端草稿；不存在返回 204 |
| PUT | `/api/v1/articles/{articleId}` | Editor/Admin | 编辑 `DRAFT` 或兼容 `REJECTED` |
| PUT | `/api/v1/articles/{articleId}/draft` | Editor/Admin | 保存当前主体的自动保存草稿 |
| DELETE | `/api/v1/articles/{articleId}/draft` | Editor/Admin | 删除当前主体的服务端草稿 |
| POST | `/api/v1/articles/{articleId}/approve` | Admin | 兼容审核通过；新 Portal 流程不调用 |
| POST | `/api/v1/articles/{articleId}/reject` | Admin | 兼容驳回；新 Portal 流程不调用 |
| DELETE | `/api/v1/articles/{articleId}` | Admin | 软删除文章及关联记录 |
| POST | `/api/v1/articles/{articleId}/restore` | Admin | 从回收站恢复 |

编辑请求必须包含 `expectedVersion`。标题最长 500，摘要最长 2000，中英文 Markdown 各最长 20000；标签最多 15 项，关键词最多 30 项。没有任何英文字段时只更新中文稿；提交英文字段时服务端同时保存英文版本。

草稿保存使用 `baseVersion` 而不是 `expectedVersion`，并按“租户 + 文章 + 当前主体”隔离。草稿不创建正式文章版本，也不能绕过正式保存时的版本冲突检查。正式保存成功后客户端可删除对应草稿。

驳回请求：

```json
{"reason":"请核对项目版本和示例命令"}
```

驳回原因必填，最长 500。

REST 当前没有 `/confirm` 或 `/reopen` 端点。个人确认与重新编辑只通过 Portal 路由提供。`approve` 对 `READY/APPROVED/PUBLISHED` 保持幂等；对 `DRAFT/REJECTED` 仍可产生兼容 `APPROVED`。

## 5. 渠道账号

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/channel-accounts` | Admin | 创建 API 渠道账号 |
| GET | `/api/v1/channel-accounts` | Editor/Admin | 查询账号列表 |
| GET | `/api/v1/channel-accounts/{accountId}` | Editor/Admin | 查询单个账号 |
| PUT | `/api/v1/channel-accounts/{accountId}` | Admin | 修改名称或安全地址 |
| PATCH | `/api/v1/channel-accounts/{accountId}/status` | Admin | 启用或停用 |
| PUT | `/api/v1/channel-accounts/{accountId}/credentials` | Admin | 轮换凭据 |
| POST | `/api/v1/channel-accounts/{accountId}/verify` | Admin | 测试连接并保存结果 |
| DELETE | `/api/v1/channel-accounts/{accountId}?expectedVersion={version}` | Admin | 安全移除 API 账号 |

创建请求包含 `type`、`displayName`、可选 `baseUrl` 和 `credentials`。凭据键必须精确匹配渠道目录；查询响应只返回元数据、版本和验证结果，不返回密文、指纹或明文。

账号资料、状态、凭据更新和删除都要求 `expectedVersion >= 1`。删除成功返回 `204 No Content`；这是保留历史外键的软删除，账号会从普通 GET/list、发布选择、巡检和监控中隐藏，加密凭据、指纹及验证结果会被清除，历史发布记录继续显示原账号名称。删除后的原幂等键可重新用于创建账号，但必须重新提交全部凭据。版本冲突返回 `CHANNEL_ACCOUNT_VERSION_CONFLICT`，账号不存在或已经删除返回 `CHANNEL_ACCOUNT_NOT_FOUND`。

## 6. 发布

| 方法 | 路径 | 角色 | 幂等 | 说明 |
|---|---|---|---|---|
| POST | `/api/v1/articles/{articleId}/publications` | Editor/Admin | 是 | 单渠道发布 |
| POST | `/api/v1/articles/{articleId}/publication-batches` | Editor/Admin | 是 | 最多 20 个账号批量发布 |
| GET | `/api/v1/publications` | Viewer/Editor/Admin | 否 | API 与人工发布统一记录 |
| GET | `/api/v1/publications/{publicationId}` | Viewer/Editor/Admin | 否 | API 发布详情 |
| GET | `/api/v1/publications/link-validation` | Viewer/Editor/Admin | 否 | 校验人工发布结果 URL |

单渠道请求：

```json
{
  "channelAccountId": "00000000-0000-0000-0000-000000000000",
  "canonicalUrl": "https://example.com/original",
  "scheduledAt": "2026-07-23T09:00:00Z"
}
```

`canonicalUrl` 可选、最长 2048，必须是不含凭据的 HTTPS URL。`scheduledAt` 可选；允许当前时间附近立即执行，最远一年。

批量请求把 `channelAccountId` 替换为 `channelAccountIds`，最多 20 个非空且去重后的 UUID。每个账号生成独立任务，并共享确定性的 `batchId`。

`GET /api/v1/publications` 的 `limit` 默认为 50，范围 1–100。返回统一记录，不包含人工发布正文快照。

链接校验参数为 `channelType` 与 `url`，成功返回规范化 URL 和允许域名。

API 发布和批量发布接受文章状态 `READY`、兼容 `APPROVED` 或 `PUBLISHED`。`DRAFT/REJECTED` 返回兼容错误码 `ARTICLE_NOT_APPROVED`；该错误码当前业务语义是“当前内容版本尚未确认”，不是要求另一人审批。

## 7. 任务

| 方法 | 路径 | 角色 | 幂等 | 说明 |
|---|---|---|---|---|
| GET | `/api/v1/jobs/{jobId}` | Viewer/Editor/Admin | 否 | 查询状态、进度和结果 |
| POST | `/api/v1/jobs/{jobId}/cancel` | Editor/Admin | 资源状态幂等 | 取消未领取任务 |
| POST | `/api/v1/jobs/{jobId}/publication-retry` | Editor/Admin | 是 | 为失败发布创建新任务 |
| POST | `/api/v1/job-replays/{jobId}` | Editor/Admin | 是 | 通用重放单个失败任务 |
| POST | `/api/v1/job-replays?jobIds=...` | Editor/Admin | 是 | 原子重放 1–20 个失败任务 |
| DELETE | `/api/v1/jobs/{jobId}` | Admin | 否 | 软删除终态任务 |
| POST | `/api/v1/jobs/{jobId}/restore` | Admin | 否 | 恢复已删除任务 |

任务类型：`IMPORT_PROJECT`、`GENERATE_ARTICLE`、`GENERATE_TOPIC_ARTICLE`、`GENERATE_WEBSITE_ARTICLE`、`PUBLISH_ARTICLE`。

任务状态：`PENDING`、`RUNNING`、`RETRY_WAIT`、`SUCCEEDED`、`FAILED`、`CANCELLED`。

只有未被领取的 `PENDING` 或 `RETRY_WAIT` 可以取消。通用重放只接受 `FAILED` 任务，创建新任务并保留原失败事实；批量重放先验证全部任务，再原子创建最多 20 个新任务。发布任务在外部结果不确定时被门禁，操作者必须先核对第三方平台，系统不会盲目重放。

## 8. 自动化工作区

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/api/v1/generation-presets?sourceType=PROJECT|TOPIC|WEBSITE` | Viewer/Editor/Admin | 查询租户生成预设 |
| POST | `/api/v1/automation/presets` | Admin | 创建或更新命名预设 |
| DELETE | `/api/v1/automation/presets/{presetId}` | Admin | 删除预设 |
| GET | `/api/v1/notifications?includeAcknowledged=false` | Viewer/Editor/Admin | 查询站内通知 |
| POST | `/api/v1/notifications/{notificationId}/acknowledge` | Viewer/Editor/Admin | 确认通知 |
| PUT | `/api/v1/articles/{articleId}/manual/{channelType}/progress` | Editor/Admin | 保存五项人工发布进度 |
| GET | `/api/v1/automation/notification-endpoints` | Admin | 查询 Webhook 端点 |
| POST | `/api/v1/automation/notification-endpoints` | Admin | 保存 Webhook 端点 |
| PATCH | `/api/v1/automation/notification-endpoints/{endpointId}` | Admin | 使用 `enabled` 启用或停用端点 |
| POST | `/api/v1/automation/notification-endpoints/{endpointId}/test` | Admin | 向指定已启用端点异步排队测试通知 |
| DELETE | `/api/v1/automation/notification-endpoints/{endpointId}` | Admin | 删除 Webhook 端点 |
| GET | `/api/v1/automation/webhook-deliveries?limit=50` | Admin | 查询当前租户最近 1–100 条投递状态 |

人工发布进度字段为 `copiedTitle`、`copiedContent`、`openedEditor`、`checkedFormat` 和 `published`，并按租户、文章、渠道、当前主体隔离。

生成预设包含名称、来源类型、语言、语气、长度、关键词上限、必备章节以及来源特定字段；名称在租户和来源类型内唯一。Webhook URL 最长 2048，只允许通过服务端端点策略的 HTTPS 公网地址；保存和发送测试通知时都会重新校验。测试通知只为指定端点创建 `PENDING` 投递，不会扩散到同租户其他端点。投递查询返回端点名称、通知标题、`PENDING|DELIVERED|FAILED` 状态、尝试次数、计划时间、送达时间和有限错误摘要。

## 9. Portal 非 REST 操作

以下端点由 Thymeleaf Portal 使用，不属于 `/api/v1`，不进入 `docs/openapi.json`：

| 方法 | 路径 | 当前角色 | 说明 |
|---|---|---|---|
| POST | `/articles/{articleId}/confirm` | Editor/Admin | 本人确认当前版本；`DRAFT/REJECTED → READY` |
| POST | `/articles/{articleId}/reopen` | Editor/Admin | 将 `READY/APPROVED` 重新进入 `DRAFT`；`PUBLISHED` 拒绝 |
| POST | `/channels/{accountId}/delete` | Admin | 使用 `expectedVersion` 安全移除 API 账号并清除凭据 |
| POST | `/channels/manual/{channelType}/profile` | Admin | 保存具有固定官方入口的平台个人配置 |
| POST | `/channels/manual/{channelType}/login-confirmation` | Admin | 历史兼容：记录人工登录确认时间；当前 Portal 不再提供入口 |

LOCAL 模式使用服务端 Session 和 CSRF Token。写操作由 Spring Security 的 POST `/channels/**` Admin 门禁保护；GET 页面按现有只读角色规则开放。这是历史角色兼容，不表示未来纯个人模式需要渠道管理员审批。

内容确认和重新编辑也使用 Session/JWT 身份、CSRF 与服务端租户过滤。确认重复提交保持幂等；重新编辑会解除未发布基线锁定，但不能用于直接修改已经发布的文章。

配置表单字段：

| 字段 | 约束 |
|---|---|
| `channelType` | 必须与路径一致，且只能是目录中 `manualAvailable=true` 的 24 个渠道 |
| `expectedVersion` | 首次保存为 0；已有配置必须提交当前正版本 |
| `enabled` | 是否在文章人工发布目标中启用 |
| `accountAlias` | 可选，最长 120 |
| `defaultTags` | 逗号或换行分隔；规范化后最多 20 项，每项最长 60 |
| `defaultSection` | 可选，最长 200 |
| `notes` | 可选，最长 1000 |
| `sortOrder` | 0–1000 |

历史登录确认端点只提交 `expectedVersion`。未配置时会创建默认启用配置；已配置时通过乐观锁更新时间。它只保存人工确认时间，不读取 Cookie、Session，不调用平台验证接口，也不保证第三方登录仍有效。当前人工发布主流程使用 `./scripts/dev browser` 启动的本机持久浏览器 Profile；该 Profile 不属于 REST 或 Portal 数据契约。

Portal 捕获应用异常后以 Flash 消息重定向到 `/channels?view=manual#manual-{CHANNEL}`。相关稳定错误码包括：

| 错误码 | 含义 |
|---|---|
| `MANUAL_CHANNEL_PROFILE_INVALID` | 渠道参数、标签、文本长度或排序不合法 |
| `MANUAL_CHANNEL_PROFILE_VERSION_CONFLICT` | 配置版本过期 |
| `MANUAL_CHANNEL_UNAVAILABLE` | 渠道没有受信任的固定官方人工入口 |
| `MANUAL_CHANNEL_DISABLED` | 目标人工平台已停用，不能进入或完成人工发布 |

人工平台配置不接受密码、Cookie、Session、验证码、恢复码或自定义平台 URL。官方入口来自 `ChannelCatalog`。

API 和人工能力彼此独立。DEV、WordPress、GitHub Discussions、Twitter/X、Reddit、Hashnode 和 Medium 即使没有 API 账号也可以进入人工工作区；Discourse、Mastodon 和 Ghost 因没有固定安全编辑入口而返回 `MANUAL_CHANNEL_UNAVAILABLE`。

## 10. 工具与监控

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/markdown/preview` | Editor/Admin | 安全渲染最长 20000 字符的 Markdown |
| GET | `/api/v1/monitoring/summary` | Viewer/Editor/Admin | 返回租户监控快照 |

监控接口可选 `range` 参数：`24h`、`7d`、`30d`，缺省或未知值按 `24h`。响应包含项目、文章、来源、任务、发布、账号和渠道表现统计。

## 11. 健康检查

| 路径 | 权限 | 用途 |
|---|---|---|
| `/actuator/health` | 匿名 | 综合健康 |
| `/actuator/health/liveness` | 匿名 | 存活探针 |
| `/actuator/health/readiness` | 匿名 | 就绪探针 |
| `/actuator/info` | 匿名 | 应用信息 |
| 其他 `/actuator/**` | Admin | metrics、prometheus 等管理端点 |

## 12. 响应模型

### 12.1 任务响应

核心字段包括 `id`、`type`、`status`、`attempt`、`maxAttempts`、`progressPercent`、`progressLabel`、`progressDetail`、`batchId`、`scheduledAt`、`resultResourceId`、`resultResourceType`、错误信息和时间。

### 12.2 文章响应

响应包含来源类型与来源信息、项目 ID、中文与英文内容、标签、关键词、语言、源版本、状态、当前版本和时间。来源类型为 `GIT`、`TOPIC` 或 `WEBSITE`；状态可能为 `DRAFT`、`READY`、`APPROVED`、`PUBLISHED` 或 `REJECTED`。

### 12.3 渠道账号响应

响应包含 ID、类型、展示名称、安全地址、账号版本、启停状态、验证状态、有限验证说明和时间，不包含任何凭据。

### 12.4 错误响应

应用异常、参数校验、缺少请求头和数据库并发冲突使用完整错误体：

```json
{
  "timestamp": "2026-08-10T10:30:00Z",
  "status": 409,
  "code": "IDEMPOTENCY_KEY_CONFLICT",
  "message": "幂等键已用于不同请求",
  "path": "/api/v1/projects/imports",
  "traceId": "c3f...",
  "violations": []
}
```

认证失败、权限不足和强制改密由 Spring Security Filter 链直接返回最小安全错误体：

```json
{
  "status": 403,
  "code": "ACCESS_DENIED",
  "message": "当前账号没有执行此操作的权限"
}
```

## 13. 错误码与 HTTP 映射

| HTTP | 主要错误 |
|---:|---|
| 400 | 请求校验、缺少请求头、无效参数、凭据格式、加密主密钥、AI 设置 |
| 401 | `AUTHENTICATION_REQUIRED` |
| 403 | `ACCESS_DENIED`、`PASSWORD_CHANGE_REQUIRED` |
| 404 | 项目、快照、文章、版本、任务、账号、发布或回收站记录不存在 |
| 409 | 状态冲突、版本冲突、幂等冲突、功能未启用、任务不可取消/重试、活动记录不可删除 |
| 422 | Git、网站、AI 或渠道地址不安全；AI 输出不合格；外链、计划时间或英文稿不合格 |
| 429 | `TENANT_JOB_QUOTA_EXCEEDED` |
| 502 | Git/网站/AI/渠道外部调用、响应解析、加解密或持久化序列化失败 |

错误码是兼容合同。修改错误文案通常兼容；删除或重命名错误码、字段或路径需要明确版本策略。

`ARTICLE_NOT_APPROVED` 暂不重命名，避免破坏旧客户端；当前触发条件是文章不属于 `READY/APPROVED/PUBLISHED`，用户可见文案要求先确认当前内容版本。

## 14. OpenAPI 契约与当前限制

- `docs/openapi.json` 是由 `OpenApiContractTest` 生成并纳入版本控制的契约快照；生产默认关闭在线 `/v3/api-docs`。
- 接口变更后先审阅代码和角色，再运行 `./scripts/openapi update`，随后必须运行 `./scripts/openapi check`。不能通过盲目更新快照掩盖意外路径或模型变化。
- REST API 目前提供资源详情和部分统一列表；项目、文章和任务的复杂筛选主要由 Portal 页面使用应用服务完成。
- 发布外部 API 通常不提供统一幂等能力；结果不确定时不会自动盲目重试。
- Medium 仅支持已有合法 Integration Token 的存量账号，不能创建新账号。
- Portal 个人确认、重新编辑和人工平台配置路由不是 REST API，不增加 OpenAPI 操作数量；`ArticleStatus` 增加 `READY` 会更新相关响应 Schema 的枚举快照。

## 15. 文档同步

Controller 路径、Security 角色规则、DTO 字段、状态码、错误码或响应模型发生变化时，必须同步更新本文档、`FUNCTIONAL_SPEC.md`、`TECHNICAL_DEVELOPMENT.md` 和 README 中受影响的示例。
