package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.WorkspaceDraftService;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@io.swagger.v3.oas.annotations.Hidden
@RequestMapping("/articles/workspace-drafts")
public class WorkspaceDraftController {
    private final WorkspaceDraftService drafts;
    private final RequestActorProvider actors;
    public WorkspaceDraftController(WorkspaceDraftService drafts, RequestActorProvider actors) {
        this.drafts = drafts; this.actors = actors;
    }
    @PutMapping("/{key}")
    public WorkspaceDraftService.Draft save(@PathVariable String key, @RequestBody Request request) {
        return drafts.save(actors.currentActor(), key, request.revision(), request.fields());
    }
    public record Request(long revision, Map<String, String> fields) {}
}
