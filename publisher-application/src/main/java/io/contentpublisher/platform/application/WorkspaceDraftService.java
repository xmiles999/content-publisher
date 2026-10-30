package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.WorkspaceDraftRepository;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.ChannelType;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Incomplete input is durable without creating an article or publication. */
public final class WorkspaceDraftService {
    private static final Map<String, Integer> NEW_FIELDS = Map.ofEntries(
            Map.entry("title", 500), Map.entry("summary", 2000), Map.entry("markdown", 100000),
            Map.entry("tags", 1000), Map.entry("keywords", 3200), Map.entry("language", 20),
            Map.entry("titleEn", 500), Map.entry("summaryEn", 2000), Map.entry("markdownEn", 100000),
            Map.entry("tagsEn", 1000), Map.entry("keywordsEn", 3200));
    private static final Map<String, Integer> MANUAL_FIELDS = Map.of(
            "title", 500, "content", 100000, "externalUrl", 2048);
    private final WorkspaceDraftRepository repository;
    private final ArticleRepository articles;
    private final Clock clock;

    public WorkspaceDraftService(WorkspaceDraftRepository repository, ArticleRepository articles, Clock clock) {
        this.repository = repository;
        this.articles = articles;
        this.clock = clock;
    }
    public static String manualKey(UUID article, ChannelType channel, String language, int version) {
        return "manual:" + article + ":" + channel + ":" + language + ":" + version;
    }
    public Optional<Draft> find(ActorContext actor, String key) {
        validateKey(actor, key);
        return repository.find(actor, key);
    }
    public Draft save(ActorContext actor, String key, long revision, Map<String, String> fields) {
        validateKey(actor, key);
        if (revision < 0 || revision == Long.MAX_VALUE || fields == null) throw new IllegalArgumentException("草稿参数无效");
        var limits = key.equals("new") ? NEW_FIELDS : MANUAL_FIELDS;
        fields.forEach((name, value) -> {
            if (!limits.containsKey(name) || value == null || value.length() > limits.get(name)) {
                throw new IllegalArgumentException("草稿字段无效或内容超过上限");
            }
        });
        var saved = new Draft(Map.copyOf(fields), revision + 1, clock.instant());
        if (!repository.save(actor, key, revision, saved)) throw conflict();
        return saved;
    }
    public void checkRevision(ActorContext actor, String key, long revision) {
        if (find(actor, key).map(Draft::revision).orElse(0L) != revision) throw conflict();
    }
    public void discard(ActorContext actor, String key, long revision) {
        validateKey(actor, key);
        repository.delete(actor, key, revision);
    }
    private void validateKey(ActorContext actor, String key) {
        if ("new".equals(key)) return;
        try {
            String[] parts = key.split(":", -1);
            if (parts.length != 5 || !parts[0].equals("manual")
                    || !(parts[3].equals("original") || parts[3].equals("en"))) throw new IllegalArgumentException();
            ChannelType.valueOf(parts[2]);
            var article = articles.findArticleById(actor.tenantId(), UUID.fromString(parts[1]))
                    .orElseThrow(() -> new ApplicationException("ARTICLE_NOT_FOUND", "文章不存在"));
            if (article.currentVersion() != Integer.parseInt(parts[4])) {
                throw new ApplicationException("DRAFT_VERSION_CONFLICT", "主稿版本已变化，请保留当前平台文案后重新打开发布页");
            }
        } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("草稿标识无效"); }
    }
    private ApplicationException conflict() {
        return new ApplicationException("DRAFT_VERSION_CONFLICT", "其他页面已保存新的草稿。当前内容未覆盖，请复制备份后刷新核对");
    }
    public record Draft(Map<String, String> fields, long revision, Instant updatedAt) {}
}
