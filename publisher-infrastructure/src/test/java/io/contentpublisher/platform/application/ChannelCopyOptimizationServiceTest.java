package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.ContentGenerator;
import io.contentpublisher.platform.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChannelCopyOptimizationServiceTest {
    private final ContentGenerator generator = mock(ContentGenerator.class);
    private final ChannelCopyOptimizationService service = new ChannelCopyOptimizationService(generator);

    private AdaptedContent source(ChannelType type, String title, String body) {
        return new AdaptedContent(type, ChannelCatalog.definition(type).contentFormat(), title, body,
                List.of("java"), ChannelCatalog.definition(type).characterLimit());
    }
    private void result(String title, String body) {
        when(generator.optimizeChannelCopy(anyString(), any(), any(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new ContentGenerator.ChannelCopy(title, body));
    }

    @Test void shouldAvoidAiWhenBothFieldsFitIncludingExactBoundary() {
        var source = source(ChannelType.XIAOHONGSHU, "题".repeat(20), "文".repeat(1000));
        assertThat(service.optimize("tenant", source)).isSameAs(source);
        verifyNoInteractions(generator);
    }

    @Test void shouldOptimizeOverflowAndKeepAnInLimitFieldUnchanged() {
        result("AI 不应改动的标题", "精简正文。\n\n#java");
        var source = source(ChannelType.XIAOHONGSHU, "原有标题", "正文".repeat(600));
        var optimized = service.optimize("tenant", source);
        assertThat(optimized.title()).isEqualTo("原有标题");
        assertThat(optimized.body()).isEqualTo("精简正文。\n\n#java");
        assertThat(optimized.tags()).containsExactly("java");
        assertThat(source.body()).hasSize(1200);
        verify(generator).optimizeChannelCopy("tenant", ChannelType.XIAOHONGSHU, ContentFormat.PLAIN_TEXT,
                "原有标题", "正文".repeat(600), 20, 1000);
    }

    @Test void shouldOnlyRewriteTitleWhenBodyFits() {
        result("精简标题", "无须修改的正文被错误改写");
        var optimized = service.optimize("tenant", source(ChannelType.TOUTIAO, "长标题".repeat(20), "原正文"));
        assertThat(optimized.title()).isEqualTo("精简标题");
        assertThat(optimized.body()).isEqualTo("原正文");
    }

    @Test void shouldKeepExistingLinksWhenAiChangesSurroundingPunctuation() {
        result("标题", "核心内容 https://example.com/reference");
        var optimized = service.optimize("tenant", source(ChannelType.XIAOHONGSHU, "标题",
                "原文".repeat(600) + "（https://example.com/reference）。"));
        assertThat(optimized.body()).isEqualTo("核心内容 https://example.com/reference");
    }

    @Test void shouldRejectOverflowEmptyAndNewLinksWithoutTruncatingOrRetrying() {
        for (String invalid : List.of("文".repeat(1001), "", "虚构链接 https://invented.example/new",
                "文" + " ".repeat(100_000), "\u0085\u3000")) {
            result("标题", invalid);
            assertThatThrownBy(() -> service.optimize("tenant", source(ChannelType.XIAOHONGSHU, "标题", "文".repeat(1100))))
                    .isInstanceOf(ApplicationException.class);
        }
        verify(generator, times(5)).optimizeChannelCopy(anyString(), any(), any(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test void shouldPropagateAiFailureAndReleaseSlot() {
        when(generator.optimizeChannelCopy(anyString(), any(), any(), anyString(), anyString(), anyInt(), anyInt()))
                .thenThrow(new ApplicationException("AI_REQUEST_FAILED", "服务不可用"));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.optimize("tenant", source(ChannelType.XIAOHONGSHU, "标题", "文".repeat(1100))))
                    .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.code()).isEqualTo("CHANNEL_COPY_OPTIMIZATION_FAILED"));
        }
    }

    @Test void shouldCountXChineseEmojiAndLinksWithoutWhitespace() {
        assertThat(ChannelTextLimits.bodyLength(ChannelType.X, "中😀 a\nhttps://example.com/" + "x".repeat(400))).isEqualTo(28);
        assertThat(ChannelTextLimits.fits(ChannelType.X, "标题", "中".repeat(140))).isTrue();
        assertThat(ChannelTextLimits.fits(ChannelType.X, "标题", "中".repeat(141))).isFalse();
        assertThat(ChannelTextLimits.bodyLength(ChannelType.XIAOHONGSHU, "😀中 a\n")).isEqualTo(3);
        assertThat(ChannelTextLimits.bodyLength(ChannelType.MASTODON, "中😀 a\nhttps://t.co/x")).isEqualTo(26);
        assertThat(ChannelTextLimits.bodyLength(ChannelType.X, "https://example.com/。" )).isEqualTo(25);
        assertThat(ChannelTextLimits.bodyLength(ChannelType.MASTODON, "https://example.com/。" )).isEqualTo(24);
        assertThat(ChannelTextLimits.bodyLength(ChannelType.X, "https://example.com/。下一句")).isEqualTo(31);
    }

    @Test void shouldExcludeUnicodeWhitespaceWithoutJoiningUrlsOrDroppingOtherCharacters() {
        String whitespace = "\t\n\u000b\f\r \u0085\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005"
                + "\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000";
        assertThat(ChannelTextLimits.textLength(whitespace)).isZero();
        assertThat(ChannelTextLimits.textLength("中😀a" + whitespace)).isEqualTo(3);
        assertThat(ChannelTextLimits.textLength("#标签，。\u200b")).isEqualTo(6);
        for (int cp : whitespace.codePoints().toArray()) {
            String text = "https://example.com/one" + Character.toString(cp) + "https://example.com/two";
            assertThat(ChannelTextLimits.bodyLength(ChannelType.X, text)).isEqualTo(46);
            assertThat(ChannelTextLimits.bodyLength(ChannelType.MASTODON, text)).isEqualTo(46);
        }
    }

    @Test void shouldKeepFormattedCopyAtExactLimitWithoutCallingAi() {
        var source = source(ChannelType.XIAOHONGSHU, "题 ".repeat(20), "文\r\n\t ".repeat(1000));
        assertThat(service.optimize("tenant", source)).isSameAs(source);
        verifyNoInteractions(generator);
    }

    @Test void shouldPreserveInLimitFormattedFieldAndAcceptFormattedAiOutput() {
        String title = "题 ".repeat(20);
        String body = "文\n\n".repeat(1000);
        result("模型不应改写标题", body);
        var optimized = service.optimize("tenant", source(ChannelType.XIAOHONGSHU, title, "文".repeat(1100)));
        assertThat(optimized.title()).isEqualTo(title);
        assertThat(optimized.body()).isEqualTo(body);
    }

    @Test void shouldRejectWhitespaceOnlyCopyAndKeepRawInputSizeCeiling() {
        for (var input : List.of(source(ChannelType.XIAOHONGSHU, "\u00a0", "正文"),
                source(ChannelType.XIAOHONGSHU, "标题", "\u0085\u3000"),
                source(ChannelType.XIAOHONGSHU, "标题", "文" + " ".repeat(100_000)))) {
            assertThatThrownBy(() -> service.optimize("tenant", input))
                    .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.code()).isEqualTo("CHANNEL_COPY_INVALID"));
        }
        verifyNoInteractions(generator);
    }

    @Test void shouldBoundSimultaneousAiRequests() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(generator.optimizeChannelCopy(anyString(), any(), any(), anyString(), anyString(), anyInt(), anyInt())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new ContentGenerator.ChannelCopy("标题", "正文");
        });
        var executor = Executors.newFixedThreadPool(2);
        var source = source(ChannelType.XIAOHONGSHU, "标题", "文".repeat(1100));
        try {
            var a = executor.submit(() -> service.optimize("one", source));
            var b = executor.submit(() -> service.optimize("two", source));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.optimize("three", source)).isInstanceOfSatisfying(ApplicationException.class,
                    e -> assertThat(e.code()).isEqualTo("CHANNEL_COPY_BUSY"));
            release.countDown();
            assertThat(a.get(5, TimeUnit.SECONDS).body()).isEqualTo("正文");
            assertThat(b.get(5, TimeUnit.SECONDS).body()).isEqualTo("正文");
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
