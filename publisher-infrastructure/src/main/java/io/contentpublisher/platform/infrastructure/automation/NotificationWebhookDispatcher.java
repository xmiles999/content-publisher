package io.contentpublisher.platform.infrastructure.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contentpublisher.platform.application.AutomationApplicationService;
import io.contentpublisher.platform.application.port.WebhookEndpointPolicy;
import io.contentpublisher.platform.infrastructure.config.AutomationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class NotificationWebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationWebhookDispatcher.class);
    private final AutomationApplicationService automation;
    private final WebhookEndpointPolicy policy;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AutomationProperties properties;
    private final Clock clock;

    public NotificationWebhookDispatcher(AutomationApplicationService automation,
                                         WebhookEndpointPolicy policy,
                                         @Qualifier("webhookHttpClient") HttpClient httpClient,
                                         ObjectMapper objectMapper, AutomationProperties properties, Clock clock) {
        this.automation = automation;
        this.policy = policy;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${publisher.automation.webhook-interval:30s}")
    public void dispatch() {
        if (!properties.webhookEnabled()) return;
        automation.prepareWebhookDeliveries(properties.webhookBatchSize());
        for (var delivery : automation.webhookDeliveriesDue(properties.webhookBatchSize())) deliver(delivery);
    }

    private void deliver(AutomationApplicationService.WebhookDelivery delivery) {
        int attempts = delivery.attempts() + 1;
        try {
            var uri = policy.validate(delivery.webhookUrl());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", delivery.type());
            payload.put("severity", delivery.severity());
            payload.put("title", delivery.title());
            payload.put("message", delivery.message());
            payload.put("targetUrl", delivery.targetUrl());
            payload.put("createdAt", delivery.notificationCreatedAt());
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(properties.webhookTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload))).build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Webhook HTTP " + response.statusCode());
            }
            automation.markWebhookDeliverySucceeded(delivery.id());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(delivery, attempts, "Webhook 投递被中断");
        } catch (Exception exception) {
            fail(delivery, attempts, safeMessage(exception));
        }
    }

    private void fail(AutomationApplicationService.WebhookDelivery delivery, int attempts, String error) {
        boolean exhausted = attempts >= properties.webhookMaxAttempts();
        long delaySeconds = Math.min(900, 15L * (1L << Math.min(6, Math.max(0, attempts - 1))));
        automation.markWebhookDeliveryFailed(delivery.id(), attempts,
                clock.instant().plus(Duration.ofSeconds(delaySeconds)), error, exhausted);
        log.warn("notification webhook delivery failed deliveryId={} attempts={} exhausted={}",
                delivery.id(), attempts, exhausted);
    }

    private String safeMessage(Exception exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) return "Webhook 投递失败";
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}
