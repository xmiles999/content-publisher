package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.ChannelCatalog.ChannelRegion;
import io.contentpublisher.platform.domain.AdaptedContent;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.ContentFormat;

import java.util.List;
import java.util.Locale;

public final class PlatformContentAdapter {
    private final ChannelCopyOptimizationService optimizer;

    public PlatformContentAdapter() { this(new ChannelCopyOptimizationService(null)); }

    public PlatformContentAdapter(ChannelCopyOptimizationService optimizer) { this.optimizer = optimizer; }
    public AdaptedContent adapt(Article article, ChannelType channelType, String canonicalUrl) {
        return adapt(article, channelType, canonicalUrl, true, "original");
    }

    public AdaptedContent adaptForManual(Article article, ChannelType channelType, String canonicalUrl) {
        return adaptForManual(article, channelType, canonicalUrl, "original");
    }

    public AdaptedContent adaptForManual(Article article, ChannelType channelType, String canonicalUrl,
                                         String language) {
        return adapt(article, channelType, canonicalUrl, false, language);
    }

    private AdaptedContent adapt(Article article, ChannelType channelType, String canonicalUrl,
                                 boolean enforcePlatformLimits, String language) {
        ChannelCatalog.ChannelDefinition definition = ChannelCatalog.definition(channelType);
        Localized content = localize(article, language);
        ChannelRegion languageRegion = "en".equals(language) || article.language().startsWith("en")
                ? ChannelRegion.OVERSEAS : ChannelRegion.DOMESTIC;
        List<String> sourceTags = content.tags().isEmpty() ? content.keywords() : content.tags();
        List<String> tags = sourceTags.stream().map(this::normalizeTag)
                .filter(value -> !value.isBlank()).distinct().limit(tagLimit(channelType)).toList();
        String body = switch (definition.contentFormat()) {
            case MARKDOWN -> markdown(content, channelType, canonicalUrl, languageRegion);
            case PLAIN_TEXT -> plainText(content, channelType, canonicalUrl, tags, languageRegion);
            case SHORT_TEXT -> shortText(content, channelType, canonicalUrl, tags);
        };
        AdaptedContent result = new AdaptedContent(channelType, definition.contentFormat(), content.title(), body, tags,
                definition.characterLimit());
        if (!enforcePlatformLimits) return result;
        AdaptedContent optimized = optimizer.optimize(article.tenantId(), result);
        if (canonicalUrl != null && body.contains(canonicalUrl) && !optimized.body().contains(canonicalUrl)) {
            throw new ApplicationException("CHANNEL_COPY_INVALID", "优化结果丢失原文链接，请调整内容或改用人工发布");
        }
        return optimized;
    }

    private Localized localize(Article article, String language) {
        if (!"original".equals(language) && !"en".equals(language)) {
            throw new ApplicationException("CONTENT_LANGUAGE_INVALID", "请选择主稿或英文稿");
        }
        if ("en".equals(language)) {
            if (!article.hasEnglishContent()) {
                throw new ApplicationException("ARTICLE_TRANSLATION_MISSING",
                        "文章暂无完整英文版本，请先补充英文稿或选择主稿");
            }
            return new Localized(article.titleEn(), article.summaryEn(), article.markdownEn(),
                    article.tagsEn(), article.keywordsEn());
        }
        return new Localized(article.title(), article.summary(), article.markdown(), article.tags(), article.keywords());
    }

    private String markdown(Localized content, ChannelType channelType, String canonicalUrl, ChannelRegion region) {
        String markdown = content.markdown();
        if (channelType == ChannelType.V2EX) {
            markdown = "## " + content.title() + "\n\n" + content.summary() + "\n\n" + markdown;
        }
        if (channelType == ChannelType.DEV || channelType == ChannelType.HASHNODE
                || channelType == ChannelType.MEDIUM || channelType == ChannelType.GHOST) {
            return markdown;
        }
        return appendLink(markdown, canonicalUrl, region == ChannelRegion.OVERSEAS ? "Source: " : "原文链接：");
    }

    private String plainText(Localized content, ChannelType channelType, String canonicalUrl, List<String> tags,
                             ChannelRegion region) {
        String plain = stripMarkdown(content.markdown());
        StringBuilder body = new StringBuilder();
        if (channelType == ChannelType.XIAOHONGSHU) {
            body.append(content.summary()).append("\n\n").append(plain);
        } else {
            body.append(plain);
        }
        if (canonicalUrl != null) {
            body.append("\n\n").append(region == ChannelRegion.OVERSEAS ? "Source: " : "原文链接：").append(canonicalUrl);
        }
        if (!tags.isEmpty()) {
            body.append("\n\n");
            tags.forEach(tag -> body.append('#').append(tag).append(' '));
        }
        return body.toString().strip();
    }

    private String shortText(Localized content, ChannelType channelType, String canonicalUrl, List<String> tags) {
        StringBuilder suffix = new StringBuilder();
        if (canonicalUrl != null) suffix.append("\n\n").append(canonicalUrl);
        if (!tags.isEmpty()) {
            suffix.append("\n");
            int allowedTags = channelType == ChannelType.X ? 2 : 3;
            tags.stream().limit(allowedTags).forEach(tag -> suffix.append('#').append(tag).append(' '));
        }
        String lead = content.title() + "\n\n" + content.summary();
        return (lead + suffix).stripTrailing();
    }

    private String appendLink(String content, String canonicalUrl, String labelWithSeparator) {
        return canonicalUrl == null ? content : content + "\n\n" + labelWithSeparator + canonicalUrl;
    }

    private String stripMarkdown(String markdown) {
        return markdown
                .replaceAll("(?m)^```[^\\n]*$", "")
                .replace("```", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^>\\s?", "")
                .replaceAll("(?m)^[-*+]\\s+", "• ")
                .replaceAll("(?m)^\\d+\\.\\s+", "")
                .replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("\\[([^]]+)]\\(([^)]+)\\)", "$1（$2）")
                .replaceAll("[*_~`]", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private int tagLimit(ChannelType type) {
        return switch (type) {
            case DEV -> 4;
            case XIAOHONGSHU -> 10;
            case LINKEDIN -> 5;
            case X, MASTODON -> 3;
            default -> 8;
        };
    }

    private String normalizeTag(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_-]", "");
    }

    private record Localized(String title, String summary, String markdown, List<String> tags, List<String> keywords) {
    }
}
