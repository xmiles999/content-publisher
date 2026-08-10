package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.AutomationApplicationService;
import io.contentpublisher.platform.application.AutomationApplicationService.DraftContent;
import io.contentpublisher.platform.application.AutomationApplicationService.ManualProgressCommand;
import io.contentpublisher.platform.application.AutomationApplicationService.PresetCommand;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AutomationController {
    private final AutomationApplicationService automation;
    private final RequestActorProvider actors;

    public AutomationController(AutomationApplicationService automation, RequestActorProvider actors) {
        this.automation = automation;
        this.actors = actors;
    }

    @GetMapping("/articles/{articleId}/draft")
    public ResponseEntity<AutomationApplicationService.ArticleDraft> draft(@PathVariable UUID articleId) {
        return automation.getDraft(actors.currentActor(), articleId)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/articles/{articleId}/draft")
    public AutomationApplicationService.ArticleDraft saveDraft(@PathVariable UUID articleId,
                                                               @RequestBody DraftRequest request) {
        return automation.saveDraft(actors.currentActor(), articleId, request.baseVersion(),
                new DraftContent(request.title(), request.summary(), request.markdown(), request.tags(),
                        request.keywords(), request.titleEn(), request.summaryEn(), request.markdownEn(),
                        request.tagsEn(), request.keywordsEn()));
    }

    @DeleteMapping("/articles/{articleId}/draft")
    public ResponseEntity<Void> deleteDraft(@PathVariable UUID articleId) {
        automation.deleteDraft(actors.currentActor(), articleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/generation-presets")
    public List<AutomationApplicationService.GenerationPreset> presets(@RequestParam String sourceType) {
        return automation.presets(actors.currentActor(), sourceType);
    }

    @PostMapping("/automation/presets")
    public AutomationApplicationService.GenerationPreset savePreset(@RequestBody PresetRequest request) {
        return automation.savePreset(actors.currentActor(), request.toCommand());
    }

    @DeleteMapping("/automation/presets/{presetId}")
    public ResponseEntity<Void> deletePreset(@PathVariable UUID presetId) {
        automation.deletePreset(actors.currentActor(), presetId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/notifications")
    public List<AutomationApplicationService.NotificationItem> notifications(
            @RequestParam(defaultValue = "false") boolean includeAcknowledged) {
        return automation.notifications(actors.currentActor(), includeAcknowledged);
    }

    @PostMapping("/notifications/{notificationId}/acknowledge")
    public ResponseEntity<Void> acknowledge(@PathVariable UUID notificationId) {
        automation.acknowledgeNotification(actors.currentActor(), notificationId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/articles/{articleId}/manual/{channelType}/progress")
    public AutomationApplicationService.ManualProgress saveManualProgress(
            @PathVariable UUID articleId, @PathVariable String channelType,
            @RequestBody ManualProgressRequest request) {
        return automation.saveManualProgress(actors.currentActor(), articleId, channelType,
                new ManualProgressCommand(request.copiedTitle(), request.copiedContent(), request.openedEditor(),
                        request.checkedFormat(), request.published()));
    }

    @GetMapping("/automation/notification-endpoints")
    public List<AutomationApplicationService.NotificationEndpoint> notificationEndpoints() {
        return automation.notificationEndpoints(actors.currentActor());
    }

    @PostMapping("/automation/notification-endpoints")
    public AutomationApplicationService.NotificationEndpoint saveNotificationEndpoint(
            @RequestBody NotificationEndpointRequest request) {
        return automation.saveNotificationEndpoint(actors.currentActor(), request.displayName(), request.webhookUrl());
    }

    @DeleteMapping("/automation/notification-endpoints/{endpointId}")
    public ResponseEntity<Void> deleteNotificationEndpoint(@PathVariable UUID endpointId) {
        automation.deleteNotificationEndpoint(actors.currentActor(), endpointId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/automation/notification-endpoints/{endpointId}")
    public AutomationApplicationService.NotificationEndpoint updateNotificationEndpoint(
            @PathVariable UUID endpointId, @RequestBody NotificationEndpointStatusRequest request) {
        return automation.updateNotificationEndpointEnabled(actors.currentActor(), endpointId, request.enabled());
    }

    @PostMapping("/automation/notification-endpoints/{endpointId}/test")
    public AutomationApplicationService.WebhookDeliveryStatus testNotificationEndpoint(
            @PathVariable UUID endpointId) {
        return automation.queueWebhookTest(actors.currentActor(), endpointId);
    }

    @GetMapping("/automation/webhook-deliveries")
    public List<AutomationApplicationService.WebhookDeliveryStatus> webhookDeliveries(
            @RequestParam(defaultValue = "50") int limit) {
        return automation.recentWebhookDeliveries(actors.currentActor(), limit);
    }

    public record DraftRequest(int baseVersion, String title, String summary, String markdown, List<String> tags,
                               List<String> keywords, String titleEn, String summaryEn, String markdownEn,
                               List<String> tagsEn, List<String> keywordsEn) {}

    public record PresetRequest(String name, String sourceType, String language, String tone, int minCharacters,
                                int maxCharacters, int maxKeywords, String requiredSections, String articleType,
                                String knowledgeLevel, String recommendationAngle, String model,
                                String promptVersion, String parameterVersion) {
        PresetCommand toCommand() {
            return new PresetCommand(name, sourceType, language, tone, minCharacters, maxCharacters, maxKeywords,
                    requiredSections, articleType, knowledgeLevel, recommendationAngle, model, promptVersion,
                    parameterVersion);
        }
    }

    public record ManualProgressRequest(boolean copiedTitle, boolean copiedContent, boolean openedEditor,
                                        boolean checkedFormat, boolean published) {}

    public record NotificationEndpointRequest(String displayName, String webhookUrl) {}
    public record NotificationEndpointStatusRequest(boolean enabled) {}
}
