package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.AutomationApplicationService;
import io.contentpublisher.platform.application.AutomationApplicationService.PresetCommand;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.web.dto.NotificationEndpointView;
import io.contentpublisher.platform.web.form.AutomationPresetForm;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

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
        if (!model.containsAttribute("automationPresetForm")) {
            model.addAttribute("automationPresetForm", new AutomationPresetForm());
        }
        populateAutomation(model, actor);
        return "automation";
    }

    private void populateAutomation(Model model, ActorContext actor) {
        var generationPresets = Stream.of("PROJECT", "TOPIC", "WEBSITE")
                .flatMap(sourceType -> automation.presets(actor, sourceType).stream())
                .toList();
        var deliveries = automation.recentWebhookDeliveries(actor, 50);
        Map<UUID, AutomationApplicationService.WebhookDeliveryStatus> latestByEndpoint = new LinkedHashMap<>();
        deliveries.forEach(delivery -> latestByEndpoint.putIfAbsent(delivery.endpointId(), delivery));
        model.addAttribute("generationPresets", generationPresets);
        model.addAttribute("presetSourceNames", Map.of(
                "PROJECT", "Git 项目",
                "TOPIC", "主题教程",
                "WEBSITE", "网站推荐"));
        model.addAttribute("notificationEndpoints", automation.notificationEndpoints(actor).stream()
                .map(endpoint -> NotificationEndpointView.from(endpoint, latestByEndpoint.get(endpoint.id())))
                .toList());
        model.addAttribute("webhookDeliveries", deliveries);
        model.addAttribute("webhookStatusNames", Map.of(
                "PENDING", "等待投递",
                "DELIVERED", "已送达",
                "FAILED", "最终失败"));
    }

    @PostMapping("/automation/presets")
    public String savePreset(@Valid @ModelAttribute("automationPresetForm") AutomationPresetForm form,
                             BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        if (form.getMinCharacters() != null && form.getMaxCharacters() != null
                && form.getMaxCharacters() < form.getMinCharacters()) {
            bindingResult.rejectValue("maxCharacters", "range",
                    "最大字符数不能小于最小字符数");
        }
        if (bindingResult.hasErrors()) {
            populateAutomation(model, actors.currentActor());
            return "automation";
        }
        try {
            automation.savePreset(actors.currentActor(), new PresetCommand(
                    form.getName(), form.getSourceType(), form.getLanguage(), form.getTone(),
                    form.getMinCharacters(), form.getMaxCharacters(), form.getMaxKeywords(),
                    form.getRequiredSections(), form.getArticleType(), form.getKnowledgeLevel(),
                    form.getRecommendationAngle(), form.getModel(), "custom-v1", "custom-v1"));
            redirectAttributes.addFlashAttribute("success", "生成预设已保存");
            return "redirect:/automation";
        } catch (ApplicationException | IllegalArgumentException exception) {
            model.addAttribute("error", exception.getMessage());
            populateAutomation(model, actors.currentActor());
            return "automation";
        }
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

    @PostMapping("/automation/notification-endpoints/{endpointId}/status")
    public String updateEndpointStatus(@PathVariable UUID endpointId, @RequestParam boolean enabled,
                                       RedirectAttributes redirectAttributes) {
        try {
            automation.updateNotificationEndpointEnabled(actors.currentActor(), endpointId, enabled);
            redirectAttributes.addFlashAttribute("success", enabled ? "通知 Webhook 已启用" : "通知 Webhook 已停用");
        } catch (ApplicationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/automation#notification-endpoints";
    }

    @PostMapping("/automation/notification-endpoints/{endpointId}/test")
    public String testEndpoint(@PathVariable UUID endpointId, RedirectAttributes redirectAttributes) {
        try {
            automation.queueWebhookTest(actors.currentActor(), endpointId);
            redirectAttributes.addFlashAttribute("success", "测试通知已进入投递队列，请稍后查看投递记录");
        } catch (ApplicationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/automation#webhook-deliveries";
    }
}
