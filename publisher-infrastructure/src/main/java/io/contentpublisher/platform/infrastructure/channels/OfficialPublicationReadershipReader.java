package io.contentpublisher.platform.infrastructure.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.PublicReadershipArticle;
import io.contentpublisher.platform.application.port.ChannelEndpointPolicy;
import io.contentpublisher.platform.application.port.CredentialVault;
import io.contentpublisher.platform.application.port.PublicationReadershipReader;
import io.contentpublisher.platform.domain.ChannelAccount;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.infrastructure.config.ChannelProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OfficialPublicationReadershipReader implements PublicationReadershipReader {
    private final HttpClient client;
    private final ObjectMapper json;
    private final ChannelProperties properties;
    private final ChannelEndpointPolicy endpoints;
    private final CredentialVault vault;

    public OfficialPublicationReadershipReader(@Qualifier("channelHttpClient") HttpClient client, ObjectMapper json,
            ChannelProperties properties, ChannelEndpointPolicy endpoints, CredentialVault vault) {
        this.client = client;
        this.json = json;
        this.properties = properties;
        this.endpoints = endpoints;
        this.vault = vault;
    }

    /** Public web endpoint, not an authenticated creator analytics API. Never requests the supplied URL. */
    @Override public long readPublicViews(ChannelType channel, String externalUrl) {
        if (!properties.enabled()) throw error("READERSHIP_DISABLED", "渠道接口服务未启用，请联系管理员启用");
        String articleId = PublicReadershipArticle.articleId(channel, externalUrl)
                .orElseThrow(() -> error("READERSHIP_EXTERNAL_ID_INVALID", "请先保存有效的掘金文章链接"));
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            Duration timeout = properties.timeout().compareTo(Duration.ofSeconds(15)) > 0
                    ? Duration.ofSeconds(15) : properties.timeout();
            byte[] body = json.writeValueAsBytes(Map.of("article_id", articleId, "req_from", 1,
                    "client_type", 2608, "forbid_count", false, "is_pre_load", false, "need_theme", true));
            var request = HttpRequest.newBuilder(URI.create("https://api.juejin.cn/content_api/v1/article/detail?aid=2608"))
                    .timeout(timeout).header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            pending = client.sendAsync(request, ignored -> new LimitedBodySubscriber());
            HttpResponse<byte[]> response = pending.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (response.statusCode() == 429) throw error("READERSHIP_RATE_LIMIT", "平台请求过于频繁，请稍后再试");
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw error("READERSHIP_PERMISSION", "平台暂不允许公开读取，请打开文章查看或稍后重试");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw error("READERSHIP_HTTP_ERROR", "平台暂未返回有效统计，请稍后重试");
            JsonNode root = json.readTree(response.body());
            JsonNode info = root.path("data").path("article_info");
            JsonNode count = info.path("view_count");
            if (!root.path("err_no").isIntegralNumber() || !root.path("err_no").canConvertToInt() || root.path("err_no").intValue() != 0
                    || !info.path("article_id").isTextual() || !info.path("article_id").textValue().equals(articleId)
                    || !count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() < 0)
                throw error("READERSHIP_RESPONSE_INVALID", "平台未返回匹配文章的有效阅读次数，已有数据未更新");
            return count.longValue();
        } catch (ApplicationException expected) { throw expected; }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw error("READERSHIP_INTERRUPTED", "同步已中断，请稍后重试");
        } catch (TimeoutException timeout) {
            throw error("READERSHIP_TIMEOUT", "平台统计请求超时，请稍后重试");
        } catch (Exception failure) {
            throw error("READERSHIP_UNAVAILABLE", "无法读取公开统计，请稍后重试或打开平台文章查看");
        } finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }

    @Override public long readViews(ChannelAccount account, String externalId) {
        if (!properties.enabled()) throw error("READERSHIP_DISABLED", "接口服务未启用，请手动录入或联系管理员启用渠道接口");
        if (account.type() != ChannelType.DEV && account.type() != ChannelType.DISCOURSE)
            throw error("READERSHIP_UNSUPPORTED", "该渠道暂不支持统计接口同步");
        if (externalId == null || !externalId.matches("[1-9][0-9]{0,18}"))
            throw error("READERSHIP_EXTERNAL_ID_INVALID", "原发布记录缺少有效的平台文章编号，请手动录入");
        String base = endpoints.validateAndNormalize(account.type(), account.baseUrl());
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            Map<String, String> credentials = vault.decrypt(account.encryptedCredentials());
            Duration timeout = properties.timeout().compareTo(Duration.ofSeconds(15)) > 0
                    ? Duration.ofSeconds(15) : properties.timeout();
            String path = account.type() == ChannelType.DEV
                    ? "/api/analytics/totals?article_id=" + externalId : "/t/" + externalId + ".json";
            var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).GET();
            if (account.type() == ChannelType.DEV) {
                request.header("api-key", credential(credentials, "apiKey"))
                        .header("Accept", "application/vnd.forem.api-v1+json");
            } else {
                request.header("Api-Key", credential(credentials, "apiKey"))
                        .header("Api-Username", credential(credentials, "apiUsername")).header("Accept", "application/json");
            }
            pending = client.sendAsync(request.build(), ignored -> new LimitedBodySubscriber());
            HttpResponse<byte[]> response = pending.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (status == 401 || status == 403) throw error("READERSHIP_PERMISSION", "平台拒绝访问统计，请检查原发布账号的凭据及统计权限");
            if (status == 404) throw error("READERSHIP_MISSING", "平台未找到文章或统计接口，请核对原发布记录");
            if (status == 429) throw error("READERSHIP_RATE_LIMIT", "平台请求过于频繁，请稍后再试");
            if (status < 200 || status >= 300) throw error("READERSHIP_HTTP_ERROR", "平台暂未返回有效统计，请稍后重试或手动录入");
            JsonNode root = json.readTree(response.body());
            JsonNode count = account.type() == ChannelType.DEV ? root.path("page_views").path("total") : root.path("views");
            if (!count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() < 0)
                throw error("READERSHIP_RESPONSE_INVALID", "平台响应缺少有效浏览次数，请手动录入或稍后重试");
            if (account.type() == ChannelType.DISCOURSE
                    && (!root.path("id").isIntegralNumber() || !root.path("id").asText().equals(externalId)))
                throw error("READERSHIP_RESPONSE_INVALID", "平台返回的文章编号不匹配，未更新统计");
            return count.longValue();
        } catch (ApplicationException expected) { throw expected; }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw error("READERSHIP_INTERRUPTED", "同步已中断，请稍后重试");
        } catch (TimeoutException timeout) {
            throw error("READERSHIP_TIMEOUT", "平台统计请求超时，请稍后重试");
        } catch (Exception failure) {
            // Never persist provider bodies, credentials, URLs, or transport exception messages.
            throw error("READERSHIP_UNAVAILABLE", "无法读取平台统计，请检查连接及凭据，或手动录入");
        } finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }

    private static String credential(Map<String, String> credentials, String key) {
        String value = credentials.get(key);
        if (value == null || value.isBlank()) throw error("READERSHIP_CREDENTIALS", "发布账号缺少统计所需凭据，请更新账号或手动录入");
        return value;
    }
    private static ApplicationException error(String code, String message) { return new ApplicationException(code, message); }

    /** Bound memory before receiving the entire body; the caller also bounds the full response duration. */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private static final int LIMIT = 2_000_000;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > LIMIT - buffer.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("统计响应超过大小限制"));
                    return;
                }
                byte[] bytes = new byte[chunk.remaining()];
                chunk.get(bytes);
                buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(buffer.toByteArray()); }
    }
}
