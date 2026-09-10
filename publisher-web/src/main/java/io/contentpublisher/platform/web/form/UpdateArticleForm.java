package io.contentpublisher.platform.web.form;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UpdateArticleForm {
    @Min(value = 1, message = "文章版本无效")
    private int expectedVersion;

    @NotBlank(message = "标题不能为空")
    @Size(max = 500, message = "标题不能超过 500 个字符")
    private String title;

    @NotBlank(message = "摘要不能为空")
    @Size(max = 2000, message = "摘要不能超过 2000 个字符")
    private String summary;

    @NotBlank(message = "正文不能为空")
    @Size(max = io.contentpublisher.platform.domain.ArticleLimits.MARKDOWN, message = "正文不能超过 100000 个字符")
    private String markdown;

    private String intent = "draft";

    @Size(max = 3200, message = "关键词内容过长")
    private String keywords;

    @Size(max = 1000, message = "标签内容过长")
    private String tags;

    @Size(max = 500, message = "英文标题不能超过 500 个字符")
    private String titleEn;

    @Size(max = 2000, message = "英文摘要不能超过 2000 个字符")
    private String summaryEn;

    @Size(max = io.contentpublisher.platform.domain.ArticleLimits.MARKDOWN, message = "英文正文不能超过 100000 个字符")
    private String markdownEn;

    @Size(max = 3200, message = "英文关键词内容过长")
    private String keywordsEn;

    @Size(max = 1000, message = "英文标签内容过长")
    private String tagsEn;

    public int getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(int expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getMarkdown() { return markdown; }
    public void setMarkdown(String markdown) { this.markdown = markdown; }
    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getTitleEn() { return titleEn; }
    public void setTitleEn(String titleEn) { this.titleEn = titleEn; }
    public String getSummaryEn() { return summaryEn; }
    public void setSummaryEn(String summaryEn) { this.summaryEn = summaryEn; }
    public String getMarkdownEn() { return markdownEn; }
    public void setMarkdownEn(String markdownEn) { this.markdownEn = markdownEn; }
    public String getKeywordsEn() { return keywordsEn; }
    public void setKeywordsEn(String keywordsEn) { this.keywordsEn = keywordsEn; }
    public String getTagsEn() { return tagsEn; }
    public void setTagsEn(String tagsEn) { this.tagsEn = tagsEn; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
}
