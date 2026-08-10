package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.application.port.JobRepository;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.Job;
import io.contentpublisher.platform.domain.JobPayload;
import io.contentpublisher.platform.domain.JobStatus;
import io.contentpublisher.platform.domain.JobType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobApplicationServiceTest {
    @Test
    void shouldIncludeCanonicalUrlInPublicationIdempotencyHash() {
        JobRepository jobs = mock(JobRepository.class);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        PublishingApplicationService publishing = mock(PublishingApplicationService.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        AtomicReference<Job> stored = new AtomicReference<>();
        when(publishing.validateCanonicalUrl(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.findByIdempotencyKey("tenant", "publication-key-001"))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(jobs.createIfWithinQuota(any(Job.class), anyInt())).thenAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            stored.set(job);
            return Optional.of(job);
        });
        JobApplicationService service = new JobApplicationService(jobs, projects, publishing, audits,
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC), 20, 4);
        ActorContext actor = new ActorContext("tenant", "editor");
        UUID articleId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        Job first = service.submitPublication(actor, articleId, accountId,
                "https://example.com/first", "publication-key-001");

        assertThat(first).isSameAs(stored.get());
        assertThatThrownBy(() -> service.submitPublication(actor, articleId, accountId,
                "https://example.com/second", "publication-key-001"))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void shouldSubmitMultiplePublicationJobsIdempotently() {
        JobRepository jobs = mock(JobRepository.class);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        PublishingApplicationService publishing = mock(PublishingApplicationService.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        Map<String, Job> stored = new LinkedHashMap<>();
        when(publishing.validateCanonicalUrl("https://example.com/original"))
                .thenReturn("https://example.com/original");
        when(jobs.findByIdempotencyKey(anyString(), anyString())).thenAnswer(invocation ->
                Optional.ofNullable(stored.get(invocation.getArgument(1))));
        when(jobs.createBatchIfWithinQuota(any(), anyInt())).thenAnswer(invocation -> {
            List<Job> batch = invocation.getArgument(0);
            batch.forEach(job -> stored.put(job.idempotencyKey(), job));
            return Optional.of(batch);
        });
        JobApplicationService service = new JobApplicationService(jobs, projects, publishing, audits,
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC), 20, 4);
        ActorContext actor = new ActorContext("tenant", "editor");
        UUID articleId = UUID.randomUUID();
        List<UUID> accountIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        List<Job> first = service.submitPublications(actor, articleId, accountIds,
                "https://example.com/original", "publication-batch-001");
        List<Job> repeated = service.submitPublications(actor, articleId, accountIds,
                "https://example.com/original", "publication-batch-001");

        assertThat(first).hasSize(2).extracting(Job::idempotencyKey)
                .allMatch(key -> key.startsWith("publication-batch:"));
        assertThat(first).extracting(Job::batchId).doesNotContainNull().containsOnly(first.get(0).batchId());
        assertThat(repeated).extracting(Job::id).containsExactlyElementsOf(first.stream().map(Job::id).toList());
        assertThat(stored).hasSize(2);
    }

    @Test
    void shouldRetryOnlyFailedAccountWithinOriginalPublicationBatch() {
        JobRepository jobs = mock(JobRepository.class);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        PublishingApplicationService publishing = mock(PublishingApplicationService.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        UUID failedJobId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Job failed = new Job(failedJobId, "tenant", "editor", JobType.PUBLISH_ARTICLE, JobStatus.FAILED,
                new JobPayload.PublishArticle(articleId, accountId, "https://example.com/original"),
                "old-publication-job", "d".repeat(64), 4, 4, 100, "执行失败", "平台调用失败", batchId,
                now, null, null, null, "CHANNEL_FAILED", "平台调用失败", now, now);
        when(jobs.findJobById("tenant", failedJobId)).thenReturn(Optional.of(failed));
        when(jobs.findByIdempotencyKey("tenant", "publication-retry-001")).thenReturn(Optional.empty());
        when(jobs.createIfWithinQuota(any(Job.class), anyInt()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        JobApplicationService service = new JobApplicationService(jobs, projects, publishing, audits,
                Clock.fixed(now, ZoneOffset.UTC), 20, 4);

        Job retried = service.retryFailedPublication(new ActorContext("tenant", "editor"), failedJobId,
                "publication-retry-001");

        assertThat(retried.status()).isEqualTo(JobStatus.PENDING);
        assertThat(retried.batchId()).isEqualTo(batchId);
        assertThat(retried.progressPercent()).isEqualTo(5);
        assertThat(retried.payload()).isEqualTo(failed.payload());
    }

    @Test
    void shouldReplayFailedNonPublicationJobAndAuditOriginalJob() {
        JobRepository jobs = mock(JobRepository.class);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        PublishingApplicationService publishing = mock(PublishingApplicationService.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        Job failed = failedImportJob(now, "IMPORT_FAILED", "仓库导入失败");
        when(jobs.findJobById("tenant", failed.id())).thenReturn(Optional.of(failed));
        when(jobs.findByIdempotencyKey("tenant", "job-replay-key-001")).thenReturn(Optional.empty());
        when(jobs.createIfWithinQuota(any(Job.class), anyInt()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        JobApplicationService service = service(jobs, projects, publishing, audits, now);
        ActorContext actor = new ActorContext("tenant", "editor");

        Job replayed = service.replayFailedJob(actor, failed.id(), "job-replay-key-001");

        assertThat(replayed.status()).isEqualTo(JobStatus.PENDING);
        assertThat(replayed.payload()).isEqualTo(failed.payload());
        assertThat(replayed.requestHash()).isEqualTo(failed.requestHash());
        assertThat(replayed.batchId()).isEqualTo(failed.batchId());
        verify(audits).record(eq(actor), eq("JOB_REPLAY_SUBMITTED"), eq("JOB"), eq(replayed.id()),
                argThat(details -> failed.id().toString().equals(details.get("replayOfJobId"))));
    }

    @Test
    void shouldRejectReplayWhenJobIsNotFailed() {
        JobRepository jobs = mock(JobRepository.class);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        PublishingApplicationService publishing = mock(PublishingApplicationService.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        Job pending = withStatus(failedImportJob(now, null, null), JobStatus.PENDING);
        when(jobs.findJobById("tenant", pending.id())).thenReturn(Optional.of(pending));
        JobApplicationService service = service(jobs, projects, publishing, audits, now);

        assertThatThrownBy(() -> service.replayFailedJob(new ActorContext("tenant", "editor"), pending.id(),
                "job-replay-key-002"))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("JOB_NOT_REPLAYABLE"));
        verify(jobs, never()).createIfWithinQuota(any(), anyInt());
    }

    @Test
    void shouldRequireManualVerificationForUncertainPublicationReplay() {
        JobRepository jobs = mock(JobRepository.class);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        PublishingApplicationService publishing = mock(PublishingApplicationService.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        Job failed = failedPublicationJob(now, UUID.randomUUID(), UUID.randomUUID(),
                "CHANNEL_TIMEOUT", "平台响应超时，结果不确定");
        when(jobs.findJobById("tenant", failed.id())).thenReturn(Optional.of(failed));
        JobApplicationService service = service(jobs, projects, publishing, audits, now);

        assertThatThrownBy(() -> service.replayFailedJob(new ActorContext("tenant", "editor"), failed.id(),
                "job-replay-key-003"))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("JOB_REPLAY_REQUIRES_MANUAL_PUBLICATION_RETRY"));
        verify(publishing, never()).assertPublishable(any(), any(), any());
        verify(jobs, never()).createIfWithinQuota(any(), anyInt());
    }

    @Test
    void shouldRejectReplayBatchLargerThanTwenty() {
        JobRepository jobs = mock(JobRepository.class);
        JobApplicationService service = service(jobs, mock(ProjectApplicationService.class),
                mock(PublishingApplicationService.class), mock(AuditRecorder.class),
                Instant.parse("2026-07-20T00:00:00Z"));
        List<UUID> ids = java.util.stream.IntStream.range(0, 21).mapToObj(ignored -> UUID.randomUUID()).toList();

        assertThatThrownBy(() -> service.replayFailedJobs(new ActorContext("tenant", "editor"), ids,
                "job-replay-batch-001"))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_ARGUMENT"));
        verify(jobs, never()).findJobById(anyString(), any());
    }

    @Test
    void shouldValidateWholeReplayBatchBeforeCreatingAnyJob() {
        JobRepository jobs = mock(JobRepository.class);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        PublishingApplicationService publishing = mock(PublishingApplicationService.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        UUID articleId = UUID.randomUUID();
        Job first = failedPublicationJob(now, articleId, UUID.randomUUID(), "CHANNEL_FAILED", "平台拒绝请求");
        Job second = failedPublicationJob(now, articleId, UUID.randomUUID(), "CHANNEL_FAILED", "平台拒绝请求");
        when(jobs.findJobById("tenant", first.id())).thenReturn(Optional.of(first));
        when(jobs.findJobById("tenant", second.id())).thenReturn(Optional.of(second));
        org.mockito.Mockito.doThrow(new ApplicationException("ARTICLE_NOT_APPROVED", "文章未审核"))
                .when(publishing).assertPublishable(any(), eq(articleId), eq(second.payload()
                        instanceof JobPayload.PublishArticle payload ? payload.channelAccountId() : null));
        JobApplicationService service = service(jobs, projects, publishing, audits, now);

        assertThatThrownBy(() -> service.replayFailedJobs(new ActorContext("tenant", "editor"),
                List.of(first.id(), second.id()), "job-replay-batch-002"))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ARTICLE_NOT_APPROVED"));
        verify(jobs, never()).createBatchIfWithinQuota(any(), anyInt());
        verify(jobs, never()).createIfWithinQuota(any(), anyInt());
    }

    @Test
    void shouldCreateReplayBatchAtomicallyWithBoundedChildKeys() {
        JobRepository jobs = mock(JobRepository.class);
        ProjectApplicationService projects = mock(ProjectApplicationService.class);
        PublishingApplicationService publishing = mock(PublishingApplicationService.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        Job first = failedImportJob(now, "IMPORT_FAILED", "仓库导入失败");
        Job second = failedImportJob(now, "IMPORT_FAILED", "仓库导入失败");
        when(jobs.findJobById("tenant", first.id())).thenReturn(Optional.of(first));
        when(jobs.findJobById("tenant", second.id())).thenReturn(Optional.of(second));
        when(jobs.findByIdempotencyKey(eq("tenant"), anyString())).thenReturn(Optional.empty());
        when(jobs.createBatchIfWithinQuota(any(), anyInt())).thenAnswer(invocation ->
                Optional.of(List.copyOf(invocation.getArgument(0))));
        JobApplicationService service = service(jobs, projects, publishing, audits, now);
        String maximumPrefix = "x".repeat(128);

        List<Job> replayed = service.replayFailedJobs(new ActorContext("tenant", "editor"),
                List.of(first.id(), second.id()), maximumPrefix);

        assertThat(replayed).hasSize(2).extracting(Job::payload)
                .containsExactly(first.payload(), second.payload());
        assertThat(replayed).extracting(Job::idempotencyKey)
                .allMatch(key -> key.startsWith("job-replay:") && key.length() <= 128);
        verify(jobs).createBatchIfWithinQuota(argThat(batch -> batch.size() == 2), eq(20));
        verify(jobs, never()).createIfWithinQuota(any(), anyInt());
    }

    private JobApplicationService service(JobRepository jobs, ProjectApplicationService projects,
                                          PublishingApplicationService publishing, AuditRecorder audits,
                                          Instant now) {
        return new JobApplicationService(jobs, projects, publishing, audits,
                Clock.fixed(now, ZoneOffset.UTC), 20, 4);
    }

    private Job failedImportJob(Instant now, String errorCode, String errorMessage) {
        return new Job(UUID.randomUUID(), "tenant", "editor", JobType.IMPORT_PROJECT, JobStatus.FAILED,
                new JobPayload.ImportProject("https://github.com/example/repository.git", "main"),
                "old-import-job-" + UUID.randomUUID(), "a".repeat(64), 4, 4, 100,
                "执行失败", "仓库导入失败", UUID.randomUUID(), now, null, null, null,
                errorCode, errorMessage, now, now);
    }

    private Job failedPublicationJob(Instant now, UUID articleId, UUID accountId,
                                     String errorCode, String errorMessage) {
        return new Job(UUID.randomUUID(), "tenant", "editor", JobType.PUBLISH_ARTICLE, JobStatus.FAILED,
                new JobPayload.PublishArticle(articleId, accountId, "https://example.com/article"),
                "old-publication-job-" + UUID.randomUUID(), "b".repeat(64), 4, 4, 100,
                "执行失败", "平台发布失败", UUID.randomUUID(), now, null, null, null,
                errorCode, errorMessage, now, now);
    }

    private Job withStatus(Job job, JobStatus status) {
        return new Job(job.id(), job.tenantId(), job.actorSubject(), job.type(), status, job.payload(),
                job.idempotencyKey(), job.requestHash(), status == JobStatus.PENDING ? 0 : job.attempt(),
                job.maxAttempts(), status == JobStatus.PENDING ? 5 : job.progressPercent(),
                job.progressLabel(), job.progressDetail(), job.batchId(), job.scheduledAt(), job.lockedAt(),
                job.lockOwner(), job.resultResourceId(), job.errorCode(), job.errorMessage(),
                job.createdAt(), job.updatedAt());
    }
}
