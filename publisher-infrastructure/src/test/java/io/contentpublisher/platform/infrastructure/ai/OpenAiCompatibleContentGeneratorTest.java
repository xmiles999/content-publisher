package io.contentpublisher.platform.infrastructure.ai;

import io.contentpublisher.platform.application.ApplicationException;
import io.contentpublisher.platform.application.port.AiProviderSettingsRepository;
import io.contentpublisher.platform.application.port.SecretCipher;
import io.contentpublisher.platform.domain.GenerationPolicy;
import io.contentpublisher.platform.domain.RepositorySnapshot;
import io.contentpublisher.platform.domain.TopicBrief;
import io.contentpublisher.platform.domain.WebsiteBrief;
import io.contentpublisher.platform.domain.WebsiteSnapshot;
import io.contentpublisher.platform.infrastructure.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatibleContentGeneratorTest {
    private HttpServer server;
    private String lastRequestBody;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void shouldNormalizeKeywordsAndAcceptControlledOutput() throws Exception {
        String markdown = "## 项目概览\nGit 自动分析平台。" + "这是经过仓库事实校验的技术说明。".repeat(20);
        OpenAiCompatibleContentGenerator generator = generator(aiEnvelope(markdown,
                List.of("#Java", "内容生成"), List.of("Java 教程", "AI", "多余词")));
        GenerationPolicy policy = new GenerationPolicy("zh-CN", "专业", 200, 2000, 2,
                List.of("Git"), List.of("夸大宣传"), List.of("项目概览"));

        var result = generator.generate("tenant-test", snapshot(), policy);

        assertThat(result.tags()).containsExactly("Java", "内容生成");
        assertThat(result.keywords()).containsExactly("Git", "Java 教程");
        assertThat(result.markdown()).contains("## 项目概览", "Git");
        assertThat(result.titleEn()).isEqualTo("Git Project Analysis Platform");
        assertThat(result.markdownEn()).contains("## Overview");
        assertThat(lastRequestBody).contains("SEO 优化要求", "Meta Description", "常见问题",
                "primaryKeyword", "Git");
    }

    @Test
    void shouldAllowEnglishTranslationToUseTheGlobalLengthLimit() throws Exception {
        String markdown = "## 项目概览\nGit 自动分析平台。" + "这是经过仓库事实校验的技术说明。".repeat(20);
        String markdownEn = "## Overview\n" + "Verified technical documentation. ".repeat(80);
        OpenAiCompatibleContentGenerator generator = generator(aiEnvelopeWithEnglish(markdown, markdownEn));
        GenerationPolicy policy = new GenerationPolicy("zh-CN", "专业", 200, 2000, 2,
                List.of("Git"), List.of(), List.of("项目概览"));

        var result = generator.generate("tenant-test", snapshot(), policy);

        assertThat(result.markdownEn()).hasSizeGreaterThan(policy.maxCharacters());
        assertThat(result.markdownEn().codePointCount(0, result.markdownEn().length()))
                .isLessThanOrEqualTo(GenerationPolicy.MAX_CHARACTERS);
    }

    @Test
    void shouldCompactEnglishTranslationInsteadOfRejectingSmallOverflow() throws Exception {
        String markdown = "## 项目概览\nGit 自动分析平台。" + "这是经过仓库事实校验的技术说明。".repeat(20);
        String markdownEn = "## Overview\n\n" + "Verified technical documentation. ".repeat(100);
        OpenAiCompatibleContentGenerator generator = generator(aiEnvelopeWithEnglish(markdown, markdownEn));
        GenerationPolicy policy = new GenerationPolicy("zh-CN", "专业", 200, 2000, 2,
                List.of("Git"), List.of(), List.of("项目概览"));

        var result = generator.generate("tenant-test", snapshot(), policy);

        assertThat(markdownEn.codePointCount(0, markdownEn.length()))
                .isGreaterThan(GenerationPolicy.MAX_CHARACTERS);
        assertThat(result.markdownEn().codePointCount(0, result.markdownEn().length()))
                .isBetween(50, GenerationPolicy.MAX_CHARACTERS);
    }

    @Test
    void shouldRejectResponseMissingEnglishContent() throws Exception {
        String markdown = "## 项目概览\nGit 自动分析平台。" + "这是经过仓库事实校验的技术说明。".repeat(20);
        ObjectMapper mapper = new ObjectMapper();
        String content = mapper.writeValueAsString(java.util.Map.of(
                "title", "Git 项目分析平台", "summary", "依据仓库事实生成文章。", "markdown", markdown,
                "keywords", List.of("Git")));
        String response = mapper.writeValueAsString(java.util.Map.of("choices", List.of(
                java.util.Map.of("message", java.util.Map.of("content", content)))));
        OpenAiCompatibleContentGenerator generator = generator(response);
        GenerationPolicy policy = new GenerationPolicy("zh-CN", "专业", 200, 2000, 2,
                List.of("Git"), List.of(), List.of("项目概览"));

        assertThatThrownBy(() -> generator.generate("tenant-test", snapshot(), policy))
                .isInstanceOf(ApplicationException.class)
                .extracting(exception -> ((ApplicationException) exception).code())
                .isEqualTo("AI_OUTPUT_REJECTED");
    }

    @Test
    void shouldFallBackToKeywordsWhenLegacyResponseHasNoTags() throws Exception {
        String markdown = "## 项目概览\nGit 自动分析平台。" + "这是经过仓库事实校验的技术说明。".repeat(20);
        OpenAiCompatibleContentGenerator generator = generator(aiEnvelope(markdown, List.of("Git", "Java")));
        GenerationPolicy policy = new GenerationPolicy("zh-CN", "专业", 200, 2000, 3,
                List.of("Git"), List.of(), List.of("项目概览"));

        var result = generator.generate("tenant-test", snapshot(), policy);

        assertThat(result.tags()).containsExactly("Git", "Java");
    }

    @Test
    void shouldRejectForbiddenKeywordInGeneratedArticle() throws Exception {
        String markdown = "## 项目概览\nGit 夸大宣传。" + "技术说明。".repeat(50);
        OpenAiCompatibleContentGenerator generator = generator(aiEnvelope(markdown, List.of("Git")));
        GenerationPolicy policy = new GenerationPolicy("zh-CN", "专业", 200, 2000, 5,
                List.of("Git"), List.of("夸大宣传"), List.of("项目概览"));

        assertThatThrownBy(() -> generator.generate("tenant-test", snapshot(), policy))
                .isInstanceOf(ApplicationException.class)
                .extracting(exception -> ((ApplicationException) exception).code())
                .isEqualTo("AI_OUTPUT_REJECTED");
    }

    @Test
    void shouldRejectLongFormArticleWithoutEnoughSeoHeadings() throws Exception {
        String markdown = "## 项目概览\nGit 自动分析平台。" + "这是经过仓库事实校验的技术说明。".repeat(60);
        OpenAiCompatibleContentGenerator generator = generator(aiEnvelope(markdown, List.of("Git")));
        GenerationPolicy policy = new GenerationPolicy("zh-CN", "专业", 600, 2000, 5,
                List.of("Git"), List.of(), List.of("项目概览"));

        assertThatThrownBy(() -> generator.generate("tenant-test", snapshot(), policy))
                .isInstanceOf(ApplicationException.class)
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.getMessage()).contains("至少需要两个二级标题"));
    }

    @Test
    void shouldTranslateChineseManuscriptToEnglish() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("titleEn", "Virtual Threads in Java 21");
        payload.put("summaryEn", "A practical guide to virtual threads.");
        payload.put("markdownEn", "## Overview\nVirtual threads simplify blocking I/O.");
        payload.put("tagsEn", List.of("Java"));
        payload.put("keywordsEn", List.of("virtual threads"));
        String response = mapper.writeValueAsString(java.util.Map.of("choices", List.of(
                java.util.Map.of("message", java.util.Map.of("content", mapper.writeValueAsString(payload))))));
        OpenAiCompatibleContentGenerator generator = generator(response);

        var result = generator.translateToEnglish("tenant-test", "Java 21 虚拟线程", "实践指南",
                "## 概览\n虚拟线程简化阻塞 IO。", List.of("Java"), List.of("虚拟线程"));

        assertThat(result.titleEn()).isEqualTo("Virtual Threads in Java 21");
        assertThat(result.markdownEn()).contains("Virtual threads");
        assertThat(result.tagsEn()).containsExactly("Java");
        assertThat(lastRequestBody).contains("把以下中文主稿翻译成英文");
    }

    @Test
    void shouldGenerateControlledTutorialFromTopicBrief() throws Exception {
        String markdown = "## 分步教程\nSpring Boot 可观测性配置。" + "包含指标、日志和追踪的操作说明。".repeat(20);
        OpenAiCompatibleContentGenerator generator = generator(aiEnvelope(markdown, List.of("Spring Boot", "可观测性")));
        TopicBrief brief = new TopicBrief("Spring Boot 可观测性", "编写生产环境配置教程", "Java 开发者",
                "TUTORIAL", "INTERMEDIATE", List.of("Spring Boot", "可观测性"), null);
        GenerationPolicy policy = new GenerationPolicy("zh-CN", "专业", 200, 2000, 4,
                brief.keywords(), List.of("虚构案例"), List.of("分步教程"));

        var result = generator.generateFromBrief("tenant-test", brief, policy);

        assertThat(result.markdown()).contains("## 分步教程", "Spring Boot", "可观测性");
        assertThat(result.keywords()).contains("Spring Boot", "可观测性");
    }

    @Test
    void shouldGenerateWebsiteRecommendationWithoutInventingClaims() throws Exception {
        String markdown = "## 网站定位\n面向内容团队的内容发布工具。## 核心功能\n支持文章审核与多渠道发布。"
                + "用户可以统一管理文章和发布记录。".repeat(20);
        OpenAiCompatibleContentGenerator generator = generator(aiEnvelope(markdown, List.of("内容发布")));
        WebsiteBrief brief = new WebsiteBrief("https://publisher.example.com", "面向开发者介绍功能",
                "内容运营人员", List.of("内容发布"));
        WebsiteSnapshot snapshot = new WebsiteSnapshot(brief.websiteUrl(), "Publisher", "多渠道内容平台",
                "支持文章生成、审核和多渠道发布。".repeat(20));
        GenerationPolicy policy = new GenerationPolicy("zh-CN", "客观", 200, 2000, 5,
                brief.keywords(), List.of("第一"), List.of("网站定位", "核心功能"));

        var result = generator.generateFromWebsite("tenant-test", brief, snapshot, policy);

        assertThat(result.markdown()).contains("网站定位", "核心功能", "内容发布");
    }

    @Test
    void shouldRejectWebsiteRecommendationContainingGenerationProcessNarration() throws Exception {
        String markdown = "## 网站定位\n从已抓取的公开页面可以确认，这是一个内容发布工具。"
                + "## 核心功能\n页面展示了文章审核与多渠道发布。" + "功能说明。".repeat(30);
        OpenAiCompatibleContentGenerator generator = generator(aiEnvelope(markdown, List.of("内容发布")));
        WebsiteBrief brief = new WebsiteBrief("https://publisher.example.com", "面向开发者介绍功能",
                "内容运营人员", List.of("内容发布"));
        WebsiteSnapshot snapshot = new WebsiteSnapshot(brief.websiteUrl(), "Publisher", "多渠道内容平台",
                "支持文章生成、审核和多渠道发布。".repeat(20));
        GenerationPolicy policy = new GenerationPolicy("zh-CN", "客观", 200, 2000, 5,
                brief.keywords(), List.of(), List.of("网站定位", "核心功能"));

        assertThatThrownBy(() -> generator.generateFromWebsite("tenant-test", brief, snapshot, policy))
                .isInstanceOf(ApplicationException.class)
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AI_META_NARRATION_REJECTED"));
    }

    private OpenAiCompatibleContentGenerator generator(String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        AiProperties properties = new AiProperties(true, baseUrl, "test-key", "test-model", Duration.ofSeconds(5), 0.2);
        AiProviderSettingsRepository settings = mock(AiProviderSettingsRepository.class);
        when(settings.findByTenantId("tenant-test")).thenReturn(java.util.Optional.empty());
        return new OpenAiCompatibleContentGenerator(properties, settings, mock(SecretCipher.class), value -> value,
                HttpClient.newHttpClient(), new ObjectMapper());
    }

    private String aiEnvelope(String markdown, List<String> keywords) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("title", "Git 项目分析平台");
        payload.put("summary", "依据仓库事实生成文章。");
        payload.put("markdown", markdown);
        payload.put("keywords", keywords);
        payload.put("titleEn", "Git Project Analysis Platform");
        payload.put("summaryEn", "Generated from verified repository facts.");
        payload.put("markdownEn", englishMarkdown());
        payload.put("keywordsEn", keywords);
        String content = mapper.writeValueAsString(payload);
        return mapper.writeValueAsString(java.util.Map.of("choices", List.of(
                java.util.Map.of("message", java.util.Map.of("content", content)))));
    }

    private String aiEnvelopeWithEnglish(String markdown, String markdownEn) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("title", "Git 项目分析平台");
        payload.put("summary", "依据仓库事实生成文章。");
        payload.put("markdown", markdown);
        payload.put("keywords", List.of("Git"));
        payload.put("titleEn", "Git Project Analysis Platform");
        payload.put("summaryEn", "Generated from verified repository facts.");
        payload.put("markdownEn", markdownEn);
        payload.put("keywordsEn", List.of("Git"));
        String content = mapper.writeValueAsString(payload);
        return mapper.writeValueAsString(java.util.Map.of("choices", List.of(
                java.util.Map.of("message", java.util.Map.of("content", content)))));
    }

    private String aiEnvelope(String markdown, List<String> tags, List<String> keywords) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("title", "Git 项目分析平台");
        payload.put("summary", "依据仓库事实生成文章。");
        payload.put("markdown", markdown);
        payload.put("tags", tags);
        payload.put("keywords", keywords);
        payload.put("titleEn", "Git Project Analysis Platform");
        payload.put("summaryEn", "Generated from verified repository facts.");
        payload.put("markdownEn", englishMarkdown());
        payload.put("tagsEn", tags);
        payload.put("keywordsEn", keywords);
        String content = mapper.writeValueAsString(payload);
        return mapper.writeValueAsString(java.util.Map.of("choices", List.of(
                java.util.Map.of("message", java.util.Map.of("content", content)))));
    }

    private String englishMarkdown() {
        return "## Overview\nAutomated repository analysis platform. " + "Verified technical documentation.".repeat(20);
    }

    private RepositorySnapshot snapshot() {
        return new RepositorySnapshot("publisher", "内容发布平台", "main", "abc123", "README",
                "pom.xml", List.of("pom.xml", "src/Main.java"), List.of("Java"), "LICENSE");
    }
}
