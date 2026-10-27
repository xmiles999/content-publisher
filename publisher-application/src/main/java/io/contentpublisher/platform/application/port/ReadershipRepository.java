package io.contentpublisher.platform.application.port;

import io.contentpublisher.platform.application.PublicationMethod;
import io.contentpublisher.platform.application.PublicationReadership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReadershipRepository {
    Optional<PublicationReadership> find(String tenantId, PublicationMethod method, UUID publicationId);
    List<PublicationReadership> findByArticles(String tenantId, List<UUID> articleIds);
    /** Version zero inserts; otherwise update only if the persisted version still matches. */
    boolean saveIfVersionMatches(PublicationReadership snapshot, int expectedVersion);
}
