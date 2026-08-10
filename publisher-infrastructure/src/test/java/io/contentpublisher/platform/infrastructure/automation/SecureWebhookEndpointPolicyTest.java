package io.contentpublisher.platform.infrastructure.automation;

import io.contentpublisher.platform.application.ApplicationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureWebhookEndpointPolicyTest {
    private final SecureWebhookEndpointPolicy policy = new SecureWebhookEndpointPolicy();

    @Test
    void shouldAcceptPublicHttpsAddress() {
        assertThat(policy.validate("https://8.8.8.8/hooks/content-publisher"))
                .hasScheme("https")
                .hasHost("8.8.8.8");
    }

    @Test
    void shouldRejectNonHttpsAddress() {
        assertRejected("http://8.8.8.8/hooks");
    }

    @Test
    void shouldRejectLoopbackAddress() {
        assertRejected("https://127.0.0.1/hooks");
    }

    @Test
    void shouldRejectPrivateAddress() {
        assertRejected("https://10.20.30.40/hooks");
        assertRejected("https://192.168.1.10/hooks");
    }

    @Test
    void shouldRejectUserInfoAndFragment() {
        assertRejected("https://user:password@8.8.8.8/hooks");
        assertRejected("https://8.8.8.8/hooks#secret");
    }

    private void assertRejected(String value) {
        assertThatThrownBy(() -> policy.validate(value))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("WEBHOOK_URL_REJECTED"));
    }
}
