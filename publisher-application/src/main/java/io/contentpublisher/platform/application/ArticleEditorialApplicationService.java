package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.domain.ArticleVersion;
import io.contentpublisher.platform.domain.ContentOrigin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ArticleEditorialApplicationService {
    private final ArticleRepository articles;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    public ArticleEditorialApplicationService(ArticleRepository articles, AuditRecorder auditRecorder, Clock clock) {
        this.articles = articles;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    public Article getArticle(ActorContext actor, UUID articleId) {
        return articles.findArticleById(actor.tenantId(), articleId)
                .orElseThrow(() -> new ApplicationException("ARTICLE_NOT_FOUND", "文章不存在"));
    }

    public List<ArticleVersion> getArticleVersions(ActorContext actor, UUID articleId) {
        getArticle(actor, articleId);
        return articles.findVersions(actor.tenantId(), articleId);
    }

    public Article createCustomArticle(ActorContext actor, String title, String summary, String markdown,
                                       List<String> tags, List<String> keywords,
                                       String titleEn, String summaryEn, String markdownEn,
                                       List<String> tagsEn, List<String> keywordsEn,
                                       String language) {
        String normalizedTitle = requireText(title, "文章标题不能为空", 500);
        String normalizedMarkdown = requireText(markdown, "文章正文不能为空", 20000);
        String normalizedSummary = summary == null || summary.isBlank()
                ? deriveSummary(normalizedMarkdown, 200)
                : requireText(summary, "文章摘要不能为空", 2000);
        List<String> normalizedTags = normalizeTags(tags);
        List<String> normalizedKeywords = normalizeKeywords(keywords);
        if (normalizedKeywords.isEmpty() && !normalizedTags.isEmpty()) {
            normalizedKeywords = normalizedTags.stream().limit(30).toList();
        }
        String normalizedTitleEn = normalizeOptionalText(titleEn, "英文标题不能超过 500 个字符", 500);
        String normalizedSummaryEn = normalizeOptionalText(summaryEn, "英文摘要不能超过 2000 个字符", 2000);
        String normalizedMarkdownEn = normalizeOptionalText(markdownEn, "英文正文不能超过 20000 个字符", 20000);
        List<String> normalizedTagsEn = normalizeTags(tagsEn);
        List<String> normalizedKeywordsEn = normalizeKeywords(keywordsEn);
        String normalizedLanguage = language == null || language.isBlank() ? "zh-CN" : language.trim();
        if (normalizedLanguage.length() > 20) {
            throw new ApplicationException("INVALID_ARGUMENT", "语言代码不能超过 20 个字符");
        }

        Instant now = clock.instant();
        UUID articleId = UUID.randomUUID();
        String sourceRevision = sha256(normalizedTitle + "\n" + normalizedMarkdown);
        ContentOrigin origin = ContentOrigin.custom(normalizedTitle, normalizedSummary, normalizedKeywords);
        Article article = new Article(articleId, actor.tenantId(), origin, null,
                normalizedTitle, normalizedSummary, normalizedMarkdown, normalizedTags, normalizedKeywords,
                normalizedTitleEn, normalizedSummaryEn, normalizedMarkdownEn, normalizedTagsEn, normalizedKeywordsEn,
                normalizedLanguage, sourceRevision, 1, ArticleStatus.DRAFT, actor.subject(), actor.subject(),
                now, now);
        ArticleVersion version = new ArticleVersion(actor.tenantId(), articleId, 1,
                normalizedTitle, normalizedSummary, normalizedMarkdown, normalizedTags, normalizedKeywords,
                normalizedTitleEn, normalizedSummaryEn, normalizedMarkdownEn, normalizedTagsEn, normalizedKeywordsEn,
                actor.subject(), now);
        Article saved = articles.saveWithVersion(article, version);
        auditRecorder.record(actor, "CUSTOM_ARTICLE_CREATED", "ARTICLE", articleId,
                Map.of("title", normalizedTitle, "language", normalizedLanguage));
        return saved;
    }

    public Article createCustomArticle(ActorContext actor, String title, String summary, String markdown,
                                       List<String> tags, List<String> keywords) {
        return createCustomArticle(actor, title, summary, markdown, tags, keywords,
                "", "", "", List.of(), List.of(), "zh-CN");
    }

    public Article updateArticle(ActorContext actor, UUID articleId, int expectedVersion, String title,
                                 String summary, String markdown, List<String> tags, List<String> keywords,
                                 String titleEn, String summaryEn, String markdownEn, List<String> tagsEn,
                                 List<String> keywordsEn) {
        Article article = getArticle(actor, articleId);
        if (!article.status().isEditable()) {
            throw new ApplicationException("ARTICLE_STATE_CONFLICT", "已确认或已发布文章不能直接修改，请先重新进入编辑");
        }
        if (article.currentVersion() != expectedVersion) {
            throw new ApplicationException("ARTICLE_VERSION_CONFLICT", "文章已被其他请求修改，请刷新后重试");
        }
        String normalizedTitle = requireText(title, "文章标题不能为空", 500);
        String normalizedSummary = requireText(summary, "文章摘要不能为空", 2000);
        String normalizedMarkdown = requireText(markdown, "文章正文不能为空", 20000);
        List<String> normalizedTags = normalizeTags(tags);
        List<String> normalizedKeywords = normalizeKeywords(keywords);
        String normalizedTitleEn = normalizeOptionalText(titleEn, "英文标题不能超过 500 个字符", 500);
        String normalizedSummaryEn = normalizeOptionalText(summaryEn, "英文摘要不能超过 2000 个字符", 2000);
        String normalizedMarkdownEn = normalizeOptionalText(markdownEn, "英文正文不能超过 20000 个字符", 20000);
        List<String> normalizedTagsEn = normalizeTags(tagsEn);
        List<String> normalizedKeywordsEn = normalizeKeywords(keywordsEn);
        int nextVersion = expectedVersion + 1;
        Instant now = clock.instant();
        Article updated = new Article(article.id(), article.tenantId(), article.origin(), article.generationJobId(),
                normalizedTitle, normalizedSummary, normalizedMarkdown, normalizedTags, normalizedKeywords,
                normalizedTitleEn, normalizedSummaryEn, normalizedMarkdownEn, normalizedTagsEn, normalizedKeywordsEn,
                article.language(), article.sourceRevision(), nextVersion, ArticleStatus.DRAFT, article.createdBy(),
                actor.subject(), article.createdAt(), now);
        Article saved = articles.saveWithVersion(updated, new ArticleVersion(actor.tenantId(), articleId, nextVersion,
                normalizedTitle, normalizedSummary, normalizedMarkdown, normalizedTags, normalizedKeywords,
                normalizedTitleEn, normalizedSummaryEn, normalizedMarkdownEn, normalizedTagsEn, normalizedKeywordsEn,
                actor.subject(), now));
        auditRecorder.record(actor, "ARTICLE_VERSION_CREATED", "ARTICLE", articleId,
                Map.of("version", Integer.toString(nextVersion)));
        return saved;
    }

    public Article updateArticle(ActorContext actor, UUID articleId, int expectedVersion, String title,
                                 String summary, String markdown, List<String> tags, List<String> keywords) {
        Article existing = getArticle(actor, articleId);
        return updateArticle(actor, articleId, expectedVersion, title, summary, markdown, tags, keywords,
                existing.titleEn(), existing.summaryEn(), existing.markdownEn(), existing.tagsEn(), existing.keywordsEn());
    }

    public Article updateArticle(ActorContext actor, UUID articleId, int expectedVersion, String title,
                                 String summary, String markdown, List<String> keywords) {
        return updateArticle(actor, articleId, expectedVersion, title, summary, markdown, keywords, keywords);
    }

    public Article approveArticle(ActorContext actor, UUID articleId) {
        Article article = getArticle(actor, articleId);
        if (article.status().isConfirmedBaseline()) return article;
        if (article.status() != ArticleStatus.DRAFT && article.status() != ArticleStatus.REJECTED) {
            throw new ApplicationException("ARTICLE_STATE_CONFLICT", "只有草稿或已驳回文章可以审核通过");
        }
        Article saved = updateArticleStatus(article, ArticleStatus.APPROVED, actor.subject());
        auditRecorder.record(actor, "ARTICLE_APPROVED", "ARTICLE", articleId, Map.of());
        return saved;
    }

    public Article confirmArticle(ActorContext actor, UUID articleId) {
        Article article = getArticle(actor, articleId);
        if (article.status().isConfirmedBaseline()) return article;
        if (!article.status().isEditable()) {
            throw new ApplicationException("ARTICLE_STATE_CONFLICT", "只有可编辑内容可以确认");
        }
        Article saved = updateArticleStatus(article, ArticleStatus.READY, actor.subject());
        auditRecorder.record(actor, "ARTICLE_CONTENT_CONFIRMED", "ARTICLE", articleId, Map.of());
        return saved;
    }

    public Article reopenArticle(ActorContext actor, UUID articleId) {
        Article article = getArticle(actor, articleId);
        if (article.status().isEditable()) return article;
        if (article.status() == ArticleStatus.PUBLISHED) {
            throw new ApplicationException("ARTICLE_STATE_CONFLICT", "已发布文章不能直接重新编辑");
        }
        if (article.status() != ArticleStatus.READY && article.status() != ArticleStatus.APPROVED) {
            throw new ApplicationException("ARTICLE_STATE_CONFLICT", "当前内容状态不能重新进入编辑");
        }
        Article saved = updateArticleStatus(article, ArticleStatus.DRAFT, actor.subject());
        auditRecorder.record(actor, "ARTICLE_EDITING_REOPENED", "ARTICLE", articleId, Map.of());
        return saved;
    }

    public Article rejectArticle(ActorContext actor, UUID articleId, String reason) {
        Article article = getArticle(actor, articleId);
        if (article.status() == ArticleStatus.REJECTED) return article;
        if (article.status() == ArticleStatus.PUBLISHED) {
            throw new ApplicationException("ARTICLE_STATE_CONFLICT", "已发布文章不能驳回");
        }
        String normalizedReason = requireText(reason, "驳回原因不能为空", 500);
        Article saved = updateArticleStatus(article, ArticleStatus.REJECTED, actor.subject());
        auditRecorder.record(actor, "ARTICLE_REJECTED", "ARTICLE", articleId,
                Map.of("reason", normalizedReason));
        return saved;
    }

    public Article restoreVersion(ActorContext actor, UUID articleId, int expectedVersion, int sourceVersion) {
        ArticleVersion version = getArticleVersions(actor, articleId).stream()
                .filter(item -> item.versionNumber() == sourceVersion)
                .findFirst()
                .orElseThrow(() -> new ApplicationException("ARTICLE_VERSION_NOT_FOUND", "文章版本不存在"));
        Article restored = updateArticle(actor, articleId, expectedVersion, version.title(), version.summary(),
                version.markdown(), version.tags(), version.keywords(), version.titleEn(), version.summaryEn(),
                version.markdownEn(), version.tagsEn(), version.keywordsEn());
        auditRecorder.record(actor, "ARTICLE_VERSION_RESTORED", "ARTICLE", articleId,
                Map.of("sourceVersion", Integer.toString(sourceVersion),
                        "newVersion", Integer.toString(restored.currentVersion())));
        return restored;
    }

    private Article updateArticleStatus(Article article, ArticleStatus status, String subject) {
        return articles.save(new Article(article.id(), article.tenantId(), article.origin(), article.generationJobId(),
                article.title(), article.summary(), article.markdown(), article.tags(), article.keywords(),
                article.titleEn(), article.summaryEn(), article.markdownEn(), article.tagsEn(), article.keywordsEn(),
                article.language(), article.sourceRevision(), article.currentVersion(), status, article.createdBy(), subject,
                article.createdAt(), clock.instant()));
    }

    private String requireText(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) throw new ApplicationException("INVALID_ARGUMENT", message);
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new ApplicationException("INVALID_ARGUMENT", "字段长度超过限制");
        return normalized;
    }

    private String normalizeOptionalText(String value, String message, int maxLength) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) throw new ApplicationException("INVALID_ARGUMENT", message);
        return trimmed;
    }

    private List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null) return List.of();
        List<String> normalized = keywords.stream().filter(java.util.Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().toList();
        if (normalized.size() > 30 || normalized.stream().anyMatch(value -> value.length() > 100)) {
            throw new ApplicationException("INVALID_ARGUMENT", "关键词最多 30 个且每个不超过 100 字符");
        }
        return normalized;
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) return List.of();
        List<String> normalized = tags.stream().filter(java.util.Objects::nonNull).map(String::trim)
                .map(value -> value.replaceFirst("^#+", "")).filter(value -> !value.isEmpty()).distinct().toList();
        if (normalized.size() > 15 || normalized.stream().anyMatch(value -> value.length() > 50)) {
            throw new ApplicationException("INVALID_ARGUMENT", "标签最多 15 个且每个不超过 50 字符");
        }
        return normalized;
    }

    private String deriveSummary(String markdown, int maxLength) {
        String cleaned = markdown
                .replaceAll("(?m)^#+\\s*", "")
                .replaceAll("\\[([^\\]]+)\\]\\([^\\)]+\\)", "$1")
                .replaceAll("[`*_>~]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank()) {
            return "自定义文章笔记";
        }
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, maxLength).stripTrailing();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }
}
