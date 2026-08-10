package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.application.port.JobRepository;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.GenerationPolicy;
import io.contentpublisher.platform.domain.Job;
import io.contentpublisher.platform.domain.JobPayload;
import io.contentpublisher.platform.domain.JobStatus;
import io.contentpublisher.platform.domain.JobType;
import io.contentpublisher.platform.domain.ProjectStatus;
import io.contentpublisher.platform.domain.TopicBrief;
import io.contentpublisher.platform.domain.WebsiteBrief;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JobApplicationService {
    private final JobRepository jobs;
    private final ProjectApplicationService projects;
    private final PublishingApplicationService publishing;
    private final AuditRecorder auditRecorder;
    private final Clock clock;
    private final int maxActiveJobsPerTenant;
    private final int maxAttempts;

    public JobApplicationService(JobRepository jobs, ProjectApplicationService projects,
                                 PublishingApplicationService publishing, AuditRecorder auditRecorder,
                                 Clock clock, int maxActiveJobsPerTenant, int maxAttempts) {
        this.jobs = jobs;
        this.projects = projects;
        this.publishing = publishing;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
        this.maxActiveJobsPerTenant = maxActiveJobsPerTenant;
        this.maxAttempts = maxAttempts;
    }

    public Job submitImport(ActorContext actor, String gitUrl, String branch, String idempotencyKey) {
        validateGitUrlForStorage(gitUrl);
        JobPayload.ImportProject payload = new JobPayload.ImportProject(gitUrl, branch);
        return submit(actor, JobType.IMPORT_PROJECT, payload, idempotencyKey,
                hash("IMPORT_PROJECT", gitUrl, value(branch)));
    }

    public Job submitArticleGeneration(ActorContext actor, UUID projectId, GenerationPolicy policy,
                                       String idempotencyKey) {
        if (projects.getProject(actor, projectId).status() != ProjectStatus.READY) {
            throw new ApplicationException("PROJECT_NOT_READY", "项目尚未完成仓库分析");
        }
        JobPayload.GenerateArticle payload = new JobPayload.GenerateArticle(projectId, policy);
        return submit(actor, JobType.GENERATE_ARTICLE, payload, idempotencyKey,
                hash("GENERATE_ARTICLE", projectId.toString(), policy.toString()));
    }

    public Job submitTopicArticleGeneration(ActorContext actor, TopicBrief brief, GenerationPolicy policy,
                                            String idempotencyKey) {
        JobPayload.GenerateTopicArticle payload = new JobPayload.GenerateTopicArticle(brief, policy);
        return submit(actor, JobType.GENERATE_TOPIC_ARTICLE, payload, idempotencyKey,
                hash("GENERATE_TOPIC_ARTICLE", brief.toString(), policy.toString()));
    }

    public Job submitWebsiteArticleGeneration(ActorContext actor, WebsiteBrief brief, GenerationPolicy policy,
                                              String idempotencyKey) {
        validateWebsiteUrlForStorage(brief.websiteUrl());
        JobPayload.GenerateWebsiteArticle payload = new JobPayload.GenerateWebsiteArticle(brief, policy);
        return submit(actor, JobType.GENERATE_WEBSITE_ARTICLE, payload, idempotencyKey,
                hash("GENERATE_WEBSITE_ARTICLE", brief.toString(), policy.toString()));
    }

    public Job submitPublication(ActorContext actor, UUID articleId, UUID channelAccountId,
                                 String canonicalUrl, String idempotencyKey) {
        return submitPublication(actor, articleId, channelAccountId, canonicalUrl, idempotencyKey, null);
    }

    public Job submitPublication(ActorContext actor, UUID articleId, UUID channelAccountId,
                                 String canonicalUrl, String idempotencyKey, Instant scheduledAt) {
        String normalizedCanonicalUrl = publishing.validateCanonicalUrl(canonicalUrl);
        publishing.assertPublishable(actor, articleId, channelAccountId);
        Instant now = clock.instant();
        Instant normalizedSchedule = normalizeSchedule(scheduledAt, now);
        JobPayload.PublishArticle payload = new JobPayload.PublishArticle(articleId, channelAccountId, normalizedCanonicalUrl);
        return submit(actor, JobType.PUBLISH_ARTICLE, payload, idempotencyKey,
                hash("PUBLISH_ARTICLE", articleId.toString(), channelAccountId.toString(), value(normalizedCanonicalUrl),
                        scheduleKey(scheduledAt)), normalizedSchedule);
    }

    public List<Job> submitPublications(ActorContext actor, UUID articleId, List<UUID> channelAccountIds,
                                        String canonicalUrl, String idempotencyKey) {
        return submitPublications(actor, articleId, channelAccountIds, canonicalUrl, idempotencyKey, null);
    }

    public List<Job> submitPublications(ActorContext actor, UUID articleId, List<UUID> channelAccountIds,
                                        String canonicalUrl, String idempotencyKey, Instant scheduledAt) {
        validateIdempotencyKey(idempotencyKey);
        List<UUID> accountIds = normalizeAccountIds(channelAccountIds);
        String normalizedCanonicalUrl = publishing.validateCanonicalUrl(canonicalUrl);
        accountIds.forEach(accountId -> publishing.assertPublishable(actor, articleId, accountId));

        LinkedHashMap<String, Job> resolved = new LinkedHashMap<>();
        List<Job> candidates = new java.util.ArrayList<>();
        Instant now = clock.instant();
        Instant normalizedSchedule = normalizeSchedule(scheduledAt, now);
        UUID batchId = publicationBatchId(actor.tenantId(), idempotencyKey);
        for (UUID accountId : accountIds) {
            String childKey = batchChildKey(idempotencyKey, accountId);
            String requestHash = hash("PUBLISH_ARTICLE", articleId.toString(), accountId.toString(),
                    value(normalizedCanonicalUrl), scheduleKey(scheduledAt));
            Job existing = existingJob(actor.tenantId(), JobType.PUBLISH_ARTICLE, childKey, requestHash);
            if (existing != null) {
                resolved.put(childKey, existing);
                continue;
            }
            JobPayload.PublishArticle payload = new JobPayload.PublishArticle(articleId, accountId,
                    normalizedCanonicalUrl);
            candidates.add(pendingJob(actor, JobType.PUBLISH_ARTICLE, payload, childKey, requestHash, batchId,
                    normalizedSchedule, now));
        }

        List<Job> created = jobs.createBatchIfWithinQuota(candidates, maxActiveJobsPerTenant)
                .orElseThrow(() -> new ApplicationException("TENANT_JOB_QUOTA_EXCEEDED",
                        "租户活跃任务配额不足，无法提交全部发布平台"));
        created.forEach(job -> {
            resolved.put(job.idempotencyKey(), job);
            auditRecorder.record(actor, "JOB_SUBMITTED", "JOB", job.id(),
                    Map.of("jobType", job.type().name(), "idempotencyKey", job.idempotencyKey(),
                            "batchIdempotencyKey", idempotencyKey));
        });
        return accountIds.stream().map(accountId -> resolved.get(batchChildKey(idempotencyKey, accountId))).toList();
    }

    public Job retryFailedPublication(ActorContext actor, UUID failedJobId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        Job failed = getJob(actor, failedJobId);
        if (failed.type() != JobType.PUBLISH_ARTICLE || failed.status() != JobStatus.FAILED) {
            throw new ApplicationException("JOB_NOT_RETRYABLE", "只有执行失败的平台发布任务可以手动重试");
        }
        JobPayload.PublishArticle payload = (JobPayload.PublishArticle) failed.payload();
        publishing.assertPublishable(actor, payload.articleId(), payload.channelAccountId());
        String requestHash = hash("PUBLISH_ARTICLE", payload.articleId().toString(),
                payload.channelAccountId().toString(), value(payload.canonicalUrl()));
        Job existing = existingJob(actor.tenantId(), JobType.PUBLISH_ARTICLE, idempotencyKey, requestHash);
        if (existing != null) return existing;
        Job saved = jobs.createIfWithinQuota(pendingJob(actor, JobType.PUBLISH_ARTICLE, payload, idempotencyKey,
                        requestHash, failed.batchId(), clock.instant()), maxActiveJobsPerTenant)
                .orElseThrow(() -> new ApplicationException("TENANT_JOB_QUOTA_EXCEEDED", "租户活跃任务数量已达到上限"));
        auditRecorder.record(actor, "JOB_MANUAL_RETRY_SUBMITTED", "JOB", saved.id(),
                Map.of("failedJobId", failedJobId.toString(),
                        "batchId", failed.batchId() == null ? "" : failed.batchId().toString()));
        return saved;
    }

    public Job replayFailedJob(ActorContext actor, UUID failedJobId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        Job failed = getJob(actor, failedJobId);
        validateReplayCandidate(actor, failed);
        Job existing = existingJob(actor.tenantId(), failed.type(), idempotencyKey, failed.requestHash());
        if (existing != null) return existing;
        Job saved = jobs.createIfWithinQuota(pendingJob(actor, failed.type(), failed.payload(), idempotencyKey,
                        failed.requestHash(), failed.batchId(), clock.instant()), maxActiveJobsPerTenant)
                .orElseThrow(() -> new ApplicationException("TENANT_JOB_QUOTA_EXCEEDED", "租户活跃任务数量已达到上限"));
        auditRecorder.record(actor, "JOB_REPLAY_SUBMITTED", "JOB", saved.id(),
                Map.of("replayOfJobId", failed.id().toString(), "jobType", failed.type().name()));
        return saved;
    }

    public List<Job> replayFailedJobs(ActorContext actor, List<UUID> failedJobIds, String idempotencyPrefix) {
        validateIdempotencyKey(idempotencyPrefix);
        if (failedJobIds == null) throw new ApplicationException("INVALID_ARGUMENT", "请选择要重放的失败任务");
        List<UUID> ids = failedJobIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > 20) {
            throw new ApplicationException("INVALID_ARGUMENT", "批量重放数量必须在 1 到 20 条之间");
        }

        List<Job> failedJobs = ids.stream().map(id -> getJob(actor, id)).toList();
        failedJobs.forEach(job -> validateReplayCandidate(actor, job));

        LinkedHashMap<String, Job> resolved = new LinkedHashMap<>();
        LinkedHashMap<String, Job> originals = new LinkedHashMap<>();
        List<Job> candidates = new java.util.ArrayList<>();
        Instant now = clock.instant();
        for (Job failed : failedJobs) {
            String childKey = replayBatchChildKey(idempotencyPrefix, failed.id());
            originals.put(childKey, failed);
            Job existing = existingJob(actor.tenantId(), failed.type(), childKey, failed.requestHash());
            if (existing != null) {
                resolved.put(childKey, existing);
                continue;
            }
            candidates.add(pendingJob(actor, failed.type(), failed.payload(), childKey, failed.requestHash(),
                    failed.batchId(), now));
        }

        List<Job> created = jobs.createBatchIfWithinQuota(candidates, maxActiveJobsPerTenant)
                .orElseThrow(() -> new ApplicationException("TENANT_JOB_QUOTA_EXCEEDED",
                        "租户活跃任务配额不足，无法重放全部失败任务"));
        created.forEach(job -> {
            Job original = originals.get(job.idempotencyKey());
            resolved.put(job.idempotencyKey(), job);
            auditRecorder.record(actor, "JOB_REPLAY_SUBMITTED", "JOB", job.id(),
                    Map.of("replayOfJobId", original.id().toString(), "jobType", original.type().name(),
                            "batchIdempotencyKey", idempotencyPrefix));
        });
        return failedJobs.stream()
                .map(job -> resolved.get(replayBatchChildKey(idempotencyPrefix, job.id())))
                .toList();
    }

    public Job getJob(ActorContext actor, UUID jobId) {
        return jobs.findJobById(actor.tenantId(), jobId)
                .orElseThrow(() -> new ApplicationException("JOB_NOT_FOUND", "任务不存在"));
    }

    public List<Job> listJobs(ActorContext actor, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("列表查询数量必须在 1 到 100 之间");
        }
        return jobs.findRecentJobs(actor.tenantId(), limit);
    }

    public PagedResult<Job> searchJobs(ActorContext actor, String query, JobType type, JobStatus status,
                                       int page, int pageSize) {
        if (page < 0 || page > 100_000 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("分页参数无效");
        }
        String normalizedQuery = query == null || query.isBlank() ? "" : query.trim();
        if (normalizedQuery.length() > 120) normalizedQuery = normalizedQuery.substring(0, 120);
        return jobs.searchJobs(actor.tenantId(), normalizedQuery, type, status, page, pageSize);
    }

    public Job cancelJob(ActorContext actor, UUID jobId) {
        Job job = getJob(actor, jobId);
        if (job.status() != JobStatus.PENDING && job.status() != JobStatus.RETRY_WAIT) {
            throw new ApplicationException("JOB_NOT_CANCELLABLE", "只有尚未执行或等待重试的任务可以取消");
        }
        if (!jobs.cancelPending(actor.tenantId(), jobId, clock.instant())) {
            throw new ApplicationException("JOB_NOT_CANCELLABLE", "任务已被工作器领取或状态已变化，请刷新后重试");
        }
        Job cancelled = getJob(actor, jobId);
        auditRecorder.record(actor, "JOB_CANCELLED", "JOB", jobId,
                Map.of("jobType", cancelled.type().name()));
        return cancelled;
    }

    private Job submit(ActorContext actor, JobType type, JobPayload payload, String idempotencyKey,
                       String requestHash) {
        return submit(actor, type, payload, idempotencyKey, requestHash, clock.instant());
    }

    private Job submit(ActorContext actor, JobType type, JobPayload payload, String idempotencyKey,
                       String requestHash, Instant scheduledAt) {
        validateIdempotencyKey(idempotencyKey);
        Job existing = existingJob(actor.tenantId(), type, idempotencyKey, requestHash);
        if (existing != null) return existing;
        Instant now = clock.instant();
        Job candidate = pendingJob(actor, type, payload, idempotencyKey, requestHash, null, scheduledAt, now);
        Job saved = jobs.createIfWithinQuota(candidate, maxActiveJobsPerTenant)
                .orElseThrow(() -> new ApplicationException("TENANT_JOB_QUOTA_EXCEEDED", "租户活跃任务数量已达到上限"));
        auditRecorder.record(actor, "JOB_SUBMITTED", "JOB", saved.id(),
                Map.of("jobType", type.name(), "idempotencyKey", idempotencyKey));
        return saved;
    }

    private Job existingJob(String tenantId, JobType type, String idempotencyKey, String requestHash) {
        Job existing = jobs.findByIdempotencyKey(tenantId, idempotencyKey).orElse(null);
        if (existing != null && (existing.type() != type || !existing.requestHash().equals(requestHash))) {
            throw new ApplicationException("IDEMPOTENCY_KEY_CONFLICT", "幂等键已用于不同请求");
        }
        return existing;
    }

    private List<UUID> normalizeAccountIds(List<UUID> accountIds) {
        if (accountIds == null) throw new ApplicationException("INVALID_ARGUMENT", "请至少选择一个发布账号");
        List<UUID> normalized = accountIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (normalized.isEmpty()) throw new ApplicationException("INVALID_ARGUMENT", "请至少选择一个发布账号");
        if (normalized.size() > 20) throw new ApplicationException("INVALID_ARGUMENT", "单次最多发布到 20 个账号");
        return normalized;
    }

    private String batchChildKey(String batchKey, UUID accountId) {
        return "publication-batch:" + hash(batchKey, accountId.toString()).substring(0, 48);
    }

    private String replayBatchChildKey(String batchKey, UUID failedJobId) {
        return "job-replay:" + hash(batchKey, failedJobId.toString()).substring(0, 48);
    }

    private UUID publicationBatchId(String tenantId, String batchKey) {
        return UUID.nameUUIDFromBytes((tenantId + ":" + batchKey).getBytes(StandardCharsets.UTF_8));
    }

    private Job pendingJob(ActorContext actor, JobType type, JobPayload payload, String idempotencyKey,
                           String requestHash, UUID batchId, Instant now) {
        return pendingJob(actor, type, payload, idempotencyKey, requestHash, batchId, now, now);
    }

    private Job pendingJob(ActorContext actor, JobType type, JobPayload payload, String idempotencyKey,
                           String requestHash, UUID batchId, Instant scheduledAt, Instant now) {
        boolean delayed = scheduledAt.isAfter(now.plusSeconds(1));
        return new Job(UUID.randomUUID(), actor.tenantId(), actor.subject(), type, JobStatus.PENDING,
                payload, idempotencyKey, requestHash, 0, maxAttempts, 5,
                delayed ? "等待定时执行" : "等待执行",
                delayed ? "任务将在计划时间到达后进入执行队列" : "任务已进入队列，等待后台工作器领取",
                batchId, scheduledAt, null, null, null,
                null, null, now, now);
    }

    private Instant normalizeSchedule(Instant requested, Instant now) {
        if (requested == null) return now;
        if (requested.isBefore(now.minusSeconds(60))) {
            throw new ApplicationException("SCHEDULED_AT_INVALID", "计划发布时间不能早于当前时间");
        }
        if (requested.isAfter(now.plus(Duration.ofDays(365)))) {
            throw new ApplicationException("SCHEDULED_AT_INVALID", "计划发布时间不能超过一年");
        }
        return requested.isBefore(now) ? now : requested;
    }

    private String scheduleKey(Instant requested) {
        return requested == null ? "" : requested.toString();
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ApplicationException("IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key 必须为 8 到 128 位字母、数字、点、下划线、冒号或连字符");
        }
    }

    private void validateGitUrlForStorage(String gitUrl) {
        try {
            java.net.URI uri = java.net.URI.create(gitUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new ApplicationException("GIT_URL_REJECTED",
                        "Git 地址必须为不含凭据、查询参数和片段的 HTTPS URL");
            }
        } catch (IllegalArgumentException exception) {
            throw new ApplicationException("GIT_URL_INVALID", "Git 地址格式无效", exception);
        }
    }

    private void validateWebsiteUrlForStorage(String websiteUrl) {
        try {
            java.net.URI uri = java.net.URI.create(websiteUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new ApplicationException("WEBSITE_URL_REJECTED",
                        "网站链接必须为不含账号密码和片段的 HTTPS URL");
            }
        } catch (ApplicationException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new ApplicationException("WEBSITE_URL_INVALID", "网站链接格式无效", exception);
        }
    }

    private String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private void rejectUncertainPublicationReplay(Job failed) {
        String code = value(failed.errorCode()).toUpperCase(java.util.Locale.ROOT);
        String message = value(failed.errorMessage()).toUpperCase(java.util.Locale.ROOT);
        if (code.contains("TIMEOUT") || code.contains("INTERRUPT") || code.contains("UNKNOWN")
                || code.contains("INTERNAL") || message.contains("超时") || message.contains("中断")
                || message.contains("结果不确定") || message.contains("TIMEOUT")) {
            throw new ApplicationException("JOB_REPLAY_REQUIRES_MANUAL_PUBLICATION_RETRY",
                    "该发布任务的外部结果不确定，请先到目标平台核对，再使用人工发布重试流程");
        }
    }

    private void validateReplayCandidate(ActorContext actor, Job failed) {
        if (failed.status() != JobStatus.FAILED) {
            throw new ApplicationException("JOB_NOT_REPLAYABLE", "只有失败任务可以重新执行");
        }
        if (failed.type() == JobType.PUBLISH_ARTICLE) {
            rejectUncertainPublicationReplay(failed);
            JobPayload.PublishArticle payload = (JobPayload.PublishArticle) failed.payload();
            publishing.assertPublishable(actor, payload.articleId(), payload.channelAccountId());
        }
    }
}
