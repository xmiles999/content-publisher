package io.contentpublisher.platform.infrastructure.persistence;

import io.contentpublisher.platform.application.DeletedRecord;
import io.contentpublisher.platform.application.PagedResult;
import io.contentpublisher.platform.domain.ArticleSourceType;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleVersion;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public class JpaArticlePersistenceAdapter implements ArticleRepository {
    private final ArticleJpaRepository articles;
    private final ArticleVersionJpaRepository articleVersions;
    private final PublicationJpaRepository publications;
    private final ManualPublicationJpaRepository manualPublications;
    private final JpaDomainMapper mapper;

    public JpaArticlePersistenceAdapter(ArticleJpaRepository articles, ArticleVersionJpaRepository articleVersions,
                                        PublicationJpaRepository publications,
                                        ManualPublicationJpaRepository manualPublications,
                                        JpaDomainMapper mapper) {
        this.articles = articles;
        this.articleVersions = articleVersions;
        this.publications = publications;
        this.manualPublications = manualPublications;
        this.mapper = mapper;
    }

    @Override
    public Article save(Article article) {
        ArticleEntity entity = new ArticleEntity();
        entity.id = article.id(); entity.tenantId = article.tenantId(); entity.projectId = article.projectId();
        entity.sourceType = article.sourceType().name(); entity.sourceTitle = article.origin().title();
        entity.sourceUrl = article.origin().sourceUrl(); entity.sourceDescription = article.origin().description();
        entity.targetAudience = article.origin().audience(); entity.articleType = article.origin().articleType();
        entity.knowledgeLevel = article.origin().knowledgeLevel();
        entity.sourceKeywordsJson = mapper.stringsJson(article.origin().requestedKeywords());
        entity.generationJobId = article.generationJobId(); entity.title = article.title();
        entity.summary = article.summary(); entity.markdown = article.markdown();
        entity.tagsJson = mapper.stringsJson(article.tags()); entity.keywordsJson = mapper.stringsJson(article.keywords());
        entity.titleEn = article.titleEn(); entity.summaryEn = article.summaryEn();
        entity.markdownEn = article.markdownEn(); entity.tagsEnJson = mapper.stringsJson(article.tagsEn());
        entity.keywordsEnJson = mapper.stringsJson(article.keywordsEn()); entity.language = article.language();
        entity.sourceRevision = article.sourceRevision(); entity.currentVersion = article.currentVersion();
        entity.status = article.status().name(); entity.createdBy = article.createdBy();
        entity.updatedBy = article.updatedBy(); entity.createdAt = article.createdAt();
        entity.updatedAt = article.updatedAt();
        return mapper.article(articles.save(entity));
    }

    @Override
    public Article saveWithVersion(Article article, ArticleVersion version) {
        Article saved = save(article);
        ArticleVersionEntity entity = new ArticleVersionEntity();
        entity.id = new ArticleVersionKey(version.articleId(), version.versionNumber());
        entity.tenantId = version.tenantId(); entity.title = version.title(); entity.summary = version.summary();
        entity.markdown = version.markdown(); entity.tagsJson = mapper.stringsJson(version.tags());
        entity.keywordsJson = mapper.stringsJson(version.keywords()); entity.titleEn = version.titleEn();
        entity.summaryEn = version.summaryEn(); entity.markdownEn = version.markdownEn();
        entity.tagsEnJson = mapper.stringsJson(version.tagsEn());
        entity.keywordsEnJson = mapper.stringsJson(version.keywordsEn());
        entity.createdBy = version.createdBy(); entity.createdAt = version.createdAt();
        articleVersions.save(entity);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Article> findArticleById(String tenantId, UUID id) {
        return articles.findByTenantIdAndIdAndDeletedAtIsNull(tenantId, id).map(mapper::article);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Article> findDeletedArticleById(String tenantId, UUID id) {
        return articles.findByTenantIdAndIdAndDeletedAtIsNotNull(tenantId, id).map(mapper::article);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Article> findByGenerationJobId(String tenantId, UUID generationJobId) {
        return articles.findByTenantIdAndGenerationJobIdAndDeletedAtIsNull(tenantId, generationJobId)
                .map(mapper::article);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Article> findDeletedByGenerationJobId(String tenantId, UUID generationJobId) {
        return articles.findByTenantIdAndGenerationJobIdAndDeletedAtIsNotNull(tenantId, generationJobId)
                .map(mapper::article);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Article> findRecentArticles(String tenantId, int limit) {
        return articles.findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(tenantId, PageRequest.of(0, limit))
                .stream().map(mapper::article).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResult<Article> searchArticles(String tenantId, String query, ArticleStatus status,
                                               ArticleSourceType sourceType, String language,
                                               int page, int pageSize) {
        var result = articles.search(tenantId, query == null ? "" : query,
                status == null ? null : status.name(), sourceType == null ? null : sourceType.name(),
                language == null ? "" : language, PageRequest.of(page, pageSize));
        return new PagedResult<>(result.getContent().stream().map(mapper::article).toList(), page, pageSize,
                result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResult<Article> searchPendingPublicationArticles(String tenantId, String query,
                                                                 int page, int pageSize) {
        var result = articles.searchPendingPublication(tenantId, query == null ? "" : query,
                PageRequest.of(page, pageSize));
        return new PagedResult<>(result.getContent().stream().map(mapper::article).toList(), page, pageSize,
                result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public long countArticles(String tenantId) {
        return articles.countByTenantIdAndDeletedAtIsNull(tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Article> findRecentByProjectId(String tenantId, UUID projectId, int limit) {
        return articles.findByTenantIdAndProjectIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                tenantId, projectId, PageRequest.of(0, limit)).stream().map(mapper::article).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArticleVersion> findVersions(String tenantId, UUID articleId) {
        return articleVersions.findVersions(tenantId, articleId).stream().map(entity ->
                new ArticleVersion(entity.tenantId, entity.id.articleId, entity.id.versionNumber,
                        entity.title, entity.summary, entity.markdown, mapper.strings(entity.tagsJson),
                        mapper.strings(entity.keywordsJson), entity.titleEn, entity.summaryEn, entity.markdownEn,
                        mapper.strings(entity.tagsEnJson), mapper.strings(entity.keywordsEnJson),
                        entity.createdBy, entity.createdAt)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeletedRecord> findDeletedArticles(String tenantId, int limit) {
        return articles.findByTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
                        tenantId, PageRequest.of(0, limit)).stream()
                .map(entity -> new DeletedRecord(entity.id, "ARTICLE", entity.title, entity.status,
                        entity.deletedAt, entity.deletedBy)).toList();
    }

    @Override
    public boolean softDeleteArticleRecord(String tenantId, UUID articleId, String subject, Instant deletedAt) {
        ArticleEntity article = articles.findByTenantIdAndIdAndDeletedAtIsNull(tenantId, articleId).orElse(null);
        if (article == null) return false;
        article.deletedAt = deletedAt; article.deletedBy = subject;
        manualPublications.findAllByTenantIdAndArticleId(tenantId, articleId).forEach(entity -> {
            entity.deletedAt = deletedAt; entity.deletedBy = subject;
        });
        publications.findAllByTenantIdAndArticleId(tenantId, articleId).forEach(entity -> {
            entity.deletedAt = deletedAt; entity.deletedBy = subject;
        });
        articles.save(article);
        return true;
    }

    @Override
    public boolean restoreArticleRecord(String tenantId, UUID articleId) {
        ArticleEntity article = articles.findByTenantIdAndIdAndDeletedAtIsNotNull(tenantId, articleId).orElse(null);
        if (article == null) return false;
        article.deletedAt = null; article.deletedBy = null;
        manualPublications.findAllByTenantIdAndArticleId(tenantId, articleId).forEach(entity -> {
            entity.deletedAt = null; entity.deletedBy = null;
        });
        publications.findAllByTenantIdAndArticleId(tenantId, articleId).forEach(entity -> {
            entity.deletedAt = null; entity.deletedBy = null;
        });
        articles.save(article);
        return true;
    }
}
