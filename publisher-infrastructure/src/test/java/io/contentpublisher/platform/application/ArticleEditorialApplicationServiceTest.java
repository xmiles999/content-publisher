package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleEditorialApplicationServiceTest {
    private static final ActorContext ACTOR = new ActorContext("personal", "owner");
    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @Test
    void shouldConfirmDraftAsReadyAndRecordPersonalConfirmation() {
        Fixture fixture = fixture(ArticleStatus.DRAFT);

        Article confirmed = fixture.service.confirmArticle(ACTOR, fixture.articleId);

        assertThat(confirmed.status()).isEqualTo(ArticleStatus.READY);
        assertThat(confirmed.updatedBy()).isEqualTo("owner");
        assertThat(confirmed.updatedAt()).isEqualTo(NOW);
        verify(fixture.audits).record(ACTOR, "ARTICLE_CONTENT_CONFIRMED", "ARTICLE",
                fixture.articleId, Map.of());
    }

    @Test
    void shouldConfirmLegacyRejectedContentAsReady() {
        Fixture fixture = fixture(ArticleStatus.REJECTED);

        assertThat(fixture.service.confirmArticle(ACTOR, fixture.articleId).status())
                .isEqualTo(ArticleStatus.READY);
    }

    @Test
    void shouldKeepRepeatedConfirmationIdempotent() {
        Fixture fixture = fixture(ArticleStatus.READY);

        Article confirmed = fixture.service.confirmArticle(ACTOR, fixture.articleId);

        assertThat(confirmed.status()).isEqualTo(ArticleStatus.READY);
        verify(fixture.articles, never()).save(any());
        verify(fixture.audits, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void shouldReopenReadyContentForEditing() {
        Fixture fixture = fixture(ArticleStatus.READY);

        Article reopened = fixture.service.reopenArticle(ACTOR, fixture.articleId);

        assertThat(reopened.status()).isEqualTo(ArticleStatus.DRAFT);
        verify(fixture.audits).record(ACTOR, "ARTICLE_EDITING_REOPENED", "ARTICLE",
                fixture.articleId, Map.of());
    }

    @Test
    void shouldRejectReopeningPublishedContent() {
        Fixture fixture = fixture(ArticleStatus.PUBLISHED);

        assertThatThrownBy(() -> fixture.service.reopenArticle(ACTOR, fixture.articleId))
                .isInstanceOfSatisfying(ApplicationException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("ARTICLE_STATE_CONFLICT");
                    assertThat(exception.getMessage()).contains("已发布文章");
                });
        verify(fixture.articles, never()).save(any());
    }

    @Test
    void shouldKeepLegacyApprovalCompatible() {
        Fixture fixture = fixture(ArticleStatus.DRAFT);

        Article approved = fixture.service.approveArticle(ACTOR, fixture.articleId);

        assertThat(approved.status()).isEqualTo(ArticleStatus.APPROVED);
        verify(fixture.audits).record(ACTOR, "ARTICLE_APPROVED", "ARTICLE",
                fixture.articleId, Map.of());
    }

    @Test
    void shouldRequireReopenBeforeEditingConfirmedContent() {
        Fixture fixture = fixture(ArticleStatus.READY);

        assertThatThrownBy(() -> fixture.service.updateArticle(ACTOR, fixture.articleId, 1,
                "更新标题", "更新摘要", "更新正文", List.of("个人")))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ARTICLE_STATE_CONFLICT"));
    }

    private Fixture fixture(ArticleStatus status) {
        ArticleRepository articles = mock(ArticleRepository.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        Article original = article(status);
        AtomicReference<Article> stored = new AtomicReference<>(original);
        when(articles.findArticleById("personal", original.id()))
                .thenAnswer(invocation -> Optional.of(stored.get()));
        when(articles.save(any())).thenAnswer(invocation -> {
            Article saved = invocation.getArgument(0);
            stored.set(saved);
            return saved;
        });
        ArticleEditorialApplicationService service = new ArticleEditorialApplicationService(
                articles, audits, Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, articles, audits, original.id());
    }

    private Article article(ArticleStatus status) {
        Instant createdAt = NOW.minusSeconds(3_600);
        return new Article(UUID.randomUUID(), "personal", UUID.randomUUID(), null,
                "个人内容", "摘要", "# 正文", List.of("个人"),
                "zh-CN", "revision", 1, status, "owner", "owner", createdAt, createdAt);
    }

    private record Fixture(ArticleEditorialApplicationService service, ArticleRepository articles,
                           AuditRecorder audits, UUID articleId) {
    }
}
