package io.contentpublisher.platform.infrastructure.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.PublicReadershipArticle;
import io.contentpublisher.platform.application.port.ChannelEndpointPolicy;
import io.contentpublisher.platform.application.port.CredentialVault;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.infrastructure.config.ChannelProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicPublicationReadershipReaderTest {
    private static final String URL = "https://juejin.cn/post/7698382188488048649?share=1#heading";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = mock(HttpClient.class);
    private final CredentialVault vault = mock(CredentialVault.class);
    private final ChannelEndpointPolicy endpoints = mock(ChannelEndpointPolicy.class);
    private final AtomicReference<HttpRequest> requested = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private HttpServer server;
    private int status = 200;
    private String response = "{\"err_no\":0,\"data\":{\"article_info\":{\"article_id\":\"7698382188488048649\",\"view_count\":0}}}";
    private OfficialPublicationReadershipReader reader;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/do-not-follow");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var transport = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(call -> {
                    HttpRequest original = call.getArgument(0);
                    requested.set(original);
                    HttpRequest local = HttpRequest.newBuilder(original, (name, value) -> true)
                            .uri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + original.uri().getRawPath()))
                            .build();
                    return transport.sendAsync(local, call.<HttpResponse.BodyHandler<byte[]>>getArgument(1));
                });
        reader = reader(Duration.ofSeconds(3), true);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void readsExactPublicZeroUsingFixedEndpointAndNoCredentials() throws Exception {
        assertThat(reader.readPublicViews(ChannelType.JUEJIN, URL)).isZero();
        assertThat(requested.get().uri()).isEqualTo(URI.create("https://api.juejin.cn/content_api/v1/article/detail?aid=2608"));
        assertThat(requested.get().method()).isEqualTo("POST");
        assertThat(requested.get().headers().firstValue("Cookie")).isEmpty();
        assertThat(requested.get().headers().firstValue("Authorization")).isEmpty();
        var body = json.readTree(requestBody.get());
        assertThat(body.path("article_id").textValue()).isEqualTo("7698382188488048649");
        assertThat(body.path("client_type").asInt()).isEqualTo(2608);
        assertThat(body.path("req_from").asInt()).isEqualTo(1);
        assertThat(body.path("forbid_count").asBoolean()).isFalse();
        verifyNoInteractions(vault, endpoints);
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "null", "{\"err_no\":2,\"err_msg\":\"private-response\"}",
            "{\"err_no\":18446744073709551616,\"data\":{\"article_info\":{\"article_id\":\"7698382188488048649\",\"view_count\":1}}}",
            "{\"err_no\":0,\"data\":{\"article_info\":{\"article_id\":\"123\",\"view_count\":1}}}",
            "{\"err_no\":0,\"data\":{\"article_info\":{\"article_id\":\"7698382188488048649\",\"view_count\":-1}}}",
            "{\"err_no\":0,\"data\":{\"article_info\":{\"article_id\":\"7698382188488048649\",\"view_count\":1.5}}}",
            "{\"err_no\":0,\"data\":{\"article_info\":{\"article_id\":\"7698382188488048649\",\"view_count\":\"1.2w\"}}}",
            "{\"err_no\":0,\"data\":{\"article_info\":{\"article_id\":\"7698382188488048649\",\"view_count\":9223372036854775808}}}"})
    void rejectsMissingApproximateInvalidAndMismatchedStatistics(String payload) {
        response = payload;
        assertThatThrownBy(() -> reader.readPublicViews(ChannelType.JUEJIN, URL))
                .isInstanceOf(ApplicationException.class).hasMessageNotContaining("private-response");
    }

    @Test void acceptsExactMaximumCountAndSafeShareUrls() {
        response = response.replace("\"view_count\":0", "\"view_count\":9223372036854775807");
        assertThat(reader.readPublicViews(ChannelType.JUEJIN, URL)).isEqualTo(Long.MAX_VALUE);
        assertThat(PublicReadershipArticle.articleId(ChannelType.JUEJIN, "https://juejin.cn:443/post/123/?share=1#x")).contains("123");
        assertThat(PublicReadershipArticle.articleId(ChannelType.JUEJIN, null)).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"https://evil.test/post/123", "http://juejin.cn/post/123",
            "https://juejin.cn.evil.test/post/123", "https://juejin.cn@127.0.0.1/post/123", "https://juejin.cn:8443/post/123",
            "https://juejin.cn/post/%31", "https://juejin.cn/post/0", "https://juejin.cn/post/123/../456",
            "https://juejin.cn/post/123%2F456", "https://juejin.cn/post/12345678901234567890", "https://juejin.cn"})
    void rejectsUnsafeAndUnrecognizedUrlsBeforeNetwork(String url) {
        assertThat(PublicReadershipArticle.articleId(ChannelType.JUEJIN, url)).isEmpty();
        assertThatThrownBy(() -> reader.readPublicViews(ChannelType.JUEJIN, url)).isInstanceOf(ApplicationException.class);
        verify(client, never()).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void rejectsUnsupportedChannelsDisabledServiceRedirectsAndOversizedResponses() {
        assertThatThrownBy(() -> reader.readPublicViews(ChannelType.CSDN, URL)).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> reader(Duration.ofSeconds(1), false).readPublicViews(ChannelType.JUEJIN, URL)).hasMessageContaining("未启用");
        assertThat(requested.get()).isNull();
        status = 302;
        assertThatThrownBy(() -> reader.readPublicViews(ChannelType.JUEJIN, URL)).hasMessageContaining("有效统计");
        status = 200;
        response = " ".repeat(2_000_001);
        assertThatThrownBy(() -> reader.readPublicViews(ChannelType.JUEJIN, URL)).isInstanceOf(ApplicationException.class);
    }

    @Test void boundsFullPublicBodyTime() {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try { Thread.sleep(700); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        assertThatThrownBy(() -> reader(Duration.ofMillis(150), true).readPublicViews(ChannelType.JUEJIN, URL))
                .hasMessageContaining("超时");
    }

    private OfficialPublicationReadershipReader reader(Duration timeout, boolean enabled) {
        return new OfficialPublicationReadershipReader(client, json,
                new ChannelProperties(enabled, null, Set.of(), timeout), endpoints, vault);
    }
}
