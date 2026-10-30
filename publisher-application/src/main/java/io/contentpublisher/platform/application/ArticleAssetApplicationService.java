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

    /** Bundle owned files only; no remote fetch or exposure of private asset endpoints. */
    public byte[] exportBundle(ActorContext actor, UUID articleId) {
        var article = articles.findArticleById(actor.tenantId(), articleId)
                .orElseThrow(() -> new ApplicationException("ARTICLE_NOT_FOUND", "文章不存在"));
        var images = list(actor, articleId);
        long total = images.stream().mapToLong(ArticleAsset::byteSize).sum();
        if (total > 25L * 1024 * 1024) {
            throw new ApplicationException("ARTICLE_ASSET_TOO_LARGE", "配图合计超过 25MB，请从编辑页逐张保存");
        }
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(bytes, java.nio.charset.StandardCharsets.UTF_8)) {
                String markdown = article.markdown();
                String english = article.markdownEn();
                StringBuilder manifest = new StringBuilder("文章：").append(article.title())
                        .append("\n版本：").append(article.currentVersion())
                        .append("\n图片需要上传到目标平台；请将正文中的 assets/ 地址替换为平台返回的图片地址。\n\n");
                for (ArticleAsset image : images) {
                    String path = "assets/" + image.id() + "-" + image.originalFilename();
                    byte[] content = store.read(actor.tenantId(), articleId, image.id());
                    if (content.length != image.byteSize()) throw new ApplicationException(
                            "ARTICLE_ASSET_INVALID", "配图文件与元数据不一致，请检查备份或重新上传");
                    addEntry(zip, path, content);
                    markdown = markdown.replace("/api/v1" + image.markdownUrl(), path).replace(image.markdownUrl(), path);
                    english = english.replace("/api/v1" + image.markdownUrl(), path).replace(image.markdownUrl(), path);
                    manifest.append(image.originalFilename()).append(" -> ").append(path).append("\n");
                }
                addEntry(zip, "article.md", markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!english.isBlank()) addEntry(zip, "article.en.md", english.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                addEntry(zip, "发布说明.txt", manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return bytes.toByteArray();
        } catch (java.io.IOException ex) {
            throw new ApplicationException("ARTICLE_ASSET_INVALID", "生成文章配图包失败，请重试", ex);
        }
    }

    private static void addEntry(java.util.zip.ZipOutputStream zip, String name, byte[] content) throws java.io.IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
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
