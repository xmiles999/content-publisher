package io.contentpublisher.platform.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contentpublisher.platform.application.AutomationApplicationService.ActionItem;
import io.contentpublisher.platform.application.AutomationApplicationService.ArticleDraft;
import io.contentpublisher.platform.application.AutomationApplicationService.CalendarItem;
import io.contentpublisher.platform.application.AutomationApplicationService.ChannelCheckTarget;
import io.contentpublisher.platform.application.AutomationApplicationService.GenerationPreset;
import io.contentpublisher.platform.application.AutomationApplicationService.ManualProgress;
import io.contentpublisher.platform.application.AutomationApplicationService.NavigationCounts;
import io.contentpublisher.platform.application.AutomationApplicationService.NotificationEndpoint;
import io.contentpublisher.platform.application.AutomationApplicationService.NotificationItem;
import io.contentpublisher.platform.application.AutomationApplicationService.WebhookDelivery;
import io.contentpublisher.platform.application.AutomationApplicationService.WebhookDeliveryStatus;
import io.contentpublisher.platform.application.port.AutomationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public class JdbcAutomationRepository implements AutomationRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcAutomationRepository(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ArticleDraft> findDraft(String tenantId, UUID articleId, String subject) {
        return jdbc.query("""
                select * from article_drafts
                where tenant_id = ? and article_id = ? and actor_subject = ?
                """, (rs, row) -> draft(rs), tenantId, articleId, subject).stream().findFirst();
    }

    @Override
    public ArticleDraft saveDraft(ArticleDraft draft) {
        int updated = jdbc.update("""
                update article_drafts set base_version=?, title=?, summary=?, markdown=?, tags_json=?,
                    keywords_json=?, title_en=?, summary_en=?, markdown_en=?, tags_en_json=?,
                    keywords_en_json=?, updated_at=?
                where tenant_id=? and article_id=? and actor_subject=?
                """, draft.baseVersion(), draft.title(), draft.summary(), draft.markdown(), write(draft.tags()),
                write(draft.keywords()), draft.titleEn(), draft.summaryEn(), draft.markdownEn(),
                write(draft.tagsEn()), write(draft.keywordsEn()), timestamp(draft.updatedAt()), draft.tenantId(),
                draft.articleId(), draft.actorSubject());
        if (updated == 0) {
            try {
                jdbc.update("""
                        insert into article_drafts(id, tenant_id, article_id, actor_subject, base_version, title,
                            summary, markdown, tags_json, keywords_json, title_en, summary_en, markdown_en,
                            tags_en_json, keywords_en_json, updated_at)
                        values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, draft.id(), draft.tenantId(), draft.articleId(), draft.actorSubject(),
                        draft.baseVersion(), draft.title(), draft.summary(), draft.markdown(), write(draft.tags()),
                        write(draft.keywords()), draft.titleEn(), draft.summaryEn(), draft.markdownEn(),
                        write(draft.tagsEn()), write(draft.keywordsEn()), timestamp(draft.updatedAt()));
            } catch (DuplicateKeyException ignored) {
                return saveDraft(draft);
            }
        }
        return findDraft(draft.tenantId(), draft.articleId(), draft.actorSubject()).orElseThrow();
    }

    @Override
    public boolean deleteDraft(String tenantId, UUID articleId, String subject) {
        return jdbc.update("delete from article_drafts where tenant_id=? and article_id=? and actor_subject=?",
                tenantId, articleId, subject) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GenerationPreset> findPresets(String tenantId, String sourceType) {
        return jdbc.query("""
                select * from generation_presets where tenant_id=? and source_type=?
                order by usage_count desc, updated_at desc
                """, (rs, row) -> preset(rs), tenantId, sourceType);
    }

    @Override
    public GenerationPreset savePreset(GenerationPreset preset) {
        int updated = jdbc.update("""
                update generation_presets set language=?, tone=?, min_characters=?, max_characters=?,
                    max_keywords=?, required_sections=?, article_type=?, knowledge_level=?,
                    recommendation_angle=?, model=?, prompt_version=?, parameter_version=?, updated_at=?
                where tenant_id=? and source_type=? and name=?
                """, preset.language(), preset.tone(), preset.minCharacters(), preset.maxCharacters(),
                preset.maxKeywords(), preset.requiredSections(), preset.articleType(), preset.knowledgeLevel(),
                preset.recommendationAngle(), preset.model(), preset.promptVersion(), preset.parameterVersion(),
                timestamp(preset.updatedAt()), preset.tenantId(), preset.sourceType(), preset.name());
        if (updated == 0) {
            try {
                jdbc.update("""
                        insert into generation_presets(id, tenant_id, name, source_type, language, tone,
                            min_characters, max_characters, max_keywords, required_sections, article_type,
                            knowledge_level, recommendation_angle, model, prompt_version, parameter_version,
                            usage_count, created_by, created_at, updated_at)
                        values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, preset.id(), preset.tenantId(), preset.name(), preset.sourceType(), preset.language(),
                        preset.tone(), preset.minCharacters(), preset.maxCharacters(), preset.maxKeywords(),
                        preset.requiredSections(), preset.articleType(), preset.knowledgeLevel(),
                        preset.recommendationAngle(), preset.model(), preset.promptVersion(), preset.parameterVersion(),
                        preset.usageCount(), preset.createdBy(), timestamp(preset.createdAt()),
                        timestamp(preset.updatedAt()));
            } catch (DuplicateKeyException ignored) {
                return savePreset(preset);
            }
        }
        return findPresets(preset.tenantId(), preset.sourceType()).stream()
                .filter(item -> item.name().equals(preset.name())).findFirst().orElseThrow();
    }

    @Override
    public boolean deletePreset(String tenantId, UUID presetId) {
        return jdbc.update("delete from generation_presets where tenant_id=? and id=?", tenantId, presetId) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActionItem> findActions(String tenantId, Instant staleBefore) {
        List<ActionItem> items = new ArrayList<>();
        addCount(items, "REVIEW", "WARNING", "待确认内容", "草稿或兼容需修改内容等待本人处理", "/content",
                "select count(*) from articles where tenant_id=? and deleted_at is null and status in ('DRAFT','REJECTED')",
                tenantId);
        addCount(items, "FAILED_JOB", "ERROR", "失败任务", "已耗尽重试或需要人工判断", "/jobs?status=FAILED",
                "select count(*) from jobs where tenant_id=? and deleted_at is null and status='FAILED'", tenantId);
        addCount(items, "STALE_JOB", "ERROR", "运行超时任务", "工作器租约可能已经失效", "/jobs?status=RUNNING",
                "select count(*) from jobs where tenant_id=? and deleted_at is null and status='RUNNING' and locked_at<?",
                tenantId, timestamp(staleBefore));
        addCount(items, "MANUAL_PROGRESS", "INFO", "未完成的人工发布",
                "已有操作进度但尚未确认发布", "/publishing",
                "select count(*) from manual_publication_progress where tenant_id=? and published=false", tenantId);
        addCount(items, "CHANNEL", "WARNING", "渠道账号需要处理",
                "账号已停用或最近一次连接验证失败", "/channels",
                "select count(*) from channel_accounts where tenant_id=? and deleted_at is null "
                        + "and (status='DISABLED' or verification_status='FAILED')",
                tenantId);
        addCount(items, "NOTIFICATION", "WARNING", "未确认通知", "请确认异常或恢复通知", "/actions",
                "select count(*) from notifications where tenant_id=? and acknowledged_at is null and resolved_at is null",
                tenantId);
        return items;
    }

    private void addCount(List<ActionItem> items, String type, String severity, String title, String detail,
                          String url, String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        if (count != null && count > 0) {
            items.add(new ActionItem(type, severity, title, detail, url, count, clock.instant()));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<CalendarItem> findCalendar(String tenantId, Instant from, Instant to) {
        List<JobCalendarRow> jobs = jdbc.query("""
                select id, status, payload_json, scheduled_at, updated_at from jobs
                where tenant_id=? and deleted_at is null and type='PUBLISH_ARTICLE'
                  and scheduled_at>=? and scheduled_at<?
                order by scheduled_at
                """, (rs, row) -> new JobCalendarRow(uuid(rs, "id"), rs.getString("status"),
                UUID.fromString(readMap(rs.getString("payload_json")).get("articleId").toString()),
                instant(rs, "scheduled_at"), instant(rs, "updated_at")), tenantId,
                timestamp(from), timestamp(to));
        Map<UUID, String> titles = jdbc.query("select id, title from articles where tenant_id=? and deleted_at is null",
                rs -> {
                    Map<UUID, String> values = new java.util.HashMap<>();
                    while (rs.next()) values.put(rs.getObject("id", UUID.class), rs.getString("title"));
                    return values;
                }, tenantId);
        return jobs.stream().map(job -> new CalendarItem(job.jobId(), job.status(),
                titles.getOrDefault(job.articleId(), "文章 " + job.articleId()), job.scheduledAt(), job.updatedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationItem> findNotifications(String tenantId, boolean includeAcknowledged, int limit) {
        String sql = """
                select * from notifications where tenant_id=?
                """ + (includeAcknowledged ? "" : " and acknowledged_at is null and resolved_at is null")
                + " order by created_at desc limit ?";
        return jdbc.query(sql, (rs, row) -> notification(rs), tenantId, limit);
    }

    @Override
    public NotificationItem saveNotification(NotificationItem item) {
        List<NotificationItem> existing = jdbc.query(
                "select * from notifications where tenant_id=? and dedup_key=?",
                (rs, row) -> notification(rs), item.tenantId(), item.dedupKey());
        if (!existing.isEmpty()) {
            jdbc.update("""
                    update notifications set type=?, severity=?, title=?, message=?, target_url=?,
                        acknowledged_at=null, acknowledged_by=null, resolved_at=null, updated_at=?,
                        webhook_delivered_at=null where tenant_id=? and dedup_key=?
                    """, item.type(), item.severity(), item.title(), item.message(), item.targetUrl(),
                    timestamp(item.updatedAt()), item.tenantId(), item.dedupKey());
            return jdbc.query("select * from notifications where tenant_id=? and dedup_key=?",
                    (rs, row) -> notification(rs), item.tenantId(), item.dedupKey()).get(0);
        }
        jdbc.update("""
                insert into notifications(id, tenant_id, type, severity, dedup_key, title, message, target_url,
                    acknowledged_at, acknowledged_by, resolved_at, created_at, updated_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, item.id(), item.tenantId(), item.type(), item.severity(), item.dedupKey(), item.title(),
                item.message(), item.targetUrl(), timestampNullable(item.acknowledgedAt()), item.acknowledgedBy(),
                timestampNullable(item.resolvedAt()), timestamp(item.createdAt()), timestamp(item.updatedAt()));
        return item;
    }

    @Override
    public boolean acknowledgeNotification(String tenantId, UUID id, String subject, Instant now) {
        return jdbc.update("""
                update notifications set acknowledged_at=?, acknowledged_by=?, updated_at=?
                where tenant_id=? and id=? and acknowledged_at is null
                """, timestamp(now), subject, timestamp(now), tenantId, id) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationEndpoint> findNotificationEndpoints(String tenantId) {
        return jdbc.query("select * from notification_endpoints where tenant_id=? order by display_name",
                (rs, row) -> endpoint(rs), tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationEndpoint> findEnabledNotificationEndpoints(String tenantId) {
        return jdbc.query("select * from notification_endpoints where tenant_id=? and enabled=true order by display_name",
                (rs, row) -> endpoint(rs), tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationEndpoint> findNotificationEndpoint(String tenantId, UUID endpointId) {
        return jdbc.query("select * from notification_endpoints where tenant_id=? and id=?",
                (rs, row) -> endpoint(rs), tenantId, endpointId).stream().findFirst();
    }

    @Override
    public NotificationEndpoint saveNotificationEndpoint(NotificationEndpoint endpoint) {
        int updated = jdbc.update("""
                update notification_endpoints set webhook_url=?, enabled=?, updated_at=?
                where tenant_id=? and display_name=?
                """, endpoint.webhookUrl(), endpoint.enabled(), timestamp(endpoint.updatedAt()),
                endpoint.tenantId(), endpoint.displayName());
        if (updated == 0) jdbc.update("""
                insert into notification_endpoints(id, tenant_id, display_name, webhook_url, enabled,
                    created_by, created_at, updated_at) values(?,?,?,?,?,?,?,?)
                """, endpoint.id(), endpoint.tenantId(), endpoint.displayName(), endpoint.webhookUrl(),
                endpoint.enabled(), endpoint.createdBy(), timestamp(endpoint.createdAt()),
                timestamp(endpoint.updatedAt()));
        return findNotificationEndpoints(endpoint.tenantId()).stream()
                .filter(item -> item.displayName().equals(endpoint.displayName())).findFirst().orElseThrow();
    }

    @Override
    public boolean updateNotificationEndpointEnabled(String tenantId, UUID endpointId, boolean enabled, Instant now) {
        return jdbc.update("""
                update notification_endpoints set enabled=?, updated_at=?
                where tenant_id=? and id=?
                """, enabled, timestamp(now), tenantId, endpointId) == 1;
    }

    @Override
    public boolean deleteNotificationEndpoint(String tenantId, UUID endpointId) {
        return jdbc.update("delete from notification_endpoints where tenant_id=? and id=?", tenantId, endpointId) == 1;
    }

    @Override
    public void prepareWebhookDeliveries(Instant now, int limit) {
        List<DeliveryPair> pairs = jdbc.query("""
                select n.id notification_id, e.id endpoint_id, n.tenant_id
                from notifications n
                join notification_endpoints e on e.tenant_id=n.tenant_id and e.enabled=true
                where n.acknowledged_at is null and n.resolved_at is null
                  and not exists (
                    select 1 from notification_webhook_deliveries d
                    where d.notification_id=n.id and d.endpoint_id=e.id
                )
                order by n.created_at
                limit ?
                """, (rs, row) -> new DeliveryPair(uuid(rs, "notification_id"), uuid(rs, "endpoint_id"),
                rs.getString("tenant_id")), limit);
        for (DeliveryPair pair : pairs) {
            try {
                jdbc.update("""
                        insert into notification_webhook_deliveries(
                            id, notification_id, endpoint_id, tenant_id, status, attempts, next_attempt_at,
                            created_at, updated_at)
                        values(?,?,?,?, 'PENDING', 0, ?, ?, ?)
                        """, UUID.randomUUID(), pair.notificationId(), pair.endpointId(), pair.tenantId(),
                        timestamp(now), timestamp(now), timestamp(now));
            } catch (DuplicateKeyException ignored) {
                // Another dispatcher prepared the same delivery concurrently.
            }
        }
    }

    @Override
    public boolean prepareWebhookDelivery(String tenantId, UUID notificationId, UUID endpointId, Instant now) {
        try {
            return jdbc.update("""
                    insert into notification_webhook_deliveries(
                        id, notification_id, endpoint_id, tenant_id, status, attempts, next_attempt_at,
                        created_at, updated_at)
                    select ?, n.id, e.id, n.tenant_id, 'PENDING', 0, ?, ?, ?
                    from notifications n
                    join notification_endpoints e on e.tenant_id=n.tenant_id
                    where n.tenant_id=? and n.id=? and e.id=? and e.enabled=true
                    """, UUID.randomUUID(), timestamp(now), timestamp(now), timestamp(now),
                    tenantId, notificationId, endpointId) == 1;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookDelivery> findWebhookDeliveriesDue(Instant now, int limit) {
        return jdbc.query("""
                select d.id, d.notification_id, d.endpoint_id, d.tenant_id, d.attempts,
                       e.webhook_url, n.type, n.severity, n.title, n.message, n.target_url, n.created_at
                from notification_webhook_deliveries d
                join notification_endpoints e on e.id=d.endpoint_id and e.tenant_id=d.tenant_id
                join notifications n on n.id=d.notification_id and n.tenant_id=d.tenant_id
                where d.status='PENDING' and d.next_attempt_at<=? and e.enabled=true
                  and (n.type='WEBHOOK_TEST'
                    or (n.acknowledged_at is null and n.resolved_at is null))
                order by d.next_attempt_at, d.created_at
                limit ?
                """, (rs, row) -> new WebhookDelivery(uuid(rs, "id"), uuid(rs, "notification_id"),
                uuid(rs, "endpoint_id"), rs.getString("tenant_id"), rs.getString("webhook_url"),
                rs.getString("type"), rs.getString("severity"), rs.getString("title"), rs.getString("message"),
                rs.getString("target_url"), instant(rs, "created_at"), rs.getInt("attempts")),
                timestamp(now), limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookDeliveryStatus> findRecentWebhookDeliveries(String tenantId, int limit) {
        return jdbc.query("""
                select d.id, d.notification_id, d.endpoint_id, d.tenant_id, e.display_name,
                       n.title, d.status, d.attempts, d.next_attempt_at, d.delivered_at,
                       d.last_error, d.created_at, d.updated_at
                from notification_webhook_deliveries d
                join notification_endpoints e on e.id=d.endpoint_id and e.tenant_id=d.tenant_id
                join notifications n on n.id=d.notification_id and n.tenant_id=d.tenant_id
                where d.tenant_id=?
                order by d.updated_at desc, d.created_at desc
                limit ?
                """, (rs, row) -> new WebhookDeliveryStatus(
                uuid(rs, "id"), uuid(rs, "notification_id"), uuid(rs, "endpoint_id"),
                rs.getString("tenant_id"), rs.getString("display_name"), rs.getString("title"),
                rs.getString("status"), rs.getInt("attempts"), instant(rs, "next_attempt_at"),
                instantNullable(rs, "delivered_at"), rs.getString("last_error"),
                instant(rs, "created_at"), instant(rs, "updated_at")), tenantId, limit);
    }

    @Override
    public void markWebhookDeliverySucceeded(UUID deliveryId, Instant deliveredAt) {
        jdbc.update("""
                update notification_webhook_deliveries
                set status='DELIVERED', attempts=attempts+1, delivered_at=?, last_error=null, updated_at=?
                where id=? and status='PENDING'
                """, timestamp(deliveredAt), timestamp(deliveredAt), deliveryId);
    }

    @Override
    public void markWebhookDeliveryFailed(UUID deliveryId, int attempts, Instant nextAttemptAt,
                                          String errorSummary, boolean exhausted, Instant now) {
        jdbc.update("""
                update notification_webhook_deliveries
                set status=?, attempts=?, next_attempt_at=?, last_error=?, updated_at=?
                where id=? and status='PENDING'
                """, exhausted ? "FAILED" : "PENDING", attempts, timestamp(nextAttemptAt), errorSummary,
                timestamp(now), deliveryId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ManualProgress> findManualProgress(String tenantId, UUID articleId, String channelType,
                                                       String subject) {
        return jdbc.query("""
                select * from manual_publication_progress
                where tenant_id=? and article_id=? and channel_type=? and actor_subject=?
                """, (rs, row) -> progress(rs), tenantId, articleId, channelType, subject).stream().findFirst();
    }

    @Override
    public ManualProgress saveManualProgress(ManualProgress progress) {
        int updated = jdbc.update("""
                update manual_publication_progress set copied_title=?, copied_content=?, opened_editor=?,
                    checked_format=?, published=?, updated_at=?
                where tenant_id=? and article_id=? and channel_type=? and actor_subject=?
                """, progress.copiedTitle(), progress.copiedContent(), progress.openedEditor(),
                progress.checkedFormat(), progress.published(), timestamp(progress.updatedAt()), progress.tenantId(),
                progress.articleId(), progress.channelType(), progress.actorSubject());
        if (updated == 0) jdbc.update("""
                insert into manual_publication_progress(id, tenant_id, article_id, channel_type, actor_subject,
                    copied_title, copied_content, opened_editor, checked_format, published, updated_at)
                values(?,?,?,?,?,?,?,?,?,?,?)
                """, progress.id(), progress.tenantId(), progress.articleId(), progress.channelType(),
                progress.actorSubject(), progress.copiedTitle(), progress.copiedContent(), progress.openedEditor(),
                progress.checkedFormat(), progress.published(), timestamp(progress.updatedAt()));
        return findManualProgress(progress.tenantId(), progress.articleId(), progress.channelType(),
                progress.actorSubject()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChannelCheckTarget> findChannelChecksDue(Instant checkedBefore, int limit) {
        return jdbc.query("""
                select tenant_id, id, verification_status from channel_accounts
                where deleted_at is null and status='ACTIVE'
                  and (last_verified_at is null or last_verified_at<?)
                order by last_verified_at nulls first, updated_at limit ?
                """, (rs, row) -> new ChannelCheckTarget(rs.getString("tenant_id"), uuid(rs, "id"),
                rs.getString("verification_status")), timestamp(checkedBefore), limit);
    }

    @Override
    @Transactional(readOnly = true)
    public NavigationCounts navigationCounts(String tenantId, Instant staleBefore) {
        return jdbc.queryForObject("""
                select
                  (select count(*) from articles
                     where tenant_id=? and deleted_at is null and status in ('DRAFT','REJECTED'))
                  + (select count(*) from jobs
                     where tenant_id=? and deleted_at is null and status='FAILED')
                  + (select count(*) from jobs
                     where tenant_id=? and deleted_at is null and status='RUNNING' and locked_at<?)
                  + (select count(*) from manual_publication_progress
                     where tenant_id=? and published=false)
                  + (select count(*) from channel_accounts
                     where tenant_id=? and deleted_at is null
                       and (status='DISABLED' or verification_status='FAILED'))
                  + (select count(*) from notifications
                     where tenant_id=? and acknowledged_at is null and resolved_at is null) action_count,
                  (select count(*) from articles
                     where tenant_id=? and deleted_at is null and status in ('READY','APPROVED'))
                    pending_publication_count,
                  (select count(*) from jobs
                     where tenant_id=? and deleted_at is null
                       and status in ('PENDING','RUNNING','RETRY_WAIT','FAILED')) job_attention_count
                """, (rs, row) -> new NavigationCounts(
                rs.getLong("action_count"), rs.getLong("pending_publication_count"),
                rs.getLong("job_attention_count")),
                tenantId, tenantId, tenantId, timestamp(staleBefore), tenantId, tenantId, tenantId,
                tenantId, tenantId);
    }

    private ArticleDraft draft(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ArticleDraft(uuid(rs, "id"), rs.getString("tenant_id"), uuid(rs, "article_id"),
                rs.getString("actor_subject"), rs.getInt("base_version"), rs.getString("title"),
                rs.getString("summary"), rs.getString("markdown"), readList(rs.getString("tags_json")),
                readList(rs.getString("keywords_json")), rs.getString("title_en"), rs.getString("summary_en"),
                rs.getString("markdown_en"), readList(rs.getString("tags_en_json")),
                readList(rs.getString("keywords_en_json")), instant(rs, "updated_at"));
    }

    private GenerationPreset preset(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GenerationPreset(uuid(rs, "id"), rs.getString("tenant_id"), rs.getString("name"),
                rs.getString("source_type"), rs.getString("language"), rs.getString("tone"),
                rs.getInt("min_characters"), rs.getInt("max_characters"), rs.getInt("max_keywords"),
                rs.getString("required_sections"), rs.getString("article_type"),
                rs.getString("knowledge_level"), rs.getString("recommendation_angle"), rs.getString("model"),
                rs.getString("prompt_version"), rs.getString("parameter_version"), rs.getLong("usage_count"),
                false, rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private NotificationItem notification(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new NotificationItem(uuid(rs, "id"), rs.getString("tenant_id"), rs.getString("type"),
                rs.getString("severity"), rs.getString("dedup_key"), rs.getString("title"),
                rs.getString("message"), rs.getString("target_url"), instantNullable(rs, "acknowledged_at"),
                rs.getString("acknowledged_by"), instantNullable(rs, "resolved_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private NotificationEndpoint endpoint(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new NotificationEndpoint(uuid(rs, "id"), rs.getString("tenant_id"), rs.getString("display_name"),
                rs.getString("webhook_url"), rs.getBoolean("enabled"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private ManualProgress progress(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ManualProgress(uuid(rs, "id"), rs.getString("tenant_id"), uuid(rs, "article_id"),
                rs.getString("channel_type"), rs.getString("actor_subject"), rs.getBoolean("copied_title"),
                rs.getBoolean("copied_content"), rs.getBoolean("opened_editor"), rs.getBoolean("checked_format"),
                rs.getBoolean("published"), instant(rs, "updated_at"));
    }

    private UUID uuid(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, UUID.class);
    }

    private Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private Instant instantNullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private Timestamp timestampNullable(Instant value) {
        return value == null ? null : timestamp(value);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("自动化数据序列化失败", exception); }
    }
    private List<String> readList(String value) {
        try { return json.readValue(value, STRING_LIST); }
        catch (Exception exception) { throw new IllegalStateException("自动化数据解析失败", exception); }
    }
    private Map<String, Object> readMap(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("任务数据解析失败", exception); }
    }

    private record JobCalendarRow(UUID jobId, String status, UUID articleId, Instant scheduledAt,
                                  Instant updatedAt) {}
    private record DeliveryPair(UUID notificationId, UUID endpointId, String tenantId) {}
}
