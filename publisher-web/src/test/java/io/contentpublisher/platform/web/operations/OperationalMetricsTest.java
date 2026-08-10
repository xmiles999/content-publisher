package io.contentpublisher.platform.web.operations;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:operational_metrics;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "publisher.security.mode=DISABLED",
        "publisher.jobs.worker-enabled=false"
})
class OperationalMetricsTest {
    @Autowired OperationalMetrics metrics;
    @Autowired MeterRegistry registry;

    @Test
    void shouldRegisterAndRefreshLowCardinalityOperationalGauges() {
        metrics.refresh();

        assertThat(registry.get("publisher.jobs.pending").gauge().value()).isZero();
        assertThat(registry.get("publisher.jobs.retry_wait").gauge().value()).isZero();
        assertThat(registry.get("publisher.jobs.oldest_pending.seconds").gauge().value()).isZero();
        assertThat(registry.get("publisher.jobs.running").gauge().value()).isZero();
        assertThat(registry.get("publisher.jobs.failed").gauge().value()).isZero();
        assertThat(registry.get("publisher.publications.published").gauge().value()).isZero();
        assertThat(registry.get("publisher.publications.failed").gauge().value()).isZero();
        assertThat(registry.get("publisher.channels.verification_failed").gauge().value()).isZero();
        assertThat(registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("publisher."))
                .flatMap(meter -> meter.getId().getTags().stream())).isEmpty();
    }
}
