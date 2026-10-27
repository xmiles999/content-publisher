package io.contentpublisher.platform.infrastructure.persistence;

import io.contentpublisher.platform.application.port.ManualChannelProfileRepository;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.ManualChannelProfile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import io.contentpublisher.platform.application.ApplicationException;

@Repository
@Transactional
public class JpaManualChannelProfilePersistenceAdapter implements ManualChannelProfileRepository {
    private final ManualChannelProfileJpaRepository profiles;
    private final JpaDomainMapper mapper;

    public JpaManualChannelProfilePersistenceAdapter(ManualChannelProfileJpaRepository profiles,
                                                     JpaDomainMapper mapper) {
        this.profiles = profiles;
        this.mapper = mapper;
    }

    @Override
    public ManualChannelProfile save(ManualChannelProfile profile) {
        ManualChannelProfileEntity entity = new ManualChannelProfileEntity();
        entity.id = profile.id();
        entity.tenantId = profile.tenantId();
        entity.channelType = profile.channelType();
        entity.enabled = profile.enabled();
        entity.accountAlias = profile.accountAlias();
        entity.defaultTagsJson = mapper.stringsJson(profile.defaultTags());
        entity.defaultSection = profile.defaultSection();
        entity.notes = profile.notes();
        entity.sortOrder = profile.sortOrder();
        entity.loginConfirmedAt = profile.loginConfirmedAt();
        entity.profileVersion = profile.version();
        entity.createdBy = profile.createdBy();
        entity.updatedBy = profile.updatedBy();
        entity.createdAt = profile.createdAt();
        entity.updatedAt = profile.updatedAt();
        return mapper.manualChannelProfile(profiles.save(entity));
    }

    @Override
    public Optional<ManualChannelProfile> updateIfVersionMatches(ManualChannelProfile profile,
                                                                 int expectedVersion) {
        int updated = profiles.updateIfVersionMatches(profile.tenantId(), profile.channelType(), profile.enabled(),
                profile.accountAlias(), mapper.stringsJson(profile.defaultTags()), profile.defaultSection(),
                profile.notes(), profile.sortOrder(), profile.loginConfirmedAt(), expectedVersion,
                profile.version(), profile.updatedBy(), profile.updatedAt());
        if (updated == 0) return Optional.empty();
        return profiles.findByTenantIdAndChannelType(profile.tenantId(), profile.channelType())
                .map(mapper::manualChannelProfile);
    }

    @Override
    public void saveAllIfVersionsMatch(List<ManualChannelProfile> candidates,
                                      Map<ChannelType, Integer> expectedVersions) {
        try {
            for (ManualChannelProfile candidate : candidates) {
                int expected = expectedVersions.get(candidate.channelType());
                if (expected == 0) {
                    if (profiles.findByTenantIdAndChannelType(candidate.tenantId(), candidate.channelType()).isPresent()) {
                        throw selectionConflict();
                    }
                    save(candidate);
                } else if (updateIfVersionMatches(candidate, expected).isEmpty()) {
                    throw selectionConflict();
                }
            }
            profiles.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw selectionConflict();
        }
    }

    private ApplicationException selectionConflict() {
        return new ApplicationException("MANUAL_CHANNEL_PROFILE_VERSION_CONFLICT", "渠道配置已变化，请刷新后重试；本次选择未保存");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ManualChannelProfile> findByChannel(String tenantId, ChannelType channelType) {
        return profiles.findByTenantIdAndChannelType(tenantId, channelType).map(mapper::manualChannelProfile);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ManualChannelProfile> findAll(String tenantId) {
        return profiles.findAllByTenantIdOrderBySortOrderAscChannelTypeAsc(tenantId).stream()
                .map(mapper::manualChannelProfile).toList();
    }
}
