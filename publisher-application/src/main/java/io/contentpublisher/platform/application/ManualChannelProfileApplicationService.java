package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.ManualChannelProfileRepository;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.ManualChannelProfile;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class ManualChannelProfileApplicationService {
    private static final int MAX_TAGS = 20;
    private static final int MAX_TAG_LENGTH = 60;

    private final ManualChannelProfileRepository profiles;
    private final Clock clock;

    public ManualChannelProfileApplicationService(ManualChannelProfileRepository profiles, Clock clock) {
        this.profiles = profiles;
        this.clock = clock;
    }

    public List<ManualChannelProfile> listProfiles(ActorContext actor) {
        return profiles.findAll(actor.tenantId());
    }

    public Optional<ManualChannelProfile> findProfile(ActorContext actor, ChannelType channelType) {
        requireManualChannel(channelType);
        return profiles.findByChannel(actor.tenantId(), channelType);
    }

    public boolean isEnabled(ActorContext actor, ChannelType channelType) {
        return findProfile(actor, channelType).map(ManualChannelProfile::enabled).orElse(true);
    }

    public ManualChannelProfile saveProfile(ActorContext actor, ChannelType channelType, int expectedVersion,
                                            boolean enabled, String accountAlias, List<String> defaultTags,
                                            String defaultSection, String notes, int sortOrder) {
        requireManualChannel(channelType);
        String normalizedAlias = optionalText(accountAlias, 120, "账号别名");
        List<String> normalizedTags = normalizeTags(defaultTags);
        String normalizedSection = optionalText(defaultSection, 200, "默认栏目");
        String normalizedNotes = optionalText(notes, 1000, "发布备注");
        if (sortOrder < 0 || sortOrder > 1000) {
            throw invalid("排序必须在 0 到 1000 之间");
        }

        ManualChannelProfile existing = profiles.findByChannel(actor.tenantId(), channelType).orElse(null);
        Instant now = clock.instant();
        if (existing == null) {
            if (expectedVersion != 0) throw versionConflict();
            return profiles.save(new ManualChannelProfile(UUID.randomUUID(), actor.tenantId(), channelType,
                    enabled, normalizedAlias, normalizedTags, normalizedSection, normalizedNotes, sortOrder,
                    null, 1, actor.subject(), actor.subject(), now, now));
        }
        requireVersion(existing, expectedVersion);
        if (sameProfile(existing, enabled, normalizedAlias, normalizedTags, normalizedSection, normalizedNotes,
                sortOrder)) {
            return existing;
        }
        ManualChannelProfile candidate = new ManualChannelProfile(existing.id(), existing.tenantId(),
                existing.channelType(), enabled, normalizedAlias, normalizedTags, normalizedSection, normalizedNotes,
                sortOrder, existing.loginConfirmedAt(), existing.version() + 1, existing.createdBy(),
                actor.subject(), existing.createdAt(), now);
        return profiles.updateIfVersionMatches(candidate, expectedVersion).orElseThrow(this::versionConflict);
    }

    public ManualChannelProfile confirmLogin(ActorContext actor, ChannelType channelType, int expectedVersion) {
        requireManualChannel(channelType);
        ManualChannelProfile existing = profiles.findByChannel(actor.tenantId(), channelType).orElse(null);
        Instant now = clock.instant();
        if (existing == null) {
            if (expectedVersion != 0) throw versionConflict();
            return profiles.save(new ManualChannelProfile(UUID.randomUUID(), actor.tenantId(), channelType,
                    true, null, List.of(), null, null, defaultSortOrder(channelType), now, 1,
                    actor.subject(), actor.subject(), now, now));
        }
        requireVersion(existing, expectedVersion);
        ManualChannelProfile candidate = new ManualChannelProfile(existing.id(), existing.tenantId(),
                existing.channelType(), existing.enabled(), existing.accountAlias(), existing.defaultTags(),
                existing.defaultSection(), existing.notes(), existing.sortOrder(), now, existing.version() + 1,
                existing.createdBy(), actor.subject(), existing.createdAt(), now);
        return profiles.updateIfVersionMatches(candidate, expectedVersion).orElseThrow(this::versionConflict);
    }

    public int defaultSortOrder(ChannelType channelType) {
        requireManualChannel(channelType);
        List<ChannelCatalog.ChannelDefinition> manual = configurableChannels();
        for (int index = 0; index < manual.size(); index++) {
            if (manual.get(index).type() == channelType) return (index + 1) * 10;
        }
        throw new ApplicationException("MANUAL_CHANNEL_UNAVAILABLE", "该渠道不支持人工平台配置");
    }

    public static List<ChannelCatalog.ChannelDefinition> configurableChannels() {
        return ChannelCatalog.manualOnly().stream()
                .filter(ChannelCatalog.ChannelDefinition::manualAvailable)
                .toList();
    }

    private void requireManualChannel(ChannelType channelType) {
        if (channelType == null) throw invalid("人工平台不能为空");
        ChannelCatalog.ChannelDefinition definition = ChannelCatalog.definition(channelType);
        if (definition.apiSupported() || !definition.manualAvailable()) {
            throw new ApplicationException("MANUAL_CHANNEL_UNAVAILABLE", "该渠道不支持人工平台配置");
        }
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            String value = tag.trim().replaceFirst("^#+", "");
            if (value.isBlank()) continue;
            if (value.length() > MAX_TAG_LENGTH) throw invalid("单个默认标签不能超过 60 个字符");
            normalized.add(value.toLowerCase(Locale.ROOT));
            if (normalized.size() > MAX_TAGS) throw invalid("默认标签不能超过 20 个");
        }
        return List.copyOf(new ArrayList<>(normalized));
    }

    private String optionalText(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw invalid(label + "不能超过 " + maxLength + " 个字符");
        return normalized;
    }

    private boolean sameProfile(ManualChannelProfile profile, boolean enabled, String accountAlias,
                                List<String> defaultTags, String defaultSection, String notes, int sortOrder) {
        return profile.enabled() == enabled
                && java.util.Objects.equals(profile.accountAlias(), accountAlias)
                && profile.defaultTags().equals(defaultTags)
                && java.util.Objects.equals(profile.defaultSection(), defaultSection)
                && java.util.Objects.equals(profile.notes(), notes)
                && profile.sortOrder() == sortOrder;
    }

    private void requireVersion(ManualChannelProfile profile, int expectedVersion) {
        if (expectedVersion < 1 || profile.version() != expectedVersion) throw versionConflict();
    }

    private ApplicationException invalid(String message) {
        return new ApplicationException("MANUAL_CHANNEL_PROFILE_INVALID", message);
    }

    private ApplicationException versionConflict() {
        return new ApplicationException("MANUAL_CHANNEL_PROFILE_VERSION_CONFLICT",
                "人工平台配置已被其他请求修改，请刷新后重试");
    }
}
