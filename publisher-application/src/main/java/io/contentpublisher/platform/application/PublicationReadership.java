package io.contentpublisher.platform.application;

import java.time.Instant;
import java.util.UUID;

/** A latest snapshot for one actual publication, not an aggregate across channels. */
public record PublicationReadership(String tenantId, UUID articleId, PublicationMethod method, UUID publicationId,
                                   Long views, Long uniqueReaders, String source, Instant recordedAt,
                                   String recordedBy, Instant lastSyncAt, String syncError, int version) {
    public String key() { return method.name() + ":" + publicationId; }
    public boolean hasCounts() { return views != null || uniqueReaders != null; }
}
