package io.contentpublisher.platform.application.port;

import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.ManualChannelProfile;

import java.util.List;
import java.util.Optional;
import java.util.Map;

public interface ManualChannelProfileRepository {
    ManualChannelProfile save(ManualChannelProfile profile);
    Optional<ManualChannelProfile> updateIfVersionMatches(ManualChannelProfile profile, int expectedVersion);
    Optional<ManualChannelProfile> findByChannel(String tenantId, ChannelType channelType);
    List<ManualChannelProfile> findAll(String tenantId);
    void saveAllIfVersionsMatch(List<ManualChannelProfile> profiles, Map<ChannelType, Integer> expectedVersions);
}
