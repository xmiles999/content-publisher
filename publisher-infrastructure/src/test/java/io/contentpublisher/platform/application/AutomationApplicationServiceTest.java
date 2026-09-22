package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.application.port.AutomationRepository;
import io.contentpublisher.platform.application.port.WebhookEndpointPolicy;
import io.contentpublisher.platform.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AutomationApplicationServiceTest {
    private final ActorContext actor = new ActorContext("personal", "owner");
    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final AutomationRepository repository = mock(AutomationRepository.class);
    private final AutomationApplicationService service = new AutomationApplicationService(
            repository, articles, mock(AuditRecorder.class), mock(WebhookEndpointPolicy.class), Clock.systemUTC());

    @Test
    void acceptsTheSameBilingualBodyLimitAsFormalVersions() {
        Article article = article(ArticleStatus.DRAFT);
        when(repository.saveDraft(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String body = "文".repeat(ArticleLimits.MARKDOWN);
        var saved = service.saveDraft(actor, article.id(), 1, content(body));
        assertThat(saved.markdown()).hasSize(ArticleLimits.MARKDOWN);
        assertThat(saved.markdownEn()).hasSize(ArticleLimits.MARKDOWN);
    }

    @Test
    void rejectsOversizedDraftWithoutWriting() {
        Article article = article(ArticleStatus.DRAFT);
        assertThatThrownBy(() -> service.saveDraft(actor, article.id(), 1,
                content("a".repeat(ArticleLimits.MARKDOWN + 1))))
                .isInstanceOf(ApplicationException.class);
        verify(repository, never()).saveDraft(any());
    }

    @Test
    void rejectsDraftWritesAfterConfirmation() {
        for (ArticleStatus status : List.of(ArticleStatus.READY, ArticleStatus.APPROVED, ArticleStatus.PUBLISHED)) {
            Article article = article(status);
            assertThatThrownBy(() -> service.saveDraft(actor, article.id(), 1, content("正文")))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            error -> assertThat(error.code()).isEqualTo("ARTICLE_STATE_CONFLICT"));
        }
        verify(repository, never()).saveDraft(any());
    }

    @Test
    void rejectsStaleVersionWithoutWriting() {
        Article article = article(ArticleStatus.DRAFT);
        assertThatThrownBy(() -> service.saveDraft(actor, article.id(), 2, content("正文")))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.code()).isEqualTo("DRAFT_VERSION_CONFLICT"));
        verify(repository, never()).saveDraft(any());
    }

    @Test
    void neverSilentlyTruncatesDraftTags() {
        Article article = article(ArticleStatus.DRAFT);
        var content = new AutomationApplicationService.DraftContent("标题", "摘要", "正文",
                java.util.stream.IntStream.range(0, 16).mapToObj(i -> "tag" + i).toList(),
                List.of(), "", "", "", List.of(), List.of());
        assertThatThrownBy(() -> service.saveDraft(actor, article.id(), 1, content))
                .isInstanceOf(ApplicationException.class).hasMessageContaining("标签最多 15");
        verify(repository, never()).saveDraft(any());
    }

    private AutomationApplicationService.DraftContent content(String body) {
        return new AutomationApplicationService.DraftContent("标题", "摘要", body, List.of(), List.of(),
                "Title", "Summary", body, List.of(), List.of());
    }

    private Article article(ArticleStatus status) {
        Instant now = Instant.now();
        Article article = new Article(UUID.randomUUID(), actor.tenantId(),
                ContentOrigin.custom("标题", "摘要", List.of()), null, "标题", "摘要", "正文",
                List.of(), List.of(), "", "", "", List.of(), List.of(),
                "zh-CN", "test", 1, status, actor.subject(), actor.subject(), now, now);
        when(articles.findArticleById(actor.tenantId(), article.id())).thenReturn(Optional.of(article));
        return article;
    }
}
