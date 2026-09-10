package io.contentpublisher.platform.web.controller;

import io.contentpublisher.platform.application.ArticleAssetApplicationService;
import io.contentpublisher.platform.domain.ArticleAsset;
import io.contentpublisher.platform.web.dto.ArticleAssetResponse;
import io.contentpublisher.platform.web.security.RequestActorProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
public class ArticleAssetController {
    private final ArticleAssetApplicationService assets;
    private final RequestActorProvider actors;

    public ArticleAssetController(ArticleAssetApplicationService assets, RequestActorProvider actors) {
        this.assets = assets;
        this.actors = actors;
    }

    @GetMapping("/api/v1/articles/{articleId}/assets")
    public List<ArticleAssetResponse> list(@PathVariable UUID articleId) {
        return assets.list(actors.currentActor(), articleId).stream().map(ArticleAssetResponse::from).toList();
    }

    @PostMapping(path = "/api/v1/articles/{articleId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ArticleAssetResponse> upload(@PathVariable UUID articleId,
                                                       @RequestParam("file") MultipartFile file) throws IOException {
        ArticleAsset saved = assets.upload(actors.currentActor(), articleId,
                file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.created(java.net.URI.create(saved.markdownUrl()))
                .body(ArticleAssetResponse.from(saved));
    }

    @GetMapping({"/articles/{articleId}/assets/{assetId}", "/api/v1/articles/{articleId}/assets/{assetId}"})
    public ResponseEntity<byte[]> content(@PathVariable UUID articleId, @PathVariable UUID assetId) {
        ArticleAsset asset = assets.get(actors.currentActor(), articleId, assetId);
        byte[] body = assets.content(actors.currentActor(), articleId, assetId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(asset.originalFilename(), StandardCharsets.UTF_8).build().toString())
                .body(body);
    }
}
