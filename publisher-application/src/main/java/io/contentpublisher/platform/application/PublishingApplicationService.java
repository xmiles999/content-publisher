package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.application.port.ChannelAccountRepository;
import io.contentpublisher.platform.application.port.ChannelEndpointPolicy;
import io.contentpublisher.platform.application.port.ChannelPublisher;
import io.contentpublisher.platform.application.port.CredentialVault;
import io.contentpublisher.platform.application.port.ManualPublicationRepository;
import io.contentpublisher.platform.application.port.PublicationRepository;
import io.contentpublisher.platform.domain.AdaptedContent;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleVersion;
import io.contentpublisher.platform.domain.ChannelAccount;
import io.contentpublisher.platform.domain.ChannelAccountStatus;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.ContentFormat;
import io.contentpublisher.platform.domain.ManualPublication;
import io.contentpublisher.platform.domain.Publication;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stable facade over the publishing domain's focused command and query services. */
public final class PublishingApplicationService {
    private final ChannelAccountApplicationService accounts;
    private final ArticleEditorialApplicationService articles;
    private final PublicationCommandApplicationService commands;
    private final PublicationQueryApplicationService queries;

    public PublishingApplicationService(ChannelAccountApplicationService accounts,
                                        ArticleEditorialApplicationService articles,
                                        PublicationCommandApplicationService commands,
                                        PublicationQueryApplicationService queries) {
        this.accounts = accounts;
        this.articles = articles;
        this.commands = commands;
        this.queries = queries;
    }

    public PublishingApplicationService(ArticleRepository articles,
                                        ChannelAccountRepository accounts,
                                        PublicationRepository publications,
                                        ManualPublicationRepository manualPublications,
                                        CredentialVault credentialVault,
                                        ChannelEndpointPolicy endpointPolicy,
                                        List<ChannelPublisher> publishers,
                                        AuditRecorder auditRecorder,
                                        PlatformContentAdapter contentAdapter,
                                        Clock clock) {
        this(new ChannelAccountApplicationService(accounts, credentialVault, endpointPolicy, auditRecorder, clock),
                new ArticleEditorialApplicationService(articles, auditRecorder, clock),
                new PublicationCommandApplicationService(articles, accounts, publications, manualPublications,
                        credentialVault, endpointPolicy, publishers, auditRecorder, contentAdapter, clock),
                new PublicationQueryApplicationService(articles, accounts, publications, manualPublications));
    }

    public ChannelAccount createAccount(ActorContext actor, ChannelType type, String displayName,
                                        String baseUrl, Map<String, String> credentials, String idempotencyKey) {
        return accounts.createAccount(actor, type, displayName, baseUrl, credentials, idempotencyKey);
    }

    public List<ChannelAccount> listAccounts(ActorContext actor) {
        return accounts.listAccounts(actor);
    }

    public long countAccounts(ActorContext actor) {
        return accounts.countAccounts(actor);
    }

    public ChannelAccount getAccount(ActorContext actor, UUID accountId) {
        return accounts.getAccount(actor, accountId);
    }

    public ChannelAccount updateAccountStatus(ActorContext actor, UUID accountId, int expectedVersion,
                                              ChannelAccountStatus status) {
        return accounts.updateAccountStatus(actor, accountId, expectedVersion, status);
    }

    public ChannelAccount rotateCredentials(ActorContext actor, UUID accountId, int expectedVersion,
                                            Map<String, String> credentials) {
        return accounts.rotateCredentials(actor, accountId, expectedVersion, credentials);
    }

    public ChannelAccount updateAccountProfile(ActorContext actor, UUID accountId, int expectedVersion,
                                               String displayName, String baseUrl) {
        return accounts.updateAccountProfile(actor, accountId, expectedVersion, displayName, baseUrl);
    }

    public ChannelAccount verifyConnection(ActorContext actor, UUID accountId) {
        return accounts.verifyConnection(actor, accountId);
    }

    public Article getArticle(ActorContext actor, UUID articleId) {
        return articles.getArticle(actor, articleId);
    }

    public List<ArticleVersion> getArticleVersions(ActorContext actor, UUID articleId) {
        return articles.getArticleVersions(actor, articleId);
    }

    public Article updateArticle(ActorContext actor, UUID articleId, int expectedVersion, String title,
                                 String summary, String markdown, List<String> tags, List<String> keywords,
                                 String titleEn, String summaryEn, String markdownEn, List<String> tagsEn,
                                 List<String> keywordsEn) {
        return articles.updateArticle(actor, articleId, expectedVersion, title, summary, markdown, tags, keywords,
                titleEn, summaryEn, markdownEn, tagsEn, keywordsEn);
    }

    public Article updateArticle(ActorContext actor, UUID articleId, int expectedVersion, String title,
                                 String summary, String markdown, List<String> tags, List<String> keywords) {
        return articles.updateArticle(actor, articleId, expectedVersion, title, summary, markdown, tags, keywords);
    }

    public Article updateArticle(ActorContext actor, UUID articleId, int expectedVersion, String title,
                                 String summary, String markdown, List<String> keywords) {
        return articles.updateArticle(actor, articleId, expectedVersion, title, summary, markdown, keywords);
    }

    public Article approveArticle(ActorContext actor, UUID articleId) {
        return articles.approveArticle(actor, articleId);
    }

    public Article confirmArticle(ActorContext actor, UUID articleId) {
        return articles.confirmArticle(actor, articleId);
    }

    public Article reopenArticle(ActorContext actor, UUID articleId) {
        return articles.reopenArticle(actor, articleId);
    }

    public Article rejectArticle(ActorContext actor, UUID articleId, String reason) {
        return articles.rejectArticle(actor, articleId, reason);
    }

    public Article restoreArticleVersion(ActorContext actor, UUID articleId, int expectedVersion, int sourceVersion) {
        return articles.restoreVersion(actor, articleId, expectedVersion, sourceVersion);
    }

    public AdaptedContent adaptContent(ActorContext actor, UUID articleId, ChannelType channelType,
                                       String canonicalUrl) {
        return commands.adaptContent(actor, articleId, channelType, canonicalUrl);
    }

    public ManualPublication completeManualPublication(ActorContext actor, UUID articleId, ChannelType channelType,
                                                       String adaptedTitle, String adaptedContent,
                                                       ContentFormat contentFormat, String externalUrl) {
        return commands.completeManualPublication(actor, articleId, channelType, adaptedTitle, adaptedContent,
                contentFormat, externalUrl);
    }

    public void assertPublishable(ActorContext actor, UUID articleId, UUID accountId) {
        commands.assertPublishable(actor, articleId, accountId);
    }

    public PublicationPreflightResult preflight(ActorContext actor, UUID articleId, UUID accountId) {
        return commands.preflight(actor, articleId, accountId);
    }

    public Publication publish(ActorContext actor, UUID articleId, UUID accountId, String canonicalUrl,
                               UUID publicationJobId) {
        return commands.publish(actor, articleId, accountId, canonicalUrl, publicationJobId);
    }

    public Publication publish(ActorContext actor, UUID articleId, UUID accountId, String canonicalUrl,
                               UUID publicationJobId, JobProgressReporter progress) {
        return commands.publish(actor, articleId, accountId, canonicalUrl, publicationJobId, progress);
    }

    public String validateCanonicalUrl(String value) {
        return commands.validateCanonicalUrl(value);
    }

    public String validatePublishedUrl(ChannelType channelType, String value) {
        return commands.validatePublishedUrl(channelType, value);
    }

    public List<ManualPublication> getManualPublications(ActorContext actor, UUID articleId) {
        return queries.getManualPublications(actor, articleId);
    }

    public List<ManualPublication> listRecentManualPublications(ActorContext actor, int limit) {
        return queries.listRecentManualPublications(actor, limit);
    }

    public List<PublicationRecord> getArticlePublicationRecords(ActorContext actor, UUID articleId) {
        return queries.getArticlePublicationRecords(actor, articleId);
    }

    public List<PublicationRecord> listPublicationRecords(ActorContext actor, int limit) {
        return queries.listPublicationRecords(actor, limit);
    }

    public PagedResult<PublicationRecord> searchPublicationRecords(ActorContext actor, String query,
                                                                   ChannelType channelType,
                                                                   io.contentpublisher.platform.domain.PublicationStatus status,
                                                                   PublicationMethod method,
                                                                   int page, int pageSize) {
        return queries.searchPublicationRecords(actor, query, channelType, status, method, page, pageSize);
    }

    public List<PublicationRecord> listPublicationRecordsForArticles(ActorContext actor, List<UUID> articleIds) {
        return queries.listPublicationRecordsForArticles(actor, articleIds);
    }

    public Publication getPublication(ActorContext actor, UUID publicationId) {
        return queries.getPublication(actor, publicationId);
    }
}
