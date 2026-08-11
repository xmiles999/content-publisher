package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.application.port.ChannelAccountRepository;
import io.contentpublisher.platform.application.port.ChannelEndpointPolicy;
import io.contentpublisher.platform.application.port.CredentialVault;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.ChannelAccount;
import io.contentpublisher.platform.domain.ChannelAccountStatus;
import io.contentpublisher.platform.domain.ChannelType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelAccountApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-11T03:00:00Z");
    private static final ActorContext ACTOR = new ActorContext("tenant-delete", "admin");

    @Test
    void shouldSoftDeleteAccountAndRecordAuditEvent() {
        ChannelAccountRepository repository = mock(ChannelAccountRepository.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        ChannelAccount account = account();
        when(repository.findChannelAccountById(ACTOR.tenantId(), account.id()))
                .thenReturn(Optional.of(account));
        when(repository.softDeleteIfVersionMatches(ACTOR.tenantId(), account.id(), 1,
                ACTOR.subject(), NOW)).thenReturn(true);

        service(repository, audits).removeAccount(ACTOR, account.id(), 1);

        verify(repository).softDeleteIfVersionMatches(ACTOR.tenantId(), account.id(), 1,
                ACTOR.subject(), NOW);
        verify(audits).record(eq(ACTOR), eq("CHANNEL_ACCOUNT_DELETED"), eq("CHANNEL_ACCOUNT"),
                eq(account.id()), eq(java.util.Map.of("channelType", "DEV", "version", "2")));
    }

    @Test
    void shouldRejectStaleDeleteBeforeChangingStoredAccount() {
        ChannelAccountRepository repository = mock(ChannelAccountRepository.class);
        AuditRecorder audits = mock(AuditRecorder.class);
        ChannelAccount account = account();
        when(repository.findChannelAccountById(ACTOR.tenantId(), account.id()))
                .thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service(repository, audits).removeAccount(ACTOR, account.id(), 2))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("CHANNEL_ACCOUNT_VERSION_CONFLICT"));

        verify(repository, never()).softDeleteIfVersionMatches(any(), any(), anyInt(), any(), any());
        verify(audits, never()).record(any(), any(), any(), any(), any());
    }

    private ChannelAccountApplicationService service(ChannelAccountRepository repository, AuditRecorder audits) {
        return new ChannelAccountApplicationService(repository, mock(CredentialVault.class),
                mock(ChannelEndpointPolicy.class), audits, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ChannelAccount account() {
        return new ChannelAccount(UUID.randomUUID(), ACTOR.tenantId(), ChannelType.DEV, "DEV",
                "https://dev.to", "v1:encrypted", "delete-account-001", "a".repeat(64),
                "b".repeat(64), 1, ChannelAccountStatus.ACTIVE, "admin", "admin", NOW, NOW);
    }
}
