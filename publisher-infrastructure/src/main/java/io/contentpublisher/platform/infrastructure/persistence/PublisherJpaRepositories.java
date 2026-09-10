package io.contentpublisher.platform.infrastructure.persistence;

import io.contentpublisher.platform.domain.JobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ProjectJpaRepository extends JpaRepository<ProjectEntity, UUID> {
    Optional<ProjectEntity> findByTenantIdAndGitUrl(String tenantId, String gitUrl);
    Optional<ProjectEntity> findByTenantIdAndId(String tenantId, UUID id);
    List<ProjectEntity> findByTenantIdOrderByUpdatedAtDesc(String tenantId, Pageable pageable);
    long countByTenantId(String tenantId);
}

interface ArticleJpaRepository extends JpaRepository<ArticleEntity, UUID> {
    Optional<ArticleEntity> findByTenantIdAndIdAndDeletedAtIsNull(String tenantId, UUID id);
    Optional<ArticleEntity> findByTenantIdAndIdAndDeletedAtIsNotNull(String tenantId, UUID id);
    Optional<ArticleEntity> findByTenantIdAndGenerationJobIdAndDeletedAtIsNull(String tenantId, UUID generationJobId);
    Optional<ArticleEntity> findByTenantIdAndGenerationJobIdAndDeletedAtIsNotNull(String tenantId, UUID generationJobId);
    List<ArticleEntity> findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String tenantId, Pageable pageable);
    List<ArticleEntity> findByTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(String tenantId, Pageable pageable);
    List<ArticleEntity> findByTenantIdAndProjectIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String tenantId,
                                                                                          UUID projectId,
                                                                                          Pageable pageable);
    long countByTenantIdAndDeletedAtIsNull(String tenantId);

    @Query("""
            select a from ArticleEntity a
            where a.tenantId = :tenantId and a.deletedAt is null
              and (:query = '' or lower(a.title) like lower(concat('%', :query, '%'))
                   or lower(a.summary) like lower(concat('%', :query, '%')))
              and (:status is null or a.status = :status)
              and (:sourceType is null or a.sourceType = :sourceType)
              and (:language = '' or lower(a.language) = lower(:language))
            order by a.updatedAt desc
            """)
    Page<ArticleEntity> search(String tenantId, String query, String status, String sourceType, String language,
                               Pageable pageable);
}

interface ArticleVersionJpaRepository extends JpaRepository<ArticleVersionEntity, ArticleVersionKey> {
    @Query("select v from ArticleVersionEntity v where v.tenantId = :tenantId and v.id.articleId = :articleId order by v.id.versionNumber desc")
    List<ArticleVersionEntity> findVersions(String tenantId, UUID articleId);
}

interface SnapshotJpaRepository extends JpaRepository<SnapshotEntity, UUID> {
    Optional<SnapshotEntity> findByTenantIdAndProjectId(String tenantId, UUID projectId);
}

interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {}

interface AiProviderSettingsJpaRepository extends JpaRepository<AiProviderSettingsEntity, String> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AiProviderSettingsEntity s set s.baseUrl = :baseUrl,
                s.encryptedApiKey = :encryptedApiKey, s.apiKeyFingerprint = :apiKeyFingerprint,
                s.model = :model, s.timeoutSeconds = :timeoutSeconds, s.temperature = :temperature,
                s.enabled = :enabled, s.settingsVersion = :nextVersion,
                s.updatedBy = :updatedBy, s.updatedAt = :updatedAt
            where s.tenantId = :tenantId and s.settingsVersion = :expectedVersion
            """)
    int updateIfVersionMatches(String tenantId, String baseUrl, String encryptedApiKey, String apiKeyFingerprint,
                               String model, int timeoutSeconds, BigDecimal temperature, boolean enabled,
                               int expectedVersion, int nextVersion, String updatedBy, Instant updatedAt);
}

interface ChannelAccountJpaRepository extends JpaRepository<ChannelAccountEntity, UUID> {
    Optional<ChannelAccountEntity> findByTenantIdAndIdAndDeletedAtIsNull(String tenantId, UUID id);
    Optional<ChannelAccountEntity> findByTenantIdAndIdempotencyKeyAndDeletedAtIsNull(
            String tenantId, String idempotencyKey);
    List<ChannelAccountEntity> findAllByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(String tenantId);
    List<ChannelAccountEntity> findAllByTenantIdOrderByCreatedAtDesc(String tenantId);
    long countByTenantIdAndDeletedAtIsNull(String tenantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ChannelAccountEntity a set a.displayName = :displayName, a.baseUrl = :baseUrl,
                a.requestHash = :requestHash, a.encryptedCredentials = :encryptedCredentials,
                a.credentialFingerprint = :credentialFingerprint, a.status = :status,
                a.verificationStatus = :verificationStatus, a.verificationMessage = :verificationMessage,
                a.lastVerifiedAt = :lastVerifiedAt,
                a.accountVersion = :nextVersion, a.updatedBy = :updatedBy, a.updatedAt = :updatedAt
            where a.tenantId = :tenantId and a.id = :id and a.deletedAt is null
              and a.accountVersion = :expectedVersion
            """)
    int updateIfVersionMatches(String tenantId, UUID id, String displayName, String baseUrl, String requestHash,
                               String encryptedCredentials,
                               String credentialFingerprint,
                               io.contentpublisher.platform.domain.ChannelAccountStatus status,
                               io.contentpublisher.platform.domain.ChannelVerificationStatus verificationStatus,
                               String verificationMessage, Instant lastVerifiedAt,
                               int expectedVersion, int nextVersion, String updatedBy, Instant updatedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ChannelAccountEntity a set a.verificationStatus = :status,
                a.verificationMessage = :message, a.lastVerifiedAt = :checkedAt
            where a.tenantId = :tenantId and a.id = :id and a.deletedAt is null
            """)
    int updateVerification(String tenantId, UUID id,
                           io.contentpublisher.platform.domain.ChannelVerificationStatus status,
                           String message, Instant checkedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ChannelAccountEntity a set a.status = :status,
                a.encryptedCredentials = 'DELETED', a.credentialFingerprint = 'DELETED',
                a.idempotencyKey = :deletedIdempotencyKey,
                a.verificationStatus = null, a.verificationMessage = null, a.lastVerifiedAt = null,
                a.accountVersion = a.accountVersion + 1, a.updatedBy = :deletedBy,
                a.updatedAt = :deletedAt, a.deletedBy = :deletedBy, a.deletedAt = :deletedAt
            where a.tenantId = :tenantId and a.id = :id and a.deletedAt is null
              and a.accountVersion = :expectedVersion
            """)
    int softDeleteIfVersionMatches(String tenantId, UUID id, int expectedVersion,
                                   String deletedIdempotencyKey,
                                   io.contentpublisher.platform.domain.ChannelAccountStatus status,
                                   String deletedBy, Instant deletedAt);
}

interface ManualChannelProfileJpaRepository extends JpaRepository<ManualChannelProfileEntity, UUID> {
    Optional<ManualChannelProfileEntity> findByTenantIdAndChannelType(
            String tenantId, io.contentpublisher.platform.domain.ChannelType channelType);
    List<ManualChannelProfileEntity> findAllByTenantIdOrderBySortOrderAscChannelTypeAsc(String tenantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ManualChannelProfileEntity p set p.enabled = :enabled,
                p.accountAlias = :accountAlias, p.defaultTagsJson = :defaultTagsJson,
                p.defaultSection = :defaultSection, p.notes = :notes, p.sortOrder = :sortOrder,
                p.loginConfirmedAt = :loginConfirmedAt, p.profileVersion = :nextVersion,
                p.updatedBy = :updatedBy, p.updatedAt = :updatedAt
            where p.tenantId = :tenantId and p.channelType = :channelType
              and p.profileVersion = :expectedVersion
            """)
    int updateIfVersionMatches(String tenantId, io.contentpublisher.platform.domain.ChannelType channelType,
                               boolean enabled, String accountAlias, String defaultTagsJson,
                               String defaultSection, String notes, int sortOrder, Instant loginConfirmedAt,
                               int expectedVersion, int nextVersion, String updatedBy, Instant updatedAt);
}

interface PublicationJpaRepository extends JpaRepository<PublicationEntity, UUID> {
    Optional<PublicationEntity> findByTenantIdAndIdAndDeletedAtIsNull(String tenantId, UUID id);
    Optional<PublicationEntity> findByTenantIdAndPublicationJobIdAndDeletedAtIsNull(String tenantId,
                                                                                     UUID publicationJobId);
    List<PublicationEntity> findByTenantIdAndArticleIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String tenantId,
                                                                                              UUID articleId);
    List<PublicationEntity> findByTenantIdAndArticleIdInAndDeletedAtIsNullOrderByUpdatedAtDesc(String tenantId,
                                                                                                List<UUID> articleIds);
    List<PublicationEntity> findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String tenantId, Pageable pageable);
    List<PublicationEntity> findAllByTenantIdAndArticleId(String tenantId, UUID articleId);
    Optional<PublicationEntity> findByTenantIdAndPublicationJobId(String tenantId, UUID publicationJobId);

    @Query("""
            select p from PublicationEntity p
            join ArticleEntity a on a.id = p.articleId and a.tenantId = p.tenantId and a.deletedAt is null
            join ChannelAccountEntity c on c.id = p.channelAccountId and c.tenantId = p.tenantId
            where p.tenantId = :tenantId and p.deletedAt is null
              and (:query = '' or lower(a.title) like lower(concat('%', :query, '%'))
                   or lower(a.summary) like lower(concat('%', :query, '%'))
                   or lower(c.displayName) like lower(concat('%', :query, '%'))
                   or lower(p.errorCode) like lower(concat('%', :query, '%'))
                   or lower(p.errorMessage) like lower(concat('%', :query, '%')))
              and (:channelType is null or p.channelType = :channelType)
              and (:status is null or p.status = :status)
            order by p.updatedAt desc, p.id asc
            """)
    Page<PublicationEntity> search(String tenantId, String query,
                                   io.contentpublisher.platform.domain.ChannelType channelType,
                                   io.contentpublisher.platform.domain.PublicationStatus status,
                                   Pageable pageable);
}

interface ManualPublicationJpaRepository extends JpaRepository<ManualPublicationEntity, UUID> {
    List<ManualPublicationEntity> findByTenantIdAndArticleIdAndDeletedAtIsNullOrderByPublishedAtDesc(String tenantId,
                                                                                                      UUID articleId);
    List<ManualPublicationEntity> findByTenantIdAndArticleIdInAndDeletedAtIsNullOrderByPublishedAtDesc(
            String tenantId, List<UUID> articleIds);
    List<ManualPublicationEntity> findByTenantIdAndDeletedAtIsNullOrderByPublishedAtDesc(String tenantId,
                                                                                          Pageable pageable);
    List<ManualPublicationEntity> findAllByTenantIdAndArticleId(String tenantId, UUID articleId);

    @Query("""
            select p from ManualPublicationEntity p
            join ArticleEntity a on a.id = p.articleId and a.tenantId = p.tenantId and a.deletedAt is null
            where p.tenantId = :tenantId and p.deletedAt is null
              and (:query = '' or lower(a.title) like lower(concat('%', :query, '%'))
                   or lower(a.summary) like lower(concat('%', :query, '%'))
                   or lower(p.adaptedTitle) like lower(concat('%', :query, '%'))
                   or lower(p.publishedBy) like lower(concat('%', :query, '%')))
              and (:channelType is null or p.channelType = :channelType)
            order by p.publishedAt desc, p.id asc
            """)
    Page<ManualPublicationEntity> search(String tenantId, String query,
                                         io.contentpublisher.platform.domain.ChannelType channelType,
                                         Pageable pageable);
}

interface JobJpaRepository extends JpaRepository<JobEntity, UUID> {
    Optional<JobEntity> findByTenantIdAndIdAndDeletedAtIsNull(String tenantId, UUID id);
    Optional<JobEntity> findByTenantIdAndIdAndDeletedAtIsNotNull(String tenantId, UUID id);
    Optional<JobEntity> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);
    List<JobEntity> findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String tenantId, Pageable pageable);
    List<JobEntity> findByTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(String tenantId, Pageable pageable);
    List<JobEntity> findAllByTenantIdAndDeletedAtIsNull(String tenantId);
    List<JobEntity> findAllByTenantIdAndDeletedAtIsNotNull(String tenantId);
    long countByTenantIdAndDeletedAtIsNullAndStatusIn(String tenantId, List<JobStatus> statuses);

    @Query("""
            select j from JobEntity j
            where j.tenantId = :tenantId and j.deletedAt is null
              and (:query = '' or lower(j.errorMessage) like lower(concat('%', :query, '%'))
                   or lower(j.progressLabel) like lower(concat('%', :query, '%'))
                   or lower(j.progressDetail) like lower(concat('%', :query, '%')))
              and (:type is null or j.type = :type)
              and (:status is null or j.status = :status)
            order by j.updatedAt desc
            """)
    Page<JobEntity> search(String tenantId, String query, io.contentpublisher.platform.domain.JobType type,
                           JobStatus status, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update JobEntity j set j.status = :failedStatus, j.errorCode = :errorCode,
                j.errorMessage = :errorMessage, j.lockOwner = null, j.lockedAt = null, j.updatedAt = :now
            where j.deletedAt is null and j.status = :runningStatus
                and j.lockedAt < :staleBefore and j.attempt >= j.maxAttempts
            """)
    int failExpiredExhaustedLeases(JobStatus runningStatus, JobStatus failedStatus,
                                   Instant staleBefore, Instant now, String errorCode, String errorMessage);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update JobEntity j set j.status = :cancelledStatus, j.progressPercent = 100,
                j.progressLabel = :progressLabel, j.progressDetail = :progressDetail,
                j.errorCode = null, j.errorMessage = null, j.updatedAt = :now
            where j.tenantId = :tenantId and j.id = :jobId and j.deletedAt is null
              and j.lockOwner is null and j.status in :cancellableStatuses
            """)
    int cancelPending(String tenantId, UUID jobId, List<JobStatus> cancellableStatuses,
                      JobStatus cancelledStatus, String progressLabel, String progressDetail, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select j from JobEntity j
            where j.deletedAt is null and j.attempt < j.maxAttempts and (
                (j.status in :readyStatuses and j.scheduledAt <= :now)
                or (j.status = :runningStatus and j.lockedAt < :staleBefore)
            )
            order by j.scheduledAt asc, j.createdAt asc
            """)
    List<JobEntity> findClaimable(List<JobStatus> readyStatuses, JobStatus runningStatus,
                                  Instant now, Instant staleBefore, Pageable pageable);
}

interface ArticleAssetJpaRepository extends JpaRepository<ArticleAssetEntity, UUID> {
    Optional<ArticleAssetEntity> findByTenantIdAndArticleIdAndId(String tenantId, UUID articleId, UUID id);

    List<ArticleAssetEntity> findByTenantIdAndArticleIdOrderByCreatedAtDesc(String tenantId, UUID articleId);

    long countByTenantIdAndArticleId(String tenantId, UUID articleId);
}
