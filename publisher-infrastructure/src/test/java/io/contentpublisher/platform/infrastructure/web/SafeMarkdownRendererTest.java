package io.contentpublisher.platform.infrastructure.web;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SafeMarkdownRendererTest {
    private final SafeMarkdownRenderer renderer = new SafeMarkdownRenderer();

    @ParameterizedTest
    @ValueSource(strings = {
            "**• 删除 ADS：**数仓只",
            "前文**删除 ADS：**数仓只",
            "- **• 删除 ADS：**数仓只",
            "> **• 删除 ADS：**数仓只",
            "## **• 删除 ADS：**数仓只",
            "**注意！**不要删除",
            "**说明（含 ADS）：**数仓只"
    })
    void rendersBoldEndingWithChinesePunctuationBeforeChineseText(String markdown) {
        var html = Jsoup.parse(renderer.render(markdown));
        assertThat(html.select("strong")).hasSize(1);
        assertThat(html.body().text()).doesNotContain("**");
    }

    @Test
    void rendersMultipleLabelsWithoutAddingWhitespace() {
        var html = Jsoup.parse(renderer.render("**• 删除 ADS：**数仓只保留明细。**原因：**减少重复。"));
        assertThat(html.select("strong").eachText()).containsExactly("• 删除 ADS：", "原因：");
        assertThat(html.body().text()).isEqualTo("• 删除 ADS：数仓只保留明细。原因：减少重复。");
    }

    @Test
    void preservesCodeBlocksAndInlineCodeAsLiteralSource() {
        String label = "**• 删除 ADS：**数仓只";
        var html = Jsoup.parse(renderer.render("```markdown\n" + label + "\n```\n\n    " + label
                + "\n\n`" + label + "`"));
        assertThat(html.select("strong")).isEmpty();
        assertThat(html.select("code").eachText()).containsExactly(label, label, label);
    }

    @Test
    void preservesEscapedMarkersAndUnclosedBold() {
        var html = Jsoup.parse(renderer.render("\\*\\*• 删除 ADS：\\*\\*数仓只\n\n**没有结束：数仓只"));
        assertThat(html.select("strong")).isEmpty();
        assertThat(html.body().text()).isEqualTo("**• 删除 ADS：**数仓只 **没有结束：数仓只");
    }

    @Test
    void preservesStandardMarkdownFormatting() {
        var html = Jsoup.parse(renderer.render("**普通粗体**正文 *斜体* [链接](https://example.com)"));
        assertThat(html.select("strong").text()).isEqualTo("普通粗体");
        assertThat(html.select("em").text()).isEqualTo("斜体");
        assertThat(html.select("a").attr("href")).isEqualTo("https://example.com");
    }

    @Test
    void escapesHtmlAndRemovesUnsafeLinks() {
        var html = Jsoup.parse(renderer.render("**提示：**正文<script>alert(1)</script> [链接](javascript:alert(1))"));
        assertThat(html.select("strong").text()).isEqualTo("提示：");
        assertThat(html.select("script, a[href^=javascript]")).isEmpty();
        assertThat(html.body().text()).contains("<script>alert(1)</script>");
    }
}
