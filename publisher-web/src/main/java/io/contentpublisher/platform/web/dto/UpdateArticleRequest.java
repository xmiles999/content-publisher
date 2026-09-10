package io.contentpublisher.platform.web.dto;

import io.contentpublisher.platform.domain.ArticleLimits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateArticleRequest(
        @Min(1) int expectedVersion,
        @NotBlank @Size(max = ArticleLimits.TITLE) String title,
        @NotBlank @Size(max = ArticleLimits.SUMMARY) String summary,
        @NotBlank @Size(max = ArticleLimits.MARKDOWN) String markdown,
        @Size(max = ArticleLimits.TAGS) List<@Size(max = ArticleLimits.TAG) String> tags,
        @Size(max = ArticleLimits.KEYWORDS) List<@Size(max = ArticleLimits.KEYWORD) String> keywords,
        @Size(max = ArticleLimits.TITLE) String titleEn,
        @Size(max = ArticleLimits.SUMMARY) String summaryEn,
        @Size(max = ArticleLimits.MARKDOWN) String markdownEn,
        @Size(max = ArticleLimits.TAGS) List<@Size(max = ArticleLimits.TAG) String> tagsEn,
        @Size(max = ArticleLimits.KEYWORDS) List<@Size(max = ArticleLimits.KEYWORD) String> keywordsEn) {
}
