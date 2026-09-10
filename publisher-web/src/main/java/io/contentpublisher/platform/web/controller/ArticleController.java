package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.JobApplicationService;
import io.contentpublisher.platform.application.PublishingApplicationService;
import io.contentpublisher.platform.application.RecordManagementApplicationService;
import io.contentpublisher.platform.web.dto.ArticleResponse;
import io.contentpublisher.platform.web.dto.JobResponse;
import io.contentpublisher.platform.web.dto.PublishArticleRequest;
import io.contentpublisher.platform.web.dto.PublishArticleBatchRequest;
import io.contentpublisher.platform.web.dto.PublicationBatchResponse;
import io.contentpublisher.platform.web.dto.RejectArticleRequest;
import io.contentpublisher.platform.web.dto.UpdateArticleRequest;
import io.contentpublisher.platform.web.dto.ArticleVersionResponse;
import io.contentpublisher.platform.web.dto.CreateCustomArticleRequest;
import io.contentpublisher.platform.web.dto.CreateTopicArticleRequest;
import io.contentpublisher.platform.web.dto.CreateWebsiteArticleRequest;
import io.contentpublisher.platform.domain.GenerationPolicy;
import io.contentpublisher.platform.domain.TopicBrief;
import io.contentpublisher.platform.domain.WebsiteBrief;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/v1/articles")
public class ArticleController {
    private final PublishingApplicationService publishing;
    private final JobApplicationService jobs;
    private final RecordManagementApplicationService records;
    private final RequestActorProvider actors;

    public ArticleController(PublishingApplicationService publishing, JobApplicationService jobs,
                             RecordManagementApplicationService records, RequestActorProvider actors) {
        this.publishing = publishing;
        this.jobs = jobs;
        this.records = records;
        this.actors = actors;
    }

    @GetMapping("/{articleId}")
    public ArticleResponse get(@PathVariable UUID articleId) {
        return ArticleResponse.from(publishing.getArticle(actors.currentActor(), articleId));
    }

    @GetMapping("/{articleId}/versions")
    public List<ArticleVersionResponse> versions(@PathVariable UUID articleId) {
        return publishing.getArticleVersions(actors.currentActor(), articleId).stream()
                .map(ArticleVersionResponse::from).toList();
    }

    @DeleteMapping("/{articleId}")
    public ResponseEntity<Void> delete(@PathVariable UUID articleId) {
        records.deleteArticleRecord(actors.currentActor(), articleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{articleId}/restore")
    public ResponseEntity<Void> restore(@PathVariable UUID articleId) {
        records.restoreArticleRecord(actors.currentActor(), articleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/custom")
    public ResponseEntity<ArticleResponse> createCustom(@Valid @RequestBody CreateCustomArticleRequest request) {
        var created = publishing.createCustomArticle(actors.currentActor(),
                request.title(), request.summary(), request.markdown(),
                request.tags(), request.keywords(),
                request.titleEn(), request.summaryEn(), request.markdownEn(),
                request.tagsEn(), request.keywordsEn(),
                request.language());
        return ResponseEntity.created(URI.create("/api/v1/articles/" + created.id()))
                .body(ArticleResponse.from(created));
    }

    @PutMapping("/{articleId}")
    public ArticleResponse update(@PathVariable UUID articleId, @Valid @RequestBody UpdateArticleRequest request) {
        List<String> tags = request.tags() == null ? request.keywords() : request.tags();
        if (request.titleEn() == null && request.summaryEn() == null && request.markdownEn() == null) {
            return ArticleResponse.from(publishing.updateArticle(actors.currentActor(), articleId,
                    request.expectedVersion(), request.title(), request.summary(), request.markdown(), tags,
                    request.keywords()));
        }
        List<String> tagsEn = request.tagsEn() == null ? request.keywordsEn() : request.tagsEn();
        return ArticleResponse.from(publishing.updateArticle(actors.currentActor(), articleId,
                request.expectedVersion(), request.title(), request.summary(), request.markdown(), tags,
                request.keywords(), request.titleEn(), request.summaryEn(), request.markdownEn(), tagsEn,
                request.keywordsEn()));
    }

    @PostMapping("/{articleId}/approve")
    public ArticleResponse approve(@PathVariable UUID articleId) {
        return ArticleResponse.from(publishing.approveArticle(actors.currentActor(), articleId));
    }

    @PostMapping("/{articleId}/reject")
    public ArticleResponse reject(@PathVariable UUID articleId, @Valid @RequestBody RejectArticleRequest request) {
        return ArticleResponse.from(publishing.rejectArticle(actors.currentActor(), articleId, request.reason()));
    }

    @PostMapping("/{articleId}/publications")
    public ResponseEntity<JobResponse> publish(@PathVariable UUID articleId,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @Valid @RequestBody PublishArticleRequest request) {
        var job = jobs.submitPublication(actors.currentActor(), articleId, request.channelAccountId(),
                request.canonicalUrl(), idempotencyKey, request.scheduledAt());
        return ResponseEntity.accepted().header(HttpHeaders.LOCATION, "/api/v1/jobs/" + job.id())
                .body(JobResponse.from(job));
    }

    @PostMapping("/{articleId}/publication-batches")
    public ResponseEntity<PublicationBatchResponse> publishBatch(
            @PathVariable UUID articleId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PublishArticleBatchRequest request) {
        var submitted = jobs.submitPublications(actors.currentActor(), articleId, request.channelAccountIds(),
                request.canonicalUrl(), idempotencyKey, request.scheduledAt());
        return ResponseEntity.accepted().body(PublicationBatchResponse.from(submitted));
    }

    @PostMapping("/topic-generations")
    public ResponseEntity<JobResponse> generateTopicArticle(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTopicArticleRequest request) {
        TopicBrief brief = new TopicBrief(request.topic(), request.description(), request.audience(),
                request.articleType(), request.knowledgeLevel(), request.keywords(), request.referenceNotes());
        GenerationPolicy policy = new GenerationPolicy(request.language(), request.tone(), request.minCharacters(),
                request.maxCharacters(), request.maxKeywords(), brief.keywords(), request.excludedKeywords(),
                request.requiredSections());
        var job = jobs.submitTopicArticleGeneration(actors.currentActor(), brief, policy, idempotencyKey);
        return ResponseEntity.accepted().header(HttpHeaders.LOCATION, "/api/v1/jobs/" + job.id())
                .body(JobResponse.from(job));
    }

    @PostMapping("/website-generations")
    public ResponseEntity<JobResponse> generateWebsiteArticle(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateWebsiteArticleRequest request) {
        WebsiteBrief brief = new WebsiteBrief(request.websiteUrl(), request.recommendationAngle(),
                request.audience(), request.keywords());
        GenerationPolicy policy = new GenerationPolicy(request.language(), request.tone(), request.minCharacters(),
                request.maxCharacters(), request.maxKeywords(), brief.keywords(), request.excludedKeywords(),
                request.requiredSections());
        var job = jobs.submitWebsiteArticleGeneration(actors.currentActor(), brief, policy, idempotencyKey);
        return ResponseEntity.accepted().header(HttpHeaders.LOCATION, "/api/v1/jobs/" + job.id())
                .body(JobResponse.from(job));
    }
}
