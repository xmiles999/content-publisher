package io.contentpublisher.platform.application.port;

import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleVersion;
import io.contentpublisher.platform.application.DeletedRecord;
import io.contentpublisher.platform.application.PagedResult;
import io.contentpublisher.platform.domain.ArticleSourceType;
import io.contentpublisher.platform.domain.ArticleStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArticleRepository {
    Article save(Article article);
    Article saveWithVersion(Article article, ArticleVersion version);
    Optional<Article> findArticleById(String tenantId, UUID id);
    Optional<Article> findDeletedArticleById(String tenantId, UUID id);
    Optional<Article> findByGenerationJobId(String tenantId, UUID generationJobId);
    Optional<Article> findDeletedByGenerationJobId(String tenantId, UUID generationJobId);
    List<Article> findRecentArticles(String tenantId, int limit);
    PagedResult<Article> searchArticles(String tenantId, String query, ArticleStatus status,
                                        ArticleSourceType sourceType, String language, int page, int pageSize);
    PagedResult<Article> searchPendingPublicationArticles(String tenantId, String query, int page, int pageSize);
    long countArticles(String tenantId);
    List<Article> findRecentByProjectId(String tenantId, UUID projectId, int limit);
    List<ArticleVersion> findVersions(String tenantId, UUID articleId);
    List<DeletedRecord> findDeletedArticles(String tenantId, int limit);
    boolean softDeleteArticleRecord(String tenantId, UUID articleId, String subject, Instant deletedAt);
    boolean restoreArticleRecord(String tenantId, UUID articleId);
}
