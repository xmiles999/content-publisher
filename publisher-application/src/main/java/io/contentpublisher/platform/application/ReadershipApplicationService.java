package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.*;
import io.contentpublisher.platform.domain.*;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ReadershipApplicationService {
    private final PublishingApplicationService publishing;
    private final PublicationRepository publications;
    private final ChannelAccountRepository accounts;
    private final ReadershipRepository repository;
    private final PublicationReadershipReader reader;
    private final AuditRecorder audit;
    private final Clock clock;
    private final Semaphore requests = new Semaphore(2);

    public ReadershipApplicationService(PublishingApplicationService publishing, PublicationRepository publications,
            ChannelAccountRepository accounts, ReadershipRepository repository, PublicationReadershipReader reader,
            AuditRecorder audit, Clock clock) {
        this.publishing = publishing;
        this.publications = publications;
        this.accounts = accounts;
        this.repository = repository;
        this.reader = reader;
        this.audit = audit;
        this.clock = clock;
    }

    public Map<String, PublicationReadership> snapshots(ActorContext actor, List<PublicationRecord> records) {
        var allowed = records.stream().map(ReadershipApplicationService::key).collect(Collectors.toSet());
        return repository.findByArticles(actor.tenantId(), records.stream().map(PublicationRecord::articleId).distinct().toList())
                .stream().filter(s -> allowed.contains(s.key()))
                .collect(Collectors.toMap(PublicationReadership::key, Function.identity()));
    }

    public PublicationRecord publication(ActorContext actor, UUID articleId, PublicationMethod method, UUID publicationId) {
        return publishing.getArticlePublicationRecords(actor, articleId).stream()
                .filter(p -> p.id().equals(publicationId) && p.method() == method && p.status() == PublicationStatus.PUBLISHED)
                .findFirst().orElseThrow(() -> error("READERSHIP_NOT_FOUND", "未找到当前文章的成功发布记录"));
    }

    public PublicationReadership snapshot(ActorContext actor, PublicationRecord record) {
        return repository.find(actor.tenantId(), record.method(), record.id()).orElseGet(() ->
                new PublicationReadership(actor.tenantId(), record.articleId(), record.method(), record.id(),
                        null, null, null, null, null, null, null, 0));
    }

    public static boolean supportsSync(PublicationRecord record) {
        return record.status() == PublicationStatus.PUBLISHED
                && ((record.method() == PublicationMethod.API
                    && (record.channelType() == ChannelType.DEV || record.channelType() == ChannelType.DISCOURSE))
                    || PublicReadershipArticle.articleId(record.channelType(), record.externalUrl()).isPresent());
    }

    public void saveManual(ActorContext actor, UUID articleId, PublicationMethod method, UUID publicationId,
                           int version, Long views, Long uniqueReaders) {
        var record = publication(actor, articleId, method, publicationId);
        if (views == null && uniqueReaders == null) throw error("READERSHIP_INVALID", "请至少填写一项统计；未知数据请留空");
        if ((views != null && views < 0) || (uniqueReaders != null && uniqueReaders < 0)
                || (views != null && uniqueReaders != null && uniqueReaders > views)) {
            throw error("READERSHIP_INVALID", "统计必须是非负整数，去重人数不能超过阅读次数");
        }
        var previous = snapshot(actor, record);
        checkVersion(previous, version);
        save(new PublicationReadership(actor.tenantId(), articleId, method, publicationId, views, uniqueReaders,
                "MANUAL", clock.instant(), actor.subject(), previous.lastSyncAt(), null, version + 1), version);
        audit.record(actor, "READERSHIP_MANUAL_SAVED", "PUBLICATION", publicationId, Map.of("method", method.name()));
    }

    public void sync(ActorContext actor, UUID articleId, PublicationMethod method, UUID publicationId, int version) {
        var record = publication(actor, articleId, method, publicationId);
        if (!supportsSync(record)) throw error("READERSHIP_UNSUPPORTED", "该发布记录暂不支持自动同步，请查看渠道接入说明");
        var previous = snapshot(actor, record);
        checkVersion(previous, version);
        if (previous.lastSyncAt() != null && previous.lastSyncAt().plusSeconds(30).isAfter(clock.instant())) {
            throw error("READERSHIP_COOLDOWN", "请在上次同步开始 30 秒后重试");
        }
        boolean publicArticle = PublicReadershipArticle.articleId(record.channelType(), record.externalUrl()).isPresent();
        Publication publication = null;
        ChannelAccount account = null;
        if (!publicArticle) {
            publication = publications.findPublicationById(actor.tenantId(), publicationId)
                .filter(p -> p.articleId().equals(articleId) && p.status() == PublicationStatus.PUBLISHED
                        && p.channelType() == record.channelType())
                .orElseThrow(() -> error("READERSHIP_NOT_FOUND", "发布记录已不可用"));
            account = accounts.findChannelAccountById(actor.tenantId(), publication.channelAccountId())
                .filter(a -> a.status() == ChannelAccountStatus.ACTIVE && a.type() == record.channelType())
                .orElseThrow(() -> error("READERSHIP_ACCOUNT_UNAVAILABLE", "发布账号已停用或移除；仍可手动录入统计"));
        }
        if (!requests.tryAcquire()) throw error("READERSHIP_BUSY", "正在同步其他统计，请稍后重试");
        try {
            var claimed = new PublicationReadership(actor.tenantId(), articleId, method, publicationId,
                    previous.views(), previous.uniqueReaders(), previous.source(), previous.recordedAt(),
                    previous.recordedBy(), clock.instant(), null, version + 1);
            save(claimed, version);
            long views;
            try {
                views = publicArticle ? reader.readPublicViews(record.channelType(), record.externalUrl())
                        : reader.readViews(account, publication.externalId());
            }
            catch (ApplicationException failure) {
                save(new PublicationReadership(actor.tenantId(), articleId, method, publicationId,
                        previous.views(), previous.uniqueReaders(), previous.source(), previous.recordedAt(),
                        previous.recordedBy(), claimed.lastSyncAt(), failure.getMessage(), claimed.version() + 1), claimed.version());
                throw failure;
            }
            save(new PublicationReadership(actor.tenantId(), articleId, method, publicationId, views, null, "API",
                    clock.instant(), actor.subject(), claimed.lastSyncAt(), null, claimed.version() + 1), claimed.version());
            audit.record(actor, "READERSHIP_SYNCED", "PUBLICATION", publicationId, Map.of("method", method.name()));
        } finally { requests.release(); }
    }

    private void save(PublicationReadership snapshot, int version) {
        if (!repository.saveIfVersionMatches(snapshot, version)) throw conflict();
    }
    private void checkVersion(PublicationReadership snapshot, int version) {
        if (version < 0 || snapshot.version() != version) throw conflict();
    }
    private ApplicationException conflict() { return error("READERSHIP_CONFLICT", "统计已被修改或正在同步。请刷新后核对，当前输入未覆盖已保存数据"); }
    private static ApplicationException error(String code, String message) { return new ApplicationException(code, message); }
    public static String key(PublicationRecord record) { return record.method().name() + ":" + record.id(); }
}
