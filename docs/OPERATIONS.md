# Content Publisher 配置、部署与运维手册

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 文档基线 | 2026-08-10 |
| 应用版本 | `0.1.0-SNAPSHOT` |
| 配置来源 | `publisher-web/src/main/resources/application.yml` |
| 本地数据库 | `deploy/dev-compose.yaml` |
| 兼容模板 | `deploy/compose.yaml`、`deploy/content-publisher.service` |
| 正式目标模板 | `deploy/dokploy-compose.yaml` |
| 正式目标 | `miles-01` Dokploy + Traefik；本文不表示已部署 |

本机只承担源码、构建、测试和预发布。正式加载、启动、健康检查和回滚必须在部署端按门禁执行，不得把开发数据库、附件、环境文件或整个工作区同步到生产。

## 2. 运行与交付路径

| 类型 | 路径 |
|---|---|
| 源码 | `/data/projects/content-publisher` |
| 临时文件 | `/data/tmp/content-publisher` |
| 本地发布目录 | `target/releases/<version>-<git-sha>/` |
| 可执行 JAR | `publisher-web/target/content-publisher.jar` |
| SBOM | `target/bom.json` |
| OpenAPI 快照 | `docs/openapi.json` |
| Dokploy Compose | `deploy/dokploy-compose.yaml` |
| PostgreSQL 卷 | `content-publisher-postgres` |

长期服务资产应放在 `/data`。任何含秘密的环境文件和备份都必须限制权限，不能提交到 Git。

## 3. 环境要求与自动化入口

- JDK 17；开发机固定 `/usr/lib/jvm/java-17-openjdk-amd64`。
- Maven Wrapper 3.9.11：统一使用 `./mvnw`。
- PostgreSQL；正式使用 `postgres:17-alpine` 模板。
- Docker Compose v2；仅渲染配置不要求 daemon，容器测试和镜像构建要求 daemon 权限。
- 生产必须使用 `LOCAL` 或 `JWT`，禁止 `DISABLED`。
- 生产入口只由 Dokploy Traefik 提供 TLS，不能启动宿主机 Caddy 或第二套公网入口。

常用入口：

```bash
./scripts/dev doctor
./scripts/dev compose-check
./scripts/dev dev-up
./scripts/dev run
./scripts/dev verify
./scripts/openapi check
./scripts/dev integration-container
./scripts/dev security
./scripts/release
```

`integration-container` 要求 Docker socket 权限。默认测试跳过 Testcontainers 或恢复演练时，不能写成“真实 PostgreSQL/恢复演练已通过”。

## 4. 完整配置清单

`.env.example` 是同步维护的模板。以下默认值来自 `application.yml` 或 Dokploy Compose。

### 4.1 服务、数据库与镜像

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_ADDRESS` | `0.0.0.0` | HTTP 监听地址 |
| `SERVER_PORT` | `8080` | 容器目标端口 |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/content_publisher` | JDBC 地址 |
| `DB_USERNAME` | `content_publisher` | 数据库用户 |
| `DB_PASSWORD` | 空 | 生产必填 Secret |
| `PUBLISHER_SERVICE_DATA_DIR` | `/data/services/content-publisher/data` | 兼容 Compose 辅助变量，不是应用属性 |
| `PUBLISHER_IMAGE_TAG` | 无 | Dokploy 必填的不可变“版本-Git SHA”标签 |

### 4.2 身份与 Session

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `PUBLISHER_SECURITY_MODE` | `DISABLED` | `LOCAL`、`JWT`、`DISABLED`；生产禁止 DISABLED |
| `PUBLISHER_SECURITY_TENANT_CLAIM` | `tenant_id` | JWT 租户 Claim |
| `PUBLISHER_SECURITY_ROLES_CLAIM` | `roles` | JWT 角色 Claim |
| `PUBLISHER_DEFAULT_TENANT` | `local` | DISABLED 默认租户 |
| `PUBLISHER_DEFAULT_SUBJECT` | `local-developer` | DISABLED 默认主体 |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | 空 | JWT 模式必填，LOCAL 模式保持空 |
| `PUBLISHER_LOCAL_ADMIN_USERNAME` | 空 | LOCAL 首次启动管理员；已有本地用户后可与初始密码一起移除 |
| `PUBLISHER_LOCAL_ADMIN_PASSWORD` | 空 | LOCAL 首次启动密码；已有本地用户后必须从运行环境移除 |
| `PUBLISHER_LOCAL_ADMIN_TENANT` | `local` | LOCAL 初始租户 |
| `PUBLISHER_LOCAL_ADMIN_MUST_CHANGE_PASSWORD` | `true` | 首次登录强制改密 |
| `PUBLISHER_SESSION_COOKIE_SECURE` | `false` | TLS 生产必须为 `true`；Dokploy 已固定 |
| `PUBLISHER_SESSION_TIMEOUT` | `30m` | Session 超时 |

### 4.3 持久化任务

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `PUBLISHER_JOBS_WORKER_ENABLED` | `true` | 是否启动 Worker |
| `PUBLISHER_JOBS_MAX_ACTIVE_PER_TENANT` | `20` | 每租户活动任务上限 |
| `PUBLISHER_JOBS_MAX_ATTEMPTS` | `4` | 最大尝试次数 |
| `PUBLISHER_JOBS_POLL_INTERVAL` | `1s` | 轮询间隔 |
| `PUBLISHER_JOBS_LOCK_TIMEOUT` | `5m` | 租约超时 |
| `PUBLISHER_JOBS_INITIAL_RETRY_DELAY` | `10s` | 首次退避 |
| `PUBLISHER_JOBS_MAX_RETRY_DELAY` | `5m` | 最大退避 |

通用失败重放由 Editor/Admin 主动发起；批量接口最多 20 个任务并原子校验。外部发布结果不确定时必须先核对第三方平台，系统不自动盲目重试。

### 4.4 Git 与网站

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `GIT_WORK_DIRECTORY` | `/data/tmp/content-publisher` | 浅克隆临时目录 |
| `GIT_ALLOWED_HOSTS` | `github.com,gitlab.com,gitee.com` | Git 主机允许列表 |
| `GIT_TIMEOUT_SECONDS` | `30` | 克隆超时 |
| `GIT_MAX_REPOSITORY_BYTES` | `104857600` | 最大仓库字节数 |
| `GIT_MAX_FILES` | `2000` | 最大文件数 |
| `GIT_MAX_README_CHARACTERS` | `60000` | README 提取上限 |
| `PUBLISHER_WEBSITE_TIMEOUT` | `20s` | 网站请求超时 |
| `PUBLISHER_WEBSITE_MAX_RESPONSE_BYTES` | `2000000` | 最大响应体 |
| `PUBLISHER_WEBSITE_MAX_TEXT_CHARACTERS` | `100000` | 最大提取文本 |
| `PUBLISHER_WEBSITE_MIN_TEXT_CHARACTERS` | `20` | 最小有效文本 |

### 4.5 AI 与秘密

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `PUBLISHER_AI_ENABLED` | `false` | 环境默认 AI 开关 |
| `PUBLISHER_AI_BASE_URL` | `http://127.0.0.1:11434/v1` | OpenAI 兼容 Base URL |
| `PUBLISHER_AI_API_KEY` | 空 | 环境默认 Key |
| `PUBLISHER_AI_MODEL` | `qwen3:14b` | 模型 |
| `PUBLISHER_AI_TIMEOUT` | `90s` | AI 超时 |
| `PUBLISHER_AI_TEMPERATURE` | `0.2` | 0–1 |
| `PUBLISHER_AI_ALLOWED_HOSTS` | 空 | 空表示任意公网 HTTPS AI 主机 |
| `PUBLISHER_AI_ALLOW_PRIVATE_ADDRESSES` | `false` | 仅受控私网模型可开启 |
| `PUBLISHER_SECRETS_ENCRYPTION_KEY` | 空 | 租户 AI Key 的 Base64 32 字节主密钥 |

### 4.6 渠道

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `PUBLISHER_CHANNELS_ENABLED` | `false` | 渠道创建/发布总开关 |
| `PUBLISHER_CHANNELS_ENCRYPTION_KEY` | 空 | 渠道凭据 Base64 32 字节主密钥 |
| `PUBLISHER_CHANNELS_ALLOWED_HOSTS` | 空 | 自托管渠道主机允许列表 |
| `PUBLISHER_CHANNELS_TIMEOUT` | `30s` | 渠道调用超时 |

两个主密钥均使用：

```bash
openssl rand -base64 32
```

必须独立生成、备份并保持稳定。当前没有在线密钥迁移，丢失或直接替换会导致已有密文不可恢复。

### 4.7 自动化、Webhook、指标和 OpenAPI

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `PUBLISHER_CHANNEL_HEALTH_ENABLED` | `false` | 应用默认巡检开关；Dokploy 默认 `true` |
| `PUBLISHER_CHANNEL_HEALTH_INTERVAL` | `15m` | 调度间隔 |
| `PUBLISHER_CHANNEL_HEALTH_STALE_AFTER` | `24h` | 验证记录陈旧阈值 |
| `PUBLISHER_CHANNEL_HEALTH_BATCH_SIZE` | `50` | 每轮 1–500 |
| `PUBLISHER_NOTIFICATION_WEBHOOK_ENABLED` | `false` | 应用默认 Webhook 开关；Dokploy 默认 `true` |
| `PUBLISHER_NOTIFICATION_WEBHOOK_INTERVAL` | `30s` | 投递间隔 |
| `PUBLISHER_NOTIFICATION_WEBHOOK_TIMEOUT` | `10s` | 单次 HTTP 超时 |
| `PUBLISHER_NOTIFICATION_WEBHOOK_BATCH_SIZE` | `50` | 每轮 1–500 |
| `PUBLISHER_NOTIFICATION_WEBHOOK_MAX_ATTEMPTS` | `4` | 最大尝试 1–10 |
| `PUBLISHER_METRICS_REFRESH_INTERVAL` | `30s` | 数据库 Gauge 刷新周期 |
| `PUBLISHER_OPENAPI_ENABLED` | `false` | 在线 OpenAPI；生产保持关闭 |

Webhook 端点只接受符合策略的 HTTPS 公网地址，Payload 仅包含类型、级别、标题、有限消息、目标 URL 和创建时间，不包含租户秘密或业务正文。应用层地址校验不能替代生产出站 ACL。

## 5. 构建、契约、安全与发布物

完整门禁：

```bash
cd /data/projects/content-publisher

git diff --check
node --check publisher-web/src/main/resources/static/assets/app.js
./scripts/openapi check
./scripts/dev compose-check
./scripts/dev verify
./scripts/dev integration-container
./scripts/dev security
./scripts/release
```

说明：

- `verify` 执行 Reactor `clean verify`、单元/集成测试、JaCoCo 和 CycloneDX SBOM。
- `openapi check` 对比 Controller 生成结果与 `docs/openapi.json`。
- `security` 使用 OWASP Dependency Check。存在 `NVD_API_KEY` 时，仅向 Maven
  传递环境变量名，不把密钥放入命令行；没有 Key 时自动使用 OWASP
  dependency-check 维护组织每日发布的只读 NVD 数据源。仍可显式传入
  `-DnvdDatafeedUrl=...`、`-DnvdApiKeyEnvironmentVariable=...` 或 Maven 支持的其他
  NVD 数据源参数覆盖默认选择。扫描仍可能受上游数据、网络或限流影响；失败必须保留
  真实原因。
- Dependency Check 抑制项集中在 `config/dependency-check-suppressions.xml`，必须
  包含可验证的不适用理由和到期时间，不能用于掩盖可升级修复的漏洞。当前唯一抑制项
  是不随嵌入式 Tomcat 打包的 examples chat 应用漏洞，并于 `2026-09-15` 自动失效。
- `release` 仅接受无未提交、无未跟踪内容的干净 Git 工作区；随后再次执行
  `verify`，输出与 Git SHA 严格对应的不可变 JAR、SBOM、构建属性和 SHA-256 清单。

产物：

```text
publisher-web/target/content-publisher.jar
target/bom.json
target/releases/<version>-<12位Git SHA>/
```

不得把工作区中未提交文件、`.env`、数据库或秘密打包进发布物。

## 6. 本地开发

```bash
./scripts/dev dev-up
./scripts/dev run
```

默认开发数据库：

```text
jdbc:postgresql://127.0.0.1:55432/content_publisher
```

`run` 默认使用 `DISABLED` 并关闭 Worker，只适合本机。停止或清空：

```bash
./scripts/dev dev-down
./scripts/dev dev-reset  # 会删除本地开发卷，必须确认数据可丢弃
```

## 7. OpenAPI 契约

生产默认不公开 `/v3/api-docs`，Swagger UI 始终关闭。契约通过测试生成快照：

```bash
./scripts/openapi update   # 仅在接口变更已审核后执行
./scripts/openapi check
```

`docs/openapi.json` 必须与 `API_REFERENCE.md` 同步。不能通过盲目更新快照掩盖意外接口变化。

## 8. Dokploy / Traefik 正式部署

### 8.1 固定边界

`miles-01` 上 Dokploy Traefik 独占公网 `80/443`：

- 禁止启动 Caddy 或另一套公网反向代理。
- 应用容器必须 `10001:10001` 非 root。
- PostgreSQL 容器必须以 Alpine 镜像内置的 `70:70` 非 root 身份运行。
- 应用只 `expose: 8080`，不能设置宿主机 `ports`。
- Traefik 通过 `dokploy-network` 直接连接应用。
- PostgreSQL 只能连接内部 `backend` 网络，不对外暴露端口。
- 正式镜像必须使用不可变标签，不能只使用 `latest`。

`deploy/dokploy-compose.yaml` 已实现上述约束，并提供只读根文件系统、tmpfs、`no-new-privileges`、readiness Healthcheck 和 PostgreSQL 命名卷。

### 8.2 部署前必须确认

1. 工作区干净且代码已经通过授权的 Git 或镜像路径交付；未经用户授权不得自动提交或推送。
2. `PUBLISHER_IMAGE_TAG` 与产物 Git SHA 一致。
3. 生产认证方式是 LOCAL 还是 JWT；JWT 需真实 Issuer，LOCAL 需管理员初始化/现有账号策略。
4. `DB_PASSWORD`、两个加密主密钥、AI/渠道配置已经通过 Dokploy Secret 安全注入。
5. 已确认现有生产数据库或旧实例归属，制定备份、迁移和回滚方案。
6. 已确认 `publisher.xyh.wiki` 当前 Cloudflare 源站及切换授权，不能覆盖未知实例。
7. Dokploy Domains 将域名映射到 `publisher:8080`，不配置 published port。

缺少其中任何一项时应停止正式切换，不得虚构值。

### 8.3 发布与验收顺序

1. 只允许 fast-forward 获取已授权提交，记录完整 Commit SHA。
2. 执行第 5 节门禁并校验 `SHA256SUMS`。
3. 部署端先备份数据库与 Secret 元数据，验证备份校验和。
4. Dokploy 构建/加载不可变镜像并启动 PostgreSQL、应用。
5. 检查 Flyway 到 V20、readiness、容器用户和网络。
6. 在 Dokploy Domains 配置正确目标端口后检查 Traefik 源站 HTTPS。
7. 最后切换或确认 Cloudflare DNS，并执行业务冒烟。

最低验收证据：

```text
应用容器 User=10001:10001
应用无宿主机 published port
PostgreSQL User=70:70、无外部端口且卷已挂载
/actuator/health/readiness = UP
flyway_schema_history 最新成功版本 = 20
登录/认证成功
租户隔离查询成功
草稿自动保存、动作台、日历、任务重放至少各一条冒烟
Traefik 源站 HTTPS 与 Cloudflare HTTPS 正常
```

未实际执行的项必须明确标记“未验证”。

## 9. Flyway V1–V20

- 迁移按 V1–V20 从空库前向执行。
- V19：文章草稿、生成预设、通知、Webhook 端点、人工发布进度。
- V20：通知 Webhook 投递状态、唯一去重和到期索引。
- 已发布脚本不可修改；新增结构只添加更高版本。
- Hibernate 使用 `ddl-auto=validate`。
- 没有 Down Migration；回滚应用前必须确认旧版本与新 Schema 兼容。

生产迁移前备份；迁移后验证：

```sql
select version, description, success
from flyway_schema_history
order by installed_rank desc
limit 5;
```

## 10. 备份与恢复演练

至少备份：

- PostgreSQL 自定义格式逻辑备份及 SHA-256。
- 两个加密主密钥和受限生产配置。
- 当前不可变镜像/JAR、完整 Git SHA、SBOM 和清单。
- Dokploy Compose 与 Domains 配置的审阅记录。

自动恢复演练：

```bash
BACKUP_SOURCE_DATABASE_URL='postgresql://...' \
DRILL_LOGIN_USERNAME='...' \
DRILL_LOGIN_PASSWORD='...' \
DRILL_EXPECTED_TENANT='...' \
PUBLISHER_SECRETS_ENCRYPTION_KEY='...' \
PUBLISHER_CHANNELS_ENCRYPTION_KEY='...' \
./scripts/backup-restore-drill
```

脚本会：创建只读逻辑备份、生成并校验 SHA-256、恢复到 `/data/tmp` 对应的隔离 PostgreSQL、执行 Flyway/登录/租户/密钥解密冒烟，最后清理临时资源。它要求当前用户具备 Docker 权限，不能对生产数据库执行写入。

## 11. 健康、指标、日志与告警

| 检查 | 入口 |
|---|---|
| 综合健康 | `/actuator/health` |
| 存活 | `/actuator/health/liveness` |
| 就绪 | `/actuator/health/readiness` |
| Prometheus | `/actuator/prometheus`，Admin |
| 业务监控 | `/monitoring`、`/api/v1/monitoring/summary` |
| 运营动作 | `/actions` |
| 发布日历 | `/calendar` |

低基数指标：

```text
publisher.jobs.pending
publisher.jobs.retry_wait
publisher.jobs.oldest_pending.seconds
publisher.jobs.running
publisher.jobs.failed
publisher.publications.published
publisher.publications.failed
publisher.channels.verification_failed
```

建议告警：readiness 失败、最老待执行任务持续增长、失败/重试异常、发布失败率上升、渠道验证失败、Webhook 最终失败、数据库和磁盘异常。

日志包含 `traceId`。禁止记录 Authorization、Cookie、密码、AI Key、渠道 Token、完整任务 Payload、完整仓库内容或 Webhook Secret。

## 12. 回滚

1. 停止接收新写入并确认没有正在执行的不可重复外部发布。
2. 保留当前失败版本日志、镜像和数据库状态证据。
3. 若 Schema 向后兼容，切回上一不可变镜像和对应配置。
4. 若不兼容，按已演练方案恢复部署前备份；不得手工篡改 Flyway 历史。
5. 重启后重复容器用户、端口、readiness、登录、租户、任务和发布冒烟。
6. Cloudflare/Dokploy 域名回切必须有明确授权和可验证源站。

## 13. 常见问题

### 13.1 Compose 校验提示 daemon 不可用

`./scripts/dev compose-check` 只调用 `docker compose config`，不要求 daemon。若仍失败，检查 Docker Compose 插件和必填插值；Dokploy 模板必须提供 `PUBLISHER_IMAGE_TAG`。

### 13.2 Testcontainers 被跳过

检查 `/var/run/docker.sock` 权限。不得把普通用户加入 root 等效 `docker` 组；改在授权 CI/预发布环境运行 `integration-container`。

### 13.3 Schema 校验失败

检查数据库用户 DDL 权限、Flyway 执行结果和 `flyway_schema_history` 是否到 V20。不要用 `ddl-auto=update` 绕过迁移。

### 13.4 LOCAL 无法登录

检查初始化管理员是否已创建、密码策略、强制改密状态和 Secure Cookie 是否与 HTTPS 一致。初始化成功后环境中的明文密码应已移除。

### 13.5 Webhook 或渠道巡检失败

检查功能开关、允许主机、DNS、公网地址、TLS、超时和出站 ACL。Webhook 重试最终耗尽后保留 `FAILED` 投递记录，不应无限重试。

### 13.6 发布结果不确定

先到第三方平台核对外部内容。确认未发布后再使用单任务或批量重放；不得因网络超时直接盲目重复发布。

## 14. SEO 适用性

本应用是认证后台，不应被搜索引擎抓取。所有顶层 Thymeleaf 模板均需保留 `noindex,nofollow`。新增页面时必须验证该 Meta；无需公开 Sitemap、结构化数据或内容 SEO 发布配置。

## 15. 文档同步

环境变量、默认值、Dockerfile、Compose、迁移、健康、指标、备份、域名或回滚方式变化时，必须同步更新本文档、README、技术设计和 `.env.example`。
