package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.AiSettingsApplicationService;
import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.port.AiAvailabilityProbe;
import io.contentpublisher.platform.domain.AiProviderSettings;
import io.contentpublisher.platform.infrastructure.config.AiProperties;
import io.contentpublisher.platform.web.form.AiSettingsForm;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PortalAiSettingsController {
    private final AiSettingsApplicationService settingsService;
    private final RequestActorProvider actors;
    private final AiProperties defaults;
    private final AiAvailabilityProbe availabilityProbe;

    public PortalAiSettingsController(AiSettingsApplicationService settingsService,
                                      RequestActorProvider actors, AiProperties defaults,
                                      AiAvailabilityProbe availabilityProbe) {
        this.settingsService = settingsService;
        this.actors = actors;
        this.defaults = defaults;
        this.availabilityProbe = availabilityProbe;
    }

    @PostMapping("/settings/ai/test")
    public String testConnection(RedirectAttributes redirectAttributes) {
        try {
            availabilityProbe.check(actors.currentActor().tenantId());
            redirectAttributes.addFlashAttribute("success", "AI 服务可用：已通过当前模型完成测试请求");
        } catch (ApplicationException exception) {
            redirectAttributes.addFlashAttribute("error", "AI 服务不可用：" + exception.getMessage());
        }
        return "redirect:/settings/ai";
    }

    @GetMapping("/settings/ai")
    public String settings(Model model) {
        var saved = settingsService.getSettings(actors.currentActor());
        if (!model.containsAttribute("aiSettingsForm")) {
            model.addAttribute("aiSettingsForm", form(saved.orElse(null)));
        }
        populateStatus(model, saved.orElse(null));
        return "ai-settings";
    }

    @PostMapping("/settings/ai")
    public String save(@Valid @ModelAttribute("aiSettingsForm") AiSettingsForm form,
                       BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes) {
        AiProviderSettings existing = settingsService.getSettings(actors.currentActor()).orElse(null);
        if (bindingResult.hasErrors()) {
            populateStatus(model, existing);
            return "ai-settings";
        }
        try {
            settingsService.save(actors.currentActor(), form.getExpectedVersion(), form.isEnabled(),
                    form.getBaseUrl(), form.getModel(), form.getTimeoutSeconds(), form.getTemperature(),
                    form.getApiKey(), form.isClearApiKey());
            redirectAttributes.addFlashAttribute("success", "AI 配置已安全保存并立即生效");
            return "redirect:/settings/ai";
        } catch (ApplicationException | IllegalArgumentException exception) {
            model.addAttribute("error", exception.getMessage());
            populateStatus(model, existing);
            return "ai-settings";
        }
    }

    private AiSettingsForm form(AiProviderSettings saved) {
        AiSettingsForm form = new AiSettingsForm();
        form.setExpectedVersion(saved == null ? 0 : saved.version());
        form.setEnabled(saved == null ? defaults.enabled() : saved.enabled());
        form.setBaseUrl(saved == null ? defaults.baseUrl().toString() : saved.baseUrl());
        form.setModel(saved == null ? defaults.model() : saved.model());
        form.setTimeoutSeconds(saved == null ? Math.toIntExact(defaults.timeout().toSeconds()) : saved.timeoutSeconds());
        form.setTemperature(saved == null ? defaults.temperature() : saved.temperature());
        return form;
    }

    private void populateStatus(Model model, AiProviderSettings saved) {
        model.addAttribute("apiKeyConfigured", saved == null
                ? defaults.apiKey() != null && !defaults.apiKey().isBlank() : saved.apiKeyConfigured());
        model.addAttribute("savedInDatabase", saved != null);
        model.addAttribute("updatedAt", saved == null ? null : saved.updatedAt());
    }
}
