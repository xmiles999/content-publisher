package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.AutomationApplicationService;
import io.contentpublisher.platform.web.security.LocalUserPrincipal;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@ControllerAdvice
public class PortalModelAdvice {
    private final AutomationApplicationService automation;
    private final RequestActorProvider actors;

    public PortalModelAdvice(AutomationApplicationService automation, RequestActorProvider actors) {
        this.automation = automation;
        this.actors = actors;
    }

    @ModelAttribute("currentUsername")
    public String currentUsername(Authentication authentication) {
        if (authentication == null) return "";
        return authentication.getPrincipal() instanceof LocalUserPrincipal principal
                ? principal.username() : authentication.getName();
    }

    @ModelAttribute("currentTenant")
    public String currentTenant(Authentication authentication) {
        if (authentication == null) return "";
        return authentication.getPrincipal() instanceof LocalUserPrincipal principal ? principal.tenantId() : "-";
    }

    @ModelAttribute("currentRoles")
    public List<String> currentRoles(Authentication authentication) {
        if (authentication == null) return List.of();
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .sorted().toList();
    }

    @ModelAttribute("currentRoleNames")
    public List<String> currentRoleNames(Authentication authentication) {
        Map<String, String> names = Map.of(
                "ADMIN", "管理员",
                "EDITOR", "编辑",
                "VIEWER", "只读用户");
        return currentRoles(authentication).stream().map(role -> names.getOrDefault(role, role)).toList();
    }

    @ModelAttribute("canAdmin")
    public boolean canAdmin(Authentication authentication) {
        return hasRole(authentication, "ROLE_ADMIN");
    }

    @ModelAttribute("canEdit")
    public boolean canEdit(Authentication authentication) {
        return hasRole(authentication, "ROLE_EDITOR") || hasRole(authentication, "ROLE_ADMIN");
    }

    @ModelAttribute("activeItem")
    public String activeItem(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("/".equals(path)) return "dashboard";
        if (path.startsWith("/projects")) {
            String source = normalized(request.getParameter("source"));
            return switch (source) {
                case "git" -> "projects-git";
                case "topic" -> "projects-topic";
                case "website" -> "projects-website";
                default -> "projects";
            };
        }
        if (path.equals("/content") || path.matches("/articles/[^/]+(?:/edit|/versions.*)?")) return "content";
        if (path.equals("/actions")) return "actions";
        if (path.startsWith("/publishing/articles") || path.contains("/manual/")) return "publishing";
        if (path.equals("/publishing")) {
            String tab = normalized(request.getParameter("tab"));
            return switch (tab) {
                case "queue" -> "publishing-queue";
                case "batches" -> "publishing-batches";
                case "coverage" -> "publishing-coverage";
                default -> "publishing-records";
            };
        }
        if (path.startsWith("/channels")) return "channels";
        if (path.startsWith("/calendar")) return "calendar";
        if (path.startsWith("/monitoring")) return "monitoring";
        if (path.startsWith("/jobs")) return "jobs";
        if (path.startsWith("/recycle-bin")) return "recycle";
        if (path.startsWith("/settings/ai")) return "ai";
        if (path.startsWith("/automation")) return "automation";
        return "";
    }

    @ModelAttribute("activeSection")
    public String activeSection(HttpServletRequest request) {
        String item = activeItem(request);
        if (item.startsWith("projects")) return "content";
        if (item.startsWith("publishing") || item.equals("channels") || item.equals("calendar")) {
            return "publishing";
        }
        if (item.equals("monitoring") || item.equals("jobs")) return "operations";
        return "";
    }

    @ModelAttribute("navigationCounts")
    public AutomationApplicationService.NavigationCounts navigationCounts(Authentication authentication,
                                                                          HttpServletRequest request) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())
                || !usesPortalNavigation(request.getRequestURI())) {
            return new AutomationApplicationService.NavigationCounts(0, 0, 0);
        }
        return automation.navigationCounts(actors.currentActor());
    }

    private boolean usesPortalNavigation(String path) {
        return !path.equals("/error") && !path.equals("/login") && !path.equals("/change-password")
                && !path.startsWith("/api/") && !path.startsWith("/actuator/")
                && !path.startsWith("/assets/");
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals(role));
    }
}
