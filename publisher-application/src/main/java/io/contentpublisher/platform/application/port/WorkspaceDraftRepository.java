package io.contentpublisher.platform.application.port;

import io.contentpublisher.platform.application.WorkspaceDraftService.Draft;
import io.contentpublisher.platform.domain.ActorContext;
import java.util.Optional;

public interface WorkspaceDraftRepository {
    Optional<Draft> find(ActorContext actor, String key);
    boolean save(ActorContext actor, String key, long expectedRevision, Draft draft);
    void delete(ActorContext actor, String key, long expectedRevision);
}
