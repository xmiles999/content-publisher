package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.ArticleAssetRepository;
import io.contentpublisher.platform.application.port.ArticleAssetStore;
import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleAsset;
import io.contentpublisher.platform.domain.ArticleStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArticleAssetApplicationServiceTest {
    private static final ActorContext ACTOR = new ActorContext("personal", "owner");
    private static final byte[] PNG = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x00
    };

    @Test
    void shouldStorePngAndExposeMarkdownUrl() {
        Fixture fixture = fixture();

        ArticleAsset saved = fixture.service.upload(ACTOR, fixture.articleId, "封面.PNG", PNG);

        assertThat(saved.contentType()).isEqualTo("image/png");
        assertThat(saved.originalFilename()).endsWith(".png");
        assertThat(saved.markdownUrl()).isEqualTo("/articles/" + fixture.articleId + "/assets/" + saved.id());
        assertThat(fixture.service.content(ACTOR, fixture.articleId, saved.id())).isEqualTo(PNG);
    }

    @Test
    void shouldRejectUnsupportedBytes() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.upload(ACTOR, fixture.articleId, "note.txt", "hello".getBytes()))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.code()).isEqualTo("ARTICLE_ASSET_INVALID"));
    }

    private Fixture fixture() {
        ArticleRepository articles = mock(ArticleRepository.class);
        ArticleAssetRepository assets = mock(ArticleAssetRepository.class);
        ArticleAssetStore store = mock(ArticleAssetStore.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        UUID articleId = UUID.randomUUID();
        Article article = new Article(articleId, "personal", UUID.randomUUID(), null,
                "标题", "摘要", "正文", java.util.List.of(), "zh-CN", "rev", 1, ArticleStatus.DRAFT,
                "owner", "owner", Instant.parse("2026-08-10T12:00:00Z"), Instant.parse("2026-08-10T12:00:00Z"));
        when(articles.findArticleById("personal", articleId)).thenReturn(Optional.of(article));
        AtomicReference<byte[]> stored = new AtomicReference<>();
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(3));
            return null;
        }).when(store).write(any(), any(), any(), any());
        when(store.read(any(), any(), any())).thenAnswer(invocation -> stored.get());
        when(assets.countByArticleId("personal", articleId)).thenReturn(0L);
        when(assets.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assets.findById(any(), any(), any())).thenAnswer(invocation -> {
            UUID assetId = invocation.getArgument(2);
            if (assetId == null) return Optional.empty();
            return Optional.of(new ArticleAsset(assetId, "personal", articleId, "cover.png", "image/png",
                    PNG.length, "owner", Instant.parse("2026-08-10T12:00:00Z")));
        });
        ArticleAssetApplicationService service = new ArticleAssetApplicationService(articles, assets, store, audits);
        return new Fixture(service, articleId);
    }

    private record Fixture(ArticleAssetApplicationService service, UUID articleId) {
    }
}
