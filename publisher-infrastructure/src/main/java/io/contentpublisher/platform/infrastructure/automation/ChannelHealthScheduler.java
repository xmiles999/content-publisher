package io.contentpublisher.platform.infrastructure.automation;

import io.contentpublisher.platform.application.AutomationApplicationService;
import io.contentpublisher.platform.application.PublishingApplicationService;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.ChannelVerificationStatus;
import io.contentpublisher.platform.infrastructure.config.AutomationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class ChannelHealthScheduler {
    private static final Logger log = LoggerFactory.getLogger(ChannelHealthScheduler.class);
    private final AutomationApplicationService automation;
    private final PublishingApplicationService publishing;
    private final AutomationProperties properties;
    private final Clock clock;

    public ChannelHealthScheduler(AutomationApplicationService automation, PublishingApplicationService publishing,
                                  AutomationProperties properties, Clock clock) {
        this.automation = automation;
        this.publishing = publishing;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${publisher.automation.channel-health-interval:15m}")
    public void check() {
        if (!properties.channelHealthEnabled()) return;
        for (var target : automation.channelChecksDue(clock.instant().minus(properties.channelHealthStaleAfter()),
                properties.channelHealthBatchSize())) {
            ActorContext actor = new ActorContext(target.tenantId(), "system:channel-health");
            try {
                var account = publishing.verifyConnection(actor, target.accountId());
                if (account.verificationStatus() == ChannelVerificationStatus.FAILED
                        && !"FAILED".equals(target.previousStatus())) {
                    automation.notify(actor, "CHANNEL_HEALTH", "ERROR", "channel-health:" + target.accountId(),
                            "渠道连接巡检失败", account.displayName() + "：" + account.verificationMessage(),
                            "/channels#account-" + target.accountId());
                } else if (account.verificationStatus() == ChannelVerificationStatus.SUCCEEDED
                        && "FAILED".equals(target.previousStatus())) {
                    automation.notify(actor, "CHANNEL_HEALTH_RECOVERED", "INFO",
                            "channel-health-recovered:" + target.accountId(), "渠道连接已恢复",
                            account.displayName() + " 已通过自动连接巡检", "/channels#account-" + target.accountId());
                }
            } catch (Exception exception) {
                log.warn("channel health check failed accountId={}", target.accountId());
            }
        }
    }
}
