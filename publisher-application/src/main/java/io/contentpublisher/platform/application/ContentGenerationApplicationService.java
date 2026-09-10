package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.application.port.ContentGenerator;
import io.contentpublisher.platform.application.port.ProjectRepository;
import io.contentpublisher.platform.application.port.RepositorySnapshotStore;
import io.contentpublisher.platform.application.port.WebsiteInspector;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.domain.ArticleVersion;
import io.contentpublisher.platform.domain.ContentOrigin;
import io.contentpublisher.platform.domain.GenerationPolicy;
import io.contentpublisher.platform.domain.Project;
import io.contentpublisher.platform.domain.ProjectStatus;
import io.contentpublisher.platform.domain.RepositorySnapshot;
import io.contentpublisher.platform.domain.TopicBrief;
import io.contentpublisher.platform.domain.WebsiteBrief;
import io.contentpublisher.platform.domain.WebsiteSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ContentGenerationApplicationService {
    private final ProjectRepository projects;
    private final ArticleRepository articles;
    private final RepositorySnapshotStore snapshots;
    private final WebsiteInspector websiteInspector;
    private final ContentGenerator contentGenerator;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    public ContentGenerationApplicationService(ProjectRepository projects, ArticleRepository articles,
                                               RepositorySnapshotStore snapshots, WebsiteInspector websiteInspector,
                                               ContentGenerator contentGenerator, AuditRecorder auditRecorder,
                                               Clock clock) {
        this.projects = projects;
        this.articles = articles;
        this.snapshots = snapshots;
        this.websiteInspector = websiteInspector;
        this.contentGenerator = contentGenerator;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    public Article generateArticle(ActorContext actor, UUID projectId, GenerationPolicy policy) {
        return generateArticle(actor, projectId, policy, null);
    }

    public Article generateArticle(ActorContext actor, UUID projectId, GenerationPolicy policy, UUID generationJobId) {
        return generateArticle(actor, projectId, policy, generationJobId, JobProgressReporter.noop());
    }

    public Article generateArticle(ActorContext actor, UUID projectId, GenerationPolicy policy, UUID generationJobId,
                                   JobProgressReporter progress) {
        progress.update(20, "读取项目事实", "正在读取项目、仓库快照和文章生成约束");
        Article existing = existingResult(actor, generationJobId, progress);
        if (existing != null) return existing;
        Project project = getProject(actor, projectId);
        if (project.status() != ProjectStatus.READY) {
            throw new ApplicationException("PROJECT_NOT_READY", "项目尚未完成仓库分析");
        }
        RepositorySnapshot snapshot = snapshots.findByProjectId(actor.tenantId(), projectId)
                .orElseThrow(() -> new ApplicationException("SNAPSHOT_NOT_FOUND", "项目仓库快照不存在，请重新导入"));
        progress.update(38, "生成并优化文章", "正在依据仓库事实分析搜索意图，生成 SEO 标题、摘要、正文、标签和长尾关键词");
        ContentGenerator.GeneratedContent generated = contentGenerator.generate(actor.tenantId(), snapshot, policy);
        progress.update(76, "SEO 质量检查完成", "已检查主关键词布局、标题摘要、章节层级、长尾词和内容可读性");
        progress.update(84, "整理文章结果", "SEO 优化完成，正在补充来源链接并保存文章版本");
        String markdown = ensureSourceLink(generated.markdown(), project.gitUrl(), "推荐地址", "项目仓库");
        String markdownEn = ensureSourceLink(generated.markdownEn(), project.gitUrl(), "Recommended Link", "Project Repository");
        Article saved = saveGenerated(actor, ContentOrigin.git(projectId), generationJobId, generated, policy.language(),
                snapshot.revision(), markdown, markdownEn);
        auditRecorder.record(actor, "ARTICLE_GENERATED", "ARTICLE", saved.id(),
                Map.of("projectId", projectId.toString(), "sourceRevision", snapshot.revision(),
                        "language", policy.language()));
        progress.update(94, "文章已经保存", "文章正文、标签、关键词和版本记录已完成落库");
        return saved;
    }

    public Article generateTopicArticle(ActorContext actor, TopicBrief brief, GenerationPolicy policy,
                                        UUID generationJobId) {
        return generateTopicArticle(actor, brief, policy, generationJobId, JobProgressReporter.noop());
    }

    public Article generateTopicArticle(ActorContext actor, TopicBrief brief, GenerationPolicy policy,
                                        UUID generationJobId, JobProgressReporter progress) {
        progress.update(22, "整理创作简报", "正在校验主题、受众、文章类型和内容约束");
        Article existing = existingResult(actor, generationJobId, progress);
        if (existing != null) return existing;
        progress.update(38, "生成并优化主题文章", "正在分析搜索意图并生成 SEO 标题、教程结构、FAQ 和长尾关键词");
        ContentGenerator.GeneratedContent generated = contentGenerator.generateFromBrief(actor.tenantId(), brief, policy);
        progress.update(76, "SEO 质量检查完成", "已检查主关键词布局、标题摘要、章节层级、FAQ 和内容可读性");
        progress.update(84, "保存主题文章", "SEO 优化完成，正在保存文章主稿和首个版本");
        Article saved = saveGenerated(actor, ContentOrigin.topic(brief), generationJobId, generated, policy.language(),
                sha256(brief.toString()), generated.markdown(), generated.markdownEn());
        auditRecorder.record(actor, "TOPIC_ARTICLE_GENERATED", "ARTICLE", saved.id(),
                Map.of("topic", brief.topic(), "articleType", brief.articleType(),
                        "knowledgeLevel", brief.knowledgeLevel(), "language", policy.language()));
        progress.update(94, "文章已经保存", "主题文章及其标签、关键词和版本记录已完成落库");
        return saved;
    }

    public Article generateWebsiteArticle(ActorContext actor, WebsiteBrief brief, GenerationPolicy policy,
                                          UUID generationJobId) {
        return generateWebsiteArticle(actor, brief, policy, generationJobId, JobProgressReporter.noop());
    }

    public Article generateWebsiteArticle(ActorContext actor, WebsiteBrief brief, GenerationPolicy policy,
                                          UUID generationJobId, JobProgressReporter progress) {
        progress.update(20, "准备网站分析", "正在校验网站地址和文章生成约束");
        Article existing = existingResult(actor, generationJobId, progress);
        if (existing != null) return existing;
        progress.update(32, "提取网站公开信息", "正在读取网页正文、标题和公开描述信息");
        WebsiteSnapshot snapshot = websiteInspector.inspect(brief.websiteUrl());
        progress.update(52, "生成并优化网站文章", "网站信息提取完成，正在生成搜索友好的标题、摘要、推荐正文和长尾关键词");
        ContentGenerator.GeneratedContent generated = contentGenerator.generateFromWebsite(
                actor.tenantId(), brief, snapshot, policy);
        progress.update(78, "SEO 质量检查完成", "已检查搜索意图、主关键词、章节层级、FAQ、来源链接和内容可信度");
        progress.update(86, "保存网站文章", "SEO 优化完成，正在补充官方网站链接并保存版本");
        String markdown = ensureSourceLink(generated.markdown(), snapshot.url(), "推荐网站", "官方网站");
        String markdownEn = ensureSourceLink(generated.markdownEn(), snapshot.url(), "Recommended Website", "Official Website");
        String revision = websiteRevision(snapshot);
        Article saved = saveGenerated(actor, ContentOrigin.website(brief, snapshot), generationJobId, generated,
                policy.language(), revision, markdown, markdownEn);
        auditRecorder.record(actor, "WEBSITE_ARTICLE_GENERATED", "ARTICLE", saved.id(),
                Map.of("websiteHost", java.net.URI.create(snapshot.url()).getHost(), "language", policy.language()));
        progress.update(94, "文章已经保存", "网站文章、来源信息和版本记录已完成落库");
        return saved;
    }

    public ContentGenerator.EnglishTranslation translateToEnglish(ActorContext actor, UUID articleId,
                                                                  String title, String summary, String markdown,
                                                                  List<String> tags, List<String> keywords) {
        Article article = articles.findArticleById(actor.tenantId(), articleId)
                .orElseThrow(() -> new ApplicationException("ARTICLE_NOT_FOUND", "文章不存在"));
        String zhTitle = firstNonBlank(title, article.title());
        String zhSummary = firstNonBlank(summary, article.summary());
        String zhMarkdown = firstNonBlank(markdown, article.markdown());
        List<String> zhTags = tags == null || tags.isEmpty() ? article.tags() : tags;
        List<String> zhKeywords = keywords == null || keywords.isEmpty() ? article.keywords() : keywords;
        ContentGenerator.EnglishTranslation translated = contentGenerator.translateToEnglish(
                actor.tenantId(), zhTitle, zhSummary, zhMarkdown, zhTags, zhKeywords);
        auditRecorder.record(actor, "ARTICLE_ENGLISH_TRANSLATED", "ARTICLE", articleId, Map.of());
        return translated;
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Article existingResult(ActorContext actor, UUID generationJobId, JobProgressReporter progress) {
        if (generationJobId == null) return null;
        Article existing = articles.findByGenerationJobId(actor.tenantId(), generationJobId).orElse(null);
        if (existing != null) progress.update(92, "复用生成结果", "检测到任务已有文章结果，正在完成状态同步");
        return existing;
    }

    private Article saveGenerated(ActorContext actor, ContentOrigin origin, UUID generationJobId,
                                  ContentGenerator.GeneratedContent generated, String language,
                                  String sourceRevision, String markdown, String markdownEn) {
        Instant now = clock.instant();
        Article article = new Article(UUID.randomUUID(), actor.tenantId(), origin, generationJobId,
                generated.title(), generated.summary(), markdown, generated.tags(), generated.keywords(),
                generated.titleEn(), generated.summaryEn(), markdownEn, generated.tagsEn(), generated.keywordsEn(),
                language, sourceRevision, 1, ArticleStatus.DRAFT, actor.subject(), actor.subject(), now, now);
        return articles.saveWithVersion(article, new ArticleVersion(actor.tenantId(), article.id(), 1,
                article.title(), article.summary(), article.markdown(), article.tags(), article.keywords(),
                article.titleEn(), article.summaryEn(), article.markdownEn(), article.tagsEn(), article.keywordsEn(),
                actor.subject(), now));
    }

    private Project getProject(ActorContext actor, UUID projectId) {
        return projects.findProjectById(actor.tenantId(), projectId)
                .orElseThrow(() -> new ApplicationException("PROJECT_NOT_FOUND", "项目不存在"));
    }

    private String ensureSourceLink(String markdown, String sourceUrl, String heading, String label) {
        if (markdown.contains(sourceUrl)) return markdown;
        return markdown.stripTrailing() + "\n\n## " + heading + "\n\n" + label + "：<" + sourceUrl + ">";
    }

    private String websiteRevision(WebsiteSnapshot snapshot) {
        return sha256(snapshot.url() + '\u001f' + snapshot.visibleText());
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
