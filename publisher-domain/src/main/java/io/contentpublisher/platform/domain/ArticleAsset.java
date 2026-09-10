package io.contentpublisher.platform.domain;

import java.time.Instant;
import java.util.UUID;

public record ArticleAsset(
        UUID id,
        String tenantId,
        UUID articleId,
        String originalFilename,
        String contentType,
        long byteSize,
        String createdBy,
        Instant createdAt) {
    public ArticleAsset {
        if (articleId == null) throw new IllegalArgumentException("配图必须关联文章");
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("配图文件名不能为空");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("配图类型不能为空");
        }
        if (byteSize <= 0) throw new IllegalArgumentException("配图大小必须大于零");
    }

    public String markdownUrl() {
        return "/articles/" + articleId + "/assets/" + id;
    }
}
