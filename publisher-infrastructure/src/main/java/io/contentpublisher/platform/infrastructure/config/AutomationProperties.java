package io.contentpublisher.platform.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "publisher.automation")
public record AutomationProperties(
        boolean channelHealthEnabled,
        Duration channelHealthInterval,
        Duration channelHealthStaleAfter,
        int channelHealthBatchSize,
        boolean webhookEnabled,
        Duration webhookInterval,
        Duration webhookTimeout,
        int webhookBatchSize,
        int webhookMaxAttempts) {
    public AutomationProperties {
        channelHealthInterval = defaultDuration(channelHealthInterval, Duration.ofMinutes(15));
        channelHealthStaleAfter = defaultDuration(channelHealthStaleAfter, Duration.ofHours(24));
        webhookInterval = defaultDuration(webhookInterval, Duration.ofSeconds(30));
        webhookTimeout = defaultDuration(webhookTimeout, Duration.ofSeconds(10));
        channelHealthBatchSize = bounded(channelHealthBatchSize, 1, 500, 50);
        webhookBatchSize = bounded(webhookBatchSize, 1, 500, 50);
        webhookMaxAttempts = bounded(webhookMaxAttempts, 1, 10, 4);
    }

    private static Duration defaultDuration(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static int bounded(int value, int min, int max, int fallback) {
        int normalized = value == 0 ? fallback : value;
        if (normalized < min || normalized > max) throw new IllegalArgumentException("自动化批次参数超出范围");
        return normalized;
    }
}
