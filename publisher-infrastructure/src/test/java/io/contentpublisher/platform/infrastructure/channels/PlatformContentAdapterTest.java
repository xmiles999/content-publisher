package io.contentpublisher.platform.infrastructure.channels;

import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.PlatformContentAdapter;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.ContentOrigin;
import io.contentpublisher.platform.domain.ContentFormat;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformContentAdapterTest {
    private final PlatformContentAdapter adapter = new PlatformContentAdapter();

    @Test
    void shouldKeepMarkdownForTechnicalCommunities() {
        var content = adapter.adapt(article(), ChannelType.JUEJIN, "https://example.com/source");

        assertThat(content.format()).isEqualTo(ContentFormat.MARKDOWN);
        assertThat(content.body()).contains("## 核心能力", "原文链接：https://example.com/source");
    }

    @Test
    void shouldCreatePlainTextAndHashtagsForXiaohongshu() {
        var content = adapter.adapt(article(), ChannelType.XIAOHONGSHU, null);

        assertThat(content.format()).isEqualTo(ContentFormat.PLAIN_TEXT);
        assertThat(content.title().codePointCount(0, content.title().length())).isLessThanOrEqualTo(20);
        assertThat(content.body()).doesNotContain("##", "**").contains("#java", "#内容分发");
    }

    @Test
    void shouldKeepFullTitleAndBodyForManualEditing() {
        String title = "不会截断的平台标题".repeat(8);
        String summary = "需要保留的正文内容".repeat(180);
        Article source = article(title, summary, "Manual publishing title", "Manual publishing body");

        var content = adapter.adaptForManual(source, ChannelType.XIAOHONGSHU, null);

        assertThat(content.title()).isEqualTo(title);
        assertThat(content.body()).contains(summary);
        assertThat(content.body().codePointCount(0, content.body().length())).isGreaterThan(1_000);
        assertThat(content.body()).doesNotEndWith("…");
    }

    @Test
    void shouldRespectTwitterUnicodeLimit() {
        Article source = article("技术平台".repeat(100), "能力摘要".repeat(100),
                "Tech Platform ".repeat(100), "Capability summary ".repeat(100));

        var generator = org.mockito.Mockito.mock(io.contentpublisher.platform.application.port.ContentGenerator.class);
        org.mockito.Mockito.when(generator.optimizeChannelCopy(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new io.contentpublisher.platform.application.port.ContentGenerator.ChannelCopy(
                        "精简标题", "技术平台摘要\n\nhttps://example.com/source"));
        var optimizing = new PlatformContentAdapter(new io.contentpublisher.platform.application.ChannelCopyOptimizationService(generator));
        var content = optimizing.adapt(source, ChannelType.X, "https://example.com/source");

        assertThat(content.format()).isEqualTo(ContentFormat.SHORT_TEXT);
        assertThat(content.body().codePointCount(0, content.body().length())).isLessThanOrEqualTo(280);
        assertThat(content.body()).contains("https://example.com/source");
    }

    @Test
    void shouldUseEnglishOnlyWhenExplicitlySelectedForManualPublishing() {
        var content = adapter.adaptForManual(article(), ChannelType.DEV, "https://example.com/source", "en");

        assertThat(content.title()).isEqualTo("Enterprise Content Platform");
        assertThat(content.body()).contains("## Core Capabilities").doesNotContain("核心能力");
    }

    @Test
    void shouldUseChineseSourceLabelForDomesticChannels() {
        var content = adapter.adapt(article(), ChannelType.CSDN, "https://example.com/source");

        assertThat(content.body()).contains("原文链接：https://example.com/source").doesNotContain("Source:");
    }

    @Test
    void shouldAllowOriginalLanguageOnAnyPlatformAndRejectOnlyExplicitMissingTranslation() {
        Article withoutEnglish = article("企业级内容发布平台", "面向开发团队的多平台内容分发能力", "", "");

        assertThat(adapter.adapt(withoutEnglish, ChannelType.MEDIUM, null).body()).contains("核心能力");
        assertThat(adapter.adapt(withoutEnglish, ChannelType.WORDPRESS, null).title()).isEqualTo("企业级内容发布平台");
        assertThat(adapter.adaptForManual(article(), ChannelType.DEV, null).body()).contains("核心能力");
        assertThatThrownBy(() -> adapter.adaptForManual(withoutEnglish, ChannelType.MEDIUM, null, "en"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("英文");
        assertThatThrownBy(() -> adapter.adaptForManual(article(), ChannelType.CSDN, null, "invalid"))
                .isInstanceOf(ApplicationException.class).hasMessageContaining("请选择");
    }

    private Article article() {
        return article("企业级内容发布平台", "面向开发团队的多平台内容分发能力",
                "Enterprise Content Platform", "Multi-platform content distribution for engineering teams");
    }

    private Article article(String title, String summary, String titleEn, String summaryEn) {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        String markdownEn = titleEn.isEmpty() ? "" : "## Core Capabilities\n\nSupports **Markdown** and [project link](https://example.com).";
        return new Article(UUID.randomUUID(), "tenant", ContentOrigin.git(UUID.randomUUID()), UUID.randomUUID(), title, summary,
                "## 核心能力\n\n支持 **Markdown** 与 [项目地址](https://example.com)。",
                List.of("Java", "内容分发"), List.of("Java 内容分发教程", "多平台发布方案"),
                titleEn, summaryEn, markdownEn, List.of("Java", "content-distribution"),
                List.of("Java content distribution tutorial", "multi-platform publishing"),
                "zh-CN", "a".repeat(40), 1, ArticleStatus.APPROVED,
                "editor", "admin", now, now);
    }
}
