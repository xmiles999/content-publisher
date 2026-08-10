package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.domain.JobStatus;
import io.contentpublisher.platform.domain.JobType;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.domain.ArticleSourceType;
import io.contentpublisher.platform.domain.ChannelAccountStatus;
import io.contentpublisher.platform.domain.PublicationStatus;
import io.contentpublisher.platform.application.PublicationMethod;

import java.util.EnumMap;
import java.util.Map;

final class PortalLabels {
    private static final Map<JobType, String> JOB_TYPES = jobTypes();
    private static final Map<JobStatus, String> JOB_STATUSES = jobStatuses();
    private static final Map<ArticleStatus, String> ARTICLE_STATUSES = articleStatuses();
    private static final Map<ArticleSourceType, String> ARTICLE_SOURCES = articleSources();
    private static final Map<PublicationStatus, String> PUBLICATION_STATUSES = publicationStatuses();
    private static final Map<ChannelAccountStatus, String> CHANNEL_ACCOUNT_STATUSES = channelAccountStatuses();
    private static final Map<PublicationMethod, String> PUBLICATION_METHODS = publicationMethods();

    private PortalLabels() {
    }

    static Map<JobType, String> jobTypeNames() {
        return JOB_TYPES;
    }

    static Map<JobStatus, String> jobStatusNames() {
        return JOB_STATUSES;
    }

    static Map<ArticleStatus, String> articleStatusNames() {
        return ARTICLE_STATUSES;
    }

    static Map<ArticleSourceType, String> articleSourceNames() {
        return ARTICLE_SOURCES;
    }

    static Map<PublicationStatus, String> publicationStatusNames() {
        return PUBLICATION_STATUSES;
    }

    static Map<ChannelAccountStatus, String> channelAccountStatusNames() {
        return CHANNEL_ACCOUNT_STATUSES;
    }

    static Map<PublicationMethod, String> publicationMethodNames() {
        return PUBLICATION_METHODS;
    }

    private static Map<JobType, String> jobTypes() {
        var names = new EnumMap<JobType, String>(JobType.class);
        names.put(JobType.IMPORT_PROJECT, "导入 Git 项目");
        names.put(JobType.GENERATE_ARTICLE, "生成项目文章");
        names.put(JobType.GENERATE_TOPIC_ARTICLE, "生成主题教程");
        names.put(JobType.GENERATE_WEBSITE_ARTICLE, "生成网站推荐");
        names.put(JobType.PUBLISH_ARTICLE, "发布文章");
        return Map.copyOf(names);
    }

    private static Map<JobStatus, String> jobStatuses() {
        var names = new EnumMap<JobStatus, String>(JobStatus.class);
        names.put(JobStatus.PENDING, "等待执行");
        names.put(JobStatus.RUNNING, "执行中");
        names.put(JobStatus.RETRY_WAIT, "等待重试");
        names.put(JobStatus.SUCCEEDED, "执行成功");
        names.put(JobStatus.FAILED, "执行失败");
        names.put(JobStatus.CANCELLED, "已取消");
        return Map.copyOf(names);
    }

    private static Map<ArticleStatus, String> articleStatuses() {
        var names = new EnumMap<ArticleStatus, String>(ArticleStatus.class);
        names.put(ArticleStatus.DRAFT, "草稿");
        names.put(ArticleStatus.READY, "可发布");
        names.put(ArticleStatus.APPROVED, "可发布（兼容）");
        names.put(ArticleStatus.PUBLISHED, "已发布");
        names.put(ArticleStatus.REJECTED, "需修改（兼容）");
        return Map.copyOf(names);
    }

    private static Map<ArticleSourceType, String> articleSources() {
        var names = new EnumMap<ArticleSourceType, String>(ArticleSourceType.class);
        names.put(ArticleSourceType.GIT, "Git 项目");
        names.put(ArticleSourceType.TOPIC, "主题教程");
        names.put(ArticleSourceType.WEBSITE, "网站推荐");
        return Map.copyOf(names);
    }

    private static Map<PublicationStatus, String> publicationStatuses() {
        var names = new EnumMap<PublicationStatus, String>(PublicationStatus.class);
        names.put(PublicationStatus.PUBLISHING, "发布中");
        names.put(PublicationStatus.PUBLISHED, "已发布");
        names.put(PublicationStatus.FAILED, "发布失败");
        return Map.copyOf(names);
    }

    private static Map<ChannelAccountStatus, String> channelAccountStatuses() {
        var names = new EnumMap<ChannelAccountStatus, String>(ChannelAccountStatus.class);
        names.put(ChannelAccountStatus.ACTIVE, "已启用");
        names.put(ChannelAccountStatus.DISABLED, "已停用");
        return Map.copyOf(names);
    }

    private static Map<PublicationMethod, String> publicationMethods() {
        var names = new EnumMap<PublicationMethod, String>(PublicationMethod.class);
        names.put(PublicationMethod.API, "API 自动发布");
        names.put(PublicationMethod.MANUAL, "人工发布");
        return Map.copyOf(names);
    }
}
