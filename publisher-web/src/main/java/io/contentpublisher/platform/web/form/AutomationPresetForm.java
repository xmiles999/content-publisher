package io.contentpublisher.platform.web.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class AutomationPresetForm {
    @NotBlank(message = "请输入预设名称")
    @Size(max = 120, message = "预设名称不能超过 120 个字符")
    private String name;

    @NotBlank(message = "请选择内容来源")
    @Pattern(regexp = "PROJECT|TOPIC|WEBSITE", message = "内容来源无效")
    private String sourceType = "PROJECT";

    @Pattern(regexp = "^$|zh-CN|en-US|bilingual", message = "请选择支持的输出语言")
    private String language = "zh-CN";

    @NotBlank(message = "请输入内容语气")
    @Size(max = 100, message = "内容语气不能超过 100 个字符")
    private String tone;

    @NotNull(message = "请输入最小字符数")
    @Min(value = 200, message = "最小字符数不能少于 200")
    @Max(value = 3000, message = "最小字符数不能超过 3000")
    private Integer minCharacters = 800;

    @NotNull(message = "请输入最大字符数")
    @Min(value = 200, message = "最大字符数不能少于 200")
    @Max(value = 3000, message = "最大字符数不能超过 3000")
    private Integer maxCharacters = 1800;

    @NotNull(message = "请输入关键词上限")
    @Min(value = 1, message = "关键词上限不能少于 1")
    @Max(value = 30, message = "关键词上限不能超过 30")
    private Integer maxKeywords = 10;

    @Size(max = 2200, message = "必须章节不能超过 2200 个字符")
    private String requiredSections;

    @Size(max = 40, message = "文章类型不能超过 40 个字符")
    private String articleType;

    @Size(max = 40, message = "知识层级不能超过 40 个字符")
    private String knowledgeLevel;

    @Size(max = 2000, message = "推荐角度不能超过 2000 个字符")
    private String recommendationAngle;

    @Size(max = 200, message = "模型名称不能超过 200 个字符")
    private String model;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getTone() {
        return tone;
    }

    public void setTone(String tone) {
        this.tone = tone;
    }

    public Integer getMinCharacters() {
        return minCharacters;
    }

    public void setMinCharacters(Integer minCharacters) {
        this.minCharacters = minCharacters;
    }

    public Integer getMaxCharacters() {
        return maxCharacters;
    }

    public void setMaxCharacters(Integer maxCharacters) {
        this.maxCharacters = maxCharacters;
    }

    public Integer getMaxKeywords() {
        return maxKeywords;
    }

    public void setMaxKeywords(Integer maxKeywords) {
        this.maxKeywords = maxKeywords;
    }

    public String getRequiredSections() {
        return requiredSections;
    }

    public void setRequiredSections(String requiredSections) {
        this.requiredSections = requiredSections;
    }

    public String getArticleType() {
        return articleType;
    }

    public void setArticleType(String articleType) {
        this.articleType = articleType;
    }

    public String getKnowledgeLevel() {
        return knowledgeLevel;
    }

    public void setKnowledgeLevel(String knowledgeLevel) {
        this.knowledgeLevel = knowledgeLevel;
    }

    public String getRecommendationAngle() {
        return recommendationAngle;
    }

    public void setRecommendationAngle(String recommendationAngle) {
        this.recommendationAngle = recommendationAngle;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
