package io.contentpublisher.platform.application.port;

import io.contentpublisher.platform.domain.ChannelAccount;
import io.contentpublisher.platform.domain.ChannelVerificationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ChannelAccountRepository {
    ChannelAccount save(ChannelAccount account);

    Optional<ChannelAccount> updateIfVersionMatches(ChannelAccount account, int expectedVersion);
    Optional<ChannelAccount> updateVerification(String tenantId, UUID accountId, ChannelVerificationStatus status,
                                                String message, Instant checkedAt);
    boolean softDeleteIfVersionMatches(String tenantId, UUID accountId, int expectedVersion,
                                       String deletedBy, Instant deletedAt);
    Optional<ChannelAccount> findChannelAccountById(String tenantId, UUID id);
    Optional<ChannelAccount> findChannelAccountByIdempotencyKey(String tenantId, String idempotencyKey);
    List<ChannelAccount> findAll(String tenantId);
    Map<UUID, String> findDisplayNamesForPublicationHistory(String tenantId);
    long countAll(String tenantId);
}
