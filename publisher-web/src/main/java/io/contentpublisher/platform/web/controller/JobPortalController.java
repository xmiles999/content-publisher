package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.JobApplicationService;
import io.contentpublisher.platform.domain.Job;
import io.contentpublisher.platform.domain.JobStatus;
import io.contentpublisher.platform.domain.JobType;
import io.contentpublisher.platform.web.dto.JobProgressView;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.Locale;

@Controller
public class JobPortalController {
    private static final DateTimeFormatter JOB_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private final JobApplicationService jobs;
    private final RequestActorProvider actors;

    public JobPortalController(JobApplicationService jobs, RequestActorProvider actors) {
        this.jobs = jobs;
        this.actors = actors;
    }

    @GetMapping("/jobs")
    public String jobQueue(@RequestParam(required = false) String q,
                           @RequestParam(required = false) String type,
                           @RequestParam(required = false) String status,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size,
                           Model model) {
        var selectedType = parseEnum(JobType.class, type);
        var selectedStatus = parseEnum(JobStatus.class, status);
        int selectedPage = Math.max(0, page);
        int selectedSize = size == 50 ? 50 : 20;
        var jobPage = jobs.searchJobs(actors.currentActor(), q, selectedType, selectedStatus,
                selectedPage, selectedSize);
        model.addAttribute("jobs", jobPage.items());
        model.addAttribute("jobPage", jobPage);
        model.addAttribute("searchQuery", q == null ? "" : q.trim());
        model.addAttribute("selectedType", selectedType);
        model.addAttribute("selectedStatus", selectedStatus);
        model.addAttribute("jobTypeOptions", JobType.values());
        model.addAttribute("jobStatusOptions", JobStatus.values());
        model.addAttribute("jobTypeNames", PortalLabels.jobTypeNames());
        model.addAttribute("jobStatusNames", PortalLabels.jobStatusNames());
        return "jobs";
    }

    @GetMapping("/jobs/{jobId}")
    public String job(@PathVariable UUID jobId, Model model) {
        Job job = jobs.getJob(actors.currentActor(), jobId);
        model.addAttribute("job", job);
        if (job.payload() instanceof io.contentpublisher.platform.domain.JobPayload.PublishArticle publication) {
            model.addAttribute("publicationVersion", publication.articleVersion());
            model.addAttribute("publicationArticleId", publication.articleId());
        }
        model.addAttribute("active", job.status() == JobStatus.PENDING || job.status() == JobStatus.RUNNING
                || job.status() == JobStatus.RETRY_WAIT);
        model.addAttribute("progress", JobProgressView.from(job));
        model.addAttribute("resultLink", resultLink(job));
        model.addAttribute("jobDisplayName", PortalLabels.jobTypeNames().get(job.type()));
        model.addAttribute("jobStatusLabel", PortalLabels.jobStatusNames().get(job.status()));
        model.addAttribute("jobCreatedAt", JOB_TIME_FORMAT.format(job.createdAt()));
        model.addAttribute("jobUpdatedAt", JOB_TIME_FORMAT.format(job.updatedAt()));
        return "job-detail";
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public String cancel(@PathVariable UUID jobId, RedirectAttributes redirectAttributes) {
        try {
            jobs.cancelJob(actors.currentActor(), jobId);
            redirectAttributes.addFlashAttribute("success", "任务已取消");
        } catch (io.contentpublisher.platform.application.ApplicationException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/jobs/" + jobId;
    }

    private String resultLink(Job job) {
        if (job.status() != JobStatus.SUCCEEDED || job.resultResourceId() == null) return null;
        return switch (job.type()) {
            case IMPORT_PROJECT -> "/projects/" + job.resultResourceId();
            case GENERATE_ARTICLE, GENERATE_TOPIC_ARTICLE, GENERATE_WEBSITE_ARTICLE ->
                    "/articles/" + job.resultResourceId();
            case PUBLISH_ARTICLE -> null;
        };
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
