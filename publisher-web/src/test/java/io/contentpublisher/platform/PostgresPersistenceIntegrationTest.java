package io.contentpublisher.platform;

import io.contentpublisher.platform.application.AutomationApplicationService.NotificationEndpoint;
import io.contentpublisher.platform.application.AutomationApplicationService.NotificationItem;
import io.contentpublisher.platform.application.port.AutomationRepository;
import io.contentpublisher.platform.application.port.JobRepository;
import io.contentpublisher.platform.application.port.ManualChannelProfileRepository;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.Job;
import io.contentpublisher.platform.domain.JobPayload;
import io.contentpublisher.platform.domain.JobStatus;
import io.contentpublisher.platform.domain.JobType;
import io.contentpublisher.platform.domain.ManualChannelProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "publisher.security.mode=DISABLED",
        "publisher.jobs.worker-enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class PostgresPersistenceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("content_publisher")
            .withUsername("content_publisher")
            .withPassword("integration-test");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JobRepository jobs;
    @Autowired AutomationRepository automation;
    @Autowired ManualChannelProfileRepository manualChannelProfiles;
    @Autowired JdbcTemplate jdbc;

    @Test
    void shouldApplyPersonalContentConfirmationMigration() {
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '22' and success = true",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void shouldRunAllMigrationsAndClaimScheduledJobOnlyOnceAcrossWorkers() throws Exception {
        Instant now = Instant.now();
        Job job = pending("postgres-claim-once", now);
        jobs.save(job);

        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return jobs.claimNext("postgres-worker-a", now.plusSeconds(1), now.minusSeconds(300));
            });
            var second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return jobs.claimNext("postgres-worker-b", now.plusSeconds(1), now.minusSeconds(300));
            });
            start.countDown();
            long claimed = java.util.stream.Stream.of(first.get(10, TimeUnit.SECONDS),
                            second.get(10, TimeUnit.SECONDS))
                    .filter(java.util.Optional::isPresent)
                    .count();
            assertThat(claimed).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        Job stored = jobs.findJobById(job.tenantId(), job.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(stored.attempt()).isEqualTo(1);
    }

    @Test
    void shouldRespectFutureScheduleAndTenantIdempotency() {
        Instant now = Instant.now();
        Job scheduled = pending("postgres-scheduled", now.plusSeconds(3_600));
        jobs.save(scheduled);

        assertThat(jobs.claimNext("postgres-worker", now, now.minusSeconds(300))).isEmpty();
        assertThat(jobs.findByIdempotencyKey("postgres-tenant", "postgres-scheduled"))
                .get().extracting(Job::id).isEqualTo(scheduled.id());
        assertThat(jobs.findByIdempotencyKey("other-tenant", "postgres-scheduled")).isEmpty();
    }

    @Test
    void shouldPersistAutomationTimestampsWithPostgres() {
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        String tenant = "postgres-automation";
        NotificationItem notification = automation.saveNotification(new NotificationItem(
                UUID.randomUUID(), tenant, "CHANNEL_HEALTH_FAILED", "ERROR", "postgres-webhook",
                "渠道巡检失败", "PostgreSQL 时间参数验证", "/channels", null, null, null, now, now));
        automation.saveNotificationEndpoint(new NotificationEndpoint(
                UUID.randomUUID(), tenant, "postgres-test", "https://example.com/webhook",
                true, "integration-test", now, now));

        automation.prepareWebhookDeliveries(now, 10);
        var delivery = automation.findWebhookDeliveriesDue(now, 10).get(0);
        assertThat(delivery.notificationId()).isEqualTo(notification.id());

        automation.markWebhookDeliveryFailed(delivery.id(), 1, now.plusSeconds(60),
                "HTTP 503", false, now.plusSeconds(1));
        assertThat(automation.findWebhookDeliveriesDue(now.plusSeconds(30), 10)).isEmpty();
        assertThat(automation.findWebhookDeliveriesDue(now.plusSeconds(60), 10)).singleElement()
                .extracting(item -> item.attempts()).isEqualTo(1);

        automation.markWebhookDeliverySucceeded(delivery.id(), now.plusSeconds(61));
        assertThat(automation.findWebhookDeliveriesDue(now.plusSeconds(120), 10)).isEmpty();
        assertThat(automation.findCalendar(tenant, now.minusSeconds(60), now.plusSeconds(60))).isEmpty();
        assertThat(automation.findChannelChecksDue(now, 10)).isEmpty();
        assertThat(automation.findActions(tenant, now.minusSeconds(900))).isNotNull();
    }

    @Test
    void shouldPersistAndOptimisticallyUpdateManualChannelProfilesPerTenant() {
        Instant createdAt = Instant.parse("2026-08-10T09:00:00Z");
        ManualChannelProfile original = new ManualChannelProfile(UUID.randomUUID(), "postgres-personal",
                ChannelType.CSDN, true, "个人 CSDN", List.of("java", "spring"), "后端开发",
                "发布前选择原创", 20, null, 1, "owner", "owner", createdAt, createdAt);

        ManualChannelProfile saved = manualChannelProfiles.save(original);

        assertThat(manualChannelProfiles.findByChannel("postgres-personal", ChannelType.CSDN))
                .contains(saved);
        assertThat(manualChannelProfiles.findAll("postgres-personal"))
                .extracting(ManualChannelProfile::channelType)
                .containsExactly(ChannelType.CSDN);
        assertThat(manualChannelProfiles.findByChannel("other-tenant", ChannelType.CSDN)).isEmpty();

        Instant updatedAt = createdAt.plusSeconds(60);
        ManualChannelProfile update = new ManualChannelProfile(saved.id(), saved.tenantId(),
                saved.channelType(), false, saved.accountAlias(), saved.defaultTags(), saved.defaultSection(),
                saved.notes(), 30, updatedAt, 2, saved.createdBy(), "owner", saved.createdAt(), updatedAt);
        assertThat(manualChannelProfiles.updateIfVersionMatches(update, 1))
                .get().satisfies(profile -> {
                    assertThat(profile.enabled()).isFalse();
                    assertThat(profile.version()).isEqualTo(2);
                    assertThat(profile.loginConfirmedAt()).isEqualTo(updatedAt);
                });

        ManualChannelProfile stale = new ManualChannelProfile(saved.id(), saved.tenantId(),
                saved.channelType(), true, saved.accountAlias(), saved.defaultTags(), saved.defaultSection(),
                saved.notes(), 40, updatedAt, 3, saved.createdBy(), "owner", saved.createdAt(),
                updatedAt.plusSeconds(60));
        assertThat(manualChannelProfiles.updateIfVersionMatches(stale, 1)).isEmpty();
    }

    @Test
    void shouldEnforceOneManualProfilePerTenantAndChannel() {
        Instant now = Instant.parse("2026-08-10T09:30:00Z");
        manualChannelProfiles.save(new ManualChannelProfile(UUID.randomUUID(), "postgres-unique",
                ChannelType.JUEJIN, true, null, List.of(), null, null, 10, null, 1,
                "owner", "owner", now, now));

        assertThatThrownBy(() -> manualChannelProfiles.save(new ManualChannelProfile(UUID.randomUUID(),
                "postgres-unique", ChannelType.JUEJIN, true, null, List.of(), null, null, 20,
                null, 1, "owner", "owner", now, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Job pending(String idempotencyKey, Instant scheduledAt) {
        Instant createdAt = Instant.now();
        return new Job(UUID.randomUUID(), "postgres-tenant", "integration-test", JobType.IMPORT_PROJECT,
                JobStatus.PENDING,
                new JobPayload.ImportProject("https://github.com/contentpublisher/platform.git", null),
                idempotencyKey, "a".repeat(64), 0, 3, 5, "等待执行", "等待 PostgreSQL 工作器领取",
                null, scheduledAt, null, null, null, null, null, createdAt, createdAt);
    }
}
