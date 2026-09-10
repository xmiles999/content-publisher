package io.contentpublisher.platform.infrastructure.assets;

import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.port.ArticleAssetStore;
import io.contentpublisher.platform.infrastructure.config.AssetProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class FilesystemArticleAssetStore implements ArticleAssetStore {
    private final Path root;

    public FilesystemArticleAssetStore(AssetProperties properties) {
        this.root = properties.directory().toAbsolutePath().normalize();
    }

    @Override
    public void write(String tenantId, UUID articleId, UUID assetId, byte[] content) {
        Path file = path(tenantId, articleId, assetId);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        } catch (IOException exception) {
            throw new ApplicationException("ARTICLE_ASSET_STORE_FAILED", "配图保存失败", exception);
        }
    }

    @Override
    public byte[] read(String tenantId, UUID articleId, UUID assetId) {
        Path file = path(tenantId, articleId, assetId);
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new ApplicationException("ARTICLE_ASSET_NOT_FOUND", "配图文件不存在", exception);
        }
    }

    private Path path(String tenantId, UUID articleId, UUID assetId) {
        String safeTenant = tenantId.replaceAll("[^A-Za-z0-9._-]", "_");
        Path resolved = root.resolve(safeTenant).resolve(articleId.toString()).resolve(assetId.toString()).normalize();
        if (!resolved.startsWith(root)) {
            throw new ApplicationException("ARTICLE_ASSET_INVALID", "配图存储路径无效");
        }
        return resolved;
    }
}
