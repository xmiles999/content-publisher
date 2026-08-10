package io.contentpublisher.platform;

import io.contentpublisher.platform.application.port.JobRepository;
import io.contentpublisher.platform.domain.Job;
import io.contentpublisher.platform.domain.JobPayload;
import io.contentpublisher.platform.domain.JobStatus;
import io.contentpublisher.platform.domain.JobType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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

    private Job pending(String idempotencyKey, Instant scheduledAt) {
        Instant createdAt = Instant.now();
        return new Job(UUID.randomUUID(), "postgres-tenant", "integration-test", JobType.IMPORT_PROJECT,
                JobStatus.PENDING,
                new JobPayload.ImportProject("https://github.com/contentpublisher/platform.git", null),
                idempotencyKey, "a".repeat(64), 0, 3, 5, "等待执行", "等待 PostgreSQL 工作器领取",
                null, scheduledAt, null, null, null, null, null, createdAt, createdAt);
    }
}
