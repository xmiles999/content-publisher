package io.contentpublisher.platform.web.dto;

import io.contentpublisher.platform.application.ChannelCatalog;
import io.contentpublisher.platform.domain.ManualChannelProfile;

import java.time.Instant;
import java.util.List;

public record ManualChannelProfileView(
        ChannelCatalog.ChannelDefinition channel,
        boolean configured,
        boolean enabled,
        String accountAlias,
        List<String> defaultTags,
        String defaultTagsText,
        String defaultSection,
        String notes,
        int sortOrder,
        Instant loginConfirmedAt,
        int version,
        Instant updatedAt) {

    public static ManualChannelProfileView from(ChannelCatalog.ChannelDefinition channel,
                                                ManualChannelProfile profile,
                                                int defaultSortOrder) {
        if (profile == null) {
            return new ManualChannelProfileView(channel, false, true, null, List.of(), "", null, null,
                    defaultSortOrder, null, 0, null);
        }
        return new ManualChannelProfileView(channel, true, profile.enabled(), profile.accountAlias(),
                profile.defaultTags(), String.join(", ", profile.defaultTags()), profile.defaultSection(),
                profile.notes(), profile.sortOrder(), profile.loginConfirmedAt(), profile.version(),
                profile.updatedAt());
    }

    public String accountLabel() {
        return accountAlias == null ? "未填写账号别名" : accountAlias;
    }

    public String defaultSettingLabel() {
        if (defaultTags.isEmpty() && defaultSection == null) return "未设置默认标签或栏目";
        if (defaultSection == null) return defaultTags.size() + " 个默认标签";
        if (defaultTags.isEmpty()) return defaultSection;
        return defaultSection + " · " + defaultTags.size() + " 个标签";
    }
}
