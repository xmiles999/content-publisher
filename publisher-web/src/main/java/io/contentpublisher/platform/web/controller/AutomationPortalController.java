package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.AutomationApplicationService;
import io.contentpublisher.platform.application.AutomationApplicationService.PresetCommand;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Controller
public class AutomationPortalController {
    private final AutomationApplicationService automation;
    private final RequestActorProvider actors;

    public AutomationPortalController(AutomationApplicationService automation, RequestActorProvider actors) {
        this.automation = automation;
        this.actors = actors;
    }

    @GetMapping("/actions")
    public String actions(@RequestParam(defaultValue = "false") boolean history, Model model) {
        var actor = actors.currentActor();
        model.addAttribute("actions", automation.actions(actor));
        model.addAttribute("notifications", automation.notifications(actor, history));
        model.addAttribute("showNotificationHistory", history);
        return "actions";
    }

    @PostMapping("/actions/notifications/{notificationId}/acknowledge")
    public String acknowledge(@PathVariable UUID notificationId, RedirectAttributes redirectAttributes) {
        try {
            automation.acknowledgeNotification(actors.currentActor(), notificationId);
            redirectAttributes.addFlashAttribute("success", "通知已确认");
        } catch (ApplicationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/actions";
    }

    @GetMapping("/calendar")
    public String calendar(@RequestParam(required = false) LocalDate from,
                           @RequestParam(required = false) LocalDate to, Model model) {
        LocalDate start = from == null ? LocalDate.now(ZoneOffset.UTC).minusDays(7) : from;
        LocalDate end = to == null ? start.plusDays(42) : to;
        Instant fromInstant = start.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        model.addAttribute("calendarItems", automation.calendar(actors.currentActor(), fromInstant, toInstant));
        model.addAttribute("calendarFrom", start);
        model.addAttribute("calendarTo", end);
        return "calendar";
    }

    @GetMapping("/automation")
    public String automation(Model model) {
        var actor = actors.currentActor();
        model.addAttribute("projectPresets", automation.presets(actor, "PROJECT"));
        model.addAttribute("topicPresets", automation.presets(actor, "TOPIC"));
        model.addAttribute("websitePresets", automation.presets(actor, "WEBSITE"));
        model.addAttribute("notificationEndpoints", automation.notificationEndpoints(actor));
        return "automation";
    }

    @PostMapping("/automation/presets")
    public String savePreset(@RequestParam String name, @RequestParam String sourceType,
                             @RequestParam(required = false) String language, @RequestParam String tone,
                             @RequestParam int minCharacters, @RequestParam int maxCharacters,
                             @RequestParam int maxKeywords, @RequestParam(required = false) String requiredSections,
                             @RequestParam(required = false) String articleType,
                             @RequestParam(required = false) String knowledgeLevel,
                             @RequestParam(required = false) String recommendationAngle,
                             @RequestParam(required = false) String model,
                             RedirectAttributes redirectAttributes) {
        try {
            automation.savePreset(actors.currentActor(), new PresetCommand(name, sourceType, language, tone,
                    minCharacters, maxCharacters, maxKeywords, requiredSections, articleType, knowledgeLevel,
                    recommendationAngle, model, "custom-v1", "custom-v1"));
            redirectAttributes.addFlashAttribute("success", "生成预设已保存");
        } catch (ApplicationException | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/automation";
    }

    @PostMapping("/automation/presets/{presetId}/delete")
    public String deletePreset(@PathVariable UUID presetId, RedirectAttributes redirectAttributes) {
        try {
            automation.deletePreset(actors.currentActor(), presetId);
            redirectAttributes.addFlashAttribute("success", "生成预设已删除");
        } catch (ApplicationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/automation";
    }

    @PostMapping("/automation/notification-endpoints")
    public String saveEndpoint(@RequestParam String displayName, @RequestParam String webhookUrl,
                               RedirectAttributes redirectAttributes) {
        try {
            automation.saveNotificationEndpoint(actors.currentActor(), displayName, webhookUrl);
            redirectAttributes.addFlashAttribute("success", "通知 Webhook 已保存");
        } catch (ApplicationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/automation";
    }

    @PostMapping("/automation/notification-endpoints/{endpointId}/delete")
    public String deleteEndpoint(@PathVariable UUID endpointId, RedirectAttributes redirectAttributes) {
        try {
            automation.deleteNotificationEndpoint(actors.currentActor(), endpointId);
            redirectAttributes.addFlashAttribute("success", "通知 Webhook 已删除");
        } catch (ApplicationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/automation";
    }
}
