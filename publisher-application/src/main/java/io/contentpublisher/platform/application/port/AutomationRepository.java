package io.contentpublisher.platform.application.port;

import io.contentpublisher.platform.application.AutomationApplicationService.ActionItem;
import io.contentpublisher.platform.application.AutomationApplicationService.ArticleDraft;
import io.contentpublisher.platform.application.AutomationApplicationService.CalendarItem;
import io.contentpublisher.platform.application.AutomationApplicationService.ChannelCheckTarget;
import io.contentpublisher.platform.application.AutomationApplicationService.GenerationPreset;
import io.contentpublisher.platform.application.AutomationApplicationService.ManualProgress;
import io.contentpublisher.platform.application.AutomationApplicationService.NavigationCounts;
import io.contentpublisher.platform.application.AutomationApplicationService.NotificationEndpoint;
import io.contentpublisher.platform.application.AutomationApplicationService.NotificationItem;
import io.contentpublisher.platform.application.AutomationApplicationService.WebhookDelivery;
import io.contentpublisher.platform.application.AutomationApplicationService.WebhookDeliveryStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AutomationRepository {
    Optional<ArticleDraft> findDraft(String tenantId, UUID articleId, String subject);
    ArticleDraft saveDraft(ArticleDraft draft);
    boolean deleteDraft(String tenantId, UUID articleId, String subject);
    List<GenerationPreset> findPresets(String tenantId, String sourceType);
    GenerationPreset savePreset(GenerationPreset preset);
    boolean deletePreset(String tenantId, UUID presetId);
    List<ActionItem> findActions(String tenantId, Instant staleBefore);
    List<CalendarItem> findCalendar(String tenantId, Instant from, Instant to);
    List<NotificationItem> findNotifications(String tenantId, boolean includeAcknowledged, int limit);
    NotificationItem saveNotification(NotificationItem notification);
    boolean acknowledgeNotification(String tenantId, UUID notificationId, String subject, Instant now);
    List<NotificationEndpoint> findNotificationEndpoints(String tenantId);
    List<NotificationEndpoint> findEnabledNotificationEndpoints(String tenantId);
    Optional<NotificationEndpoint> findNotificationEndpoint(String tenantId, UUID endpointId);
    NotificationEndpoint saveNotificationEndpoint(NotificationEndpoint endpoint);
    boolean updateNotificationEndpointEnabled(String tenantId, UUID endpointId, boolean enabled, Instant now);
    boolean deleteNotificationEndpoint(String tenantId, UUID endpointId);
    void prepareWebhookDeliveries(Instant now, int limit);
    boolean prepareWebhookDelivery(String tenantId, UUID notificationId, UUID endpointId, Instant now);
    List<WebhookDelivery> findWebhookDeliveriesDue(Instant now, int limit);
    List<WebhookDeliveryStatus> findRecentWebhookDeliveries(String tenantId, int limit);
    void markWebhookDeliverySucceeded(UUID deliveryId, Instant deliveredAt);
    void markWebhookDeliveryFailed(UUID deliveryId, int attempts, Instant nextAttemptAt, String errorSummary,
                                   boolean exhausted, Instant now);
    Optional<ManualProgress> findManualProgress(String tenantId, UUID articleId, String channelType, String subject);
    ManualProgress saveManualProgress(ManualProgress progress);
    List<ChannelCheckTarget> findChannelChecksDue(Instant checkedBefore, int limit);
    NavigationCounts navigationCounts(String tenantId, Instant staleBefore);
}
