package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.ApplicationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.zone.ZoneRulesException;
import java.util.List;

@Component
public final class ScheduleParser {
    public Instant parse(String localValue, String offsetValue, String timeZone) {
        if (offsetValue != null && !offsetValue.isBlank()) {
            try {
                return OffsetDateTime.parse(offsetValue.trim()).toInstant();
            } catch (DateTimeParseException exception) {
                try {
                    return Instant.parse(offsetValue.trim());
                } catch (DateTimeParseException ignored) {
                    throw invalid("带时区的计划发布时间格式无效", exception);
                }
            }
        }
        if (localValue == null || localValue.isBlank()) return null;
        try {
            LocalDateTime local = LocalDateTime.parse(localValue.trim());
            ZoneId zone = ZoneId.of(timeZone == null || timeZone.isBlank() ? "UTC" : timeZone.trim());
            List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
            if (offsets.isEmpty()) {
                throw invalid("计划发布时间处于夏令时跳过的本地时间，请选择其他时间", null);
            }
            return local.toInstant(offsets.get(0));
        } catch (ApplicationException exception) {
            throw exception;
        } catch (DateTimeParseException | ZoneRulesException exception) {
            throw invalid("计划发布时间或时区格式无效", exception);
        }
    }

    private ApplicationException invalid(String message, Exception cause) {
        return cause == null
                ? new ApplicationException("SCHEDULED_AT_INVALID", message)
                : new ApplicationException("SCHEDULED_AT_INVALID", message, cause);
    }
}
