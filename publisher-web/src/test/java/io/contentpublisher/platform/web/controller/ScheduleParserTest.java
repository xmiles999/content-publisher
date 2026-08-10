package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.ApplicationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleParserTest {
    private final ScheduleParser parser = new ScheduleParser();

    @Test
    void shouldPreferOffsetTimestamp() {
        assertThat(parser.parse("2026-08-10T12:00", "2026-08-10T12:00:00+08:00", "Asia/Shanghai"))
                .isEqualTo(Instant.parse("2026-08-10T04:00:00Z"));
    }

    @Test
    void shouldParseLocalTimeWithZoneId() {
        assertThat(parser.parse("2026-08-10T12:00", "", "Asia/Shanghai"))
                .isEqualTo(Instant.parse("2026-08-10T04:00:00Z"));
    }

    @Test
    void shouldApplyDaylightSavingOffset() {
        assertThat(parser.parse("2026-07-15T09:30", "", "America/New_York"))
                .isEqualTo(Instant.parse("2026-07-15T13:30:00Z"));
    }

    @Test
    void shouldRejectInvalidZoneAndDaylightSavingGap() {
        assertInvalid(() -> parser.parse("2026-08-10T12:00", "", "Not/A-Time-Zone"));
        assertInvalid(() -> parser.parse("2026-03-08T02:30", "", "America/New_York"));
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SCHEDULED_AT_INVALID"));
    }
}
