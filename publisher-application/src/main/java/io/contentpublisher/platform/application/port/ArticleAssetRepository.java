package io.contentpublisher.platform.application.port;

import io.contentpublisher.platform.domain.ArticleAsset;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArticleAssetRepository {
    ArticleAsset save(ArticleAsset asset);

    Optional<ArticleAsset> findById(String tenantId, UUID articleId, UUID assetId);

    List<ArticleAsset> findByArticleId(String tenantId, UUID articleId);

    long countByArticleId(String tenantId, UUID articleId);
}
