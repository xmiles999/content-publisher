package io.contentpublisher.platform.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("publisher.assets")
public record AssetProperties(Path directory) {
    public AssetProperties {
        directory = directory == null ? Path.of("/data/services/content-publisher/assets") : directory;
    }
}
