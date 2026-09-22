package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.AutomationApplicationService;
import io.contentpublisher.platform.application.ProjectApplicationService;
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
    private final AutomationApplicationService automation;
    private final RequestActorProvider actors;

    public PortalController(ProjectApplicationService projects, AutomationApplicationService automation,
                            RequestActorProvider actors) {
        this.projects = projects;
        this.automation = automation;
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
    public String dashboard(Model model) {
        var actor = actors.currentActor();
        var articleList = projects.listArticles(actor, 8);
        long draftCount = projects.searchArticles(actor, "", ArticleStatus.DRAFT, null, "", 0, 1).totalItems();
        long readyCount = projects.searchArticles(actor, "", ArticleStatus.READY, null, "", 0, 1).totalItems()
                + projects.searchArticles(actor, "", ArticleStatus.APPROVED, null, "", 0, 1).totalItems();
        var actions = automation.actions(actor);
        model.addAttribute("articleCount", projects.countArticles(actor));
        model.addAttribute("draftCount", draftCount);
        model.addAttribute("readyCount", readyCount);
        model.addAttribute("actionCount", actions.stream().mapToLong(AutomationApplicationService.ActionItem::count).sum());
        model.addAttribute("recentArticles", articleList);
        model.addAttribute("pendingActions", actions.stream().limit(4).toList());
        model.addAttribute("articleStatusNames", PortalLabels.articleStatusNames());
        return "dashboard";
    }

    @GetMapping("/access-denied")
    public String accessDenied() {
        return "access-denied";
    }
}
