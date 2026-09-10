package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleSourceType;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.domain.ArticleVersion;
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
    void shouldCreateCustomArticleWithDerivedSummaryAndInitialVersion() {
        Fixture fixture = fixture(ArticleStatus.DRAFT);

        Article created = fixture.service.createCustomArticle(ACTOR, "自定义开发笔记", "",
                "# 深入理解虚拟线程\n\n虚拟线程是 Java 21 引入的核心轻量并发特性，极大简化了高吞吐网络服务的开发模型。",
                List.of("Java", "并发"), List.of());

        assertThat(created.title()).isEqualTo("自定义开发笔记");
        assertThat(created.summary()).contains("虚拟线程是 Java 21 引入的核心轻量并发特性");
        assertThat(created.sourceType()).isEqualTo(ArticleSourceType.CUSTOM);
        assertThat(created.status()).isEqualTo(ArticleStatus.DRAFT);
        assertThat(created.currentVersion()).isEqualTo(1);
        assertThat(created.tags()).containsExactly("Java", "并发");
        assertThat(created.keywords()).containsExactly("Java", "并发");
        assertThat(created.sourceRevision()).isNotBlank();
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.updatedAt()).isEqualTo(NOW);
        verify(fixture.articles).saveWithVersion(any(Article.class), any(ArticleVersion.class));
        verify(fixture.audits).record(ACTOR, "CUSTOM_ARTICLE_CREATED", "ARTICLE",
                created.id(), Map.of("title", "自定义开发笔记", "language", "zh-CN"));
    }

    @Test
    void shouldCreateCustomArticleWithExplicitBilingualFields() {
        Fixture fixture = fixture(ArticleStatus.DRAFT);

        Article created = fixture.service.createCustomArticle(ACTOR, "微服务架构演进", "从单体到服务网格的演进路径",
                "## 架构演进实战\n\n服务治理与链路追踪方案。", List.of("微服务"), List.of("架构演进", "服务网格"),
                "Microservice Evolution", "Path from monolith to service mesh",
                "## Evolution Practice\n\nObservability and governance.",
                List.of("Microservices"), List.of("Architecture"), "zh-CN");

        assertThat(created.title()).isEqualTo("微服务架构演进");
        assertThat(created.summary()).isEqualTo("从单体到服务网格的演进路径");
        assertThat(created.titleEn()).isEqualTo("Microservice Evolution");
        assertThat(created.summaryEn()).isEqualTo("Path from monolith to service mesh");
        assertThat(created.markdownEn()).contains("Observability and governance");
        assertThat(created.hasEnglishContent()).isTrue();
    }

    @Test
    void shouldRejectCustomArticleWhenTitleOrBodyIsBlank() {
        Fixture fixture = fixture(ArticleStatus.DRAFT);

        assertThatThrownBy(() -> fixture.service.createCustomArticle(ACTOR, "", "摘要", "正文", List.of(), List.of()))
                .isInstanceOfSatisfying(ApplicationException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("INVALID_ARGUMENT");
                    assertThat(exception.getMessage()).contains("文章标题不能为空");
                });

        assertThatThrownBy(() -> fixture.service.createCustomArticle(ACTOR, "标题", "摘要", "   ", List.of(), List.of()))
                .isInstanceOfSatisfying(ApplicationException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("INVALID_ARGUMENT");
                    assertThat(exception.getMessage()).contains("文章正文不能为空");
                });
    }

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
    void shouldReopenPublishedContentForExplicitRevision() {
        Fixture fixture = fixture(ArticleStatus.PUBLISHED);

        Article reopened = fixture.service.reopenArticle(ACTOR, fixture.articleId);

        assertThat(reopened.status()).isEqualTo(ArticleStatus.DRAFT);
        verify(fixture.audits).record(ACTOR, "ARTICLE_REVISION_OPENED", "ARTICLE",
                fixture.articleId, Map.of());
    }

    @Test
    void shouldSaveNewVersionAndConfirmInOneStep() {
        Fixture fixture = fixture(ArticleStatus.DRAFT);

        Article confirmed = fixture.service.updateAndConfirm(ACTOR, fixture.articleId, 1,
                "确认标题", "确认摘要", "确认正文内容足够长", List.of("个人"), List.of("个人"),
                "", "", "", List.of(), List.of());

        assertThat(confirmed.status()).isEqualTo(ArticleStatus.READY);
        assertThat(confirmed.title()).isEqualTo("确认标题");
        assertThat(confirmed.currentVersion()).isEqualTo(2);
        verify(fixture.audits).record(ACTOR, "ARTICLE_CONTENT_CONFIRMED", "ARTICLE",
                fixture.articleId, Map.of());
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
        when(articles.saveWithVersion(any(), any())).thenAnswer(invocation -> {
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
