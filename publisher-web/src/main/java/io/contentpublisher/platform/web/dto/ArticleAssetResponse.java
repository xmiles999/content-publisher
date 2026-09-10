package io.contentpublisher.platform.web.dto;

import io.contentpublisher.platform.domain.ArticleAsset;

import java.time.Instant;
import java.util.UUID;

public record ArticleAssetResponse(UUID id, UUID articleId, String originalFilename, String contentType,
                                   long byteSize, String markdownUrl, Instant createdAt) {
    public static ArticleAssetResponse from(ArticleAsset asset) {
        return new ArticleAssetResponse(asset.id(), asset.articleId(), asset.originalFilename(), asset.contentType(),
                asset.byteSize(), asset.markdownUrl(), asset.createdAt());
    }
}
