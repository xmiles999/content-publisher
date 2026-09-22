package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.AutomationApplicationService;
import io.contentpublisher.platform.web.security.LocalUserPrincipal;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import io.contentpublisher.platform.web.security.SecurityMode;
import io.contentpublisher.platform.web.security.SecurityProperties;
import io.contentpublisher.platform.infrastructure.config.JobProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Locale;

@ControllerAdvice
public class PortalModelAdvice {
    private final AutomationApplicationService automation;
    private final RequestActorProvider actors;
    private final SecurityProperties security;
    private final JobProperties jobs;

    public PortalModelAdvice(AutomationApplicationService automation, RequestActorProvider actors,
                             SecurityProperties security, JobProperties jobs) {
        this.automation = automation;
        this.actors = actors;
        this.security = security;
        this.jobs = jobs;
    }

    @ModelAttribute("jobWorkerEnabled")
    public boolean jobWorkerEnabled() {
        return jobs.workerEnabled();
    }

    @ModelAttribute("localLogin")
    public boolean localLogin() {
        return security.mode() == SecurityMode.LOCAL;
    }

    @ModelAttribute("currentUsername")
    public String currentUsername(Authentication authentication) {
        if (security.mode() == SecurityMode.DISABLED) return security.defaultSubject();
        if (authentication == null) return "";
        return authentication.getPrincipal() instanceof LocalUserPrincipal principal
                ? principal.username() : authentication.getName();
    }

    @ModelAttribute("currentTenant")
    public String currentTenant(Authentication authentication) {
        if (security.mode() == SecurityMode.DISABLED) return security.defaultTenant();
        if (authentication == null) return "";
        return authentication.getPrincipal() instanceof LocalUserPrincipal principal ? principal.tenantId() : "-";
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
                case "custom" -> "projects-custom";
                default -> "projects-custom";
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
                case "records" -> "publishing-records";
                default -> "publishing-coverage";
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
        if (item.startsWith("projects") || item.equals("content")) return "manuscripts";
        if (item.equals("actions")) return "inbox";
        if (item.startsWith("publishing") || item.equals("channels") || item.equals("calendar")) {
            return "publishing";
        }
        if (item.equals("monitoring") || item.equals("jobs") || item.equals("ai")
                || item.equals("automation") || item.equals("recycle")) {
            return "settings";
        }
        return "";
    }

    @ModelAttribute("navigationCounts")
    public AutomationApplicationService.NavigationCounts navigationCounts(Authentication authentication,
                                                                          HttpServletRequest request) {
        if (!usesPortalNavigation(request.getRequestURI())
                || (security.mode() != SecurityMode.DISABLED && (authentication == null
                || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())))) {
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
        if (security.mode() == SecurityMode.DISABLED) return true;
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals(role));
    }
}
