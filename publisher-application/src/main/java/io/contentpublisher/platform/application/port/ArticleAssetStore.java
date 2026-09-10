package io.contentpublisher.platform.application.port;

import java.util.UUID;

public interface ArticleAssetStore {
    void write(String tenantId, UUID articleId, UUID assetId, byte[] content);

    byte[] read(String tenantId, UUID articleId, UUID assetId);
}
