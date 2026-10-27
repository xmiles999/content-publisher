package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.*;
import io.contentpublisher.platform.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadershipApplicationServiceTest {
    private final ActorContext actor = new ActorContext("personal", "owner");
    private final UUID articleId = UUID.randomUUID(), publicationId = UUID.randomUUID(), accountId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-27T04:00:00Z");
    private final PublishingApplicationService publishing = mock(PublishingApplicationService.class);
    private final PublicationRepository publications = mock(PublicationRepository.class);
    private final ChannelAccountRepository accounts = mock(ChannelAccountRepository.class);
    private final ReadershipRepository repository = mock(ReadershipRepository.class);
    private final PublicationReadershipReader reader = mock(PublicationReadershipReader.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final AtomicReference<PublicationReadership> stored = new AtomicReference<>();
    private final ReadershipApplicationService service = new ReadershipApplicationService(publishing, publications, accounts,
            repository, reader, audit, Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach void setup() {
        when(publishing.getArticlePublicationRecords(actor, articleId)).thenReturn(List.of(record(PublicationMethod.API, PublicationStatus.PUBLISHED)));
        when(repository.find(eq("personal"), any(), eq(publicationId))).thenAnswer(i -> Optional.ofNullable(stored.get()));
        when(repository.saveIfVersionMatches(any(), anyInt())).thenAnswer(i -> {
            PublicationReadership next = i.getArgument(0);
            int expected = i.getArgument(1);
            if ((stored.get() == null ? 0 : stored.get().version()) != expected) return false;
            stored.set(next);
            return true;
        });
        when(publications.findPublicationById("personal", publicationId)).thenReturn(Optional.of(new Publication(
                publicationId, "personal", articleId, accountId, UUID.randomUUID(), ChannelType.DEV, null,
                PublicationStatus.PUBLISHED, "123", "https://dev.to/author/test", null, null, now, now, now)));
        when(accounts.findChannelAccountById("personal", accountId)).thenReturn(Optional.of(new ChannelAccount(accountId,
                "personal", ChannelType.DEV, "个人账号", "https://dev.to", "ciphertext", "key", "hash", "fingerprint",
                1, ChannelAccountStatus.ACTIVE, "owner", "owner", now, now)));
    }

    @Test void keepsUnknownDistinctFromZeroAndAllowsManualOnlyReaderCounts() {
        assertThat(service.snapshot(actor, record(PublicationMethod.API, PublicationStatus.PUBLISHED)).hasCounts()).isFalse();
        service.saveManual(actor, articleId, PublicationMethod.API, publicationId, 0, 0L, null);
        assertThat(stored.get().views()).isZero();
        assertThat(stored.get().uniqueReaders()).isNull();
        service.saveManual(actor, articleId, PublicationMethod.API, publicationId, 1, null, 12L);
        assertThat(stored.get().views()).isNull();
        assertThat(stored.get().uniqueReaders()).isEqualTo(12);
        assertThat(stored.get().source()).isEqualTo("MANUAL");
        verifyNoInteractions(reader);
    }

    @Test void rejectsEmptyNegativeInconsistentOrStaleInput() {
        for (Long[] pair : new Long[][]{{null, null}, {-1L, null}, {1L, 2L}, {null, -1L}}) {
            assertThatThrownBy(() -> service.saveManual(actor, articleId, PublicationMethod.API, publicationId, 0, pair[0], pair[1]))
                    .isInstanceOf(ApplicationException.class);
        }
        service.saveManual(actor, articleId, PublicationMethod.API, publicationId, 0, 100L, 90L);
        assertThatThrownBy(() -> service.saveManual(actor, articleId, PublicationMethod.API, publicationId, 0, 5L, null))
                .hasMessageContaining("已被修改");
        assertThat(stored.get().views()).isEqualTo(100);
    }

    @Test void requiresSuccessfulRecordFromTheSameArticleAndTenant() {
        when(publishing.getArticlePublicationRecords(actor, articleId)).thenReturn(List.of(record(PublicationMethod.API, PublicationStatus.FAILED)));
        assertThatThrownBy(() -> service.saveManual(actor, articleId, PublicationMethod.API, publicationId, 0, 1L, null))
                .hasMessageContaining("成功发布记录");
        assertThatThrownBy(() -> service.publication(actor, articleId, PublicationMethod.MANUAL, publicationId))
                .isInstanceOf(ApplicationException.class);
        verify(repository, never()).saveIfVersionMatches(any(), anyInt());
    }

    @Test void successfulSyncStoresOfficialZeroAndDoesNotInventUniqueReaders() {
        service.saveManual(actor, articleId, PublicationMethod.API, publicationId, 0, 100L, 90L);
        when(reader.readViews(any(), eq("123"))).thenReturn(0L);
        service.sync(actor, articleId, PublicationMethod.API, publicationId, 1);
        assertThat(stored.get().source()).isEqualTo("API");
        assertThat(stored.get().views()).isZero();
        assertThat(stored.get().uniqueReaders()).isNull();
        assertThat(stored.get().version()).isEqualTo(3);
        assertThatThrownBy(() -> service.sync(actor, articleId, PublicationMethod.API, publicationId, 3))
                .hasMessageContaining("30 秒");
        verify(reader, times(1)).readViews(any(), eq("123"));
    }

    @Test void failedSyncKeepsLastGoodCountsSourceAndRecordingTime() {
        service.saveManual(actor, articleId, PublicationMethod.API, publicationId, 0, 100L, 90L);
        when(reader.readViews(any(), any())).thenThrow(new ApplicationException("READERSHIP_PERMISSION", "平台拒绝访问统计"));
        assertThatThrownBy(() -> service.sync(actor, articleId, PublicationMethod.API, publicationId, 1))
                .hasMessageContaining("拒绝访问");
        assertThat(stored.get().views()).isEqualTo(100);
        assertThat(stored.get().uniqueReaders()).isEqualTo(90);
        assertThat(stored.get().source()).isEqualTo("MANUAL");
        assertThat(stored.get().recordedAt()).isEqualTo(now);
        assertThat(stored.get().syncError()).contains("拒绝访问");
    }

    @Test void manualSaveDuringSyncWinsOverLateProviderResult() {
        when(reader.readViews(any(), any())).thenAnswer(i -> {
            service.saveManual(actor, articleId, PublicationMethod.API, publicationId, stored.get().version(), 456L, 123L);
            return 789L;
        });
        assertThatThrownBy(() -> service.sync(actor, articleId, PublicationMethod.API, publicationId, 0))
                .hasMessageContaining("已被修改");
        assertThat(stored.get().views()).isEqualTo(456);
        assertThat(stored.get().uniqueReaders()).isEqualTo(123);
        assertThat(stored.get().source()).isEqualTo("MANUAL");
    }

    @Test void firstSyncFailureRemainsUnknownAndLateFailureDoesNotOverwriteManualEdit() {
        when(reader.readViews(any(), any())).thenThrow(new ApplicationException("READERSHIP_PERMISSION", "没有统计权限"));
        assertThatThrownBy(() -> service.sync(actor, articleId, PublicationMethod.API, publicationId, 0)).isInstanceOf(ApplicationException.class);
        assertThat(stored.get().hasCounts()).isFalse();
        assertThat(stored.get().syncError()).isEqualTo("没有统计权限");
        stored.set(null);
        doAnswer(i -> {
            service.saveManual(actor, articleId, PublicationMethod.API, publicationId, stored.get().version(), 500L, null);
            throw new ApplicationException("READERSHIP_TIMEOUT", "请求超时");
        }).when(reader).readViews(any(), any());
        assertThatThrownBy(() -> service.sync(actor, articleId, PublicationMethod.API, publicationId, 0)).hasMessageContaining("已被修改");
        assertThat(stored.get().views()).isEqualTo(500);
        assertThat(stored.get().syncError()).isNull();
    }

    @Test void removedAccountAndManualRecordsDoNotCallOfficialApi() {
        when(accounts.findChannelAccountById("personal", accountId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.sync(actor, articleId, PublicationMethod.API, publicationId, 0)).hasMessageContaining("停用或移除");
        when(publishing.getArticlePublicationRecords(actor, articleId)).thenReturn(List.of(record(PublicationMethod.MANUAL, PublicationStatus.PUBLISHED)));
        assertThatThrownBy(() -> service.sync(actor, articleId, PublicationMethod.MANUAL, publicationId, 0)).hasMessageContaining("暂不支持");
        service.saveManual(actor, articleId, PublicationMethod.MANUAL, publicationId, 0, 1L, null);
        verifyNoInteractions(reader);
    }

    @Test void manualJuejinRecordReadsPublicLinkWithoutAnApiAccountAndKeepsCooldown() {
        String url = "https://juejin.cn/post/7698382188488048649?utm_source=share#heading";
        when(publishing.getArticlePublicationRecords(actor, articleId)).thenReturn(List.of(
                new PublicationRecord(publicationId, articleId, "文章", ChannelType.JUEJIN, PublicationMethod.MANUAL,
                        PublicationStatus.PUBLISHED, null, null, ContentFormat.MARKDOWN, url, null, null, "owner", now, now)));
        when(reader.readPublicViews(ChannelType.JUEJIN, url)).thenReturn(123L);
        service.sync(actor, articleId, PublicationMethod.MANUAL, publicationId, 0);
        assertThat(stored.get().views()).isEqualTo(123);
        assertThat(stored.get().uniqueReaders()).isNull();
        assertThat(stored.get().source()).isEqualTo("API");
        assertThatThrownBy(() -> service.sync(actor, articleId, PublicationMethod.MANUAL, publicationId, 2))
                .hasMessageContaining("30 秒");
        verifyNoInteractions(publications, accounts);
        verify(reader, never()).readViews(any(), any());
    }

    @Test void publicReadFailureKeepsManualCountsAndConcurrentEditWinsOverLateResult() {
        String url = "https://juejin.cn/post/123";
        when(publishing.getArticlePublicationRecords(actor, articleId)).thenReturn(List.of(
                new PublicationRecord(publicationId, articleId, "文章", ChannelType.JUEJIN, PublicationMethod.MANUAL,
                        PublicationStatus.PUBLISHED, null, null, ContentFormat.MARKDOWN, url, null, null, "owner", now, now)));
        service.saveManual(actor, articleId, PublicationMethod.MANUAL, publicationId, 0, 100L, 90L);
        when(reader.readPublicViews(any(), any())).thenThrow(new ApplicationException("READERSHIP_UNAVAILABLE", "无法读取公开统计"));
        assertThatThrownBy(() -> service.sync(actor, articleId, PublicationMethod.MANUAL, publicationId, 1)).hasMessageContaining("无法读取");
        assertThat(stored.get().views()).isEqualTo(100);
        assertThat(stored.get().uniqueReaders()).isEqualTo(90);
        assertThat(stored.get().source()).isEqualTo("MANUAL");
        stored.set(null);
        doAnswer(i -> {
            service.saveManual(actor, articleId, PublicationMethod.MANUAL, publicationId, stored.get().version(), 500L, null);
            return 789L;
        }).when(reader).readPublicViews(any(), any());
        assertThatThrownBy(() -> service.sync(actor, articleId, PublicationMethod.MANUAL, publicationId, 0)).hasMessageContaining("已被修改");
        assertThat(stored.get().views()).isEqualTo(500);
    }

    private PublicationRecord record(PublicationMethod method, PublicationStatus status) {
        return new PublicationRecord(publicationId, articleId, "文章", ChannelType.DEV, method, status, accountId,
                "个人账号", ContentFormat.MARKDOWN, "https://dev.to/author/test", null, null, "owner", now, now);
    }
}
