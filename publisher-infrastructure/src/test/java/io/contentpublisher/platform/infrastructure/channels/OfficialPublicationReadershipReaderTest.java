package io.contentpublisher.platform.infrastructure.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.port.ChannelEndpointPolicy;
import io.contentpublisher.platform.application.port.CredentialVault;
import io.contentpublisher.platform.domain.*;
import io.contentpublisher.platform.infrastructure.config.ChannelProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OfficialPublicationReadershipReaderTest {
    private HttpServer server;
    private final ChannelEndpointPolicy endpoints = mock(ChannelEndpointPolicy.class);
    private final CredentialVault vault = mock(CredentialVault.class);
    private final AtomicReference<String> path = new AtomicReference<>(), key = new AtomicReference<>(), username = new AtomicReference<>();
    private String response = "{\"page_views\":{\"total\":123}}";
    private int status = 200;
    private OfficialPublicationReadershipReader reader;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().toString());
            key.set(exchange.getRequestHeaders().getFirst("api-key"));
            username.set(exchange.getRequestHeaders().getFirst("api-username"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/must-not-follow");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        when(endpoints.validateAndNormalize(any(), any())).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(vault.decrypt("encrypted-test")).thenReturn(Map.of("apiKey", "test-key", "apiUsername", "test-author"));
        reader = reader(Duration.ofSeconds(3), true);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void usesDevArticleScopedAnalyticsAndCredentials() {
        assertThat(reader.readViews(account(ChannelType.DEV), "123")).isEqualTo(123);
        assertThat(path.get()).isEqualTo("/api/analytics/totals?article_id=123");
        assertThat(key.get()).isEqualTo("test-key");
        verify(endpoints).validateAndNormalize(ChannelType.DEV, "https://example.com");
    }
    @Test void readsDiscourseTopicViewsIncludingZero() {
        response = "{\"id\":42,\"views\":0}";
        assertThat(reader.readViews(account(ChannelType.DISCOURSE), "42")).isZero();
        assertThat(path.get()).isEqualTo("/t/42.json");
        assertThat(username.get()).isEqualTo("test-author");
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "null", "{\"page_views_count\":42}", "{\"page_views\":{\"total\":null}}",
            "{\"page_views\":{\"total\":-1}}", "{\"page_views\":{\"total\":1.5}}",
            "{\"page_views\":{\"total\":\"123\"}}", "{\"page_views\":{\"total\":9223372036854775808}}"})
    void rejectsUnknownAndInvalidCountsInsteadOfInventingZero(String payload) {
        response = payload;
        assertThatThrownBy(() -> reader.readViews(account(ChannelType.DEV), "123")).isInstanceOf(ApplicationException.class);
    }
    @Test void rejectsDifferentDiscourseTopic() {
        response = "{\"id\":43,\"views\":100}";
        assertThatThrownBy(() -> reader.readViews(account(ChannelType.DISCOURSE), "42")).hasMessageContaining("编号不匹配");
    }
    @Test void boundsResponseBytes() {
        response = " ".repeat(2_000_001);
        assertThatThrownBy(() -> reader.readViews(account(ChannelType.DEV), "123")).isInstanceOf(ApplicationException.class);
    }
    @Test void cancelsSubscriberBeforeBufferingOversizedChunk() {
        var subscriber = new OfficialPublicationReadershipReader.LimitedBodySubscriber();
        var subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.allocate(2_000_001)));
        verify(subscription).cancel();
        assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
    }
    @Test void doesNotFollowRedirectOrExposeRawErrors() {
        status = 302;
        response = "private-platform-body test-key";
        assertThatThrownBy(() -> reader.readViews(account(ChannelType.DEV), "123"))
                .isInstanceOf(ApplicationException.class).hasMessageNotContaining("test-key").hasMessageNotContaining("127.0.0.1");
    }
    @Test void reportsPermissionFailure() {
        status = 403;
        assertThatThrownBy(() -> reader.readViews(account(ChannelType.DEV), "123")).hasMessageContaining("统计权限");
    }
    @Test void checksFeatureIdEndpointAndCredentialsBeforeCalling() {
        assertThatThrownBy(() -> reader(Duration.ofSeconds(3), false).readViews(account(ChannelType.DEV), "123"))
                .hasMessageContaining("未启用");
        for (String invalid : List.of("../42", "https://evil.test/42", "0", "42?other=1"))
            assertThatThrownBy(() -> reader.readViews(account(ChannelType.DEV), invalid)).hasMessageContaining("编号");
        when(endpoints.validateAndNormalize(any(), any())).thenThrow(new ApplicationException("CHANNEL_HOST_REJECTED", "渠道站点不在服务器允许列表中"));
        assertThatThrownBy(() -> reader.readViews(account(ChannelType.DISCOURSE), "42")).hasMessageContaining("允许列表");
        assertThat(path.get()).isNull();
    }
    @Test void boundsFullBodyTimeAfterHeaders() throws Exception {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try { Thread.sleep(900); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        assertThatThrownBy(() -> reader(Duration.ofMillis(250), true).readViews(account(ChannelType.DEV), "123"))
                .isInstanceOf(ApplicationException.class).hasMessageContaining("超时");
    }
    private OfficialPublicationReadershipReader reader(Duration timeout, boolean enabled) {
        return new OfficialPublicationReadershipReader(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build(), new ObjectMapper(),
                new ChannelProperties(enabled, null, Set.of(), timeout), endpoints, vault);
    }
    private ChannelAccount account(ChannelType type) {
        Instant now = Instant.now();
        return new ChannelAccount(UUID.randomUUID(), "test", type, "test", "https://example.com", "encrypted-test",
                "key", "hash", "fingerprint", 1, ChannelAccountStatus.ACTIVE, "test", "test", now, now);
    }
}
