package io.contentpublisher.platform.infrastructure.web;

import io.contentpublisher.platform.application.port.MarkdownRenderer;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class SafeMarkdownRenderer implements MarkdownRenderer {
    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().escapeHtml(true).build();
    private final Safelist safelist = Safelist.relaxed()
            .addTags("h1", "h2", "h3", "h4", "h5", "h6", "pre", "code")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https")
            .addAttributes("a", "rel", "target")
            .preserveRelativeLinks(true);

    @Override
    public String render(String markdown) {
        String source = markdown == null ? "" : markdown;
        String html = renderer.render(parser.parse(source));
        return Jsoup.clean(html, safelist);
    }
}
