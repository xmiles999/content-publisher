package io.contentpublisher.platform.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.domain.AiProviderSettings;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleSourceType;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.domain.ChannelAccount;
import io.contentpublisher.platform.domain.ContentOrigin;
import io.contentpublisher.platform.domain.Job;
import io.contentpublisher.platform.domain.JobPayload;
import io.contentpublisher.platform.domain.JobType;
import io.contentpublisher.platform.domain.ManualPublication;
import io.contentpublisher.platform.domain.ManualChannelProfile;
import io.contentpublisher.platform.domain.Project;
import io.contentpublisher.platform.domain.ProjectStatus;
import io.contentpublisher.platform.domain.Publication;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
final class JpaDomainMapper {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final ObjectMapper objectMapper;

    JpaDomainMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Project project(ProjectEntity entity) {
        return new Project(entity.id, entity.tenantId, entity.gitUrl, entity.name, entity.description,
                entity.defaultBranch, entity.revision, strings(entity.languagesJson), entity.license,
                ProjectStatus.valueOf(entity.status), entity.createdBy, entity.updatedBy,
                entity.createdAt, entity.updatedAt);
    }

    Article article(ArticleEntity entity) {
        ArticleSourceType sourceType = ArticleSourceType.valueOf(entity.sourceType);
        ContentOrigin origin = sourceType == ArticleSourceType.GIT ? ContentOrigin.git(entity.projectId)
                : new ContentOrigin(sourceType, null, entity.sourceUrl, entity.sourceTitle, entity.sourceDescription,
                        entity.targetAudience, entity.articleType, entity.knowledgeLevel,
                        strings(entity.sourceKeywordsJson));
        return new Article(entity.id, entity.tenantId, origin, entity.generationJobId,
                entity.title, entity.summary, entity.markdown, strings(entity.tagsJson), strings(entity.keywordsJson),
                entity.titleEn, entity.summaryEn, entity.markdownEn, strings(entity.tagsEnJson),
                strings(entity.keywordsEnJson), entity.language, entity.sourceRevision, entity.currentVersion,
                ArticleStatus.valueOf(entity.status), entity.createdBy, entity.updatedBy,
                entity.createdAt, entity.updatedAt);
    }

    AiProviderSettings aiSettings(AiProviderSettingsEntity entity) {
        return new AiProviderSettings(entity.tenantId, entity.baseUrl, entity.encryptedApiKey,
                entity.apiKeyFingerprint, entity.model, entity.timeoutSeconds, entity.temperature.doubleValue(),
                entity.enabled, entity.settingsVersion, entity.createdBy, entity.updatedBy,
                entity.createdAt, entity.updatedAt);
    }

    ChannelAccount channelAccount(ChannelAccountEntity entity) {
        return new ChannelAccount(entity.id, entity.tenantId, entity.type, entity.displayName, entity.baseUrl,
                entity.encryptedCredentials, entity.idempotencyKey, entity.requestHash,
                entity.credentialFingerprint, entity.accountVersion, entity.status, entity.verificationStatus,
                entity.verificationMessage, entity.lastVerifiedAt, entity.createdBy, entity.updatedBy,
                entity.createdAt, entity.updatedAt);
    }

    Publication publication(PublicationEntity entity) {
        return new Publication(entity.id, entity.tenantId, entity.articleId, entity.channelAccountId,
                entity.publicationJobId, entity.channelType, entity.canonicalUrl, entity.status,
                entity.externalId, entity.externalUrl, entity.errorCode, entity.errorMessage,
                entity.publishedAt, entity.createdAt, entity.updatedAt);
    }

    ManualPublication manualPublication(ManualPublicationEntity entity) {
        return new ManualPublication(entity.id, entity.tenantId, entity.articleId, entity.channelType,
                entity.contentFormat, entity.adaptedTitle, entity.adaptedContent, entity.externalUrl,
                entity.publishedBy, entity.publishedAt);
    }

    ManualChannelProfile manualChannelProfile(ManualChannelProfileEntity entity) {
        return new ManualChannelProfile(entity.id, entity.tenantId, entity.channelType, entity.enabled,
                entity.accountAlias, strings(entity.defaultTagsJson), entity.defaultSection, entity.notes,
                entity.sortOrder, entity.loginConfirmedAt, entity.profileVersion, entity.createdBy,
                entity.updatedBy, entity.createdAt, entity.updatedAt);
    }

    Job job(JobEntity entity) {
        return new Job(entity.id, entity.tenantId, entity.actorSubject, entity.type, entity.status,
                payload(entity.type, entity.payloadJson), entity.idempotencyKey, entity.requestHash,
                entity.attempt, entity.maxAttempts, entity.progressPercent, entity.progressLabel,
                entity.progressDetail, entity.batchId, entity.scheduledAt, entity.lockedAt, entity.lockOwner,
                entity.resultResourceId, entity.errorCode, entity.errorMessage, entity.createdAt, entity.updatedAt);
    }

    String stringsJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new ApplicationException("PERSISTENCE_SERIALIZATION_FAILED", "数据序列化失败", exception);
        }
    }

    List<String> strings(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new ApplicationException("PERSISTENCE_DESERIALIZATION_FAILED", "数据反序列化失败", exception);
        }
    }

    String mapJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new ApplicationException("AUDIT_SERIALIZATION_FAILED", "审计数据序列化失败", exception);
        }
    }

    String payloadJson(JobPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ApplicationException("JOB_SERIALIZATION_FAILED", "任务数据序列化失败", exception);
        }
    }

    private JobPayload payload(JobType type, String payload) {
        try {
            return switch (type) {
                case IMPORT_PROJECT -> objectMapper.readValue(payload, JobPayload.ImportProject.class);
                case GENERATE_ARTICLE -> objectMapper.readValue(payload, JobPayload.GenerateArticle.class);
                case GENERATE_TOPIC_ARTICLE -> objectMapper.readValue(payload, JobPayload.GenerateTopicArticle.class);
                case GENERATE_WEBSITE_ARTICLE -> objectMapper.readValue(payload, JobPayload.GenerateWebsiteArticle.class);
                case PUBLISH_ARTICLE -> objectMapper.readValue(payload, JobPayload.PublishArticle.class);
            };
        } catch (JsonProcessingException exception) {
            throw new ApplicationException("JOB_DESERIALIZATION_FAILED", "任务数据反序列化失败", exception);
        }
    }
}
