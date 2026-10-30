package io.contentpublisher.platform.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contentpublisher.platform.application.WorkspaceDraftService.Draft;
import io.contentpublisher.platform.application.port.WorkspaceDraftRepository;
import io.contentpublisher.platform.domain.ActorContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcWorkspaceDraftRepository implements WorkspaceDraftRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcWorkspaceDraftRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public Optional<Draft> find(ActorContext actor, String key) {
        return jdbc.query("select content_json, revision, updated_at from workspace_drafts where tenant_id=? and actor_subject=? and draft_key=?",
                (rs, row) -> new Draft(read(rs.getString(1)), rs.getLong(2), rs.getTimestamp(3).toInstant()),
                actor.tenantId(), actor.subject(), key).stream().findFirst();
    }
    public boolean save(ActorContext actor, String key, long expectedRevision, Draft draft) {
        if (expectedRevision == 0) {
            try {
                jdbc.update("insert into workspace_drafts(tenant_id,actor_subject,draft_key,content_json,revision,updated_at) values(?,?,?,?,?,?)",
                        actor.tenantId(), actor.subject(), key, write(draft.fields()), draft.revision(), Timestamp.from(draft.updatedAt()));
                return true;
            } catch (DuplicateKeyException conflict) { return false; }
        }
        return jdbc.update("update workspace_drafts set content_json=?,revision=?,updated_at=? where tenant_id=? and actor_subject=? and draft_key=? and revision=?",
                write(draft.fields()), draft.revision(), Timestamp.from(draft.updatedAt()), actor.tenantId(), actor.subject(), key, expectedRevision) == 1;
    }
    public void delete(ActorContext actor, String key, long expectedRevision) {
        // Retain a revision tombstone so old tabs cannot overwrite a later new draft.
        jdbc.update("update workspace_drafts set content_json='{}', revision=revision+1 where tenant_id=? and actor_subject=? and draft_key=? and revision=?",
                actor.tenantId(), actor.subject(), key, expectedRevision);
    }
    private String write(Map<String, String> fields) {
        try { return json.writeValueAsString(fields); }
        catch (java.io.IOException ex) { throw new IllegalStateException("草稿序列化失败", ex); }
    }
    private Map<String, String> read(String content) {
        try { return json.readValue(content, new TypeReference<Map<String, String>>() {}); }
        catch (java.io.IOException ex) { throw new IllegalStateException("草稿读取失败", ex); }
    }
}
