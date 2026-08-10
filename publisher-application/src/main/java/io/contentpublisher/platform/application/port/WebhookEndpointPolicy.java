package io.contentpublisher.platform.application.port;

import java.net.URI;

public interface WebhookEndpointPolicy {
    URI validate(String value);
}
