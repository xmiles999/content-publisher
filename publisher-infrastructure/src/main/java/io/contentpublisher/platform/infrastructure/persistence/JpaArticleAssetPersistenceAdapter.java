package io.contentpublisher.platform.infrastructure.persistence;

import io.contentpublisher.platform.application.port.ArticleAssetRepository;
import io.contentpublisher.platform.domain.ArticleAsset;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public class JpaArticleAssetPersistenceAdapter implements ArticleAssetRepository {
    private final ArticleAssetJpaRepository assets;

    public JpaArticleAssetPersistenceAdapter(ArticleAssetJpaRepository assets) {
        this.assets = assets;
    }

    @Override
    public ArticleAsset save(ArticleAsset asset) {
        ArticleAssetEntity entity = new ArticleAssetEntity();
        entity.id = asset.id();
        entity.tenantId = asset.tenantId();
        entity.articleId = asset.articleId();
        entity.originalFilename = asset.originalFilename();
        entity.contentType = asset.contentType();
        entity.byteSize = asset.byteSize();
        entity.createdBy = asset.createdBy();
        entity.createdAt = asset.createdAt();
        return toDomain(assets.save(entity));
    }

    @Override
    public Optional<ArticleAsset> findById(String tenantId, UUID articleId, UUID assetId) {
        return assets.findByTenantIdAndArticleIdAndId(tenantId, articleId, assetId).map(this::toDomain);
    }

    @Override
    public List<ArticleAsset> findByArticleId(String tenantId, UUID articleId) {
        return assets.findByTenantIdAndArticleIdOrderByCreatedAtDesc(tenantId, articleId).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public long countByArticleId(String tenantId, UUID articleId) {
        return assets.countByTenantIdAndArticleId(tenantId, articleId);
    }

    private ArticleAsset toDomain(ArticleAssetEntity entity) {
        return new ArticleAsset(entity.id, entity.tenantId, entity.articleId, entity.originalFilename,
                entity.contentType, entity.byteSize, entity.createdBy, entity.createdAt);
    }
}
