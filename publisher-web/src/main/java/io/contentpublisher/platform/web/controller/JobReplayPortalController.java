package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.JobApplicationService;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
public class JobReplayPortalController {
    private final JobApplicationService jobs;
    private final RequestActorProvider actors;

    public JobReplayPortalController(JobApplicationService jobs, RequestActorProvider actors) {
        this.jobs = jobs;
        this.actors = actors;
    }

    @PostMapping("/jobs/{jobId}/replay")
    public String replay(@PathVariable UUID jobId, @RequestParam String idempotencyKey,
                         RedirectAttributes redirectAttributes) {
        try {
            var replayed = jobs.replayFailedJob(actors.currentActor(), jobId, idempotencyKey);
            redirectAttributes.addFlashAttribute("success", "失败任务已重新提交");
            return "redirect:/jobs/" + replayed.id();
        } catch (ApplicationException | IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
            return "redirect:/jobs/" + jobId;
        }
    }
}
