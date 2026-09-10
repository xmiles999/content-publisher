package io.contentpublisher.platform.web.dto;

import io.contentpublisher.platform.domain.ArticleLimits;
import jakarta.validation.constraints.Size;

import java.util.List;

public record EnglishTranslationRequest(
        @Size(max = ArticleLimits.TITLE) String title,
        @Size(max = ArticleLimits.SUMMARY) String summary,
        @Size(max = ArticleLimits.MARKDOWN) String markdown,
        @Size(max = ArticleLimits.TAGS) List<@Size(max = ArticleLimits.TAG) String> tags,
        @Size(max = ArticleLimits.KEYWORDS) List<@Size(max = ArticleLimits.KEYWORD) String> keywords) {
}
