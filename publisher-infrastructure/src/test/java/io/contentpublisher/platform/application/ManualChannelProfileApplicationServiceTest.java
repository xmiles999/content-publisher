package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.ManualChannelProfileRepository;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.ManualChannelProfile;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualChannelProfileApplicationServiceTest {
    private static final ActorContext ACTOR = new ActorContext("personal", "owner");
    private static final Instant NOW = Instant.parse("2026-08-10T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void shouldCreateProfileAndNormalizePersonalDefaults() {
        ManualChannelProfileRepository repository = mock(ManualChannelProfileRepository.class);
        when(repository.findByChannel("personal", ChannelType.CSDN)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ManualChannelProfile saved = service(repository).saveProfile(ACTOR, ChannelType.CSDN, 0, true,
                "  个人技术号  ", List.of(" #Java ", "SPRING", "java", "", "  "),
                "  后端开发  ", "  发布前选择原创  ", 20);

        assertThat(saved.tenantId()).isEqualTo("personal");
        assertThat(saved.channelType()).isEqualTo(ChannelType.CSDN);
        assertThat(saved.accountAlias()).isEqualTo("个人技术号");
        assertThat(saved.defaultTags()).containsExactly("java", "spring");
        assertThat(saved.defaultSection()).isEqualTo("后端开发");
        assertThat(saved.notes()).isEqualTo("发布前选择原创");
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.createdAt()).isEqualTo(NOW);
        assertThat(saved.loginConfirmedAt()).isNull();
    }

    @Test
    void shouldUpdateWithOptimisticVersionAndKeepLoginConfirmation() {
        ManualChannelProfileRepository repository = mock(ManualChannelProfileRepository.class);
        Instant loginConfirmedAt = NOW.minusSeconds(3_600);
        ManualChannelProfile existing = profile(ChannelType.JUEJIN, true, 3, loginConfirmedAt);
        when(repository.findByChannel("personal", ChannelType.JUEJIN)).thenReturn(Optional.of(existing));
        when(repository.updateIfVersionMatches(any(), org.mockito.ArgumentMatchers.eq(3)))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));

        ManualChannelProfile updated = service(repository).saveProfile(ACTOR, ChannelType.JUEJIN, 3, false,
                "掘金个人号", List.of("Java"), "后端", "使用默认封面", 40);

        assertThat(updated.enabled()).isFalse();
        assertThat(updated.version()).isEqualTo(4);
        assertThat(updated.loginConfirmedAt()).isEqualTo(loginConfirmedAt);
        assertThat(updated.updatedAt()).isEqualTo(NOW);
        verify(repository).updateIfVersionMatches(any(), org.mockito.ArgumentMatchers.eq(3));
    }

    @Test
    void shouldRejectStaleVersionBeforeWriting() {
        ManualChannelProfileRepository repository = mock(ManualChannelProfileRepository.class);
        when(repository.findByChannel("personal", ChannelType.ZHIHU))
                .thenReturn(Optional.of(profile(ChannelType.ZHIHU, true, 2, null)));

        assertThatThrownBy(() -> service(repository).saveProfile(ACTOR, ChannelType.ZHIHU, 1, true,
                null, List.of(), null, null, 30))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("MANUAL_CHANNEL_PROFILE_VERSION_CONFLICT"));
        verify(repository, never()).save(any());
        verify(repository, never()).updateIfVersionMatches(any(), anyInt());
    }

    @Test
    void shouldRejectInvalidTagLimits() {
        ManualChannelProfileRepository repository = mock(ManualChannelProfileRepository.class);
        when(repository.findByChannel("personal", ChannelType.CNBLOGS)).thenReturn(Optional.empty());
        List<String> tooMany = IntStream.rangeClosed(1, 21).mapToObj(index -> "tag-" + index).toList();

        assertInvalid(() -> service(repository).saveProfile(ACTOR, ChannelType.CNBLOGS, 0, true,
                null, tooMany, null, null, 10), "默认标签不能超过 20 个");
        assertInvalid(() -> service(repository).saveProfile(ACTOR, ChannelType.CNBLOGS, 0, true,
                null, List.of("x".repeat(61)), null, null, 10), "单个默认标签不能超过 60 个字符");
    }

    @Test
    void shouldRejectApiChannelAsManualProfile() {
        ManualChannelProfileRepository repository = mock(ManualChannelProfileRepository.class);

        assertThatThrownBy(() -> service(repository).saveProfile(ACTOR, ChannelType.DEV, 0, true,
                null, List.of(), null, null, 10))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MANUAL_CHANNEL_UNAVAILABLE"));
        verify(repository, never()).findByChannel(any(), any());
    }

    @Test
    void shouldCreateDefaultEnabledProfileWhenLoginIsFirstConfirmed() {
        ManualChannelProfileRepository repository = mock(ManualChannelProfileRepository.class);
        when(repository.findByChannel("personal", ChannelType.XIAOHONGSHU)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ManualChannelProfile confirmed = service(repository)
                .confirmLogin(ACTOR, ChannelType.XIAOHONGSHU, 0);

        assertThat(confirmed.enabled()).isTrue();
        assertThat(confirmed.loginConfirmedAt()).isEqualTo(NOW);
        assertThat(confirmed.sortOrder()).isEqualTo(10);
        assertThat(confirmed.version()).isEqualTo(1);
    }

    @Test
    void shouldUpdateLoginConfirmationAndVersion() {
        ManualChannelProfileRepository repository = mock(ManualChannelProfileRepository.class);
        ManualChannelProfile existing = profile(ChannelType.WECHAT_OFFICIAL, true, 4, NOW.minusSeconds(7_200));
        when(repository.findByChannel("personal", ChannelType.WECHAT_OFFICIAL))
                .thenReturn(Optional.of(existing));
        when(repository.updateIfVersionMatches(any(), org.mockito.ArgumentMatchers.eq(4)))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));

        ManualChannelProfile confirmed = service(repository)
                .confirmLogin(ACTOR, ChannelType.WECHAT_OFFICIAL, 4);

        assertThat(confirmed.loginConfirmedAt()).isEqualTo(NOW);
        assertThat(confirmed.version()).isEqualTo(5);
        assertThat(confirmed.defaultTags()).containsExactly("java");
    }

    @Test
    void shouldTreatUnconfiguredChannelAsEnabledAndRespectSavedDisabledState() {
        ManualChannelProfileRepository repository = mock(ManualChannelProfileRepository.class);
        when(repository.findByChannel("personal", ChannelType.CSDN)).thenReturn(Optional.empty());
        when(repository.findByChannel("personal", ChannelType.V2EX))
                .thenReturn(Optional.of(profile(ChannelType.V2EX, false, 1, null)));
        ManualChannelProfileApplicationService service = service(repository);

        assertThat(service.isEnabled(ACTOR, ChannelType.CSDN)).isTrue();
        assertThat(service.isEnabled(ACTOR, ChannelType.V2EX)).isFalse();
    }

    private ManualChannelProfileApplicationService service(ManualChannelProfileRepository repository) {
        return new ManualChannelProfileApplicationService(repository, CLOCK);
    }

    private ManualChannelProfile profile(ChannelType channelType, boolean enabled, int version,
                                         Instant loginConfirmedAt) {
        return new ManualChannelProfile(UUID.randomUUID(), "personal", channelType, enabled,
                "个人账号", List.of("java"), "后端", "个人备注", 30, loginConfirmedAt, version,
                "owner", "owner", NOW.minusSeconds(86_400), NOW.minusSeconds(3_600));
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String message) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ApplicationException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("MANUAL_CHANNEL_PROFILE_INVALID");
                    assertThat(exception.getMessage()).isEqualTo(message);
                });
    }
}
