package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.ChannelCatalog;
import io.contentpublisher.platform.application.JobApplicationService;
import io.contentpublisher.platform.application.MonitoringApplicationService;
import io.contentpublisher.platform.application.MonitoringSnapshot;
import io.contentpublisher.platform.application.MonitoringWindow;
import io.contentpublisher.platform.application.ProjectApplicationService;
import io.contentpublisher.platform.application.PublishingApplicationService;
import io.contentpublisher.platform.domain.ArticleSourceType;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.domain.ChannelAccountStatus;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.JobStatus;
import io.contentpublisher.platform.domain.JobType;
import io.contentpublisher.platform.domain.ProjectStatus;
import io.contentpublisher.platform.domain.PublicationStatus;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class PortalMonitoringController {
    private static final int RECENT_ACTIVITY_LIMIT = 100;

    private final MonitoringApplicationService monitoring;
    private final ProjectApplicationService projects;
    private final JobApplicationService jobs;
    private final PublishingApplicationService publishing;
    private final RequestActorProvider actors;

    public PortalMonitoringController(MonitoringApplicationService monitoring,
                                      ProjectApplicationService projects,
                                      JobApplicationService jobs,
                                      PublishingApplicationService publishing,
                                      RequestActorProvider actors) {
        this.monitoring = monitoring;
        this.projects = projects;
        this.jobs = jobs;
        this.publishing = publishing;
        this.actors = actors;
    }

    @GetMapping("/monitoring")
    public String monitoring(@RequestParam(name = "range", required = false) String range, Model model) {
        populateMonitoring(range, model);
        return "monitoring";
    }

    @GetMapping("/monitoring/live")
    public String monitoringLive(@RequestParam(name = "range", required = false) String range, Model model) {
        populateMonitoring(range, model);
        return "monitoring :: liveData";
    }

    private void populateMonitoring(String range, Model model) {
        var actor = actors.currentActor();
        MonitoringWindow window = MonitoringWindow.fromCode(range);
        MonitoringSnapshot snapshot = monitoring.snapshot(actor, window);
        var articleList = projects.listArticles(actor, RECENT_ACTIVITY_LIMIT);
        var jobList = jobs.listJobs(actor, RECENT_ACTIVITY_LIMIT);
        var publicationList = publishing.listPublicationRecords(actor, RECENT_ACTIVITY_LIMIT);

        long activeJobs = value(snapshot.jobsByStatus(), JobStatus.PENDING)
                + value(snapshot.jobsByStatus(), JobStatus.RUNNING)
                + value(snapshot.jobsByStatus(), JobStatus.RETRY_WAIT);
        long succeededJobs = value(snapshot.windowJobsByStatus(), JobStatus.SUCCEEDED);
        long failedJobs = value(snapshot.windowJobsByStatus(), JobStatus.FAILED);
        long publishedRecords = value(snapshot.windowPublicationsByStatus(), PublicationStatus.PUBLISHED);
        long failedPublications = value(snapshot.windowPublicationsByStatus(), PublicationStatus.FAILED);
        long activeAccounts = value(snapshot.accountsByStatus(), ChannelAccountStatus.ACTIVE);
        long publishableArticles = value(snapshot.articlesByStatus(), ArticleStatus.READY)
                + value(snapshot.articlesByStatus(), ArticleStatus.APPROVED)
                + value(snapshot.articlesByStatus(), ArticleStatus.PUBLISHED);

        model.addAttribute("capturedAt", snapshot.capturedAt());
        model.addAttribute("windowStart", snapshot.windowStart());
        model.addAttribute("selectedRange", window.code());
        model.addAttribute("rangeLabel", window.label());
        model.addAttribute("ranges", MonitoringWindow.values());
        model.addAttribute("projectCount", snapshot.projectCount());
        model.addAttribute("articleCount", snapshot.articleCount());
        model.addAttribute("activeJobCount", activeJobs);
        model.addAttribute("publicationCount", snapshot.publicationCount());
        model.addAttribute("projectActivityCount", snapshot.projectActivityCount());
        model.addAttribute("articleActivityCount", snapshot.articleActivityCount());
        model.addAttribute("jobActivityCount", snapshot.jobActivityCount());
        model.addAttribute("publicationActivityCount", snapshot.publicationActivityCount());
        model.addAttribute("readyProjectRate", percentage(
                value(snapshot.projectsByStatus(), ProjectStatus.READY), snapshot.projectCount()));
        model.addAttribute("jobSuccessRate", percentage(succeededJobs, succeededJobs + failedJobs));
        model.addAttribute("publicationSuccessRate", percentage(
                publishedRecords, publishedRecords + failedPublications));
        model.addAttribute("activeAccountRate", percentage(activeAccounts, snapshot.accountCount()));
        model.addAttribute("activeAccountCount", activeAccounts);
        model.addAttribute("accountCount", snapshot.accountCount());
        model.addAttribute("publishableArticleCount", publishableArticles);
        model.addAttribute("coveredChannelCount", snapshot.coveredChannelCount());
        model.addAttribute("supportedChannelCount", ChannelCatalog.all().size());
        model.addAttribute("failedJobCount", failedJobs);
        model.addAttribute("failedPublicationCount", failedPublications);
        model.addAttribute("disabledAccountCount",
                value(snapshot.accountsByStatus(), ChannelAccountStatus.DISABLED));
        model.addAttribute("systemStatus", systemStatus(activeJobs, failedJobs, failedPublications));
        model.addAttribute("systemStatusTone", systemStatusTone(failedJobs, failedPublications));
        model.addAttribute("projectStatus", projectStatus(snapshot));
        model.addAttribute("articleStatus", articleStatus(snapshot));
        model.addAttribute("jobStatus", jobStatus(snapshot));
        model.addAttribute("publicationStatus", publicationStatus(snapshot));
        model.addAttribute("sourceStatus", sourceStatus(snapshot));
        model.addAttribute("channelPerformance", channelPerformance(snapshot));
        model.addAttribute("recentJobs", jobList.stream().limit(8).toList());
        model.addAttribute("recentPublications", publicationList.stream().limit(8).toList());
        model.addAttribute("articleNames", articleNames(articleList));
        model.addAttribute("channelNames", channelNames());
        model.addAttribute("jobStatusNames", jobStatusNames());
        model.addAttribute("jobTypeNames", jobTypeNames());
        model.addAttribute("publicationStatusNames", publicationStatusNames());
        model.addAttribute("alerts", alerts(failedJobs, failedPublications,
                value(snapshot.accountsByStatus(), ChannelAccountStatus.DISABLED), activeJobs, window.label()));
    }

    private List<MonitorBar> projectStatus(MonitoringSnapshot snapshot) {
        return List.of(
                bar("就绪", value(snapshot.projectsByStatus(), ProjectStatus.READY), snapshot.projectCount(), "success"),
                bar("分析中", value(snapshot.projectsByStatus(), ProjectStatus.ANALYZING), snapshot.projectCount(), "primary"),
                bar("失败", value(snapshot.projectsByStatus(), ProjectStatus.FAILED), snapshot.projectCount(), "danger")
        );
    }

    private List<MonitorBar> articleStatus(MonitoringSnapshot snapshot) {
        return List.of(
                bar("草稿", value(snapshot.articlesByStatus(), ArticleStatus.DRAFT), snapshot.articleCount(), "warning"),
                bar("可发布", value(snapshot.articlesByStatus(), ArticleStatus.READY)
                        + value(snapshot.articlesByStatus(), ArticleStatus.APPROVED),
                        snapshot.articleCount(), "primary"),
                bar("已发布", value(snapshot.articlesByStatus(), ArticleStatus.PUBLISHED), snapshot.articleCount(), "success"),
                bar("需修改", value(snapshot.articlesByStatus(), ArticleStatus.REJECTED), snapshot.articleCount(), "danger")
        );
    }

    private List<MonitorBar> jobStatus(MonitoringSnapshot snapshot) {
        return List.of(
                bar("等待", value(snapshot.jobsByStatus(), JobStatus.PENDING), snapshot.jobCount(), "muted"),
                bar("运行中", value(snapshot.jobsByStatus(), JobStatus.RUNNING), snapshot.jobCount(), "primary"),
                bar("重试等待", value(snapshot.jobsByStatus(), JobStatus.RETRY_WAIT), snapshot.jobCount(), "warning"),
                bar("成功", value(snapshot.jobsByStatus(), JobStatus.SUCCEEDED), snapshot.jobCount(), "success"),
                bar("失败", value(snapshot.jobsByStatus(), JobStatus.FAILED), snapshot.jobCount(), "danger")
        );
    }

    private List<MonitorBar> publicationStatus(MonitoringSnapshot snapshot) {
        return List.of(
                bar("发布中", value(snapshot.publicationsByStatus(), PublicationStatus.PUBLISHING),
                        snapshot.publicationCount(), "primary"),
                bar("发布成功", value(snapshot.publicationsByStatus(), PublicationStatus.PUBLISHED),
                        snapshot.publicationCount(), "success"),
                bar("发布失败", value(snapshot.publicationsByStatus(), PublicationStatus.FAILED),
                        snapshot.publicationCount(), "danger")
        );
    }

    private List<MonitorBar> sourceStatus(MonitoringSnapshot snapshot) {
        return List.of(
                bar("Git 项目", value(snapshot.articlesBySource(), ArticleSourceType.GIT),
                        snapshot.articleCount(), "primary"),
                bar("主题教程", value(snapshot.articlesBySource(), ArticleSourceType.TOPIC),
                        snapshot.articleCount(), "success"),
                bar("网站推荐", value(snapshot.articlesBySource(), ArticleSourceType.WEBSITE),
                        snapshot.articleCount(), "warning")
        );
    }

    private List<ChannelMetric> channelPerformance(MonitoringSnapshot snapshot) {
        return snapshot.channelPerformance().stream().map(item -> new ChannelMetric(item.channelType(),
                        ChannelCatalog.definition(item.channelType()).displayName(), item.total(), item.published(),
                        item.failed(), percentage(item.published(), item.published() + item.failed())))
                .sorted(Comparator.comparingLong(ChannelMetric::total).reversed()
                        .thenComparing(ChannelMetric::displayName)).limit(8).toList();
    }

    private List<MonitorAlert> alerts(long failedJobs, long failedPublications, long disabledAccounts,
                                      long activeJobs, String rangeLabel) {
        List<MonitorAlert> alerts = new ArrayList<>();
        if (failedJobs > 0) alerts.add(new MonitorAlert("danger", "后台任务失败", failedJobs,
                rangeLabel + "内存在失败任务，请检查原因", "/jobs"));
        if (failedPublications > 0) alerts.add(new MonitorAlert("danger", "发布记录失败", failedPublications,
                rangeLabel + "内存在发布失败，请核对渠道与内容限制", "/publishing"));
        if (disabledAccounts > 0) alerts.add(new MonitorAlert("warning", "渠道账号停用", disabledAccounts,
                "停用账号不会接收新的自动发布任务", "/channels"));
        if (activeJobs > 0) alerts.add(new MonitorAlert("primary", "任务正在处理", activeJobs,
                "队列正在推进，页面会自动刷新", "/jobs"));
        if (alerts.isEmpty()) alerts.add(new MonitorAlert("success", "当前无运行异常", 0,
                rangeLabel + "内任务、渠道与发布链路均未发现异常", "/monitoring"));
        return alerts;
    }

    private Map<UUID, String> articleNames(List<io.contentpublisher.platform.domain.Article> articles) {
        Map<UUID, String> names = new java.util.HashMap<>();
        articles.forEach(article -> names.put(article.id(), article.title()));
        return names;
    }

    private Map<ChannelType, String> channelNames() {
        Map<ChannelType, String> names = new EnumMap<>(ChannelType.class);
        ChannelCatalog.all().forEach(channel -> names.put(channel.type(), channel.displayName()));
        return names;
    }

    private Map<JobStatus, String> jobStatusNames() {
        return Map.of(JobStatus.PENDING, "等待", JobStatus.RUNNING, "运行中", JobStatus.RETRY_WAIT, "等待重试",
                JobStatus.SUCCEEDED, "成功", JobStatus.FAILED, "失败", JobStatus.CANCELLED, "已取消");
    }

    private Map<JobType, String> jobTypeNames() {
        return Map.of(JobType.IMPORT_PROJECT, "导入项目", JobType.GENERATE_ARTICLE, "生成项目文章",
                JobType.GENERATE_TOPIC_ARTICLE, "生成主题教程",
                JobType.GENERATE_WEBSITE_ARTICLE, "生成网站推荐", JobType.PUBLISH_ARTICLE, "发布文章");
    }

    private Map<PublicationStatus, String> publicationStatusNames() {
        return Map.of(PublicationStatus.PUBLISHING, "发布中", PublicationStatus.PUBLISHED, "已发布",
                PublicationStatus.FAILED, "发布失败");
    }

    private String systemStatus(long activeJobs, long failedJobs, long failedPublications) {
        if (failedJobs + failedPublications > 0) return "需要关注";
        if (activeJobs > 0) return "任务处理中";
        return "运行正常";
    }

    private String systemStatusTone(long failedJobs, long failedPublications) {
        return failedJobs + failedPublications > 0 ? "warning" : "success";
    }

    private MonitorBar bar(String label, long value, long total, String tone) {
        return new MonitorBar(label, value, Math.max(total, 1), percentage(value, total), tone);
    }

    private int percentage(long value, long total) {
        if (total <= 0) return 0;
        return (int) Math.round(value * 100.0 / total);
    }

    private <E extends Enum<E>> long value(Map<E, Long> values, E key) {
        return values.getOrDefault(key, 0L);
    }

    public record MonitorBar(String label, long value, long total, int percentage, String tone) {
    }

    public record ChannelMetric(ChannelType type, String displayName, long total, long published, long failed,
                                int successRate) {
    }

    public record MonitorAlert(String tone, String title, long count, String description, String link) {
    }
}
