package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.AutomationApplicationService;
import io.contentpublisher.platform.application.ProjectApplicationService;
import io.contentpublisher.platform.application.PublishingApplicationService;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleSourceType;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.web.dto.ArticleSeoView;
import io.contentpublisher.platform.web.form.UpdateArticleForm;
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

import java.util.UUID;
import java.util.Locale;

import static io.contentpublisher.platform.web.controller.PortalFormSupport.firstError;
import static io.contentpublisher.platform.web.controller.PortalFormSupport.splitValues;

@Controller
public class ContentLibraryPortalController {
    private final ProjectApplicationService projects;
    private final PublishingApplicationService publishing;
    private final AutomationApplicationService automation;
    private final RequestActorProvider actors;

    public ContentLibraryPortalController(ProjectApplicationService projects,
                                          PublishingApplicationService publishing,
                                          AutomationApplicationService automation,
                                          RequestActorProvider actors) {
        this.projects = projects;
        this.publishing = publishing;
        this.automation = automation;
        this.actors = actors;
    }

    @GetMapping("/content")
    public String content(@RequestParam(required = false) String q,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) String source,
                          @RequestParam(required = false) String language,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "20") int size,
                          Model model) {
        var selectedStatus = parseEnum(ArticleStatus.class, status);
        var selectedSource = parseEnum(ArticleSourceType.class, source);
        int selectedPage = Math.max(0, page);
        int selectedSize = size == 50 ? 50 : 20;
        var articlePage = projects.searchArticles(actors.currentActor(), q, selectedStatus, selectedSource,
                language, selectedPage, selectedSize);
        model.addAttribute("articles", articlePage.items());
        model.addAttribute("articlePage", articlePage);
        model.addAttribute("searchQuery", q == null ? "" : q.trim());
        model.addAttribute("selectedStatus", selectedStatus);
        model.addAttribute("selectedSource", selectedSource);
        model.addAttribute("selectedLanguage", language == null ? "" : language.trim());
        model.addAttribute("statusOptions", ArticleStatus.values());
        model.addAttribute("sourceOptions", ArticleSourceType.values());
        model.addAttribute("articleStatusNames", PortalLabels.articleStatusNames());
        model.addAttribute("articleSourceNames", PortalLabels.articleSourceNames());
        return "content-records";
    }

    @GetMapping("/articles/{articleId}")
    public String article(@PathVariable UUID articleId,
                          @RequestParam(required = false) Integer fromVersion,
                          @RequestParam(required = false) Integer toVersion,
                          Model model) {
        var actor = actors.currentActor();
        Article article = publishing.getArticle(actor, articleId);
        model.addAttribute("article", article);
        model.addAttribute("seo", ArticleSeoView.from(article));
        var versions = publishing.getArticleVersions(actor, articleId);
        model.addAttribute("versions", versions);
        if (fromVersion != null) {
            model.addAttribute("comparisonFrom", versions.stream()
                    .filter(version -> version.versionNumber() == fromVersion).findFirst().orElse(null));
            int targetVersion = toVersion == null ? article.currentVersion() : toVersion;
            model.addAttribute("comparisonTo", versions.stream()
                    .filter(version -> version.versionNumber() == targetVersion).findFirst().orElse(null));
        }
        var draft = automation.getDraft(actor, articleId);
        model.addAttribute("updateArticleForm", draft.map(this::articleForm).orElseGet(() -> articleForm(article)));
        model.addAttribute("hasServerDraft", draft.isPresent());
        model.addAttribute("draftUpdatedAt", draft.map(AutomationApplicationService.ArticleDraft::updatedAt).orElse(null));
        model.addAttribute("editable", article.status().isEditable());
        model.addAttribute("publishable", article.status().isPublishable());
        model.addAttribute("reopenable", article.status() == ArticleStatus.READY
                || article.status() == ArticleStatus.APPROVED);
        model.addAttribute("articleStatusNames", PortalLabels.articleStatusNames());
        return "article-detail";
    }

    @PostMapping("/articles/{articleId}/versions/{sourceVersion}/restore")
    public String restoreVersion(@PathVariable UUID articleId, @PathVariable int sourceVersion,
                                 @RequestParam int expectedVersion,
                                 RedirectAttributes redirectAttributes) {
        try {
            publishing.restoreArticleVersion(actors.currentActor(), articleId, expectedVersion, sourceVersion);
            redirectAttributes.addFlashAttribute("success", "历史版本已恢复为新的当前版本");
        } catch (ApplicationException | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/articles/" + articleId;
    }

    @PostMapping("/articles/{articleId}/edit")
    public String updateArticle(@PathVariable UUID articleId,
                                @Valid @ModelAttribute UpdateArticleForm form, BindingResult bindingResult,
                                RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("error", firstError(bindingResult));
            return "redirect:/articles/" + articleId;
        }
        try {
            publishing.updateArticle(actors.currentActor(), articleId, form.getExpectedVersion(), form.getTitle(),
                    form.getSummary(), form.getMarkdown(), splitValues(form.getTags()), splitValues(form.getKeywords()),
                    form.getTitleEn(), form.getSummaryEn(), form.getMarkdownEn(), splitValues(form.getTagsEn()),
                    splitValues(form.getKeywordsEn()));
            automation.deleteDraft(actors.currentActor(), articleId);
            redirectAttributes.addFlashAttribute("success", "文章已保存为新版本");
        } catch (ApplicationException | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/articles/" + articleId;
    }

    @PostMapping("/articles/{articleId}/confirm")
    public String confirmArticle(@PathVariable UUID articleId, RedirectAttributes redirectAttributes) {
        try {
            publishing.confirmArticle(actors.currentActor(), articleId);
            redirectAttributes.addFlashAttribute("success", "当前内容版本已确认，可以进入发布流程");
        } catch (ApplicationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/articles/" + articleId;
    }

    @PostMapping("/articles/{articleId}/reopen")
    public String reopenArticle(@PathVariable UUID articleId, RedirectAttributes redirectAttributes) {
        try {
            publishing.reopenArticle(actors.currentActor(), articleId);
            redirectAttributes.addFlashAttribute("success", "当前内容已重新进入编辑状态");
        } catch (ApplicationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/articles/" + articleId;
    }

    private UpdateArticleForm articleForm(Article article) {
        UpdateArticleForm form = new UpdateArticleForm();
        form.setExpectedVersion(article.currentVersion());
        form.setTitle(article.title());
        form.setSummary(article.summary());
        form.setMarkdown(article.markdown());
        form.setTags(String.join("\n", article.tags()));
        form.setKeywords(String.join("\n", article.keywords()));
        form.setTitleEn(article.titleEn());
        form.setSummaryEn(article.summaryEn());
        form.setMarkdownEn(article.markdownEn());
        form.setTagsEn(String.join("\n", article.tagsEn()));
        form.setKeywordsEn(String.join("\n", article.keywordsEn()));
        return form;
    }

    private UpdateArticleForm articleForm(AutomationApplicationService.ArticleDraft draft) {
        UpdateArticleForm form = new UpdateArticleForm();
        form.setExpectedVersion(draft.baseVersion());
        form.setTitle(draft.title());
        form.setSummary(draft.summary());
        form.setMarkdown(draft.markdown());
        form.setTags(String.join("\n", draft.tags()));
        form.setKeywords(String.join("\n", draft.keywords()));
        form.setTitleEn(draft.titleEn());
        form.setSummaryEn(draft.summaryEn());
        form.setMarkdownEn(draft.markdownEn());
        form.setTagsEn(String.join("\n", draft.tagsEn()));
        form.setKeywordsEn(String.join("\n", draft.keywordsEn()));
        return form;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
