package io.contentpublisher.platform.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateCustomArticleRequest(
        @NotBlank(message = "文章标题不能为空")
        @Size(max = 500, message = "文章标题不能超过 500 个字符")
        String title,

        @Size(max = 2000, message = "文章摘要不能超过 2000 个字符")
        String summary,

        @NotBlank(message = "正文内容不能为空")
        @Size(max = 20000, message = "正文内容不能超过 20000 个字符")
        String markdown,

        List<String> tags,
        List<String> keywords,

        @Size(max = 500, message = "英文标题不能超过 500 个字符")
        String titleEn,

        @Size(max = 2000, message = "英文摘要不能超过 2000 个字符")
        String summaryEn,

        @Size(max = 20000, message = "英文正文不能超过 20000 个字符")
        String markdownEn,

        List<String> tagsEn,
        List<String> keywordsEn,

        @Size(max = 20, message = "语言代码不能超过 20 个字符")
        String language
) {
}
