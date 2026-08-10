package io.contentpublisher.platform.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ManualChannelProfile(
        UUID id,
        String tenantId,
        ChannelType channelType,
        boolean enabled,
        String accountAlias,
        List<String> defaultTags,
        String defaultSection,
        String notes,
        int sortOrder,
        Instant loginConfirmedAt,
        int version,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    public ManualChannelProfile {
        defaultTags = defaultTags == null ? List.of() : List.copyOf(defaultTags);
        if (sortOrder < 0 || sortOrder > 1000) {
            throw new IllegalArgumentException("人工平台排序必须在 0 到 1000 之间");
        }
        if (version < 1) {
            throw new IllegalArgumentException("人工平台配置版本必须大于零");
        }
    }
}
