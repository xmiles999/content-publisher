package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.ArticleAssetRepository;
import io.contentpublisher.platform.application.port.ArticleAssetStore;
import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.ArticleAsset;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ArticleAssetApplicationService {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_ASSETS_PER_ARTICLE = 20;

    private final ArticleRepository articles;
    private final ArticleAssetRepository assets;
    private final ArticleAssetStore store;
    private final AuditRecorder auditRecorder;

    public ArticleAssetApplicationService(ArticleRepository articles, ArticleAssetRepository assets,
                                          ArticleAssetStore store, AuditRecorder auditRecorder) {
        this.articles = articles;
        this.assets = assets;
        this.store = store;
        this.auditRecorder = auditRecorder;
    }

    public ArticleAsset upload(ActorContext actor, UUID articleId, String originalFilename, byte[] content) {
        articles.findArticleById(actor.tenantId(), articleId)
                .orElseThrow(() -> new ApplicationException("ARTICLE_NOT_FOUND", "文章不存在"));
        if (content == null || content.length == 0) {
            throw new ApplicationException("ARTICLE_ASSET_INVALID", "配图内容不能为空");
        }
        if (content.length > MAX_BYTES) {
            throw new ApplicationException("ARTICLE_ASSET_TOO_LARGE", "配图不能超过 5MB");
        }
        DetectedImage detected = detect(content);
        if (assets.countByArticleId(actor.tenantId(), articleId) >= MAX_ASSETS_PER_ARTICLE) {
            throw new ApplicationException("ARTICLE_ASSET_LIMIT", "单篇文章最多 20 张配图");
        }
        UUID assetId = UUID.randomUUID();
        store.write(actor.tenantId(), articleId, assetId, content);
        ArticleAsset saved = assets.save(new ArticleAsset(assetId, actor.tenantId(), articleId,
                sanitizeFilename(originalFilename, detected.extension()), detected.contentType(), content.length,
                actor.subject(), java.time.Instant.now()));
        auditRecorder.record(actor, "ARTICLE_ASSET_UPLOADED", "ARTICLE_ASSET", assetId,
                Map.of("articleId", articleId.toString(), "contentType", saved.contentType(),
                        "bytes", Long.toString(saved.byteSize())));
        return saved;
    }

    public ArticleAsset get(ActorContext actor, UUID articleId, UUID assetId) {
        return assets.findById(actor.tenantId(), articleId, assetId)
                .orElseThrow(() -> new ApplicationException("ARTICLE_ASSET_NOT_FOUND", "配图不存在"));
    }

    public byte[] content(ActorContext actor, UUID articleId, UUID assetId) {
        ArticleAsset asset = get(actor, articleId, assetId);
        return store.read(asset.tenantId(), asset.articleId(), asset.id());
    }

    public List<ArticleAsset> list(ActorContext actor, UUID articleId) {
        articles.findArticleById(actor.tenantId(), articleId)
                .orElseThrow(() -> new ApplicationException("ARTICLE_NOT_FOUND", "文章不存在"));
        return assets.findByArticleId(actor.tenantId(), articleId);
    }

    static DetectedImage detect(byte[] content) {
        if (content.length >= 3 && content[0] == (byte) 0xFF && content[1] == (byte) 0xD8 && content[2] == (byte) 0xFF) {
            return DetectedImage.JPEG;
        }
        if (content.length >= 8 && content[0] == (byte) 0x89 && content[1] == 0x50 && content[2] == 0x4E
                && content[3] == 0x47) {
            return DetectedImage.PNG;
        }
        if (content.length >= 6 && content[0] == 'G' && content[1] == 'I' && content[2] == 'F') {
            return DetectedImage.GIF;
        }
        if (content.length >= 12 && content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return DetectedImage.WEBP;
        }
        throw new ApplicationException("ARTICLE_ASSET_INVALID", "只支持 JPEG、PNG、GIF 或 WebP 配图");
    }

    private String sanitizeFilename(String originalFilename, String extension) {
        String name = originalFilename == null ? "image" : originalFilename.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("[^\\p{L}\\p{N}._-]+", "-").replaceAll("^-+|-+$", "");
        if (name.isBlank()) name = "image";
        if (name.length() > 80) name = name.substring(0, 80);
        return name + "." + extension;
    }

    enum DetectedImage {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        GIF("image/gif", "gif"),
        WEBP("image/webp", "webp");

        private final String contentType;
        private final String extension;

        DetectedImage(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        String contentType() {
            return contentType;
        }

        String extension() {
            return extension;
        }
    }
}
