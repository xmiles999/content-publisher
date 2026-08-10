package io.contentpublisher.platform.web.operations;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public final class OperationalMetrics {
    private static final Logger log = LoggerFactory.getLogger(OperationalMetrics.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, AtomicLong> values = new LinkedHashMap<>();

    public OperationalMetrics(JdbcTemplate jdbc, MeterRegistry registry, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        register(registry, "publisher.jobs.pending", "等待执行的任务数量");
        register(registry, "publisher.jobs.retry_wait", "等待重试的任务数量");
        register(registry, "publisher.jobs.oldest_pending.seconds", "最早待执行任务的等待秒数");
        register(registry, "publisher.jobs.running", "正在执行的任务数量");
        register(registry, "publisher.jobs.failed", "失败任务数量");
        register(registry, "publisher.publications.published", "已成功发布的 API 发布记录数量");
        register(registry, "publisher.publications.failed", "失败的 API 发布记录数量");
        register(registry, "publisher.channels.verification_failed", "最近连接验证失败的渠道账号数量");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${publisher.metrics.refresh-interval:30s}")
    public void refresh() {
        try {
            set("publisher.jobs.pending", count("""
                    select count(*) from jobs where deleted_at is null and status='PENDING'
                    """));
            set("publisher.jobs.retry_wait", count("""
                    select count(*) from jobs where deleted_at is null and status='RETRY_WAIT'
                    """));
            set("publisher.jobs.running", count("""
                    select count(*) from jobs where deleted_at is null and status='RUNNING'
                    """));
            set("publisher.jobs.failed", count("""
                    select count(*) from jobs where deleted_at is null and status='FAILED'
                    """));
            set("publisher.publications.published", count("""
                    select count(*) from publications where deleted_at is null and status='PUBLISHED'
                    """));
            set("publisher.publications.failed", count("""
                    select count(*) from publications where deleted_at is null and status='FAILED'
                    """));
            set("publisher.channels.verification_failed", count("""
                    select count(*) from channel_accounts where verification_status='FAILED'
                    """));
            set("publisher.jobs.oldest_pending.seconds", oldestPendingSeconds());
        } catch (RuntimeException exception) {
            log.warn("operational metrics refresh failed; retaining previous values: {}",
                    exception.getClass().getSimpleName());
        }
    }

    private void register(MeterRegistry registry, String name, String description) {
        AtomicLong value = new AtomicLong();
        values.put(name, value);
        Gauge.builder(name, value, AtomicLong::get).description(description).register(registry);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private long oldestPendingSeconds() {
        Instant oldest = jdbc.query("""
                select min(scheduled_at) from jobs where deleted_at is null and status in ('PENDING','RETRY_WAIT')
                """, resultSet -> {
            if (!resultSet.next()) return null;
            var timestamp = resultSet.getTimestamp(1);
            return timestamp == null ? null : timestamp.toInstant();
        });
        return oldest == null ? 0 : Math.max(0, clock.instant().getEpochSecond() - oldest.getEpochSecond());
    }

    private void set(String name, long value) {
        values.get(name).set(value);
    }
}
