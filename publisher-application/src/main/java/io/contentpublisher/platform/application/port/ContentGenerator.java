package io.contentpublisher.platform.application.port;

import io.contentpublisher.platform.domain.GenerationPolicy;
import io.contentpublisher.platform.domain.RepositorySnapshot;
import io.contentpublisher.platform.domain.TopicBrief;
import io.contentpublisher.platform.domain.WebsiteBrief;
import io.contentpublisher.platform.domain.WebsiteSnapshot;

import java.util.List;

public interface ContentGenerator {
    GeneratedContent generate(String tenantId, RepositorySnapshot snapshot, GenerationPolicy policy);
    GeneratedContent generateFromBrief(String tenantId, TopicBrief brief, GenerationPolicy policy);
    GeneratedContent generateFromWebsite(String tenantId, WebsiteBrief brief, WebsiteSnapshot snapshot,
                                         GenerationPolicy policy);

    default EnglishTranslation translateToEnglish(String tenantId, String title, String summary, String markdown,
                                                  List<String> tags, List<String> keywords) {
        throw new io.contentpublisher.platform.application.ApplicationException(
                "AI_DISABLED", "当前生成器不支持从中文生成英文稿");
    }

    record EnglishTranslation(String titleEn, String summaryEn, String markdownEn,
                              List<String> tagsEn, List<String> keywordsEn) {
        public EnglishTranslation {
            titleEn = titleEn == null ? "" : titleEn;
            summaryEn = summaryEn == null ? "" : summaryEn;
            markdownEn = markdownEn == null ? "" : markdownEn;
            tagsEn = tagsEn == null ? List.of() : List.copyOf(tagsEn);
            keywordsEn = keywordsEn == null ? List.of() : List.copyOf(keywordsEn);
        }
    }

    record GeneratedContent(String title, String summary, String markdown, List<String> tags, List<String> keywords,
                            String titleEn, String summaryEn, String markdownEn, List<String> tagsEn,
                            List<String> keywordsEn) {
        public GeneratedContent {
            tags = tags == null ? List.of() : List.copyOf(tags);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            titleEn = titleEn == null ? "" : titleEn;
            summaryEn = summaryEn == null ? "" : summaryEn;
            markdownEn = markdownEn == null ? "" : markdownEn;
            tagsEn = tagsEn == null ? List.of() : List.copyOf(tagsEn);
            keywordsEn = keywordsEn == null ? List.of() : List.copyOf(keywordsEn);
        }

        public GeneratedContent(String title, String summary, String markdown, List<String> keywords) {
            this(title, summary, markdown, keywords, keywords, "", "", "", List.of(), List.of());
        }
    }
}
