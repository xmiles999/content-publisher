package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.AutomationApplicationService;
import io.contentpublisher.platform.application.ChannelCatalog;
import io.contentpublisher.platform.application.JobApplicationService;
import io.contentpublisher.platform.application.ProjectApplicationService;
import io.contentpublisher.platform.application.PublishingApplicationService;
import io.contentpublisher.platform.application.PublicationMethod;
import io.contentpublisher.platform.application.PublicationRecord;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ChannelAccountStatus;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.PublicationStatus;
import io.contentpublisher.platform.web.form.CreateChannelAccountForm;
import io.contentpublisher.platform.web.form.BatchPublishArticleForm;
import io.contentpublisher.platform.web.form.ManualPublicationForm;
import io.contentpublisher.platform.web.form.PublishArticleForm;
import io.contentpublisher.platform.web.dto.ChannelAccountView;
import io.contentpublisher.platform.web.dto.PublicationMatrixCell;
import io.contentpublisher.platform.web.dto.PublicationMatrixRow;
import io.contentpublisher.platform.web.dto.PublicationBatchView;
import io.contentpublisher.platform.web.dto.PortalPage;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

@Controller
public class PortalPublishingController {
    private final PublishingApplicationService publishing;
    private final ProjectApplicationService projects;
    private final JobApplicationService jobs;
    private final AutomationApplicationService automation;
    private final ScheduleParser scheduleParser;
    private final RequestActorProvider actors;

    public PortalPublishingController(PublishingApplicationService publishing,
                                      ProjectApplicationService projects,
                                      JobApplicationService jobs,
                                      AutomationApplicationService automation,
                                      ScheduleParser scheduleParser,
                                      RequestActorProvider actors) {
        this.publishing = publishing;
        this.projects = projects;
        this.jobs = jobs;
        this.automation = automation;
        this.scheduleParser = scheduleParser;
        this.actors = actors;
    }

    @GetMapping("/publishing")
    public String publishing(@RequestParam(defaultValue = "records") String tab,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) String channel,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String method,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size,
                             Model model) {
        var actor = actors.currentActor();
        var articles = projects.listArticles(actor, 50);
        var accountViews = publishing.listAccounts(actor).stream().map(ChannelAccountView::from).toList();
        var channelNames = channelNames();
        Map<UUID, String> articleNames = new java.util.HashMap<>();
        articles.forEach(article -> articleNames.put(article.id(), article.title()));
        Map<UUID, ChannelAccountView> accountsById = new java.util.HashMap<>();
        accountViews.forEach(account -> accountsById.put(account.id(), account));
        var publicationBatches = PublicationBatchView.aggregate(jobs.listJobs(actor, 100), articleNames,
                accountsById, channelNames);
        String selectedTab = normalizeTab(tab);
        String search = normalizeSearch(q);
        ChannelType selectedChannel = parseEnum(ChannelType.class, channel);
        PublicationStatus selectedStatus = parseEnum(PublicationStatus.class, status);
        PublicationMethod selectedMethod = parseEnum(PublicationMethod.class, method);
        int selectedPage = Math.min(100, Math.max(0, page));
        int selectedSize = size == 50 ? 50 : 20;
        var publicationRecordPage = publishing.searchPublicationRecords(actor, search, selectedChannel,
                selectedStatus, selectedMethod, selectedPage, selectedSize);
        long totalRecordCount = publishing.searchPublicationRecords(actor, "", null, null,
                null, 0, 1).totalItems();
        long publishedCount = publishing.searchPublicationRecords(actor, "", null,
                PublicationStatus.PUBLISHED, null, 0, 1).totalItems();
        List<Article> filteredArticles = articles.stream()
                .filter(article -> matchesText(search, article.title(), article.summary()))
                .toList();
        List<PublicationBatchView> filteredBatches = publicationBatches.stream()
                .filter(batch -> matchesBatch(batch, search))
                .toList();

        model.addAttribute("articles", articles);
        model.addAttribute("articlePage", PortalPage.from(filteredArticles, selectedPage, selectedSize));
        model.addAttribute("publicationRecordPage", publicationRecordPage);
        model.addAttribute("publicationBatchPage", PortalPage.from(filteredBatches, selectedPage, selectedSize));
        model.addAttribute("publicationRecords", publicationRecordPage.items());
        model.addAttribute("totalRecordCount", totalRecordCount);
        model.addAttribute("publicationBatches", publicationBatches);
        model.addAttribute("activeBatchCount", publicationBatches.stream()
                .filter(batch -> batch.activeCount() > 0).count());
        model.addAttribute("failedBatchCount", publicationBatches.stream()
                .filter(batch -> batch.failedCount() > 0).count());
        model.addAttribute("publicationMatrix", selectedTab.equals("coverage")
                ? publicationMatrix(actor, articles) : List.of());
        model.addAttribute("articleNames", articleNames);
        model.addAttribute("publishedCount", publishedCount);
        model.addAttribute("channelNames", channelNames);
        model.addAttribute("selectedTab", selectedTab);
        model.addAttribute("searchQuery", search);
        model.addAttribute("selectedChannel", selectedChannel);
        model.addAttribute("selectedStatus", selectedStatus);
        model.addAttribute("selectedMethod", selectedMethod);
        model.addAttribute("channelOptions", ChannelCatalog.all());
        model.addAttribute("statusOptions", PublicationStatus.values());
        model.addAttribute("methodOptions", PublicationMethod.values());
        model.addAttribute("articleStatusNames", PortalLabels.articleStatusNames());
        model.addAttribute("publicationStatusNames", PortalLabels.publicationStatusNames());
        model.addAttribute("publicationMethodNames", PortalLabels.publicationMethodNames());
        model.addAttribute("jobStatusNames", PortalLabels.jobStatusNames());
        return "publishing";
    }

    private List<PublicationMatrixRow> publicationMatrix(io.contentpublisher.platform.domain.ActorContext actor,
                                                          List<Article> articles) {
        var matrixRecords = publishing.listPublicationRecordsForArticles(actor,
                articles.stream().map(Article::id).toList());
        Map<ArticleChannelKey, PublicationRecord> latest = new java.util.HashMap<>();
        matrixRecords.forEach(record -> latest.putIfAbsent(
                new ArticleChannelKey(record.articleId(), record.channelType()), record));
        return articles.stream().map(article -> new PublicationMatrixRow(article, ChannelCatalog.all().stream()
                .map(channel -> new PublicationMatrixCell(channel,
                        latest.get(new ArticleChannelKey(article.id(), channel.type())))).toList())).toList();
    }

    private String normalizeTab(String tab) {
        String normalized = tab == null ? "" : tab.trim().toLowerCase(Locale.ROOT);
        return Set.of("queue", "batches", "records", "coverage").contains(normalized) ? normalized : "records";
    }

    private String normalizeSearch(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean matchesBatch(PublicationBatchView batch, String search) {
        if (matchesText(search, batch.articleTitle(), batch.statusLabel())) return true;
        return batch.items().stream().anyMatch(item -> matchesText(search, item.accountName(), item.channelName(),
                item.progressLabel(), item.errorMessage()));
    }

    private boolean matchesText(String search, String... values) {
        if (search == null || search.isBlank()) return true;
        String needle = search.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    @GetMapping("/publishing/articles/{articleId}")
    public String articlePublishing(@PathVariable UUID articleId, Model model) {
        var actor = actors.currentActor();
        Article article = publishing.getArticle(actor, articleId);
        model.addAttribute("article", article);
        model.addAttribute("articleStatusName", PortalLabels.articleStatusNames().get(article.status()));
        BatchPublishArticleForm form = new BatchPublishArticleForm();
        form.setIdempotencyKey(idempotencyKey("publish-batch"));
        model.addAttribute("batchPublishArticleForm", form);
        var activeAccounts = publishing.listAccounts(actor).stream()
                .filter(account -> account.status() == ChannelAccountStatus.ACTIVE).toList();
        model.addAttribute("channelAccounts", activeAccounts.stream().map(ChannelAccountView::from).toList());
        Map<UUID, io.contentpublisher.platform.application.PublicationPreflightResult> preflights =
                new java.util.HashMap<>();
        activeAccounts.forEach(account -> preflights.put(account.id(),
                publishing.preflight(actor, articleId, account.id())));
        model.addAttribute("publicationPreflights", preflights);
        model.addAttribute("readyChannelAccountCount", preflights.values().stream()
                .filter(io.contentpublisher.platform.application.PublicationPreflightResult::ready).count());
        model.addAttribute("manualTargets", ChannelCatalog.all().stream()
                .filter(ChannelCatalog.ChannelDefinition::manualAvailable).toList());
        model.addAttribute("publicationRecords", publishing.getArticlePublicationRecords(actor, articleId));
        model.addAttribute("channelNames", channelNames());
        Map<ChannelType, ChannelCatalog.ChannelRegion> channelRegions = new java.util.EnumMap<>(ChannelType.class);
        ChannelCatalog.all().forEach(channel -> channelRegions.put(channel.type(), channel.region()));
        model.addAttribute("channelRegions", channelRegions);
        return "article-publishing";
    }

    @GetMapping("/channels")
    public String channels(@RequestParam(defaultValue = "api") String view, Model model) {
        if (!model.containsAttribute("channelAccountForm")) {
            CreateChannelAccountForm form = new CreateChannelAccountForm();
            form.setIdempotencyKey(idempotencyKey("channel"));
            model.addAttribute("channelAccountForm", form);
        }
        model.addAttribute("selectedChannelView", "manual".equalsIgnoreCase(view) ? "manual" : "api");
        populateChannels(model);
        return "channels";
    }

    @PostMapping("/channels")
    public String createChannel(@Valid @ModelAttribute("channelAccountForm") CreateChannelAccountForm form,
                                BindingResult bindingResult, Model model,
                                RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            populateChannels(model);
            return "channels";
        }
        try {
            publishing.createAccount(actors.currentActor(), form.getType(), form.getDisplayName(),
                    blankToNull(form.getBaseUrl()), credentials(form), form.getIdempotencyKey());
            redirectAttributes.addFlashAttribute("success", "渠道账号已加密保存，可以用于自动发布");
            return "redirect:/channels";
        } catch (ApplicationException | IllegalArgumentException exception) {
            model.addAttribute("error", exception.getMessage());
            populateChannels(model);
            return "channels";
        }
    }

    @PostMapping("/channels/{accountId}/profile")
    public String updateChannelProfile(@PathVariable UUID accountId,
                                       @RequestParam int expectedVersion,
                                       @RequestParam String displayName,
                                       @RequestParam(required = false) String baseUrl,
                                       RedirectAttributes redirectAttributes) {
        try {
            publishing.updateAccountProfile(actors.currentActor(), accountId, expectedVersion, displayName,
                    blankToNull(baseUrl));
            redirectAttributes.addFlashAttribute("success", "渠道账号信息已更新");
        } catch (ApplicationException | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/channels#account-" + accountId;
    }

    @PostMapping("/channels/{accountId}/status")
    public String updateChannelStatus(@PathVariable UUID accountId,
                                      @RequestParam int expectedVersion,
                                      @RequestParam ChannelAccountStatus status,
                                      RedirectAttributes redirectAttributes) {
        try {
            publishing.updateAccountStatus(actors.currentActor(), accountId, expectedVersion, status);
            redirectAttributes.addFlashAttribute("success", status == ChannelAccountStatus.ACTIVE
                    ? "渠道账号已启用" : "渠道账号已停用");
        } catch (ApplicationException | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/channels#account-" + accountId;
    }

    @PostMapping("/channels/{accountId}/credentials")
    public String rotateChannelCredentials(@PathVariable UUID accountId,
                                           @RequestParam int expectedVersion,
                                           @RequestParam(required = false) String credentialOne,
                                           @RequestParam(required = false) String credentialTwo,
                                           @RequestParam(required = false) String credentialThree,
                                           @RequestParam(required = false) String credentialFour,
                                           @RequestParam(required = false) String credentialFive,
                                           RedirectAttributes redirectAttributes) {
        try {
            ChannelType type = publishing.getAccount(actors.currentActor(), accountId).type();
            publishing.rotateCredentials(actors.currentActor(), accountId, expectedVersion,
                    credentials(type, credentialOne, credentialTwo, credentialThree, credentialFour,
                            credentialFive));
            redirectAttributes.addFlashAttribute("success", "渠道凭据已安全轮换");
        } catch (ApplicationException | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/channels#account-" + accountId;
    }

    @PostMapping("/channels/{accountId}/verify")
    public String verifyChannelConnection(@PathVariable UUID accountId,
                                          RedirectAttributes redirectAttributes) {
        try {
            var verified = publishing.verifyConnection(actors.currentActor(), accountId);
            if (verified.verificationStatus()
                    == io.contentpublisher.platform.domain.ChannelVerificationStatus.SUCCEEDED) {
                redirectAttributes.addFlashAttribute("success", verified.verificationMessage());
            } else {
                redirectAttributes.addFlashAttribute("error", verified.verificationMessage());
            }
        } catch (ApplicationException | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/channels#account-" + accountId;
    }

    @PostMapping("/articles/{articleId}/publications")
    public String publishByApi(@PathVariable UUID articleId,
                               @Valid @ModelAttribute PublishArticleForm form,
                               BindingResult bindingResult,
                               RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("error", firstError(bindingResult));
            return "redirect:/publishing/articles/" + articleId;
        }
        try {
            var job = jobs.submitPublication(actors.currentActor(), articleId, form.getChannelAccountId(),
                    blankToNull(form.getCanonicalUrl()), form.getIdempotencyKey(),
                    scheduleParser.parse(form.getScheduledAt(), form.getScheduledAtOffset(), form.getTimeZone()));
            return "redirect:/jobs/" + job.id();
        } catch (ApplicationException | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
            return "redirect:/publishing/articles/" + articleId;
        }
    }

    @PostMapping("/articles/{articleId}/publication-batches")
    public String publishBatch(@PathVariable UUID articleId,
                               @Valid @ModelAttribute BatchPublishArticleForm form,
                               BindingResult bindingResult,
                               RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("error", firstError(bindingResult));
            return "redirect:/publishing/articles/" + articleId;
        }
        try {
            var submitted = jobs.submitPublications(actors.currentActor(), articleId, form.getChannelAccountIds(),
                    blankToNull(form.getCanonicalUrl()), form.getIdempotencyKey(),
                    scheduleParser.parse(form.getScheduledAt(), form.getScheduledAtOffset(), form.getTimeZone()));
            redirectAttributes.addFlashAttribute("success", (form.getScheduledAt() == null || form.getScheduledAt().isBlank()
                    ? "已提交 " : "已定时提交 ") + submitted.size() + " 个渠道发布任务");
        } catch (ApplicationException | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/publishing/articles/" + articleId;
    }

    @PostMapping("/jobs/{jobId}/publication-retry")
    public String retryPublication(@PathVariable UUID jobId,
                                   @org.springframework.web.bind.annotation.RequestParam String idempotencyKey,
                                   RedirectAttributes redirectAttributes) {
        try {
            var retried = jobs.retryFailedPublication(actors.currentActor(), jobId, idempotencyKey);
            redirectAttributes.addFlashAttribute("success", "失败平台已重新提交，任务进度将在批次看板中更新");
            return retried.batchId() == null
                    ? "redirect:/jobs/" + retried.id()
                    : "redirect:/publishing#batch-" + retried.batchId();
        } catch (ApplicationException | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
            return "redirect:/publishing";
        }
    }

    @GetMapping("/articles/{articleId}/manual/{channelType}")
    public String manualPublish(@PathVariable UUID articleId, @PathVariable ChannelType channelType, Model model,
                                RedirectAttributes redirectAttributes) {
        var actor = actors.currentActor();
        var article = publishing.getArticle(actor, articleId);
        var definition = ChannelCatalog.definition(channelType);
        if (!definition.manualAvailable()) {
            throw new ApplicationException("MANUAL_PUBLISH_UNAVAILABLE", "该渠道尚未配置人工发布入口");
        }
        io.contentpublisher.platform.domain.AdaptedContent adapted;
        try {
            adapted = publishing.adaptContent(actor, articleId, channelType, null);
        } catch (ApplicationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
            return "redirect:/publishing/articles/" + articleId;
        }
        if (!model.containsAttribute("manualPublicationForm")) {
            ManualPublicationForm form = new ManualPublicationForm();
            form.setContentFormat(adapted.format());
            form.setTitle(adapted.title());
            form.setContent(adapted.body());
            model.addAttribute("manualPublicationForm", form);
        }
        model.addAttribute("article", article);
        model.addAttribute("channel", definition);
        model.addAttribute("adapted", adapted);
        model.addAttribute("history", publishing.getManualPublications(actor, articleId).stream()
                .filter(item -> item.channelType() == channelType).toList());
        var progress = automation.manualProgress(actor, articleId, channelType.name())
                .orElse(new AutomationApplicationService.ManualProgress(null, actor.tenantId(), articleId,
                        channelType.name(), actor.subject(), false, false, false, false, false, Instant.EPOCH));
        model.addAttribute("manualProgress", progress);
        model.addAttribute("manualProgressUrl", "/api/v1/articles/" + articleId + "/manual/"
                + channelType.name() + "/progress");
        return "manual-publish";
    }

    @PostMapping("/articles/{articleId}/manual/{channelType}")
    public String completeManualPublish(@PathVariable UUID articleId, @PathVariable ChannelType channelType,
                                        @Valid @ModelAttribute("manualPublicationForm") ManualPublicationForm form,
                                        BindingResult bindingResult, Model model,
                                        RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return manualPublish(articleId, channelType, model, redirectAttributes);
        }
        try {
            var actor = actors.currentActor();
            publishing.completeManualPublication(actor, articleId, channelType, form.getTitle(),
                    form.getContent(), form.getContentFormat(), form.getExternalUrl());
            automation.saveManualProgress(actor, articleId, channelType.name(),
                    new AutomationApplicationService.ManualProgressCommand(true, true, true, true, true));
            redirectAttributes.addFlashAttribute("success", "人工发布结果已记录，内容快照和外链已留档");
            return "redirect:/publishing/articles/" + articleId;
        } catch (ApplicationException | IllegalArgumentException exception) {
            model.addAttribute("error", exception.getMessage());
            return manualPublish(articleId, channelType, model, redirectAttributes);
        }
    }

    private void populateChannels(Model model) {
        if (!model.containsAttribute("selectedChannelView")) model.addAttribute("selectedChannelView", "api");
        model.addAttribute("accounts", publishing.listAccounts(actors.currentActor()).stream()
                .map(ChannelAccountView::from).toList());
        model.addAttribute("apiChannels", ChannelCatalog.automated());
        model.addAttribute("manualChannels", ChannelCatalog.manualOnly());
        model.addAttribute("channelNames", channelNames());
        Map<ChannelType, ChannelCatalog.ChannelDefinition> definitions = new java.util.EnumMap<>(ChannelType.class);
        ChannelCatalog.all().forEach(definition -> definitions.put(definition.type(), definition));
        model.addAttribute("channelDefinitions", definitions);
        model.addAttribute("channelAccountStatusNames", PortalLabels.channelAccountStatusNames());
    }

    private Map<String, String> credentials(CreateChannelAccountForm form) {
        List<String> keys = ChannelCatalog.definition(form.getType()).credentialKeys();
        List<String> values = List.of(value(form.getCredentialOne()), value(form.getCredentialTwo()),
                value(form.getCredentialThree()), value(form.getCredentialFour()), value(form.getCredentialFive()));
        Map<String, String> credentials = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) credentials.put(keys.get(index), values.get(index));
        return credentials;
    }

    private Map<String, String> credentials(ChannelType type, String... values) {
        List<String> keys = ChannelCatalog.definition(type).credentialKeys();
        Map<String, String> credentials = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            String value = index < values.length ? values[index] : null;
            credentials.put(keys.get(index), value(value));
        }
        return credentials;
    }

    private Map<ChannelType, String> channelNames() {
        Map<ChannelType, String> names = new java.util.EnumMap<>(ChannelType.class);
        ChannelCatalog.all().forEach(channel -> names.put(channel.type(), channel.displayName()));
        return names;
    }

    private String idempotencyKey(String prefix) {
        return "portal:" + prefix + ":" + UUID.randomUUID();
    }

    private String firstError(BindingResult bindingResult) {
        var error = bindingResult.getFieldError();
        return error == null ? "表单参数校验失败" : error.getDefaultMessage();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private record ArticleChannelKey(UUID articleId, ChannelType channelType) {
    }
}
