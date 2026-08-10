package io.contentpublisher.platform.infrastructure.channels;

import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.ChannelCredentialRefreshResult;
import io.contentpublisher.platform.application.ChannelCatalog;
import io.contentpublisher.platform.application.PublishingApplicationService;
import io.contentpublisher.platform.application.PlatformContentAdapter;
import io.contentpublisher.platform.application.PublicationCommandApplicationService;
import io.contentpublisher.platform.application.PublicationMethod;
import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.application.port.ChannelAccountRepository;
import io.contentpublisher.platform.application.port.ChannelEndpointPolicy;
import io.contentpublisher.platform.application.port.ChannelCredentialRefresher;
import io.contentpublisher.platform.application.port.ChannelPublisher;
import io.contentpublisher.platform.application.port.CredentialVault;
import io.contentpublisher.platform.application.port.PublicationRepository;
import io.contentpublisher.platform.application.port.ManualPublicationRepository;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.domain.ChannelAccount;
import io.contentpublisher.platform.domain.ChannelAccountStatus;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.Publication;
import io.contentpublisher.platform.domain.PublicationStatus;
import io.contentpublisher.platform.domain.ContentFormat;
import io.contentpublisher.platform.domain.ManualPublication;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class PublishingApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final ActorContext ACTOR = new ActorContext("tenant", "admin");

    @Test
    void shouldRequireContentConfirmationBeforePublishing() {
        Fixture fixture = fixture(ArticleStatus.DRAFT);

        assertThatThrownBy(() -> fixture.service.assertPublishable(ACTOR, fixture.article.id(), fixture.account.id()))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ARTICLE_NOT_APPROVED"));
    }

    @Test
    void shouldPublishReadyPersonalContent() {
        Fixture fixture = fixture(ArticleStatus.READY);

        assertThat(fixture.service.preflight(ACTOR, fixture.article.id(), fixture.account.id()).ready()).isTrue();
    }

    @Test
    void shouldKeepLegacyApprovedContentPublishable() {
        Fixture fixture = fixture(ArticleStatus.APPROVED);

        assertThat(fixture.service.preflight(ACTOR, fixture.article.id(), fixture.account.id()).ready()).isTrue();
    }

    @Test
    void shouldKeepMediumExistingAccountsOperationalWithoutAllowingNewAccounts() {
        var medium = ChannelCatalog.definition(ChannelType.MEDIUM);

        assertThat(medium.configurationAllowed()).isFalse();
        assertThat(medium.existingAccountOperational()).isTrue();
        assertThat(medium.availabilityLabel()).isEqualTo("停止新接入");
    }

    @Test
    void shouldBlockOverseasPreflightWhenEnglishContentIsMissing() {
        Fixture fixture = fixture(ArticleStatus.READY);
        Article articleWithoutEnglish = new Article(fixture.article.id(), fixture.article.tenantId(),
                fixture.article.origin(), fixture.article.generationJobId(), fixture.article.title(),
                fixture.article.summary(), fixture.article.markdown(), fixture.article.tags(),
                fixture.article.keywords(), "", "", "", List.of(), List.of(), fixture.article.language(),
                fixture.article.sourceRevision(), fixture.article.currentVersion(), fixture.article.status(),
                fixture.article.createdBy(), fixture.article.updatedBy(), fixture.article.createdAt(),
                fixture.article.updatedAt());
        when(fixture.articles.findArticleById("tenant", fixture.article.id()))
                .thenReturn(Optional.of(articleWithoutEnglish));

        var result = fixture.service.preflight(ACTOR, fixture.article.id(), fixture.account.id());

        assertThat(result.ready()).isFalse();
        assertThat(result.message()).contains("暂无英文版本").contains("补充英文标题、摘要和正文");
    }

    @Test
    void shouldCreatePublicationAndMarkArticlePublished() {
        Fixture fixture = fixture(ArticleStatus.READY);
        UUID jobId = UUID.randomUUID();

        Publication result = fixture.service.publish(ACTOR, fixture.article.id(), fixture.account.id(),
                "https://example.com/article", jobId);

        assertThat(result.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(result.externalUrl()).isEqualTo("https://dev.to/example/article");
    }

    @Test
    void shouldPersistAutomaticallyRefreshedCredentialsBeforePublishing() {
        ArticleRepository articles = mock(ArticleRepository.class);
        ChannelAccountRepository accounts = mock(ChannelAccountRepository.class);
        PublicationRepository publications = mock(PublicationRepository.class);
        ManualPublicationRepository manualPublications = mock(ManualPublicationRepository.class);
        CredentialVault vault = mock(CredentialVault.class);
        ChannelEndpointPolicy endpointPolicy = mock(ChannelEndpointPolicy.class);
        ChannelCredentialRefresher refresher = mock(ChannelCredentialRefresher.class);
        ChannelPublisher publisher = mock(ChannelPublisher.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        Article article = article(ArticleStatus.READY);
        ChannelAccount account = new ChannelAccount(UUID.randomUUID(), "tenant", ChannelType.X, "X",
                "https://api.x.com", "encrypted-x", "channel-x-001", "a".repeat(64), "b".repeat(64), 1,
                ChannelAccountStatus.ACTIVE, "admin", "admin", NOW, NOW);
        Map<String, String> currentCredentials = Map.of(
                "accessToken", "old-access", "refreshToken", "old-refresh",
                "clientId", "client-id", "clientSecret", "client-secret");
        Map<String, String> refreshedCredentials = Map.of(
                "accessToken", "new-access", "refreshToken", "new-refresh",
                "clientId", "client-id", "clientSecret", "client-secret");
        when(articles.findArticleById("tenant", article.id())).thenReturn(Optional.of(article));
        when(articles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accounts.findChannelAccountById("tenant", account.id())).thenReturn(Optional.of(account));
        when(accounts.updateIfVersionMatches(any(), anyInt()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        when(publications.findByPublicationJobId(any(), any())).thenReturn(Optional.empty());
        when(publications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(endpointPolicy.validateAndNormalize(ChannelType.X, account.baseUrl())).thenReturn(account.baseUrl());
        when(vault.decrypt("encrypted-x")).thenReturn(currentCredentials);
        when(vault.fingerprint(refreshedCredentials)).thenReturn("e".repeat(64));
        when(vault.encrypt(refreshedCredentials)).thenReturn("encrypted-refreshed-x");
        when(refresher.refresh(account, currentCredentials))
                .thenReturn(new ChannelCredentialRefreshResult(refreshedCredentials, true));
        when(publisher.channelType()).thenReturn(ChannelType.X);
        when(publisher.publish(any(), any(), eq(refreshedCredentials)))
                .thenReturn(new ChannelPublisher.PublishResult("x-42", "https://x.com/i/web/status/x-42"));
        var service = new PublicationCommandApplicationService(articles, accounts, publications,
                manualPublications, vault, endpointPolicy, refresher, List.of(publisher), audits,
                new PlatformContentAdapter(), Clock.fixed(NOW, ZoneOffset.UTC));

        Publication result = service.publish(ACTOR, article.id(), account.id(),
                "https://example.com/article", UUID.randomUUID());

        ArgumentCaptor<ChannelAccount> savedAccount = ArgumentCaptor.forClass(ChannelAccount.class);
        verify(accounts).updateIfVersionMatches(savedAccount.capture(), eq(1));
        assertThat(savedAccount.getValue().version()).isEqualTo(2);
        assertThat(savedAccount.getValue().encryptedCredentials()).isEqualTo("encrypted-refreshed-x");
        assertThat(savedAccount.getValue().credentialFingerprint()).isEqualTo("e".repeat(64));
        assertThat(result.externalUrl()).isEqualTo("https://x.com/i/web/status/x-42");
        verify(publisher).publish(any(), any(), eq(refreshedCredentials));
    }

    @Test
    void shouldCreateImmutableArticleVersionWithOptimisticCheck() {
        Fixture fixture = fixture(ArticleStatus.DRAFT);

        Article updated = fixture.service.updateArticle(ACTOR, fixture.article.id(), 1,
                "Updated", "Updated summary", "## Updated body", List.of("Java", "AI"));

        assertThat(updated.currentVersion()).isEqualTo(2);
        assertThat(updated.title()).isEqualTo("Updated");
        assertThatThrownBy(() -> fixture.service.updateArticle(ACTOR, fixture.article.id(), 2,
                "Again", "Summary", "Body", List.of()))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ARTICLE_VERSION_CONFLICT"));
    }

    @Test
    void shouldVersionAccountStatusAndCredentialRotation() {
        Fixture statusFixture = fixture(ArticleStatus.DRAFT);
        ChannelAccount disabled = statusFixture.service.updateAccountStatus(ACTOR, statusFixture.account.id(), 1,
                ChannelAccountStatus.DISABLED);
        assertThat(disabled.version()).isEqualTo(2);
        assertThat(disabled.status()).isEqualTo(ChannelAccountStatus.DISABLED);

        Fixture rotationFixture = fixture(ArticleStatus.DRAFT);
        ChannelAccount rotated = rotationFixture.service.rotateCredentials(ACTOR, rotationFixture.account.id(), 1,
                Map.of("apiKey", "rotated-secret"));
        assertThat(rotated.version()).isEqualTo(2);
        assertThat(rotated.encryptedCredentials()).isEqualTo("new-encrypted");
    }

    @Test
    void shouldRejectNonHttpsCanonicalUrl() {
        Fixture fixture = fixture(ArticleStatus.READY);

        assertThatThrownBy(() -> fixture.service.validateCanonicalUrl("http://example.com/article"))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CANONICAL_URL_REJECTED"));
    }

    @Test
    void shouldRejectCredentialRotationWhenDatabaseVersionChanged() {
        Fixture fixture = fixture(ArticleStatus.DRAFT);
        doReturn(Optional.empty()).when(fixture.accounts).updateIfVersionMatches(any(), anyInt());

        assertThatThrownBy(() -> fixture.service.rotateCredentials(ACTOR, fixture.account.id(), 1,
                Map.of("apiKey", "rotated-secret")))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CHANNEL_ACCOUNT_VERSION_CONFLICT"));
    }

    @Test
    void shouldRecordManualPublicationSnapshotAndMarkArticlePublished() {
        Fixture fixture = fixture(ArticleStatus.READY);
        when(fixture.manualPublications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ManualPublication result = fixture.service.completeManualPublication(ACTOR, fixture.article.id(),
                ChannelType.XIAOHONGSHU, "小红书标题", "适配后的纯文本内容", ContentFormat.PLAIN_TEXT,
                "https://www.xiaohongshu.com/explore/example");

        assertThat(result.channelType()).isEqualTo(ChannelType.XIAOHONGSHU);
        assertThat(result.adaptedContent()).isEqualTo("适配后的纯文本内容");
        verify(fixture.articles).save(any());
    }

    @Test
    void shouldAcceptOfficialRedNoteUrlForXiaohongshuChannel() {
        Fixture fixture = fixture(ArticleStatus.APPROVED);
        when(fixture.manualPublications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ManualPublication result = fixture.service.completeManualPublication(ACTOR, fixture.article.id(),
                ChannelType.XIAOHONGSHU, "RedNote title", "RedNote content", ContentFormat.PLAIN_TEXT,
                "https://www.rednote.com/explore/example");

        assertThat(result.externalUrl()).isEqualTo("https://www.rednote.com/explore/example");
    }

    @Test
    void shouldAcceptOfficialXiaohongshuShortLink() {
        Fixture fixture = fixture(ArticleStatus.APPROVED);

        assertThat(fixture.service.validatePublishedUrl(ChannelType.XIAOHONGSHU,
                "https://xhslink.com/a/example"))
                .isEqualTo("https://xhslink.com/a/example");
    }

    @Test
    void shouldRejectLookalikeRedNoteDomain() {
        Fixture fixture = fixture(ArticleStatus.APPROVED);

        assertThatThrownBy(() -> fixture.service.completeManualPublication(ACTOR, fixture.article.id(),
                ChannelType.XIAOHONGSHU, "Title", "Content", ContentFormat.PLAIN_TEXT,
                "https://rednote.com.evil.example/explore/example"))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> {
                            assertThat(exception.code()).isEqualTo("PUBLISHED_URL_REJECTED");
                            assertThat(exception.getMessage()).contains("rednote.com.evil.example")
                                    .contains("xiaohongshu.com").contains("rednote.com");
                        });
    }

    @Test
    void shouldKeepManualContentBeyondAutomaticPlatformLimit() {
        Fixture fixture = fixture(ArticleStatus.APPROVED);
        when(fixture.manualPublications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String fullContent = "人工发布完整正文".repeat(180);

        ManualPublication result = fixture.service.completeManualPublication(ACTOR, fixture.article.id(),
                ChannelType.XIAOHONGSHU, "完整平台标题", fullContent, ContentFormat.PLAIN_TEXT,
                "https://www.xiaohongshu.com/explore/example");

        assertThat(fullContent.codePointCount(0, fullContent.length())).isGreaterThan(1_000);
        assertThat(result.adaptedContent()).isEqualTo(fullContent);
    }

    @Test
    void shouldMergeApiAndManualPublicationRecordsInLatestFirstOrder() {
        Fixture fixture = fixture(ArticleStatus.PUBLISHED);
        Publication apiPublication = new Publication(UUID.randomUUID(), "tenant", fixture.article.id(),
                fixture.account.id(), UUID.randomUUID(), ChannelType.DEV, "https://example.com/article",
                PublicationStatus.PUBLISHED, "dev-42", "https://dev.to/example/article", null, null,
                NOW.minusSeconds(60), NOW.minusSeconds(120), NOW.minusSeconds(60));
        ManualPublication manualPublication = new ManualPublication(UUID.randomUUID(), "tenant", fixture.article.id(),
                ChannelType.XIAOHONGSHU, ContentFormat.PLAIN_TEXT, "标题", "正文",
                "https://www.xiaohongshu.com/explore/example", "editor", NOW);
        when(fixture.accounts.findAll("tenant")).thenReturn(List.of(fixture.account));
        when(fixture.publications.findRecentApi("tenant", 20)).thenReturn(List.of(apiPublication));
        when(fixture.manualPublications.findRecent("tenant", 20)).thenReturn(List.of(manualPublication));

        var records = fixture.service.listPublicationRecords(ACTOR, 20);

        assertThat(records).hasSize(2);
        assertThat(records.get(0).method()).isEqualTo(PublicationMethod.MANUAL);
        assertThat(records.get(0).publishedBy()).isEqualTo("editor");
        assertThat(records.get(1).method()).isEqualTo(PublicationMethod.API);
        assertThat(records.get(1).channelAccountName()).isEqualTo("DEV");
        assertThat(records.get(1).contentFormat()).isEqualTo(ContentFormat.MARKDOWN);
    }

    private Fixture fixture(ArticleStatus status) {
        ArticleRepository articles = mock(ArticleRepository.class);
        ChannelAccountRepository accounts = mock(ChannelAccountRepository.class);
        PublicationRepository publications = mock(PublicationRepository.class);
        ManualPublicationRepository manualPublications = mock(ManualPublicationRepository.class);
        CredentialVault vault = mock(CredentialVault.class);
        ChannelEndpointPolicy endpointPolicy = mock(ChannelEndpointPolicy.class);
        ChannelPublisher publisher = mock(ChannelPublisher.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        Article article = article(status);
        ChannelAccount account = account();
        when(articles.findArticleById("tenant", article.id())).thenReturn(Optional.of(article));
        when(articles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(articles.saveWithVersion(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accounts.findChannelAccountById("tenant", account.id())).thenReturn(Optional.of(account));
        when(accounts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accounts.updateIfVersionMatches(any(), anyInt()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        when(endpointPolicy.validateAndNormalize(account.type(), account.baseUrl())).thenReturn(account.baseUrl());
        when(publications.findByPublicationJobId(any(), any())).thenReturn(Optional.empty());
        when(publications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(vault.decrypt("encrypted")).thenReturn(Map.of("apiKey", "secret"));
        when(vault.fingerprint(any())).thenReturn("c".repeat(64));
        when(vault.encrypt(any())).thenReturn("new-encrypted");
        when(publisher.channelType()).thenReturn(ChannelType.DEV);
        when(publisher.publish(any(), any(), any())).thenReturn(
                new ChannelPublisher.PublishResult("42", "https://dev.to/example/article"));
        var service = new PublishingApplicationService(articles, accounts, publications, manualPublications, vault,
                endpointPolicy, List.of(publisher), audits, new PlatformContentAdapter(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, article, account, accounts, articles, publications, manualPublications);
    }

    private Article article(ArticleStatus status) {
        return new Article(UUID.randomUUID(), "tenant", io.contentpublisher.platform.domain.ContentOrigin.git(UUID.randomUUID()),
                UUID.randomUUID(), "Title", "Summary", "## Body", List.of("Java"), List.of("Java"),
                "Title", "Summary", "## Body", List.of("Java"), List.of("Java"),
                "zh-CN", "a".repeat(40), 1, status, "editor", "admin", NOW, NOW);
    }

    private ChannelAccount account() {
        return new ChannelAccount(UUID.randomUUID(), "tenant", ChannelType.DEV, "DEV", "https://dev.to",
                "encrypted", "channel-key-001", "a".repeat(64), "b".repeat(64), 1, ChannelAccountStatus.ACTIVE,
                "admin", "admin", NOW, NOW);
    }

    private record Fixture(PublishingApplicationService service, Article article, ChannelAccount account,
                           ChannelAccountRepository accounts, ArticleRepository articles,
                           PublicationRepository publications,
                           ManualPublicationRepository manualPublications) {}
}
