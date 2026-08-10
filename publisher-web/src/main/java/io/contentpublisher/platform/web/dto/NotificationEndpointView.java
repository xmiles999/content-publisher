package io.contentpublisher.platform.web.dto;

import io.contentpublisher.platform.application.AutomationApplicationService.NotificationEndpoint;
import io.contentpublisher.platform.application.AutomationApplicationService.WebhookDeliveryStatus;

import java.net.URI;

public record NotificationEndpointView(NotificationEndpoint endpoint, String maskedUrl,
                                       WebhookDeliveryStatus lastDelivery) {
    public static NotificationEndpointView from(NotificationEndpoint endpoint, WebhookDeliveryStatus lastDelivery) {
        return new NotificationEndpointView(endpoint, mask(endpoint.webhookUrl()), lastDelivery);
    }

    private static String mask(String value) {
        try {
            URI uri = URI.create(value);
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isBlank()) return "HTTPS 端点";
            boolean hasSensitiveSuffix = (uri.getRawPath() != null && !uri.getRawPath().isBlank()
                    && !"/".equals(uri.getRawPath()))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null;
            return uri.getScheme() + "://" + authority + (hasSensitiveSuffix ? "/••••" : "");
        } catch (IllegalArgumentException ignored) {
            return "HTTPS 端点";
        }
    }
}
