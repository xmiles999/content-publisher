package io.contentpublisher.platform.domain;

import java.util.List;
import java.util.UUID;

public record ContentOrigin(
        ArticleSourceType type,
        UUID projectId,
        String sourceUrl,
        String title,
        String description,
        String audience,
        String articleType,
        String knowledgeLevel,
        List<String> requestedKeywords) {
    public ContentOrigin {
        requestedKeywords = requestedKeywords == null ? List.of() : List.copyOf(requestedKeywords);
        if (type == ArticleSourceType.GIT && projectId == null) {
            throw new IllegalArgumentException("Git 来源必须关联项目");
        }
        if (type != ArticleSourceType.GIT && projectId != null) {
            throw new IllegalArgumentException("非 Git 来源不能关联 Git 项目");
        }
        if (type == ArticleSourceType.WEBSITE && (sourceUrl == null || sourceUrl.isBlank())) {
            throw new IllegalArgumentException("网站来源必须包含公开 URL");
        }
    }

    public static ContentOrigin git(UUID projectId) {
        return new ContentOrigin(ArticleSourceType.GIT, projectId, null, null, null, null, null, null, List.of());
    }

    public static ContentOrigin topic(TopicBrief brief) {
        return new ContentOrigin(ArticleSourceType.TOPIC, null, null, brief.topic(), brief.description(), brief.audience(),
                brief.articleType(), brief.knowledgeLevel(), brief.keywords());
    }

    public static ContentOrigin website(WebsiteBrief brief, WebsiteSnapshot snapshot) {
        return new ContentOrigin(ArticleSourceType.WEBSITE, null, snapshot.url(), snapshot.title(),
                brief.recommendationAngle(), brief.audience(), "WEBSITE_RECOMMENDATION", "MIXED", brief.keywords());
    }

    public static ContentOrigin custom(String title, String description, List<String> keywords) {
        return new ContentOrigin(ArticleSourceType.CUSTOM, null, null, title, description, null,
                "CUSTOM_NOTE", null, keywords);
    }

    public static ContentOrigin custom(String title, String description) {
        return custom(title, description, List.of());
    }

    public static ContentOrigin custom() {
        return custom(null, null, List.of());
    }
}
