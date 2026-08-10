# Content Publisher

Content Publisher 是一个多租户技术内容生产与多渠道分发平台。系统从 Git 仓库、结构化主题或公开网站提取受控事实，通过 OpenAI Chat Completions 兼容服务生成中英文内容，经过草稿自动保存、人工编辑和审核后，以官方 API 或合规人工流程发布到不同平台。

## 当前基线

| 项目 | 内容 |
|---|---|
| 应用版本 | `0.1.0-SNAPSHOT` |
| 文档基线 | 2026-08-10 |
| Java / 构建 | Java 17、Maven Wrapper 3.9.11 |
| Spring Boot | 3.5.16 |
| 数据迁移 | Flyway V1–V20 |
| 生产数据库 | PostgreSQL |
| 架构 | 模块化单体、领域/应用/基础设施/Web 分层 |
| 生产交付目标 | `miles-01` Dokploy + Traefik；仓库模板不代表已经部署 |

当前代码形成“可信来源 → 持久化生成任务 → 草稿自动保存与版本 → Admin 审核 → 时区化计划 → API/人工发布 → 通知、重放、巡检与监控”的闭环。

## 已实现能力

| 能力域 | 状态 | 说明 |
|---|---|---|
| 三类内容来源 | 已实现 | Git 安全导入、结构化主题、带 SSRF 防护的公开网站抓取 |
| AI 与 SEO 辅助 | 已实现 | 租户级 OpenAI 兼容配置、中英文结构化输出、确定性质量校验 |
| 草稿与预设 | 已实现 | 服务端用户级草稿、浏览器自动保存、版本基线冲突检查、生成预设复用 |
| 内容与审核 | 已实现 | 不可变版本、`expectedVersion` 并发控制、Admin 审核与驳回 |
| 持久化任务 | 已实现 | 幂等、配额、进度、批次、定时、租约、退避、取消、恢复和通用失败重放 |
| 发布计划 | 已实现 | 浏览器时区输入转 UTC、DST 间隙/歧义校验、发布日历 |
| API 渠道 | 已实现 | 9 个可新接入渠道，Medium 只保留合法存量账号 |
| 人工渠道 | 已实现 | 17 个平台，五项操作进度、适配、复制、官方入口和外链回填 |
| 动作与通知 | 已实现 | 待办动作台、站内通知、确认、渠道失败/恢复通知 |
| Webhook | 已实现 | 租户端点、去重投递、有限 Payload、退避重试、HTTPS/公网地址校验 |
| 渠道巡检 | 已实现 | 按陈旧时间批量验证，状态变化时生成失败或恢复通知 |
| 监控 | 已实现 | Portal/API 业务快照、Prometheus 低基数任务/发布/渠道 Gauge |
| 契约与供应链 | 已实现 | OpenAPI 快照、JaCoCo、CycloneDX SBOM、OWASP 扫描、Dependabot、CI |
| 生产交付模板 | 已提供 | 非 root 镜像、Dokploy Compose、无应用宿主端口、Traefik 网络、恢复演练脚本 |

## 文档

- [完整业务说明](docs/FUNCTIONAL_SPEC.md)
- [详细技术设计](docs/TECHNICAL_DEVELOPMENT.md)
- [REST API 参考](docs/API_REFERENCE.md)
- [OpenAPI JSON 快照](docs/openapi.json)
- [配置、Dokploy 部署与运维](docs/OPERATIONS.md)
- [内容生成与发布流程](docs/PUBLISHING_WORKFLOW.md)
- [环境变量模板](.env.example)

代码、配置、迁移、接口、页面或部署方式变化时，必须在同一变更中更新相关文档。

## 技术栈与模块

| 层面 | 采用技术 |
|---|---|
| 运行时 | Java 17、Spring Boot 3.5.16 |
| Web | Spring MVC、Thymeleaf、Spring Security、Springdoc |
| 数据 | Spring Data JPA、Hibernate、PostgreSQL、Flyway |
| 外部内容 | Eclipse JGit、Jsoup、CommonMark、Java HttpClient |
| 可观测性 | Actuator、Micrometer、Prometheus |
| 测试 | JUnit 5、AssertJ、Mockito、MockMvc、H2 PostgreSQL mode、Testcontainers |
| 交付 | Maven Wrapper、Docker、Dokploy、GitHub Actions、CycloneDX |

| 模块 | 职责 |
|---|---|
| `publisher-domain` | 领域模型、来源、状态和生成策略；不依赖 Spring |
| `publisher-application` | 应用用例、端口、内容适配、任务、自动化和统一查询 |
| `publisher-infrastructure` | JPA/JDBC、JGit、AI、网站、渠道、加密、Worker、巡检和 Webhook |
| `publisher-web` | REST、Thymeleaf、LOCAL/JWT、安全、OpenAPI、指标和启动入口 |

```text
publisher-web ───────────────┐
                            ▼
publisher-infrastructure → publisher-application → publisher-domain
```

架构边界由 `ArchitectureBoundaryTest` 验证。

## 环境要求

- JDK 17；本机固定使用 `/usr/lib/jvm/java-17-openjdk-amd64`。
- 使用仓库自带的 `./mvnw`，不依赖全局 Maven。
- PostgreSQL；正式运行不使用 H2。
- 本地容器化数据库和真实 PostgreSQL 集成测试需要可访问的 Docker daemon。
- AI、Git、网站和渠道调用需要满足主机允许列表与公网地址策略。

## 常用自动化命令

```bash
cd /data/projects/content-publisher

./scripts/dev doctor          # 环境诊断
./scripts/dev compose-check   # 只渲染 Compose，不要求 daemon
./scripts/dev dev-up          # 启动本地 PostgreSQL
./scripts/dev run             # DISABLED 模式本地运行
./scripts/dev verify          # clean verify、测试、JaCoCo、SBOM
./scripts/openapi check       # 校验 OpenAPI 快照
./scripts/openapi update      # Controller 合法变化后更新快照
./scripts/dev security        # OWASP Dependency Check；优先读取 NVD_API_KEY，否则使用每日 NVD 缓存
./scripts/release             # 干净 Git 工作区才生成不可变 JAR/SBOM/属性/SHA-256 目录
```

真实 PostgreSQL Testcontainers 可在具备 Docker socket 权限的环境执行：

```bash
./scripts/dev integration-container
```

默认 `verify` 中的 Testcontainers 和恢复演练会在前置条件不满足时跳过；跳过不能视为真实 PostgreSQL 或恢复路径已通过。

## 本地启动

推荐使用开发 Compose：

```bash
./scripts/dev dev-up
./scripts/dev run
```

数据库默认监听 `127.0.0.1:55432`。健康检查：

```bash
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

安全模式：

- `DISABLED`：仅受控本地开发。
- `LOCAL`：PostgreSQL 本地账号、BCrypt、Session、CSRF 和首次改密。
- `JWT`：OIDC/JWT Bearer Token，无状态 REST。

生产必须使用 `LOCAL` 或 `JWT`。LOCAL 初始化成功后必须移除环境中的初始明文密码。

## 主要工作流

### 内容生产

1. 在 `/projects` 选择 Git、主题或网站来源。
2. 选择或保存生成预设并提交异步任务。
3. 在文章编辑页使用服务端草稿自动保存；正式保存时仍要求正确版本。
4. Admin 审核通过后进入发布阶段。

### API 发布

- 支持立即或计划发布；页面接收 IANA 时区并转换为 UTC。
- DST 不存在时间会被拒绝，歧义时间要求明确偏移。
- 单批最多 20 个去重账号，任务共享批次 ID。
- 外部结果不确定时不自动盲目重试；确认第三方未成功后再使用任务重放。

### 人工发布

- 工作区记录“复制标题、复制正文、打开编辑器、检查格式、已发布”五项进度。
- 系统只提供派生内容、复制和官方入口，不保存第三方密码、Cookie 或验证码，不模拟登录。
- 发布后回填公开 HTTPS URL，服务端校验渠道域名并保存最终快照。

### 运营自动化

- `/actions` 汇总待处理动作和站内通知。
- `/calendar` 展示计划任务与发布记录。
- `/automation` 管理生成预设和通知 Webhook。
- 渠道巡检按配置周期运行，失败及恢复只在状态变化时通知。
- Webhook 每个“通知 × 端点”唯一投递，失败按退避策略最多重试配置次数。

## REST 与 OpenAPI

REST 前缀为 `/api/v1`。接口、角色、幂等和错误合同见 [API 参考](docs/API_REFERENCE.md)。

生产默认：

```text
PUBLISHER_OPENAPI_ENABLED=false
```

`docs/openapi.json` 是版本控制中的契约快照，不需要在线暴露 `/v3/api-docs`。Controller 或请求/响应模型变化后运行：

```bash
./scripts/openapi update
./scripts/openapi check
```

## 配置与秘密

`.env.example` 只提供变量清单，不是生产 Secret。两个 Base64 32 字节主密钥必须独立生成、备份并限制访问：

```bash
openssl rand -base64 32
```

- `PUBLISHER_SECRETS_ENCRYPTION_KEY`：租户 AI API Key。
- `PUBLISHER_CHANNELS_ENCRYPTION_KEY`：渠道凭据。

当前没有主密钥在线轮换迁移；丢失或直接替换会使历史密文不可恢复。

## 构建与不可变发布物

```bash
./scripts/release
```

输出目录：

```text
target/releases/<version>-<12位Git SHA>/
```

包含：

- `content-publisher-<release-id>.jar`
- `content-publisher-<release-id>.cdx.json`
- `release.properties`
- `SHA256SUMS`

Docker 镜像同样必须使用“版本 + Git SHA”不可变标签，禁止正式版本只标记为 `latest`。

## Dokploy 部署边界

正式目标为 `miles-01` 的 Dokploy/Traefik。模板为 `deploy/dokploy-compose.yaml`：

- 应用运行身份 `10001:10001`。
- PostgreSQL 固定以 Alpine 镜像内置的 `70:70` 身份运行。
- 应用只 `expose: 8080`，不配置宿主机 `ports`。
- Traefik 通过外部 `dokploy-network` 访问应用。
- PostgreSQL 仅位于内部 `backend` 网络并使用命名卷。
- 应用根文件系统只读，临时目录使用 tmpfs，启用 `no-new-privileges`。
- 不包含 Caddy；不能与 Dokploy Traefik 混用第二套公网入口。

实际部署前必须确认 Git/镜像交付路径、生产认证方式、管理员策略、两个主密钥、数据库备份/迁移、域名当前源站和 Cloudflare 切换授权。仅存在模板不等于已经部署。

## 健康与指标

- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/prometheus`（Admin）
- `/monitoring`
- `/api/v1/monitoring/summary`

新增低基数 Gauge：

- `publisher.jobs.pending`
- `publisher.jobs.retry_wait`
- `publisher.jobs.running`
- `publisher.jobs.failed`
- `publisher.jobs.oldest_pending.seconds`
- `publisher.publications.published`
- `publisher.publications.failed`
- `publisher.channels.verification_failed`

## SEO 与索引策略

本项目是认证后台而非公开内容站。全部顶层 Thymeleaf 模板必须包含：

```html
<meta name="robots" content="noindex,nofollow">
```

因此本轮自动化页面不进入搜索引擎索引；不存在面向公开搜索流量的结构化数据或站点地图需求。

## 当前限制与风险

- 审核事实仍使用通用审计日志，没有独立审核历史模型。
- 主加密密钥没有版本化和在线迁移。
- Worker 仍为单线程轮询，没有可配置并发和独立死信表。
- PostgreSQL 锁与串行化语义需要在可用 Docker/Testcontainers 环境持续验证。
- 浏览器端尚无完整 E2E 测试。
- Webhook 地址虽在保存和投递前校验 DNS，但 DNS 校验与 Java HttpClient 建连间仍存在 rebinding 时间窗；生产必须配合出站网络策略。
- UTM、效果回收、OAuth 到期提醒尚未实现。
