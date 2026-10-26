package io.contentpublisher.platform.application.port;

public interface AiAvailabilityProbe {
    void check(String tenantId);
}
