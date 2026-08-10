package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.application.port.AutomationRepository;
import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.WebhookEndpointPolicy;
import io.contentpublisher.platform.domain.ActorContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class AutomationApplicationService {
    private final AutomationRepository repository;
    private final ArticleRepository articles;
    private final AuditRecorder audit;
    private final WebhookEndpointPolicy webhookEndpointPolicy;
    private final Clock clock;

    public AutomationApplicationService(AutomationRepository repository, ArticleRepository articles,
                                        AuditRecorder audit, WebhookEndpointPolicy webhookEndpointPolicy,
                                        Clock clock) {
        this.repository = repository;
        this.articles = articles;
        this.audit = audit;
        this.webhookEndpointPolicy = webhookEndpointPolicy;
        this.clock = clock;
    }

    public Optional<ArticleDraft> getDraft(ActorContext actor, UUID articleId) {
        return repository.findDraft(actor.tenantId(), articleId, actor.subject());
    }

    public ArticleDraft saveDraft(ActorContext actor, UUID articleId, int baseVersion, DraftContent content) {
        if (baseVersion < 1) throw new ApplicationException("DRAFT_VERSION_INVALID", "草稿基线版本无效");
        var article = articles.findArticleById(actor.tenantId(), articleId)
                .orElseThrow(() -> new ApplicationException("ARTICLE_NOT_FOUND", "文章不存在"));
        if (article.currentVersion() != baseVersion) {
            throw new ApplicationException("DRAFT_VERSION_CONFLICT", "文章已有新版本，请刷新后再继续编辑");
        }
        ArticleDraft draft = new ArticleDraft(UUID.randomUUID(), actor.tenantId(), articleId, actor.subject(),
                baseVersion, limited(content.title(), 500, true), limited(content.summary(), 2000, true),
                limited(content.markdown(), 20_000, true), normalizedList(content.tags(), 100),
                normalizedList(content.keywords(), 100), limited(content.titleEn(), 500, false),
                limited(content.summaryEn(), 2000, false), limited(content.markdownEn(), 20_000, false),
                normalizedList(content.tagsEn(), 100), normalizedList(content.keywordsEn(), 100), clock.instant());
        return repository.saveDraft(draft);
    }

    public void deleteDraft(ActorContext actor, UUID articleId) {
        repository.deleteDraft(actor.tenantId(), articleId, actor.subject());
    }

    public List<GenerationPreset> presets(ActorContext actor, String sourceType) {
        String source = normalizeSource(sourceType);
        List<GenerationPreset> custom = repository.findPresets(actor.tenantId(), source);
        return java.util.stream.Stream.concat(defaultPresets(source).stream(), custom.stream()).toList();
    }

    public GenerationPreset savePreset(ActorContext actor, PresetCommand command) {
        String source = normalizeSource(command.sourceType());
        Instant now = clock.instant();
        GenerationPreset preset = new GenerationPreset(UUID.randomUUID(), actor.tenantId(),
                limited(command.name(), 120, true), source, limited(command.language(), 20, false),
                limited(command.tone(), 100, true), bounded(command.minCharacters(), 200, 3000),
                bounded(command.maxCharacters(), 200, 3000), bounded(command.maxKeywords(), 1, 30),
                optional(command.requiredSections(), 2200), limited(command.articleType(), 40, false),
                limited(command.knowledgeLevel(), 40, false),
                limited(command.recommendationAngle(), 2000, false), limited(command.model(), 200, false),
                limited(command.promptVersion(), 100, false), limited(command.parameterVersion(), 100, false),
                0, false, actor.subject(), now, now);
        if (preset.maxCharacters() < preset.minCharacters()) {
            throw new ApplicationException("PRESET_LENGTH_INVALID", "预设最大字符数不能小于最小字符数");
        }
        GenerationPreset saved = repository.savePreset(preset);
        audit.record(actor, "GENERATION_PRESET_SAVED", "GENERATION_PRESET", saved.id(),
                Map.of("sourceType", source, "name", saved.name()));
        return saved;
    }

    public void deletePreset(ActorContext actor, UUID presetId) {
        if (!repository.deletePreset(actor.tenantId(), presetId)) {
            throw new ApplicationException("PRESET_NOT_FOUND", "生成预设不存在");
        }
        audit.record(actor, "GENERATION_PRESET_DELETED", "GENERATION_PRESET", presetId, Map.of());
    }

    public List<ActionItem> actions(ActorContext actor) {
        return repository.findActions(actor.tenantId(), clock.instant().minus(Duration.ofMinutes(15)));
    }

    public List<CalendarItem> calendar(ActorContext actor, Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)
                || Duration.between(from, to).compareTo(Duration.ofDays(93)) > 0) {
            throw new ApplicationException("CALENDAR_RANGE_INVALID", "日历查询范围必须在 93 天以内");
        }
        return repository.findCalendar(actor.tenantId(), from, to);
    }

    public List<NotificationItem> notifications(ActorContext actor, boolean includeAcknowledged) {
        return repository.findNotifications(actor.tenantId(), includeAcknowledged, 100);
    }

    public NotificationItem notify(ActorContext actor, String type, String severity, String dedupKey,
                                   String title, String message, String targetUrl) {
        Instant now = clock.instant();
        return repository.saveNotification(new NotificationItem(UUID.randomUUID(), actor.tenantId(),
                limited(type, 60, true), normalizeSeverity(severity), limited(dedupKey, 200, true),
                limited(title, 200, true), limited(message, 1000, true), limited(targetUrl, 2048, false),
                null, null, null, now, now));
    }

    public void acknowledgeNotification(ActorContext actor, UUID notificationId) {
        if (!repository.acknowledgeNotification(actor.tenantId(), notificationId, actor.subject(), clock.instant())) {
            throw new ApplicationException("NOTIFICATION_NOT_FOUND", "通知不存在或已确认");
        }
    }

    public List<NotificationEndpoint> notificationEndpoints(ActorContext actor) {
        return repository.findNotificationEndpoints(actor.tenantId());
    }

    public List<WebhookDeliveryStatus> recentWebhookDeliveries(ActorContext actor, int limit) {
        if (limit < 1 || limit > 100) {
            throw new ApplicationException("INVALID_ARGUMENT", "Webhook 投递记录查询数量无效");
        }
        return repository.findRecentWebhookDeliveries(actor.tenantId(), limit);
    }

    public NotificationEndpoint saveNotificationEndpoint(ActorContext actor, String displayName, String webhookUrl) {
        String url = limited(webhookUrl, 2048, true);
        webhookEndpointPolicy.validate(url);
        Instant now = clock.instant();
        NotificationEndpoint endpoint = repository.saveNotificationEndpoint(new NotificationEndpoint(
                UUID.randomUUID(), actor.tenantId(), limited(displayName, 120, true), url, true,
                actor.subject(), now, now));
        audit.record(actor, "NOTIFICATION_ENDPOINT_SAVED", "NOTIFICATION_ENDPOINT", endpoint.id(), Map.of());
        return endpoint;
    }

    public NotificationEndpoint updateNotificationEndpointEnabled(ActorContext actor, UUID endpointId,
                                                                  boolean enabled) {
        Instant now = clock.instant();
        if (!repository.updateNotificationEndpointEnabled(actor.tenantId(), endpointId, enabled, now)) {
            throw new ApplicationException("NOTIFICATION_ENDPOINT_NOT_FOUND", "通知端点不存在");
        }
        NotificationEndpoint endpoint = repository.findNotificationEndpoint(actor.tenantId(), endpointId)
                .orElseThrow(() -> new ApplicationException(
                        "NOTIFICATION_ENDPOINT_NOT_FOUND", "通知端点不存在"));
        audit.record(actor, enabled ? "NOTIFICATION_ENDPOINT_ENABLED" : "NOTIFICATION_ENDPOINT_DISABLED",
                "NOTIFICATION_ENDPOINT", endpointId, Map.of("displayName", endpoint.displayName()));
        return endpoint;
    }

    public WebhookDeliveryStatus queueWebhookTest(ActorContext actor, UUID endpointId) {
        NotificationEndpoint endpoint = repository.findNotificationEndpoint(actor.tenantId(), endpointId)
                .orElseThrow(() -> new ApplicationException(
                        "NOTIFICATION_ENDPOINT_NOT_FOUND", "通知端点不存在"));
        if (!endpoint.enabled()) {
            throw new ApplicationException("NOTIFICATION_ENDPOINT_DISABLED", "请先启用通知端点再发送测试通知");
        }
        webhookEndpointPolicy.validate(endpoint.webhookUrl());
        Instant now = clock.instant();
        UUID testId = UUID.randomUUID();
        NotificationItem notification = repository.saveNotification(new NotificationItem(
                UUID.randomUUID(), actor.tenantId(), "WEBHOOK_TEST", "INFO",
                "webhook-test:" + endpointId + ":" + testId,
                "Webhook 测试通知", "这是一条由管理员主动发起的连通性测试通知。",
                "/automation", null, null, now, now, now));
        if (!repository.prepareWebhookDelivery(actor.tenantId(), notification.id(), endpointId, now)) {
            throw new ApplicationException("WEBHOOK_TEST_PREPARE_FAILED", "测试通知进入投递队列失败");
        }
        audit.record(actor, "NOTIFICATION_ENDPOINT_TEST_QUEUED", "NOTIFICATION_ENDPOINT", endpointId,
                Map.of("notificationId", notification.id().toString()));
        return repository.findRecentWebhookDeliveries(actor.tenantId(), 20).stream()
                .filter(delivery -> delivery.notificationId().equals(notification.id())
                        && delivery.endpointId().equals(endpointId))
                .findFirst()
                .orElseThrow(() -> new ApplicationException(
                        "WEBHOOK_TEST_PREPARE_FAILED", "测试通知进入投递队列失败"));
    }

    public void deleteNotificationEndpoint(ActorContext actor, UUID endpointId) {
        if (!repository.deleteNotificationEndpoint(actor.tenantId(), endpointId)) {
            throw new ApplicationException("NOTIFICATION_ENDPOINT_NOT_FOUND", "通知端点不存在");
        }
        audit.record(actor, "NOTIFICATION_ENDPOINT_DELETED", "NOTIFICATION_ENDPOINT", endpointId, Map.of());
    }

    public NavigationCounts navigationCounts(ActorContext actor) {
        return repository.navigationCounts(actor.tenantId(), clock.instant().minus(Duration.ofMinutes(15)));
    }

    public Optional<ManualProgress> manualProgress(ActorContext actor, UUID articleId, String channelType) {
        return repository.findManualProgress(actor.tenantId(), articleId, channelType, actor.subject());
    }

    public ManualProgress saveManualProgress(ActorContext actor, UUID articleId, String channelType,
                                             ManualProgressCommand command) {
        return repository.saveManualProgress(new ManualProgress(UUID.randomUUID(), actor.tenantId(), articleId,
                limited(channelType, 40, true), actor.subject(), command.copiedTitle(), command.copiedContent(),
                command.openedEditor(), command.checkedFormat(), command.published(), clock.instant()));
    }

    public List<ChannelCheckTarget> channelChecksDue(Instant checkedBefore, int limit) {
        if (limit < 1 || limit > 500) throw new ApplicationException("INVALID_ARGUMENT", "巡检批次大小无效");
        return repository.findChannelChecksDue(checkedBefore, limit);
    }

    public void prepareWebhookDeliveries(int limit) {
        if (limit < 1 || limit > 500) throw new ApplicationException("INVALID_ARGUMENT", "通知投递批次大小无效");
        repository.prepareWebhookDeliveries(clock.instant(), limit);
    }

    public List<WebhookDelivery> webhookDeliveriesDue(int limit) {
        if (limit < 1 || limit > 500) throw new ApplicationException("INVALID_ARGUMENT", "通知投递批次大小无效");
        return repository.findWebhookDeliveriesDue(clock.instant(), limit);
    }

    public void markWebhookDeliverySucceeded(UUID deliveryId) {
        repository.markWebhookDeliverySucceeded(deliveryId, clock.instant());
    }

    public void markWebhookDeliveryFailed(UUID deliveryId, int attempts, Instant nextAttemptAt,
                                          String errorSummary, boolean exhausted) {
        repository.markWebhookDeliveryFailed(deliveryId, attempts, nextAttemptAt,
                limited(errorSummary, 500, false), exhausted, clock.instant());
    }

    private List<GenerationPreset> defaultPresets(String source) {
        Instant epoch = Instant.EPOCH;
        List<GenerationPreset> all = List.of(
                preset("快速开始", "PROJECT", "专业、清晰、面向首次使用者", 800, 1800, 10,
                        "项目概述\n适用场景\n安装与配置\n快速开始\n常见问题", null, null, null),
                preset("架构解析", "PROJECT", "专业、客观、突出架构取舍", 1200, 2600, 14,
                        "项目概述\n架构与模块\n核心流程\n关键技术取舍\n扩展方式\n总结", null, null, null),
                preset("生产实践", "PROJECT", "务实、克制、面向生产环境", 1000, 2400, 12,
                        "适用场景\n生产配置\n安全与权限\n性能与可观测性\n常见故障\n上线检查清单",
                        null, null, null),
                preset("分步教程", "TOPIC", "专业、清晰、循序渐进", 1000, 2400, 12,
                        "学习目标\n前置知识\n分步教程\n完整示例\n常见问题\n总结", "TUTORIAL", "MIXED", null),
                preset("最佳实践", "TOPIC", "务实、客观、突出取舍", 1000, 2400, 14,
                        "问题背景\n推荐做法\n反例与风险\n实施步骤\n检查清单\n总结",
                        "BEST_PRACTICES", "INTERMEDIATE", null),
                preset("问题排查", "TOPIC", "直接、准确、便于排查", 900, 2200, 12,
                        "问题现象\n影响范围\n排查步骤\n常见原因\n修复方法\n预防措施",
                        "TROUBLESHOOTING", "INTERMEDIATE", null),
                preset("产品概览", "WEBSITE", "客观、克制、信息密度高", 700, 1800, 12,
                        "网站定位\n核心功能\n适用人群\n使用方式\n优势与局限\n总结", null, null,
                        "说明网站定位、核心功能、适用人群、使用方式、优势与局限"),
                preset("选型评估", "WEBSITE", "中立、具体、突出决策依据", 900, 2200, 14,
                        "产品定位\n核心能力\n使用门槛\n费用与限制\n数据与安全\n适合与不适合的人群\n选型结论",
                        null, null, "从使用门槛、核心能力、费用边界、数据与安全、适用场景进行选型评估"),
                preset("使用指南", "WEBSITE", "清晰、直接、面向首次使用者", 800, 2000, 10,
                        "使用前准备\n注册与配置\n核心操作\n常见问题\n使用限制\n总结",
                        null, null, "围绕首次使用流程，说明准备工作、核心操作、常见问题和使用限制")
        );
        return all.stream().filter(item -> item.sourceType().equals(source))
                .map(item -> new GenerationPreset(item.id(), item.tenantId(), item.name(), item.sourceType(),
                        item.language(), item.tone(), item.minCharacters(), item.maxCharacters(), item.maxKeywords(),
                        item.requiredSections(), item.articleType(), item.knowledgeLevel(),
                        item.recommendationAngle(), item.model(), item.promptVersion(), item.parameterVersion(),
                        item.usageCount(), true, item.createdBy(), epoch, epoch))
                .toList();
    }

    private GenerationPreset preset(String name, String source, String tone, int min, int max, int keywords,
                                    String sections, String articleType, String level, String angle) {
        UUID id = UUID.nameUUIDFromBytes(("preset:" + source + ":" + name)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new GenerationPreset(id, "", name, source, null, tone, min, max, keywords, sections,
                articleType, level, angle, null, "builtin-v1", "builtin-v1", 0, true, "system",
                Instant.EPOCH, Instant.EPOCH);
    }

    private String normalizeSource(String value) {
        String source = limited(value, 20, true).toUpperCase(Locale.ROOT);
        if (!List.of("PROJECT", "TOPIC", "WEBSITE").contains(source)) {
            throw new ApplicationException("PRESET_SOURCE_INVALID", "生成预设来源无效");
        }
        return source;
    }

    private String normalizeSeverity(String value) {
        String severity = limited(value, 20, true).toUpperCase(Locale.ROOT);
        if (!List.of("INFO", "WARNING", "ERROR").contains(severity)) {
            throw new ApplicationException("NOTIFICATION_SEVERITY_INVALID", "通知级别无效");
        }
        return severity;
    }

    private int bounded(int value, int min, int max) {
        if (value < min || value > max) throw new ApplicationException("INVALID_ARGUMENT", "数值超出允许范围");
        return value;
    }

    private String limited(String value, int max, boolean required) {
        String normalized = value == null ? "" : value.trim();
        if (required && normalized.isEmpty()) throw new ApplicationException("INVALID_ARGUMENT", "必填字段不能为空");
        if (normalized.length() > max) throw new ApplicationException("INVALID_ARGUMENT", "字段长度超过限制");
        return normalized.isEmpty() ? null : normalized;
    }

    private String optional(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max) throw new ApplicationException("INVALID_ARGUMENT", "字段长度超过限制");
        return normalized;
    }

    private List<String> normalizedList(List<String> values, int maxItems) {
        if (values == null) return List.of();
        return values.stream().filter(java.util.Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
                .distinct().limit(maxItems).toList();
    }

    public record DraftContent(String title, String summary, String markdown, List<String> tags,
                               List<String> keywords, String titleEn, String summaryEn, String markdownEn,
                               List<String> tagsEn, List<String> keywordsEn) {}
    public record ArticleDraft(UUID id, String tenantId, UUID articleId, String actorSubject, int baseVersion,
                               String title, String summary, String markdown, List<String> tags,
                               List<String> keywords, String titleEn, String summaryEn, String markdownEn,
                               List<String> tagsEn, List<String> keywordsEn, Instant updatedAt) {}
    public record PresetCommand(String name, String sourceType, String language, String tone, int minCharacters,
                                int maxCharacters, int maxKeywords, String requiredSections, String articleType,
                                String knowledgeLevel, String recommendationAngle, String model,
                                String promptVersion, String parameterVersion) {}
    public record GenerationPreset(UUID id, String tenantId, String name, String sourceType, String language,
                                   String tone, int minCharacters, int maxCharacters, int maxKeywords,
                                   String requiredSections, String articleType, String knowledgeLevel,
                                   String recommendationAngle, String model, String promptVersion,
                                   String parameterVersion, long usageCount, boolean builtin, String createdBy,
                                   Instant createdAt, Instant updatedAt) {}
    public record ActionItem(String type, String severity, String title, String detail, String targetUrl,
                             long count, Instant occurredAt) {}
    public record CalendarItem(UUID jobId, String status, String title, Instant scheduledAt, Instant updatedAt) {}
    public record NotificationItem(UUID id, String tenantId, String type, String severity, String dedupKey,
                                   String title, String message, String targetUrl, Instant acknowledgedAt,
                                   String acknowledgedBy, Instant resolvedAt, Instant createdAt, Instant updatedAt) {}
    public record NotificationEndpoint(UUID id, String tenantId, String displayName, String webhookUrl,
                                       boolean enabled, String createdBy, Instant createdAt, Instant updatedAt) {}
    public record WebhookDeliveryStatus(UUID id, UUID notificationId, UUID endpointId, String tenantId,
                                        String endpointName, String notificationTitle, String status, int attempts,
                                        Instant nextAttemptAt, Instant deliveredAt, String lastError,
                                        Instant createdAt, Instant updatedAt) {}
    public record NavigationCounts(long actionCount, long pendingPublicationCount, long jobAttentionCount) {}
    public record ManualProgressCommand(boolean copiedTitle, boolean copiedContent, boolean openedEditor,
                                        boolean checkedFormat, boolean published) {}
    public record ManualProgress(UUID id, String tenantId, UUID articleId, String channelType, String actorSubject,
                                 boolean copiedTitle, boolean copiedContent, boolean openedEditor,
                                 boolean checkedFormat, boolean published, Instant updatedAt) {}
    public record ChannelCheckTarget(String tenantId, UUID accountId, String previousStatus) {}
    public record WebhookDelivery(UUID id, UUID notificationId, UUID endpointId, String tenantId,
                                  String webhookUrl, String type, String severity, String title, String message,
                                  String targetUrl, Instant notificationCreatedAt, int attempts) {}
}
