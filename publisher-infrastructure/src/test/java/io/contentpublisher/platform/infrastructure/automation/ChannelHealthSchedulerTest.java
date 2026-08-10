package io.contentpublisher.platform.infrastructure.automation;

import io.contentpublisher.platform.application.AutomationApplicationService;
import io.contentpublisher.platform.application.PublishingApplicationService;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.ChannelAccount;
import io.contentpublisher.platform.domain.ChannelAccountStatus;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.ChannelVerificationStatus;
import io.contentpublisher.platform.infrastructure.config.AutomationProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelHealthSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-10T05:00:00Z");
    private static final String TENANT = "tenant-health";
    private static final ActorContext SYSTEM_ACTOR = new ActorContext(TENANT, "system:channel-health");

    @Test
    void shouldNotifyWhenChannelChangesToFailed() {
        Fixture fixture = fixture(null, ChannelVerificationStatus.FAILED);

        fixture.scheduler.check();

        verify(fixture.publishing).verifyConnection(SYSTEM_ACTOR, fixture.accountId);
        verify(fixture.automation).notify(SYSTEM_ACTOR, "CHANNEL_HEALTH", "ERROR",
                "channel-health:" + fixture.accountId, "渠道连接巡检失败",
                "测试渠道：连接验证失败", "/channels#account-" + fixture.accountId);
    }

    @Test
    void shouldNotifyWhenFailedChannelRecovers() {
        Fixture fixture = fixture("FAILED", ChannelVerificationStatus.SUCCEEDED);

        fixture.scheduler.check();

        verify(fixture.automation).notify(SYSTEM_ACTOR, "CHANNEL_HEALTH_RECOVERED", "INFO",
                "channel-health-recovered:" + fixture.accountId, "渠道连接已恢复",
                "测试渠道 已通过自动连接巡检", "/channels#account-" + fixture.accountId);
    }

    @Test
    void shouldNotRepeatNotificationWhenVerificationStatusDidNotChange() {
        Fixture failed = fixture("FAILED", ChannelVerificationStatus.FAILED);
        Fixture succeeded = fixture("SUCCEEDED", ChannelVerificationStatus.SUCCEEDED);

        failed.scheduler.check();
        succeeded.scheduler.check();

        verify(failed.automation, never()).notify(any(), any(), any(), any(), any(), any(), any());
        verify(succeeded.automation, never()).notify(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldSkipChecksWhenAutomationIsDisabled() {
        AutomationApplicationService automation = mock(AutomationApplicationService.class);
        PublishingApplicationService publishing = mock(PublishingApplicationService.class);
        AutomationProperties properties = properties(false);
        var scheduler = new ChannelHealthScheduler(automation, publishing, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        scheduler.check();

        verify(automation, never()).channelChecksDue(any(), anyInt());
        verify(publishing, never()).verifyConnection(any(), any());
    }

    private Fixture fixture(String previousStatus, ChannelVerificationStatus currentStatus) {
        AutomationApplicationService automation = mock(AutomationApplicationService.class);
        PublishingApplicationService publishing = mock(PublishingApplicationService.class);
        UUID accountId = UUID.randomUUID();
        when(automation.channelChecksDue(eq(NOW.minus(Duration.ofHours(24))), eq(50)))
                .thenReturn(List.of(new AutomationApplicationService.ChannelCheckTarget(
                        TENANT, accountId, previousStatus)));
        when(publishing.verifyConnection(SYSTEM_ACTOR, accountId))
                .thenReturn(account(accountId, currentStatus));
        return new Fixture(automation, publishing,
                new ChannelHealthScheduler(automation, publishing, properties(true),
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                accountId);
    }

    private AutomationProperties properties(boolean enabled) {
        return new AutomationProperties(enabled, Duration.ofMinutes(15), Duration.ofHours(24), 50,
                false, Duration.ofSeconds(30), Duration.ofSeconds(10), 50, 4);
    }

    private ChannelAccount account(UUID accountId, ChannelVerificationStatus status) {
        return new ChannelAccount(accountId, TENANT, ChannelType.DEV, "测试渠道", "https://example.com",
                "encrypted", "health-account", "a".repeat(64), "b".repeat(64), 2,
                ChannelAccountStatus.ACTIVE, status,
                status == ChannelVerificationStatus.FAILED ? "连接验证失败" : "连接正常",
                NOW, "admin", "system:channel-health", NOW.minusSeconds(3600), NOW);
    }

    private record Fixture(AutomationApplicationService automation, PublishingApplicationService publishing,
                           ChannelHealthScheduler scheduler, UUID accountId) {}
}
