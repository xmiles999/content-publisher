package io.contentpublisher.platform.infrastructure.persistence;

import io.contentpublisher.platform.application.MonitoringSnapshot;
import io.contentpublisher.platform.application.MonitoringWindow;
import io.contentpublisher.platform.application.port.MonitoringQuery;
import io.contentpublisher.platform.domain.ArticleSourceType;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.domain.ChannelAccountStatus;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.JobStatus;
import io.contentpublisher.platform.domain.ProjectStatus;
import io.contentpublisher.platform.domain.PublicationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Repository
@Transactional(readOnly = true)
public class JdbcMonitoringQuery implements MonitoringQuery {
    private final JdbcTemplate jdbcTemplate;

    public JdbcMonitoringQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public MonitoringSnapshot load(String tenantId, MonitoringWindow window, Instant windowStart,
                                   Instant capturedAt) {
        Timestamp since = Timestamp.from(windowStart);
        Map<ProjectStatus, Long> projectStatus = grouped(
                "select status, count(*) as total from projects where tenant_id = ? group by status",
                tenantId, ProjectStatus.class);
        Map<ArticleStatus, Long> articleStatus = grouped(
                "select status, count(*) as total from articles where tenant_id = ? and deleted_at is null group by status",
                tenantId, ArticleStatus.class);
        Map<ArticleSourceType, Long> articleSources = grouped(
                "select source_type, count(*) as total from articles where tenant_id = ? and deleted_at is null group by source_type",
                tenantId, ArticleSourceType.class);
        Map<JobStatus, Long> jobStatus = grouped(
                "select status, count(*) as total from jobs where tenant_id = ? and deleted_at is null group by status",
                tenantId, JobStatus.class);
        Map<JobStatus, Long> windowJobStatus = grouped(
                "select status, count(*) as total from jobs where tenant_id = ? and deleted_at is null "
                        + "and updated_at >= ? group by status",
                new Object[]{tenantId, since}, JobStatus.class);
        Map<PublicationStatus, Long> publicationStatus = publicationStatus(tenantId, null);
        Map<PublicationStatus, Long> windowPublicationStatus = publicationStatus(tenantId, since);

        long projectCount = count("select count(*) from projects where tenant_id = ?", tenantId);
        long articleCount = count("select count(*) from articles where tenant_id = ? and deleted_at is null", tenantId);
        long jobCount = count("select count(*) from jobs where tenant_id = ? and deleted_at is null", tenantId);
        long apiPublicationCount = count(
                "select count(*) from publications where tenant_id = ? and deleted_at is null", tenantId);
        long manualPublicationCount = count(
                "select count(*) from manual_publications where tenant_id = ? and deleted_at is null", tenantId);
        long accountCount = count(
                "select count(*) from channel_accounts where tenant_id = ? and deleted_at is null", tenantId);

        return new MonitoringSnapshot(capturedAt, windowStart, window,
                projectCount, activityCount("projects", tenantId, since, false), projectStatus,
                articleCount, activityCount("articles", tenantId, since, true), articleStatus, articleSources,
                jobCount, activityCount("jobs", tenantId, since, true), jobStatus, windowJobStatus,
                apiPublicationCount + manualPublicationCount, publicationActivityCount(tenantId, since),
                publicationStatus, windowPublicationStatus,
                accountCount, grouped(
                        "select status, count(*) as total from channel_accounts "
                                + "where tenant_id = ? and deleted_at is null group by status",
                        tenantId, ChannelAccountStatus.class),
                coveredChannelCount(tenantId), channelPerformance(tenantId, since));
    }

    private Map<PublicationStatus, Long> publicationStatus(String tenantId, Timestamp since) {
        String apiSql = "select status, count(*) as total from publications where tenant_id = ? and deleted_at is null"
                + (since == null ? "" : " and updated_at >= ?") + " group by status";
        Map<PublicationStatus, Long> result = since == null
                ? grouped(apiSql, tenantId, PublicationStatus.class)
                : grouped(apiSql, new Object[]{tenantId, since}, PublicationStatus.class);
        String manualSql = "select count(*) from manual_publications where tenant_id = ? and deleted_at is null"
                + (since == null ? "" : " and published_at >= ?");
        long manualPublished = since == null ? count(manualSql, tenantId) : count(manualSql, tenantId, since);
        result.merge(PublicationStatus.PUBLISHED, manualPublished, Long::sum);
        return result;
    }

    private List<MonitoringSnapshot.ChannelPerformance> channelPerformance(String tenantId, Timestamp since) {
        Map<ChannelType, MutableChannelPerformance> metrics = new EnumMap<>(ChannelType.class);
        jdbcTemplate.query("select channel_type, status, count(*) as total from publications "
                        + "where tenant_id = ? and deleted_at is null and updated_at >= ? "
                        + "group by channel_type, status",
                rs -> {
                    ChannelType type = ChannelType.valueOf(rs.getString("channel_type"));
                    PublicationStatus status = PublicationStatus.valueOf(rs.getString("status"));
                    MutableChannelPerformance metric = metrics.computeIfAbsent(type,
                            ignored -> new MutableChannelPerformance());
                    long value = rs.getLong("total");
                    metric.total += value;
                    if (status == PublicationStatus.PUBLISHED) metric.published += value;
                    if (status == PublicationStatus.FAILED) metric.failed += value;
                }, tenantId, since);
        jdbcTemplate.query("select channel_type, count(*) as total from manual_publications "
                        + "where tenant_id = ? and deleted_at is null and published_at >= ? group by channel_type",
                rs -> {
                    ChannelType type = ChannelType.valueOf(rs.getString("channel_type"));
                    long value = rs.getLong("total");
                    MutableChannelPerformance metric = metrics.computeIfAbsent(type,
                            ignored -> new MutableChannelPerformance());
                    metric.total += value;
                    metric.published += value;
                }, tenantId, since);
        List<MonitoringSnapshot.ChannelPerformance> result = new ArrayList<>();
        metrics.forEach((type, metric) -> result.add(new MonitoringSnapshot.ChannelPerformance(
                type, metric.total, metric.published, metric.failed)));
        return result;
    }

    private long coveredChannelCount(String tenantId) {
        return count("select count(*) from (select channel_type from publications "
                + "where tenant_id = ? and deleted_at is null and status = 'PUBLISHED' union select channel_type "
                + "from manual_publications where tenant_id = ? and deleted_at is null) covered", tenantId, tenantId);
    }

    private long publicationActivityCount(String tenantId, Timestamp since) {
        return count("select count(*) from publications where tenant_id = ? and deleted_at is null "
                        + "and updated_at >= ?", tenantId, since)
                + count("select count(*) from manual_publications where tenant_id = ? and deleted_at is null "
                        + "and published_at >= ?", tenantId, since);
    }

    private long activityCount(String table, String tenantId, Timestamp since, boolean supportsSoftDelete) {
        if (!List.of("projects", "articles", "jobs").contains(table)) {
            throw new IllegalArgumentException("不支持的监控表: " + table);
        }
        String activeFilter = supportsSoftDelete ? " and deleted_at is null" : "";
        return count("select count(*) from " + table + " where tenant_id = ?" + activeFilter
                + " and updated_at >= ?", tenantId, since);
    }

    private <E extends Enum<E>> Map<E, Long> grouped(String sql, String tenantId, Class<E> type) {
        return grouped(sql, new Object[]{tenantId}, type);
    }

    private <E extends Enum<E>> Map<E, Long> grouped(String sql, Object[] arguments, Class<E> type) {
        Map<E, Long> result = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) result.put(value, 0L);
        jdbcTemplate.query(sql, rs -> {
            result.put(Enum.valueOf(type, rs.getString(1)), rs.getLong("total"));
        }, arguments);
        return result;
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private static final class MutableChannelPerformance {
        private long total;
        private long published;
        private long failed;
    }
}
