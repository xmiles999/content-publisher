package io.contentpublisher.platform.domain;

import java.util.UUID;

public sealed interface JobPayload permits JobPayload.ImportProject, JobPayload.GenerateArticle,
        JobPayload.GenerateTopicArticle, JobPayload.GenerateWebsiteArticle, JobPayload.PublishArticle {
    record ImportProject(String gitUrl, String branch) implements JobPayload {}

    record GenerateArticle(UUID projectId, GenerationPolicy policy) implements JobPayload {}

    record GenerateTopicArticle(TopicBrief brief, GenerationPolicy policy) implements JobPayload {}

    record GenerateWebsiteArticle(WebsiteBrief brief, GenerationPolicy policy) implements JobPayload {}

    record PublishArticle(UUID articleId, UUID channelAccountId, String canonicalUrl, Integer articleVersion) implements JobPayload {
        public PublishArticle(UUID articleId, UUID channelAccountId, String canonicalUrl) {
            this(articleId, channelAccountId, canonicalUrl, null);
        }
    }
}
