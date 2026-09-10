package io.contentpublisher.platform;

import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.application.port.ProjectRepository;
import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.ChannelAccountRepository;
import io.contentpublisher.platform.application.port.JobRepository;
import io.contentpublisher.platform.application.port.ManualPublicationRepository;
import io.contentpublisher.platform.application.port.MonitoringQuery;
import io.contentpublisher.platform.application.port.PublicationRepository;
import io.contentpublisher.platform.application.MonitoringWindow;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.domain.ArticleVersion;
import io.contentpublisher.platform.domain.ChannelAccount;
import io.contentpublisher.platform.domain.ChannelAccountStatus;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.ContentOrigin;
import io.contentpublisher.platform.domain.ContentFormat;
import io.contentpublisher.platform.domain.GenerationPolicy;
import io.contentpublisher.platform.domain.Job;
import io.contentpublisher.platform.domain.JobPayload;
import io.contentpublisher.platform.domain.JobStatus;
import io.contentpublisher.platform.domain.JobType;
import io.contentpublisher.platform.domain.ManualPublication;
import io.contentpublisher.platform.domain.Publication;
import io.contentpublisher.platform.domain.PublicationStatus;
import io.contentpublisher.platform.domain.TopicBrief;
import io.contentpublisher.platform.domain.Project;
import io.contentpublisher.platform.domain.ProjectStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tenant;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "publisher.security.mode=DISABLED"
})
class TenantPersistenceIntegrationTest {
    @Autowired ProjectRepository projects;
    @Autowired ArticleRepository articles;
    @Autowired ChannelAccountRepository channelAccounts;
    @Autowired JobRepository jobs;
    @Autowired PublicationRepository publications;
    @Autowired ManualPublicationRepository manualPublications;
    @Autowired MonitoringQuery monitoring;
    @Autowired AuditRecorder auditRecorder;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void shouldIsolateSameRepositoryAcrossTenantsAndWriteAuditLog() {
        String gitUrl = "https://github.com/contentpublisher/platform.git";
        Project tenantA = project("tenant-a", gitUrl);
        Project tenantB = project("tenant-b", gitUrl);
        projects.save(tenantA);
        projects.save(tenantB);

        assertThat(projects.findByGitUrl("tenant-a", gitUrl)).get().extracting(Project::id).isEqualTo(tenantA.id());
        assertThat(projects.findProjectById("tenant-b", tenantA.id())).isEmpty();

        auditRecorder.record(new ActorContext("tenant-a", "editor-a"), "PROJECT_IMPORTED", "PROJECT",
                tenantA.id(), Map.of("revision", "abc123"));
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where tenant_id = ? and target_id = ?", Integer.class,
                "tenant-a", tenantA.id());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void shouldPersistArticleAndImmutableVersionTogether() {
        Project project = project("tenant-version", "https://github.com/contentpublisher/versioned.git");
        projects.save(project);
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        Article article = new Article(UUID.randomUUID(), "tenant-version", ContentOrigin.git(project.id()), null,
                "Title", "Summary", "## Body", List.of("Java", "Publishing"),
                List.of("Java publishing tutorial", "content workflow"),
                "Title EN", "Summary EN", "## Body EN", List.of("java-en", "publishing-en"),
                List.of("java publishing tutorial en", "content workflow en"),
                "en", "abc123", 1, ArticleStatus.DRAFT, "editor", "editor", now, now);

        articles.saveWithVersion(article, new ArticleVersion("tenant-version", article.id(), 1,
                article.title(), article.summary(), article.markdown(), article.tags(), article.keywords(),
                article.titleEn(), article.summaryEn(), article.markdownEn(), article.tagsEn(), article.keywordsEn(),
                "editor", now));

        assertThat(articles.findArticleById("tenant-version", article.id())).get().satisfies(saved -> {
            assertThat(saved.currentVersion()).isEqualTo(1);
            assertThat(saved.tags()).containsExactly("Java", "Publishing");
            assertThat(saved.keywords()).containsExactly("Java publishing tutorial", "content workflow");
            assertThat(saved.titleEn()).isEqualTo("Title EN");
            assertThat(saved.tagsEn()).containsExactly("java-en", "publishing-en");
            assertThat(saved.keywordsEn()).containsExactly("java publishing tutorial en", "content workflow en");
        });
        assertThat(articles.findVersions("tenant-version", article.id())).singleElement().satisfies(version -> {
            assertThat(version.versionNumber()).isEqualTo(1);
            assertThat(version.tags()).containsExactly("Java", "Publishing");
            assertThat(version.keywords()).containsExactly("Java publishing tutorial", "content workflow");
            assertThat(version.titleEn()).isEqualTo("Title EN");
        });
        assertThat(articles.findVersions("other-tenant", article.id())).isEmpty();
    }

    @Test
    void shouldPersistCustomArticleWithoutGitProject() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        Article article = new Article(UUID.randomUUID(), "tenant-custom",
                ContentOrigin.custom("架构重构随笔", "关于单体架构模块化的实战记录", List.of("架构", "模块化")),
                null, "架构重构随笔", "关于单体架构模块化的实战记录", "## 架构设计\n直接记录的笔记正文。",
                List.of("架构", "重构"), List.of("架构", "模块化"), "", "", "", List.of(), List.of(),
                "zh-CN", "c".repeat(64), 1, ArticleStatus.DRAFT, "editor", "editor", now, now);

        articles.saveWithVersion(article, new ArticleVersion("tenant-custom", article.id(), 1, article.title(),
                article.summary(), article.markdown(), article.tags(), article.keywords(),
                article.titleEn(), article.summaryEn(), article.markdownEn(), article.tagsEn(), article.keywordsEn(),
                "editor", now));

        assertThat(articles.findArticleById("tenant-custom", article.id())).get().satisfies(saved -> {
            assertThat(saved.projectId()).isNull();
            assertThat(saved.sourceType().name()).isEqualTo("CUSTOM");
            assertThat(saved.origin().title()).isEqualTo("架构重构随笔");
            assertThat(saved.tags()).containsExactly("架构", "重构");
            assertThat(saved.keywords()).containsExactly("架构", "模块化");
        });

        var searchResult = articles.searchArticles("tenant-custom", "架构", null,
                io.contentpublisher.platform.domain.ArticleSourceType.CUSTOM, null, 0, 10);
        assertThat(searchResult.items()).hasSize(1);
        assertThat(searchResult.items().get(0).id()).isEqualTo(article.id());
    }

    @Test
    void shouldPersistTopicArticleWithoutGitProject() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        TopicBrief brief = new TopicBrief("PostgreSQL 索引优化", "创建面向开发者的排查教程", "后端开发者",
                "TROUBLESHOOTING", "INTERMEDIATE", List.of("PostgreSQL", "索引"), null);
        Article article = new Article(UUID.randomUUID(), "tenant-topic", ContentOrigin.topic(brief), UUID.randomUUID(),
                "PostgreSQL 索引优化教程", "摘要", "## 分步教程\n正文", brief.keywords(), "zh-CN",
                "b".repeat(64), 1, ArticleStatus.DRAFT, "editor", "editor", now, now);

        articles.saveWithVersion(article, new ArticleVersion("tenant-topic", article.id(), 1, article.title(),
                article.summary(), article.markdown(), article.keywords(), "editor", now));

        assertThat(articles.findArticleById("tenant-topic", article.id())).get().satisfies(saved -> {
            assertThat(saved.projectId()).isNull();
            assertThat(saved.sourceType().name()).isEqualTo("TOPIC");
            assertThat(saved.origin().title()).isEqualTo("PostgreSQL 索引优化");
            assertThat(saved.origin().requestedKeywords()).containsExactly("PostgreSQL", "索引");
        });
    }

    @Test
    void shouldMoveArticleToRecycleBinAndRestoreItWithinTenant() {
        Project project = project("tenant-delete", "https://github.com/contentpublisher/delete.git");
        projects.save(project);
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        Article article = new Article(UUID.randomUUID(), "tenant-delete", ContentOrigin.git(project.id()), null,
                "不合理内容", "摘要", "正文", List.of("delete"), "zh-CN", "abc123", 1,
                ArticleStatus.DRAFT, "editor", "editor", now, now);
        articles.saveWithVersion(article, new ArticleVersion("tenant-delete", article.id(), 1,
                article.title(), article.summary(), article.markdown(), article.keywords(), "editor", now));

        Instant deletedAt = now.plusSeconds(60);
        assertThat(articles.softDeleteArticleRecord("other-tenant", article.id(), "admin", deletedAt)).isFalse();
        assertThat(articles.softDeleteArticleRecord("tenant-delete", article.id(), "admin", deletedAt)).isTrue();

        assertThat(articles.findArticleById("tenant-delete", article.id())).isEmpty();
        assertThat(articles.findDeletedArticleById("tenant-delete", article.id())).isPresent();
        assertThat(articles.findDeletedArticles("tenant-delete", 10)).singleElement().satisfies(deleted -> {
            assertThat(deleted.id()).isEqualTo(article.id());
            assertThat(deleted.deletedBy()).isEqualTo("admin");
            assertThat(deleted.deletedAt()).isEqualTo(deletedAt);
        });

        assertThat(articles.restoreArticleRecord("other-tenant", article.id())).isFalse();
        assertThat(articles.restoreArticleRecord("tenant-delete", article.id())).isTrue();
        assertThat(articles.findArticleById("tenant-delete", article.id())).isPresent();
        assertThat(articles.findDeletedArticleById("tenant-delete", article.id())).isEmpty();
        assertThat(articles.findVersions("tenant-delete", article.id())).hasSize(1);
    }

    @Test
    void shouldRollbackArticleAndAssociatedPublicationsAsOneTransaction() {
        String tenantId = "tenant-article-rollback";
        Instant now = Instant.parse("2026-07-20T01:00:00Z");
        Project project = project(tenantId, "https://github.com/contentpublisher/article-rollback.git");
        projects.save(project);
        Article article = new Article(UUID.randomUUID(), tenantId, ContentOrigin.git(project.id()), null,
                "事务文章", "摘要", "正文", List.of("transaction"), "zh-CN", "abc123", 1,
                ArticleStatus.APPROVED, "editor", "editor", now, now);
        articles.saveWithVersion(article, new ArticleVersion(tenantId, article.id(), 1, article.title(),
                article.summary(), article.markdown(), article.keywords(), "editor", now));
        ChannelAccount account = channelAccount(tenantId, now, "article-rollback-account");
        channelAccounts.save(account);
        Job job = new Job(UUID.randomUUID(), tenantId, "editor", JobType.PUBLISH_ARTICLE, JobStatus.SUCCEEDED,
                new JobPayload.PublishArticle(article.id(), account.id(), null), "article-rollback-job",
                "d".repeat(64), 1, 3, 100, "执行完成", "任务完成", null, now, null, null,
                article.id(), null, null, now, now);
        jobs.save(job);
        Publication publication = new Publication(UUID.randomUUID(), tenantId, article.id(), account.id(),
                job.id(), ChannelType.DEV, null, PublicationStatus.PUBLISHED, "external",
                "https://dev.to/article-rollback", null, null, now, now, now);
        publications.save(publication);
        ManualPublication manual = new ManualPublication(UUID.randomUUID(), tenantId, article.id(), ChannelType.CSDN,
                ContentFormat.MARKDOWN, article.title(), article.markdown(), "https://blog.csdn.net/article-rollback",
                "editor", now);
        manualPublications.save(manual);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            assertThat(articles.softDeleteArticleRecord(tenantId, article.id(), "admin", now.plusSeconds(30)))
                    .isTrue();
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(articles.findArticleById(tenantId, article.id())).isPresent();
        assertThat(publications.findPublicationById(tenantId, publication.id())).isPresent();
        assertThat(manualPublications.findByArticle(tenantId, article.id())).extracting(ManualPublication::id)
                .contains(manual.id());

        assertThat(articles.softDeleteArticleRecord(tenantId, article.id(), "admin", now.plusSeconds(60))).isTrue();
        assertThat(articles.findArticleById(tenantId, article.id())).isEmpty();
        assertThat(publications.findPublicationById(tenantId, publication.id())).isEmpty();
        assertThat(manualPublications.findByArticle(tenantId, article.id())).isEmpty();
        assertThat(articles.restoreArticleRecord(tenantId, article.id())).isTrue();
        assertThat(publications.findPublicationById(tenantId, publication.id())).isPresent();
        assertThat(manualPublications.findByArticle(tenantId, article.id())).hasSize(1);
    }

    @Test
    void shouldRollbackJobAndAssociatedPublicationAsOneTransaction() {
        String tenantId = "tenant-job-rollback";
        Instant now = Instant.parse("2026-07-20T02:00:00Z");
        Project project = project(tenantId, "https://github.com/contentpublisher/job-rollback.git");
        projects.save(project);
        Article article = new Article(UUID.randomUUID(), tenantId, ContentOrigin.git(project.id()), null,
                "任务事务文章", "摘要", "正文", List.of("transaction"), "zh-CN", "abc123", 1,
                ArticleStatus.APPROVED, "editor", "editor", now, now);
        articles.saveWithVersion(article, new ArticleVersion(tenantId, article.id(), 1, article.title(),
                article.summary(), article.markdown(), article.keywords(), "editor", now));
        ChannelAccount account = channelAccount(tenantId, now, "job-rollback-account");
        channelAccounts.save(account);
        Job job = new Job(UUID.randomUUID(), tenantId, "editor", JobType.PUBLISH_ARTICLE, JobStatus.SUCCEEDED,
                new JobPayload.PublishArticle(article.id(), account.id(), null), "job-rollback-key",
                "a".repeat(64), 1, 3, 100, "执行完成", "任务完成", null, now, null, null,
                UUID.randomUUID(), null, null, now, now);
        jobs.save(job);
        Publication publication = new Publication(UUID.randomUUID(), tenantId, article.id(), account.id(), job.id(),
                ChannelType.DEV, null, PublicationStatus.PUBLISHED, "external", "https://dev.to/job-rollback",
                null, null, now, now, now);
        publications.save(publication);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            assertThat(jobs.softDeleteJobRecord(tenantId, job.id(), "admin", now.plusSeconds(30))).isTrue();
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jobs.findJobById(tenantId, job.id())).isPresent();
        assertThat(publications.findPublicationById(tenantId, publication.id())).isPresent();
        assertThat(jobs.softDeleteJobRecord(tenantId, job.id(), "admin", now.plusSeconds(60))).isTrue();
        assertThat(jobs.findJobById(tenantId, job.id())).isEmpty();
        assertThat(publications.findPublicationById(tenantId, publication.id())).isEmpty();
        assertThat(jobs.restoreJobRecord(tenantId, job.id())).isTrue();
        assertThat(jobs.findJobById(tenantId, job.id())).isPresent();
        assertThat(publications.findPublicationById(tenantId, publication.id())).isPresent();
    }

    @Test
    void shouldExcludeRecycleBinRecordsFromMonitoring() {
        String tenantId = "tenant-monitoring-delete";
        Instant now = Instant.parse("2026-07-20T08:00:00Z");
        Project project = project(tenantId, "https://github.com/contentpublisher/monitoring-delete.git");
        projects.save(project);
        Article article = new Article(UUID.randomUUID(), tenantId, ContentOrigin.git(project.id()), UUID.randomUUID(),
                "监控软删除验证", "摘要", "## 正文", List.of("monitoring"), "zh-CN", "abc123", 1,
                ArticleStatus.APPROVED, "editor", "editor", now, now);
        articles.saveWithVersion(article, new ArticleVersion(tenantId, article.id(), 1, article.title(),
                article.summary(), article.markdown(), article.keywords(), "editor", now));

        GenerationPolicy policy = new GenerationPolicy("zh-CN", "专业", 200, 800, 5,
                List.of(), List.of(), List.of());
        Job job = new Job(UUID.randomUUID(), tenantId, "editor", JobType.GENERATE_ARTICLE,
                JobStatus.SUCCEEDED, new JobPayload.GenerateArticle(project.id(), policy),
                "monitoring-delete-job", "a".repeat(64), 1, 4, 100, "执行完成", "任务完成", null,
                now, null, null, article.id(), null, null, now, now);
        jobs.save(job);

        ChannelAccount account = new ChannelAccount(UUID.randomUUID(), tenantId, ChannelType.DEV, "DEV",
                "https://dev.to", "v1:encrypted", "monitoring-delete-account", "b".repeat(64),
                "c".repeat(64), 1, ChannelAccountStatus.ACTIVE, "admin", "admin", now, now);
        channelAccounts.save(account);
        publications.save(new Publication(UUID.randomUUID(), tenantId, article.id(), account.id(), job.id(),
                ChannelType.DEV, null, PublicationStatus.PUBLISHED, "external", "https://dev.to/example",
                null, null, now, now, now));
        manualPublications.save(new ManualPublication(UUID.randomUUID(), tenantId, article.id(), ChannelType.CSDN,
                ContentFormat.MARKDOWN, article.title(), article.markdown(), "https://blog.csdn.net/example",
                "editor", now));

        var before = monitoring.load(tenantId, MonitoringWindow.HOURS_24, now.minusSeconds(60), now.plusSeconds(60));
        assertThat(before.articleCount()).isEqualTo(1);
        assertThat(before.jobCount()).isEqualTo(1);
        assertThat(before.publicationCount()).isEqualTo(2);

        Instant deletedAt = now.plusSeconds(30);
        assertThat(articles.softDeleteArticleRecord(tenantId, article.id(), "admin", deletedAt)).isTrue();
        assertThat(jobs.softDeleteJobRecord(tenantId, job.id(), "admin", deletedAt)).isTrue();

        var after = monitoring.load(tenantId, MonitoringWindow.HOURS_24, now.minusSeconds(60), now.plusSeconds(60));
        assertThat(after.articleCount()).isZero();
        assertThat(after.articleActivityCount()).isZero();
        assertThat(after.articlesByStatus().values()).allMatch(value -> value == 0L);
        assertThat(after.jobCount()).isZero();
        assertThat(after.jobActivityCount()).isZero();
        assertThat(after.jobsByStatus().values()).allMatch(value -> value == 0L);
        assertThat(after.publicationCount()).isZero();
        assertThat(after.publicationActivityCount()).isZero();
        assertThat(after.publicationsByStatus().values()).allMatch(value -> value == 0L);
        assertThat(after.coveredChannelCount()).isZero();
        assertThat(after.channelPerformance()).isEmpty();
    }

    @Test
    void shouldAtomicallyRejectStaleChannelAccountVersion() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        ChannelAccount account = new ChannelAccount(UUID.randomUUID(), "tenant-account-version", ChannelType.DEV,
                "DEV", "https://dev.to", "v1:encrypted", "account-version-key", "a".repeat(64),
                "b".repeat(64), 1, ChannelAccountStatus.ACTIVE, "admin", "admin", now, now);
        channelAccounts.save(account);
        ChannelAccount disabled = new ChannelAccount(account.id(), account.tenantId(), account.type(),
                account.displayName(), account.baseUrl(), account.encryptedCredentials(), account.idempotencyKey(),
                account.requestHash(), account.credentialFingerprint(), 2, ChannelAccountStatus.DISABLED,
                account.createdBy(), "admin-1", account.createdAt(), now.plusSeconds(1));
        ChannelAccount staleRotation = new ChannelAccount(account.id(), account.tenantId(), account.type(),
                account.displayName(), account.baseUrl(), "v1:other", account.idempotencyKey(), account.requestHash(),
                "c".repeat(64), 2, account.status(), account.createdBy(), "admin-2", account.createdAt(),
                now.plusSeconds(2));

        assertThat(channelAccounts.updateIfVersionMatches(disabled, 1)).get()
                .extracting(ChannelAccount::status).isEqualTo(ChannelAccountStatus.DISABLED);
        assertThat(channelAccounts.updateIfVersionMatches(staleRotation, 1)).isEmpty();
        assertThat(channelAccounts.findChannelAccountById(account.tenantId(), account.id())).get()
                .extracting(ChannelAccount::version, ChannelAccount::status)
                .containsExactly(2, ChannelAccountStatus.DISABLED);
    }

    @Test
    void shouldSoftDeleteChannelAccountClearCredentialsAndReleaseIdempotencyKey() {
        Instant now = Instant.parse("2026-08-11T03:00:00Z");
        String tenantId = "tenant-account-delete";
        ChannelAccount account = channelAccount(tenantId, now, "account-delete-key");
        channelAccounts.save(account);

        assertThat(channelAccounts.softDeleteIfVersionMatches(tenantId, account.id(), 1,
                "admin", now.plusSeconds(1))).isTrue();
        assertThat(channelAccounts.findChannelAccountById(tenantId, account.id())).isEmpty();
        assertThat(channelAccounts.findChannelAccountById("other-tenant", account.id())).isEmpty();
        assertThat(channelAccounts.findAll(tenantId)).isEmpty();
        assertThat(channelAccounts.countAll(tenantId)).isZero();
        assertThat(channelAccounts.findDisplayNamesForPublicationHistory(tenantId))
                .containsEntry(account.id(), account.displayName());
        assertThat(channelAccounts.findDisplayNamesForPublicationHistory("other-tenant")).isEmpty();
        assertThat(jdbcTemplate.queryForMap("""
                select status, encrypted_credentials, credential_fingerprint, idempotency_key,
                       account_version, verification_status, deleted_by, deleted_at
                from channel_accounts where id = ?
                """, account.id())).containsEntry("status", "DISABLED")
                .containsEntry("encrypted_credentials", "DELETED")
                .containsEntry("credential_fingerprint", "DELETED")
                .containsEntry("idempotency_key", "deleted:" + account.id())
                .containsEntry("account_version", 2)
                .containsEntry("deleted_by", "admin");

        ChannelAccount replacement = channelAccount(tenantId, now.plusSeconds(2), "account-delete-key");
        assertThat(channelAccounts.save(replacement).id()).isEqualTo(replacement.id());
        assertThat(channelAccounts.countAll(tenantId)).isEqualTo(1);
    }

    private Project project(String tenantId, String gitUrl) {
        Instant now = Instant.now();
        return new Project(UUID.randomUUID(), tenantId, gitUrl, "platform", "publisher", "main", "abc123",
                List.of("Java"), "LICENSE", ProjectStatus.READY, "creator", "creator", now, now);
    }

    private ChannelAccount channelAccount(String tenantId, Instant now, String idempotencyKey) {
        return new ChannelAccount(UUID.randomUUID(), tenantId, ChannelType.DEV, "DEV", "https://dev.to",
                "v1:encrypted", idempotencyKey, "b".repeat(64), "c".repeat(64), 1,
                ChannelAccountStatus.ACTIVE, "admin", "admin", now, now);
    }
}
