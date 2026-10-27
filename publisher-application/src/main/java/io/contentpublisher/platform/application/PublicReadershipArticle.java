package io.contentpublisher.platform.application;

import io.contentpublisher.platform.domain.ChannelType;

import java.net.URI;
import java.util.Optional;

/** Extracts an article identifier only; the submitted URL is never used as a request target. */
public final class PublicReadershipArticle {
    private PublicReadershipArticle() {}

    public static Optional<String> articleId(ChannelType channel, String url) {
        if (channel != ChannelType.JUEJIN || url == null) return Optional.empty();
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"juejin.cn".equalsIgnoreCase(uri.getHost())
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !uri.getRawPath().matches("/post/[1-9][0-9]{0,18}/?")) return Optional.empty();
            return Optional.of(uri.getRawPath().split("/")[2]);
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
}
