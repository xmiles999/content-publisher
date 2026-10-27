package io.contentpublisher.platform.infrastructure.persistence;

import io.contentpublisher.platform.application.PublicationMethod;
import io.contentpublisher.platform.application.PublicationReadership;
import io.contentpublisher.platform.application.port.ReadershipRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcReadershipRepository implements ReadershipRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<PublicationReadership> MAPPER = (rs, row) -> new PublicationReadership(
            rs.getString("tenant_id"), rs.getObject("article_id", UUID.class),
            PublicationMethod.valueOf(rs.getString("publication_method")), rs.getObject("publication_id", UUID.class),
            rs.getObject("views", Long.class), rs.getObject("unique_readers", Long.class), rs.getString("source"),
            instant(rs.getTimestamp("recorded_at")), rs.getString("recorded_by"),
            instant(rs.getTimestamp("last_sync_at")), rs.getString("sync_error"), rs.getInt("version"));

    public JdbcReadershipRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<PublicationReadership> find(String tenantId, PublicationMethod method, UUID publicationId) {
        return jdbc.query("select * from publication_readership where tenant_id=? and publication_method=? and publication_id=?",
                MAPPER, tenantId, method.name(), publicationId).stream().findFirst();
    }

    @Override public List<PublicationReadership> findByArticles(String tenantId, List<UUID> articleIds) {
        if (articleIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(articleIds);
        return jdbc.query("select * from publication_readership where tenant_id=? and article_id in ("
                + String.join(",", Collections.nCopies(articleIds.size(), "?")) + ")", MAPPER, args.toArray());
    }

    @Override public boolean saveIfVersionMatches(PublicationReadership s, int expectedVersion) {
        if (s.version() != expectedVersion + 1) throw new IllegalArgumentException("统计版本无效");
        if (expectedVersion == 0) {
            try {
                return jdbc.update("""
                        insert into publication_readership(tenant_id, article_id, publication_method, publication_id,
                            views, unique_readers, source, recorded_at, recorded_by, last_sync_at, sync_error, version)
                        values(?,?,?,?,?,?,?,?,?,?,?,?)
                        """, s.tenantId(), s.articleId(), s.method().name(), s.publicationId(), s.views(),
                        s.uniqueReaders(), s.source(), timestamp(s.recordedAt()), s.recordedBy(),
                        timestamp(s.lastSyncAt()), s.syncError(), s.version()) == 1;
            } catch (DuplicateKeyException conflict) { return false; }
        }
        return jdbc.update("""
                update publication_readership set views=?, unique_readers=?, source=?, recorded_at=?, recorded_by=?,
                    last_sync_at=?, sync_error=?, version=?
                where tenant_id=? and article_id=? and publication_method=? and publication_id=? and version=?
                """, s.views(), s.uniqueReaders(), s.source(), timestamp(s.recordedAt()), s.recordedBy(),
                timestamp(s.lastSyncAt()), s.syncError(), s.version(), s.tenantId(), s.articleId(),
                s.method().name(), s.publicationId(), expectedVersion) == 1;
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
