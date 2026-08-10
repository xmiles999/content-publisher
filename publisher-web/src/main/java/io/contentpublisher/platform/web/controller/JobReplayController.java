package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.JobApplicationService;
import io.contentpublisher.platform.web.dto.JobResponse;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/job-replays")
public class JobReplayController {
    private final JobApplicationService jobs;
    private final RequestActorProvider actors;

    public JobReplayController(JobApplicationService jobs, RequestActorProvider actors) {
        this.jobs = jobs;
        this.actors = actors;
    }

    @PostMapping("/{jobId}")
    public ResponseEntity<JobResponse> replay(@PathVariable UUID jobId,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var replayed = jobs.replayFailedJob(actors.currentActor(), jobId, idempotencyKey);
        return ResponseEntity.accepted().header(HttpHeaders.LOCATION, "/api/v1/jobs/" + replayed.id())
                .body(JobResponse.from(replayed));
    }

    @PostMapping
    public List<JobResponse> replayBatch(@RequestParam List<UUID> jobIds,
                                         @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return jobs.replayFailedJobs(actors.currentActor(), jobIds, idempotencyKey)
                .stream().map(JobResponse::from).toList();
    }
}
