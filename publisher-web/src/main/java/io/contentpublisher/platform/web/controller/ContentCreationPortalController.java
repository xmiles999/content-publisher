package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.AiSettingsApplicationService;
import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.AutomationApplicationService;
import io.contentpublisher.platform.application.JobApplicationService;
import io.contentpublisher.platform.application.ProjectApplicationService;
import io.contentpublisher.platform.application.PublishingApplicationService;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.GenerationPolicy;
import io.contentpublisher.platform.domain.Job;
import io.contentpublisher.platform.domain.TopicBrief;
import io.contentpublisher.platform.domain.WebsiteBrief;
import io.contentpublisher.platform.infrastructure.config.AiProperties;
import io.contentpublisher.platform.web.form.CreateCustomArticleForm;
import io.contentpublisher.platform.web.form.CreateTopicArticleForm;
import io.contentpublisher.platform.web.form.CreateWebsiteArticleForm;
import io.contentpublisher.platform.web.form.GenerateArticleForm;
import io.contentpublisher.platform.web.form.ImportProjectForm;
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

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.contentpublisher.platform.web.controller.PortalFormSupport.firstError;
import static io.contentpublisher.platform.web.controller.PortalFormSupport.idempotencyKey;
import static io.contentpublisher.platform.web.controller.PortalFormSupport.splitValues;
import static io.contentpublisher.platform.web.controller.PortalFormSupport.trimToNull;

@Controller
public class ContentCreationPortalController {
    private static final int LIST_LIMIT = 50;

    private final ProjectApplicationService projects;
    private final PublishingApplicationService publishing;
    private final JobApplicationService jobs;
    private final RequestActorProvider actors;
    private final AiProperties aiProperties;
    private final AiSettingsApplicationService aiSettings;
    private final AutomationApplicationService automation;

    public ContentCreationPortalController(ProjectApplicationService projects,
                                           PublishingApplicationService publishing,
                                           JobApplicationService jobs,
                                           RequestActorProvider actors,
                                           AiProperties aiProperties,
                                           AiSettingsApplicationService aiSettings,
                                           AutomationApplicationService automation) {
        this.projects = projects;
        this.publishing = publishing;
        this.jobs = jobs;
        this.actors = actors;
        this.aiProperties = aiProperties;
        this.aiSettings = aiSettings;
        this.automation = automation;
    }

    @GetMapping("/projects")
    public String projects(@RequestParam(defaultValue = "custom") String source, Model model) {
        activateSource(model, normalizedSource(source));
        populateSourceForms(model);
        populateCreationWorkspace(model);
        return "projects";
    }

    @PostMapping("/articles/custom")
    public String createCustomArticle(@Valid @ModelAttribute("customArticleForm") CreateCustomArticleForm form,
                                      BindingResult bindingResult, Model model,
                                      RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) return invalidSourceForm(model, "custom");
        try {
            Article article = publishing.createCustomArticle(actors.currentActor(),
                    form.getTitle(), form.getSummary(), form.getMarkdown(),
                    splitValues(form.getTags()), splitValues(form.getKeywords()),
                    form.getTitleEn(), form.getSummaryEn(), form.getMarkdownEn(),
                    splitValues(form.getTagsEn()), splitValues(form.getKeywordsEn()),
                    form.getLanguage());
            redirectAttributes.addFlashAttribute("success", "自定义文章主稿已创建，可直接编辑确认或进入发布中心分发");
            return "redirect:/articles/" + article.id();
        } catch (ApplicationException | IllegalArgumentException exception) {
            model.addAttribute("error", exception.getMessage());
            return invalidSourceForm(model, "custom");
        }
    }

    @PostMapping("/articles/topic-generations")
    public String generateTopicArticle(@Valid @ModelAttribute("topicArticleForm") CreateTopicArticleForm form,
                                       BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) return invalidSourceForm(model, "topic");
        try {
            List<String> keywords = splitValues(form.getKeywords());
            TopicBrief brief = new TopicBrief(form.getTopic(), form.getDescription(), form.getAudience(),
                    form.getArticleType(), form.getKnowledgeLevel(), keywords, trimToNull(form.getReferenceNotes()));
            GenerationPolicy policy = new GenerationPolicy(form.getLanguage(), form.getTone(),
                    form.getMinCharacters(), form.getMaxCharacters(), form.getMaxKeywords(), keywords,
                    splitValues(form.getExcludedKeywords()), splitValues(form.getRequiredSections()));
            Job job = jobs.submitTopicArticleGeneration(actors.currentActor(), brief, policy, form.getIdempotencyKey());
            return "redirect:/jobs/" + job.id();
        } catch (ApplicationException | IllegalArgumentException exception) {
            model.addAttribute("error", exception.getMessage());
            return invalidSourceForm(model, "topic");
        }
    }

    @PostMapping("/articles/website-generations")
    public String generateWebsiteArticle(@Valid @ModelAttribute("websiteArticleForm") CreateWebsiteArticleForm form,
                                         BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) return invalidSourceForm(model, "website");
        try {
            List<String> keywords = splitValues(form.getKeywords());
            WebsiteBrief brief = new WebsiteBrief(form.getWebsiteUrl(), form.getRecommendationAngle(),
                    form.getAudience(), keywords);
            GenerationPolicy policy = new GenerationPolicy(form.getLanguage(), form.getTone(),
                    form.getMinCharacters(), form.getMaxCharacters(), form.getMaxKeywords(), keywords,
                    splitValues(form.getExcludedKeywords()), splitValues(form.getRequiredSections()));
            Job job = jobs.submitWebsiteArticleGeneration(actors.currentActor(), brief, policy,
                    form.getIdempotencyKey());
            return "redirect:/jobs/" + job.id();
        } catch (ApplicationException | IllegalArgumentException exception) {
            model.addAttribute("error", exception.getMessage());
            return invalidSourceForm(model, "website");
        }
    }

    @PostMapping("/projects/imports")
    public String importProject(@Valid @ModelAttribute("importProjectForm") ImportProjectForm form,
                                BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) return invalidSourceForm(model, "git");
        try {
            Job job = jobs.submitImport(actors.currentActor(), form.getGitUrl().trim(), trimToNull(form.getBranch()),
                    form.getIdempotencyKey());
            return "redirect:/jobs/" + job.id();
        } catch (ApplicationException | IllegalArgumentException exception) {
            model.addAttribute("error", exception.getMessage());
            return invalidSourceForm(model, "git");
        }
    }

    @GetMapping("/projects/{projectId}")
    public String project(@PathVariable UUID projectId, Model model) {
        var actor = actors.currentActor();
        model.addAttribute("project", projects.getProject(actor, projectId));
        model.addAttribute("articles", projects.listProjectArticles(actor, projectId, LIST_LIMIT));
        model.addAttribute("generateArticleForm", newGenerationForm());
        model.addAttribute("aiEnabled", aiEnabled());
        model.addAttribute("aiModel", aiModel());
        model.addAttribute("projectPresets", automation.presets(actor, "PROJECT"));
        return "project-detail";
    }

    @PostMapping("/projects/{projectId}/articles")
    public String generateArticle(@PathVariable UUID projectId,
                                  @Valid @ModelAttribute("generateArticleForm") GenerateArticleForm form,
                                  BindingResult bindingResult, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("error", firstError(bindingResult));
            return "redirect:/projects/" + projectId;
        }
        if (!aiEnabled()) {
            redirectAttributes.addFlashAttribute("error", "AI 内容生成尚未启用，请先配置 AI 服务");
            return "redirect:/projects/" + projectId;
        }
        try {
            GenerationPolicy policy = new GenerationPolicy(form.getLanguage().trim(), form.getTone().trim(),
                    form.getMinCharacters(), form.getMaxCharacters(), form.getMaxKeywords(),
                    splitValues(form.getRequiredKeywords()), splitValues(form.getExcludedKeywords()),
                    splitValues(form.getRequiredSections()));
            Job job = jobs.submitArticleGeneration(actors.currentActor(), projectId, policy,
                    form.getIdempotencyKey());
            return "redirect:/jobs/" + job.id();
        } catch (ApplicationException | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
            return "redirect:/projects/" + projectId;
        }
    }

    private String invalidSourceForm(Model model, String source) {
        activateSource(model, source);
        populateSourceForms(model);
        populateCreationWorkspace(model);
        return "projects";
    }

    private void populateCreationWorkspace(Model model) {
        var actor = actors.currentActor();
        model.addAttribute("projects", projects.listProjects(actor, LIST_LIMIT));
        model.addAttribute("aiEnabled", aiEnabled());
        model.addAttribute("aiModel", aiModel());
        model.addAttribute("topicPresets", automation.presets(actor, "TOPIC"));
        model.addAttribute("websitePresets", automation.presets(actor, "WEBSITE"));
    }

    private void populateSourceForms(Model model) {
        if (!model.containsAttribute("customArticleForm")) {
            CreateCustomArticleForm form = new CreateCustomArticleForm();
            model.addAttribute("customArticleForm", form);
        }
        if (!model.containsAttribute("importProjectForm")) {
            ImportProjectForm form = new ImportProjectForm();
            form.setIdempotencyKey(idempotencyKey("import"));
            model.addAttribute("importProjectForm", form);
        }
        if (!model.containsAttribute("topicArticleForm")) {
            CreateTopicArticleForm form = new CreateTopicArticleForm();
            form.setIdempotencyKey(idempotencyKey("topic"));
            model.addAttribute("topicArticleForm", form);
        }
        if (!model.containsAttribute("websiteArticleForm")) {
            CreateWebsiteArticleForm form = new CreateWebsiteArticleForm();
            form.setIdempotencyKey(idempotencyKey("website"));
            model.addAttribute("websiteArticleForm", form);
        }
    }

    private void activateSource(Model model, String source) {
        if (!model.containsAttribute("activeSourcePanel")) model.addAttribute("activeSourcePanel", source);
    }

    private String normalizedSource(String source) {
        String normalized = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "git", "topic", "website", "custom" -> normalized;
            default -> "custom";
        };
    }

    private GenerateArticleForm newGenerationForm() {
        GenerateArticleForm form = new GenerateArticleForm();
        form.setIdempotencyKey(idempotencyKey("article"));
        return form;
    }

    private boolean aiEnabled() {
        return aiSettings.getSettings(actors.currentActor()).map(settings -> settings.enabled())
                .orElse(aiProperties.enabled());
    }

    private String aiModel() {
        return aiSettings.getSettings(actors.currentActor()).map(settings -> settings.model())
                .orElse(aiProperties.model());
    }
}
