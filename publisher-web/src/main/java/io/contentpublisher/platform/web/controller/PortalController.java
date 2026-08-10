package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.web.security.LocalUserPrincipal;
import io.contentpublisher.platform.application.JobApplicationService;
import io.contentpublisher.platform.application.ProjectApplicationService;
import io.contentpublisher.platform.application.PublishingApplicationService;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PortalController {
    private final ProjectApplicationService projects;
    private final JobApplicationService jobs;
    private final PublishingApplicationService publishing;
    private final RequestActorProvider actors;

    public PortalController(ProjectApplicationService projects, JobApplicationService jobs,
                            PublishingApplicationService publishing, RequestActorProvider actors) {
        this.projects = projects;
        this.jobs = jobs;
        this.publishing = publishing;
        this.actors = actors;
    }

    @GetMapping("/login")
    public String login(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "redirect:/";
        }
        return "login";
    }

    @GetMapping("/")
    public String dashboard(Authentication authentication, Model model) {
        if (authentication.getPrincipal() instanceof LocalUserPrincipal principal) {
            model.addAttribute("username", principal.username());
            model.addAttribute("tenantId", principal.tenantId());
        } else {
            model.addAttribute("username", authentication.getName());
            model.addAttribute("tenantId", "-");
        }
        model.addAttribute("roles", authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .sorted().toList());
        var actor = actors.currentActor();
        var articleList = projects.listArticles(actor, 6);
        var jobList = jobs.listJobs(actor, 20);
        model.addAttribute("projectCount", projects.countProjects(actor));
        model.addAttribute("articleCount", projects.countArticles(actor));
        long readyCount = projects.searchArticles(actor, "", ArticleStatus.READY,
                null, "", 0, 1).totalItems()
                + projects.searchArticles(actor, "", ArticleStatus.APPROVED,
                null, "", 0, 1).totalItems();
        model.addAttribute("readyCount", readyCount);
        model.addAttribute("channelCount", publishing.countAccounts(actor));
        model.addAttribute("recentArticles", articleList);
        model.addAttribute("recentJobs", jobList.stream().limit(5).toList());
        model.addAttribute("articleStatusNames", PortalLabels.articleStatusNames());
        model.addAttribute("jobTypeNames", PortalLabels.jobTypeNames());
        model.addAttribute("jobStatusNames", PortalLabels.jobStatusNames());
        return "dashboard";
    }

    @GetMapping("/access-denied")
    public String accessDenied() {
        return "access-denied";
    }
}
