package io.contentpublisher.platform;

import io.contentpublisher.platform.application.AutomationApplicationService.ArticleDraft;
import io.contentpublisher.platform.application.AutomationApplicationService.GenerationPreset;
import io.contentpublisher.platform.application.AutomationApplicationService.ManualProgress;
import io.contentpublisher.platform.application.AutomationApplicationService.NotificationEndpoint;
import io.contentpublisher.platform.application.AutomationApplicationService.NotificationItem;
import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.AutomationRepository;
import io.contentpublisher.platform.application.port.ChannelAccountRepository;
import io.contentpublisher.platform.application.port.JobRepository;
import io.contentpublisher.platform.application.port.ProjectRepository;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.domain.ArticleVersion;
import io.contentpublisher.platform.domain.ChannelAccount;
import io.contentpublisher.platform.domain.ChannelAccountStatus;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.ChannelVerificationStatus;
import io.contentpublisher.platform.domain.ContentOrigin;
import io.contentpublisher.platform.domain.Job;
import io.contentpublisher.platform.domain.JobPayload;
import io.contentpublisher.platform.domain.JobStatus;
import io.contentpublisher.platform.domain.JobType;
import io.contentpublisher.platform.domain.Project;
import io.contentpublisher.platform.domain.ProjectStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:automation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "publisher.security.mode=DISABLED",
        "publisher.jobs.worker-enabled=false"
})
class AutomationPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-10T05:00:00Z");

    @Autowired AutomationRepository automation;
    @Autowired ProjectRepository projects;
    @Autowired ArticleRepository articles;
    @Autowired JobRepository jobs;
    @Autowired ChannelAccountRepository channelAccounts;
    @Autowired JdbcTemplate jdbc;

    @Test
    void shouldIsolateDraftsAndUpsertPresetsAndManualProgress() {
        String tenant = "automation-workspace";
        Article article = article(tenant, "workspace");

        ArticleDraft alice = draft(tenant, article.id(), "alice", "Alice 初稿", NOW);
        ArticleDraft bob = draft(tenant, article.id(), "bob", "Bob 初稿", NOW);
        automation.saveDraft(alice);
        automation.saveDraft(bob);
        automation.saveDraft(draft(tenant, article.id(), "alice", "Alice 新稿", NOW.plusSeconds(60)));

        assertThat(automation.findDraft(tenant, article.id(), "alice")).get()
                .extracting(ArticleDraft::title).isEqualTo("Alice 新稿");
        assertThat(automation.findDraft(tenant, article.id(), "bob")).get()
                .extracting(ArticleDraft::title).isEqualTo("Bob 初稿");
        assertThat(automation.findDraft("other-tenant", article.id(), "alice")).isEmpty();

        GenerationPreset first = preset(tenant, "团队教程", "初始语气", NOW);
        GenerationPreset updated = preset(tenant, "团队教程", "更新语气", NOW.plusSeconds(60));
        GenerationPreset saved = automation.savePreset(first);
        GenerationPreset upserted = automation.savePreset(updated);
        assertThat(upserted.id()).isEqualTo(saved.id());
        assertThat(automation.findPresets(tenant, "TOPIC")).singleElement()
                .extracting(GenerationPreset::tone).isEqualTo("更新语气");

        ManualProgress progress = progress(tenant, article.id(), "alice", false, NOW);
        automation.saveManualProgress(progress);
        ManualProgress completed = automation.saveManualProgress(progress(
                tenant, article.id(), "alice", true, NOW.plusSeconds(60)));
        assertThat(completed.id()).isEqualTo(progress.id());
        assertThat(completed.published()).isTrue();
    }

    @Test
    void shouldDeduplicateAndAcknowledgeNotifications() {
        String tenant = "automation-notifications";
        NotificationItem first = notification(tenant, "channel:one", "首次告警", NOW);
        NotificationItem updated = notification(tenant, "channel:one", "更新告警", NOW.plusSeconds(60));

        NotificationItem saved = automation.saveNotification(first);
        NotificationItem deduplicated = automation.saveNotification(updated);

        assertThat(deduplicated.id()).isEqualTo(saved.id());
        assertThat(automation.findNotifications(tenant, false, 100)).singleElement()
                .extracting(NotificationItem::title).isEqualTo("更新告警");
        assertThat(automation.acknowledgeNotification(tenant, saved.id(), "operator", NOW.plusSeconds(120)))
                .isTrue();
        assertThat(automation.acknowledgeNotification(tenant, saved.id(), "operator", NOW.plusSeconds(180)))
                .isFalse();
        assertThat(automation.findNotifications(tenant, false, 100)).isEmpty();
        assertThat(automation.findNotifications(tenant, true, 100)).singleElement()
                .extracting(NotificationItem::acknowledgedBy).isEqualTo("operator");
    }

    @Test
    void shouldQueryCalendarAndDueChannelChecks() {
        String tenant = "automation-calendar";
        Article article = article(tenant, "calendar");
        Instant scheduledAt = NOW.plusSeconds(3600);
        Job job = new Job(UUID.randomUUID(), tenant, "editor", JobType.PUBLISH_ARTICLE, JobStatus.PENDING,
                new JobPayload.PublishArticle(article.id(), UUID.randomUUID(), null),
                "calendar-job", "a".repeat(64), 0, 4, 5, "等待执行", "等待计划时间",
                UUID.randomUUID(), scheduledAt, null, null, null, null, null, NOW, NOW);
        jobs.save(job);

        assertThat(automation.findCalendar(tenant, NOW, NOW.plusSeconds(7200))).singleElement().satisfies(item -> {
            assertThat(item.jobId()).isEqualTo(job.id());
            assertThat(item.title()).isEqualTo(article.title());
            assertThat(item.scheduledAt()).isEqualTo(scheduledAt);
        });
        assertThat(automation.findCalendar("other-tenant", NOW, NOW.plusSeconds(7200))).isEmpty();

        ChannelAccount due = account(tenant, NOW.minusSeconds(172800), ChannelVerificationStatus.FAILED);
        ChannelAccount recent = account(tenant, NOW.minusSeconds(60), ChannelVerificationStatus.SUCCEEDED);
        channelAccounts.save(due);
        channelAccounts.save(recent);
        assertThat(automation.findChannelChecksDue(NOW.minusSeconds(86400), 50))
                .extracting(target -> target.accountId())
                .contains(due.id())
                .doesNotContain(recent.id());
    }

    @Test
    void shouldPrepareRetryAndCompleteWebhookDelivery() {
        String tenant = "automation-webhook-success";
        NotificationItem notification = automation.saveNotification(
                notification(tenant, "delivery:success", "需要投递", NOW));
        automation.saveNotificationEndpoint(endpoint(tenant, "primary", NOW));

        automation.prepareWebhookDeliveries(NOW, 50);
        automation.prepareWebhookDeliveries(NOW, 50);
        assertThat(jdbc.queryForObject(
                "select count(*) from notification_webhook_deliveries where tenant_id=?",
                Integer.class, tenant)).isEqualTo(1);

        var delivery = automation.findWebhookDeliveriesDue(NOW, 50).get(0);
        assertThat(delivery.notificationId()).isEqualTo(notification.id());
        automation.markWebhookDeliveryFailed(delivery.id(), 1, NOW.plusSeconds(60), "HTTP 503", false, NOW);
        assertThat(automation.findWebhookDeliveriesDue(NOW.plusSeconds(30), 50)).isEmpty();
        assertThat(automation.findWebhookDeliveriesDue(NOW.plusSeconds(60), 50)).singleElement()
                .extracting(item -> item.attempts()).isEqualTo(1);

        automation.markWebhookDeliverySucceeded(delivery.id(), NOW.plusSeconds(61));
        assertThat(automation.findWebhookDeliveriesDue(NOW.plusSeconds(120), 50)).isEmpty();
        assertThat(jdbc.queryForObject(
                "select status from notification_webhook_deliveries where id=?",
                String.class, delivery.id())).isEqualTo("DELIVERED");
    }

    @Test
    void shouldStopWebhookDeliveryAfterAttemptsAreExhausted() {
        String tenant = "automation-webhook-exhausted";
        automation.saveNotification(notification(tenant, "delivery:failed", "最终失败", NOW));
        automation.saveNotificationEndpoint(endpoint(tenant, "primary", NOW));
        automation.prepareWebhookDeliveries(NOW, 50);
        var delivery = automation.findWebhookDeliveriesDue(NOW, 50).get(0);

        automation.markWebhookDeliveryFailed(delivery.id(), 4, NOW.plusSeconds(300),
                "timeout", true, NOW.plusSeconds(10));

        assertThat(automation.findWebhookDeliveriesDue(NOW.plusSeconds(600), 50)).isEmpty();
        assertThat(jdbc.queryForMap(
                "select status, attempts, last_error from notification_webhook_deliveries where id=?",
                delivery.id()))
                .containsEntry("status", "FAILED")
                .containsEntry("attempts", 4)
                .containsEntry("last_error", "timeout");
    }

    private Article article(String tenant, String suffix) {
        Project project = new Project(UUID.randomUUID(), tenant,
                "https://github.com/contentpublisher/" + suffix + ".git", suffix, "automation test",
                "main", "abc123", List.of("Java"), "MIT", ProjectStatus.READY,
                "tester", "tester", NOW, NOW);
        projects.save(project);
        Article article = new Article(UUID.randomUUID(), tenant, ContentOrigin.git(project.id()), null,
                "自动化文章 " + suffix, "摘要", "## 正文", List.of("automation"), "zh-CN",
                "abc123", 1, ArticleStatus.APPROVED, "tester", "tester", NOW, NOW);
        articles.saveWithVersion(article, new ArticleVersion(tenant, article.id(), 1, article.title(),
                article.summary(), article.markdown(), article.keywords(), "tester", NOW));
        return article;
    }

    private ArticleDraft draft(String tenant, UUID articleId, String subject, String title, Instant updatedAt) {
        return new ArticleDraft(UUID.randomUUID(), tenant, articleId, subject, 1,
                title, "摘要", "正文", List.of("tag"), List.of("keyword"),
                null, null, null, List.of(), List.of(), updatedAt);
    }

    private GenerationPreset preset(String tenant, String name, String tone, Instant updatedAt) {
        return new GenerationPreset(UUID.randomUUID(), tenant, name, "TOPIC", "zh-CN", tone,
                800, 1600, 10, "概述\n正文", "TUTORIAL", "MIXED", null,
                null, "prompt-v1", "params-v1", 0, false, "tester", NOW, updatedAt);
    }

    private ManualProgress progress(String tenant, UUID articleId, String subject,
                                    boolean published, Instant updatedAt) {
        return new ManualProgress(UUID.randomUUID(), tenant, articleId, "CSDN", subject,
                true, true, true, true, published, updatedAt);
    }

    private NotificationItem notification(String tenant, String dedupKey, String title, Instant updatedAt) {
        return new NotificationItem(UUID.randomUUID(), tenant, "CHANNEL_HEALTH", "ERROR", dedupKey,
                title, "通知正文", "/channels", null, null, null, NOW, updatedAt);
    }

    private NotificationEndpoint endpoint(String tenant, String name, Instant updatedAt) {
        return new NotificationEndpoint(UUID.randomUUID(), tenant, name,
                "https://hooks.example.com/content-publisher", true, "tester", NOW, updatedAt);
    }

    private ChannelAccount account(String tenant, Instant verifiedAt, ChannelVerificationStatus verificationStatus) {
        String suffix = UUID.randomUUID().toString();
        return new ChannelAccount(UUID.randomUUID(), tenant, ChannelType.DEV, "DEV " + suffix,
                "https://dev.to", "v1:encrypted", "channel-" + suffix, "b".repeat(64),
                "c".repeat(64), 1, ChannelAccountStatus.ACTIVE, verificationStatus,
                verificationStatus == ChannelVerificationStatus.FAILED ? "failed" : "ok",
                verifiedAt, "tester", "tester", NOW.minusSeconds(172800), NOW);
    }
}
