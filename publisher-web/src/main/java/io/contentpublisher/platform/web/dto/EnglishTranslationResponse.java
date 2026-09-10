package io.contentpublisher.platform.web.dto;

import io.contentpublisher.platform.application.port.ContentGenerator;

import java.util.List;

public record EnglishTranslationResponse(String titleEn, String summaryEn, String markdownEn,
                                         List<String> tagsEn, List<String> keywordsEn) {
    public static EnglishTranslationResponse from(ContentGenerator.EnglishTranslation translation) {
        return new EnglishTranslationResponse(translation.titleEn(), translation.summaryEn(), translation.markdownEn(),
                translation.tagsEn(), translation.keywordsEn());
    }
}
