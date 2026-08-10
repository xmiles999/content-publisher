package io.contentpublisher.platform;

import io.contentpublisher.platform.application.port.ArticleRepository;
import io.contentpublisher.platform.application.port.ProjectRepository;
import io.contentpublisher.platform.application.port.ManualPublicationRepository;
import io.contentpublisher.platform.application.port.ChannelAccountRepository;
import io.contentpublisher.platform.application.port.CredentialVault;
import io.contentpublisher.platform.application.port.JobRepository;
import io.contentpublisher.platform.application.AiSettingsApplicationService;
import io.contentpublisher.platform.domain.Article;
import io.contentpublisher.platform.domain.ArticleStatus;
import io.contentpublisher.platform.domain.ArticleVersion;
import io.contentpublisher.platform.domain.ChannelAccount;
import io.contentpublisher.platform.domain.ChannelAccountStatus;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.ContentOrigin;
import io.contentpublisher.platform.domain.JobPayload;
import io.contentpublisher.platform.domain.Project;
import io.contentpublisher.platform.domain.ProjectStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:local_security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "publisher.security.mode=LOCAL",
        "publisher.security.local.bootstrap-username=admin",
        "publisher.security.local.bootstrap-password=enterprise-password-001",
        "publisher.security.local.bootstrap-tenant=tenant-local",
        "publisher.security.local.bootstrap-must-change-password=false",
        "publisher.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "publisher.channels.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "publisher.ai.security.allow-private-addresses=true",
        "publisher.jobs.worker-enabled=false",
        "publisher.channels.enabled=true"
})
@AutoConfigureMockMvc
class LocalSecurityIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ProjectRepository projects;
    @Autowired ArticleRepository articles;
    @Autowired AiSettingsApplicationService aiSettings;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ManualPublicationRepository manualPublications;
    @Autowired ChannelAccountRepository channelAccounts;
    @Autowired CredentialVault credentialVault;
    @Autowired JobRepository jobRepository;

    @Test
    void shouldRenderLoginAndCreateAuthenticatedTenantSession() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("安全登录")));

        var login = mockMvc.perform(formLogin().user("admin").password("enterprise-password-001"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("tenant-local")));
        mockMvc.perform(get("/monitoring").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("全链路监控大屏")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-monitor-screen")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-monitor-gauge")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-monitor-column")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-monitor-tabs")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-monitor-tab-group=\"state\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-monitor-tab-group=\"feed\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("role=\"tabpanel\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("发布成功率")));
        mockMvc.perform(get("/monitoring?range=7d").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("近 7 天")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-monitor-live-url")));
        mockMvc.perform(get("/monitoring/live?range=7d").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-monitor-live-region")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("monitor-gauge-value")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("渠道发布表现")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<!doctype html>"))));
        mockMvc.perform(get("/api/v1/monitoring/summary?range=30d").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window").value("DAYS_30"))
                .andExpect(jsonPath("$.projectCount").isNumber())
                .andExpect(jsonPath("$.publicationsByStatus.PUBLISHED").isNumber());
        mockMvc.perform(get("/api/v1/projects/" + UUID.randomUUID()).session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRenderAutomationSettingsWithoutTemplateErrors() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(get("/automation").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("服务端生成预设")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("通知 Webhook")));
    }

    @Test
    void shouldUseExactDatabaseAggregatesBeyondRecentListLimit() throws Exception {
        MockHttpSession session = login();
        long baseline = jdbcTemplate.queryForObject(
                "select count(*) from projects where tenant_id = 'tenant-local'", Long.class);
        String marker = "monitoring-exact-" + UUID.randomUUID();
        Instant now = Instant.parse("2026-07-20T06:00:00Z");
        try {
            for (int index = 0; index < 105; index++) {
                jdbcTemplate.update("insert into projects (id, tenant_id, git_url, name, default_branch, "
                                + "languages_json, status, created_by, updated_by, created_at, updated_at) "
                                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), "tenant-local", "https://example.com/" + marker + "-" + index + ".git",
                        marker + "-" + index, "main", "[]", "READY", "admin", "admin", now, now);
            }

            mockMvc.perform(get("/api/v1/monitoring/summary?range=24h").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.projectCount").value(baseline + 105))
                    .andExpect(jsonPath("$.projectsByStatus.READY").isNumber());
        } finally {
            jdbcTemplate.update("delete from projects where name like ?", marker + "%");
        }
    }

    @Test
    void shouldReturnJsonForUnauthenticatedApiAndRequireCsrfForSessionWrites() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        var login = mockMvc.perform(formLogin().user("admin").password("enterprise-password-001"))
                .andExpect(status().is3xxRedirection()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        String body = "{\"gitUrl\":\"https://github.com/contentpublisher/platform.git\"}";

        mockMvc.perform(post("/api/v1/projects/imports").session(session)
                        .header("Idempotency-Key", "local-security-import-001")
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/projects/imports").session(session).with(csrf())
                        .header("Idempotency-Key", "local-security-import-001")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void shouldSubmitGitImportFromAuthenticatedManagementPortal() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(get("/projects").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("从网站生成推荐文章")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/projects?source=website")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("主题教程")));

        mockMvc.perform(get("/projects").param("source", "git").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("导入 Git 项目")));

        mockMvc.perform(post("/projects/imports").session(session)
                        .param("gitUrl", "https://github.com/contentpublisher/portal-test.git")
                        .param("branch", "main")
                        .param("idempotencyKey", "portal:import:test-001"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        var submitted = mockMvc.perform(post("/projects/imports").session(session).with(csrf())
                        .param("gitUrl", "https://github.com/contentpublisher/portal-test.git")
                        .param("branch", "main")
                        .param("idempotencyKey", "portal:import:test-001"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/jobs/*"))
                .andReturn();

        mockMvc.perform(get(submitted.getResponse().getRedirectedUrl()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("任务正在后台执行")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("入队时间")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("幂等键"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("结果资源"))));
    }

    @Test
    void shouldSeparateContentJobsAndRecycleBinFromCreationWorkspace() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(get("/projects").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("选择内容来源")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-sidebar-group=\"content\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-sidebar-active=\"true\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-sidebar-toggle")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("sidebar-icon-sprite")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("nav-icon")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-controls=\"app-sidebar\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("aria-current=\"page\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("近期后台任务"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("已删除记录"))));
        mockMvc.perform(get("/content").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("文章主稿")));
        mockMvc.perform(get("/jobs").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("任务队列")));
        mockMvc.perform(get("/recycle-bin").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("已删除记录")));
        var calendarHtml = mockMvc.perform(get("/calendar").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var publishingGroup = org.jsoup.Jsoup.parse(calendarHtml)
                .selectFirst("div[data-sidebar-group=publishing]");
        assertThat(publishingGroup).isNotNull();
        assertThat(publishingGroup.attr("data-sidebar-active")).isEqualTo("true");
        assertThat(publishingGroup.hasClass("expanded")).isTrue();
        var calendarLink = publishingGroup.selectFirst("a[href='/calendar']");
        assertThat(calendarLink).isNotNull();
        assertThat(calendarLink.attr("aria-current")).isEqualTo("page");
    }

    @Test
    void shouldSubmitTopicTutorialGenerationFromManagementPortal() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(get("/projects").param("source", "topic").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("按主题创建知识教程")));

        var submitted = mockMvc.perform(post("/articles/topic-generations").session(session).with(csrf())
                        .param("topic", "Spring Boot 生产环境可观测性")
                        .param("description", "讲解指标、日志与链路追踪的配置和排查流程")
                        .param("audience", "Java 后端开发者")
                        .param("articleType", "TUTORIAL")
                        .param("knowledgeLevel", "INTERMEDIATE")
                        .param("keywords", "Spring Boot, 可观测性")
                        .param("language", "zh-CN")
                        .param("tone", "专业、清晰、循序渐进")
                        .param("minCharacters", "1200")
                        .param("maxCharacters", "1800")
                        .param("maxKeywords", "8")
                        .param("requiredSections", "学习目标\n分步教程\n常见问题\n总结")
                        .param("idempotencyKey", "portal:topic:test-001"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/jobs/*"))
                .andReturn();

        mockMvc.perform(get(submitted.getResponse().getRedirectedUrl()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("生成主题教程")));
    }

    @Test
    void shouldSubmitWebsiteRecommendationGenerationFromManagementPortal() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(get("/projects").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("从网站生成推荐文章")));

        var submitted = mockMvc.perform(post("/articles/website-generations").session(session).with(csrf())
                        .param("websiteUrl", "https://example.com/product")
                        .param("recommendationAngle", "客观总结网站功能、适用场景和限制")
                        .param("audience", "技术工具选型人员")
                        .param("keywords", "开发者工具, 效率")
                        .param("language", "zh-CN")
                        .param("tone", "客观、克制、信息密度高")
                        .param("minCharacters", "1000")
                        .param("maxCharacters", "2200")
                        .param("maxKeywords", "8")
                        .param("excludedKeywords", "最好,第一,保证")
                        .param("requiredSections", "网站定位\n核心功能\n适用人群\n优势与局限\n总结")
                        .param("idempotencyKey", "portal:website:test-001"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/jobs/*"))
                .andReturn();

        mockMvc.perform(get(submitted.getResponse().getRedirectedUrl()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("生成网站推荐")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("任务进度")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-job-status-url")));
    }

    @Test
    void shouldEditAndApproveArticleThroughManagementPortal() throws Exception {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        UUID projectId = UUID.randomUUID();
        projects.save(new Project(projectId, "tenant-local", "https://github.com/example/portal.git", "portal",
                "Portal integration project", "main", "abc123", List.of("Java"), "MIT", ProjectStatus.READY,
                "admin", "admin", now, now));
        UUID articleId = UUID.randomUUID();
        Article article = new Article(articleId, "tenant-local", projectId, null, "初始标题", "初始摘要",
                "# 初始正文", List.of("平台"), "zh-CN", "abc123", 1, ArticleStatus.DRAFT,
                "admin", "admin", now, now);
        articles.saveWithVersion(article, new ArticleVersion("tenant-local", articleId, 1, article.title(),
                article.summary(), article.markdown(), article.keywords(), "admin", now));
        MockHttpSession session = login();

        mockMvc.perform(get("/projects/" + projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("生成文章草稿")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("初始标题")));

        mockMvc.perform(post("/articles/" + articleId + "/edit").session(session).with(csrf())
                        .param("expectedVersion", "1")
                        .param("title", "企业级管理后台")
                        .param("summary", "更新后的文章摘要")
                        .param("tags", "#企业级, 内容平台")
                        .param("keywords", "企业级内容平台教程, 多渠道发布方案")
                        .param("markdown", "# 企业级管理后台\n\n正文内容"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/articles/" + articleId));

        mockMvc.perform(get("/articles/" + articleId).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("企业级管理后台")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("企业级内容平台教程")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SEO 质量检查")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("版本 2")));

        assertThat(articles.findArticleById("tenant-local", articleId)).get().satisfies(saved -> {
            assertThat(saved.tags()).containsExactly("企业级", "内容平台");
            assertThat(saved.keywords()).containsExactly("企业级内容平台教程", "多渠道发布方案");
        });

        mockMvc.perform(post("/articles/" + articleId + "/approve").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/articles/" + articleId));

        mockMvc.perform(get("/articles/" + articleId).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("已审核")));
    }

    @Test
    void shouldSecurelyConfigureTenantAiProviderWithoutEchoingApiKey() throws Exception {
        MockHttpSession session = login();
        String apiKey = "sk-sensitive-integration-secret";

        mockMvc.perform(get("/settings/ai").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("OpenAI 兼容接口")));

        mockMvc.perform(post("/settings/ai").session(session).with(csrf())
                        .param("expectedVersion", "0")
                        .param("enabled", "true")
                        .param("baseUrl", "https://127.0.0.1:9443/v1")
                        .param("apiKey", apiKey)
                        .param("model", "enterprise-model")
                        .param("timeoutSeconds", "45")
                        .param("temperature", "0.2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings/ai"));

        var saved = aiSettings.getSettings(new io.contentpublisher.platform.domain.ActorContext("tenant-local", "admin"))
                .orElseThrow();
        assertThat(saved.encryptedApiKey()).startsWith("v1:").doesNotContain(apiKey);
        assertThat(saved.apiKeyFingerprint()).hasSize(64);

        mockMvc.perform(get("/settings/ai").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("已安全配置")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(apiKey))));
    }

    @Test
    void shouldRenderPublishingWorkspaceAndRecordManualPublication() throws Exception {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        UUID projectId = UUID.randomUUID();
        projects.save(new Project(projectId, "tenant-local", "https://github.com/example/manual.git", "manual",
                "Manual publishing project", "main", "manual123", List.of("Java"), "MIT", ProjectStatus.READY,
                "admin", "admin", now, now));
        UUID articleId = UUID.randomUUID();
        Article article = new Article(articleId, "tenant-local", ContentOrigin.git(projectId), null,
                "多平台发布指南", "发布摘要", "## 核心能力\n\n支持 Markdown 与纯文本。",
                List.of("Java", "发布"), List.of("多平台发布", "Markdown"),
                "Multi-channel publishing guide", "Publishing summary",
                "## Core capability\n\nPublish Markdown and plain text through verified channels.",
                List.of("Java", "publishing"), List.of("multi-channel publishing", "Markdown"),
                "zh-CN", "manual123", 1, ArticleStatus.APPROVED, "admin", "admin", now, now);
        articles.saveWithVersion(article, new ArticleVersion("tenant-local", articleId, 1, article.title(),
                article.summary(), article.markdown(), article.tags(), article.keywords(), article.titleEn(),
                article.summaryEn(), article.markdownEn(), article.tagsEn(), article.keywordsEn(), "admin", now));
        UUID devAccountId = UUID.randomUUID();
        UUID xAccountId = UUID.randomUUID();
        Map<String, String> devCredentials = Map.of("apiKey", "dev-test-key");
        Map<String, String> xCredentials = Map.of(
                "accessToken", "x-access",
                "refreshToken", "x-refresh",
                "clientId", "x-client",
                "clientSecret", "x-secret");
        channelAccounts.save(new ChannelAccount(devAccountId, "tenant-local", ChannelType.DEV, "DEV 主账号",
                "https://dev.to", credentialVault.encrypt(devCredentials), "account-dev-001", "a".repeat(64),
                credentialVault.fingerprint(devCredentials), 1,
                ChannelAccountStatus.ACTIVE, "admin", "admin", now, now));
        channelAccounts.save(new ChannelAccount(xAccountId, "tenant-local", ChannelType.X, "X 主账号",
                "https://api.x.com", credentialVault.encrypt(xCredentials), "account-x-001", "c".repeat(64),
                credentialVault.fingerprint(xCredentials), 1,
                ChannelAccountStatus.ACTIVE, "admin", "admin", now, now));
        Instant scheduledAt = Instant.now().plusSeconds(7_200).truncatedTo(ChronoUnit.MINUTES);
        String scheduledAtValue = LocalDateTime.ofInstant(scheduledAt, ZoneOffset.UTC).toString();
        MockHttpSession session = login();

        mockMvc.perform(get("/publishing").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("多平台发布中心")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("已连接接口"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("人工发布平台"))));
        mockMvc.perform(get("/articles/" + articleId).session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("进入发布中心")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("一键多平台发布"))));
        mockMvc.perform(get("/publishing/articles/" + articleId).session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("批量 API 发布")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DEV 主账号")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("X 主账号")));
        mockMvc.perform(post("/articles/" + articleId + "/publication-batches").session(session).with(csrf())
                        .param("channelAccountIds", devAccountId.toString(), xAccountId.toString())
                        .param("canonicalUrl", "https://example.com/original")
                        .param("scheduledAt", scheduledAtValue)
                        .param("idempotencyKey", "portal-publication-batch-001"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/publishing/articles/" + articleId));

        var publicationJobs = jobRepository.findRecentJobs("tenant-local", 100).stream()
                .filter(job -> job.payload() instanceof JobPayload.PublishArticle payload
                        && payload.articleId().equals(articleId)).toList();
        assertThat(publicationJobs).hasSize(2)
                .extracting(job -> ((JobPayload.PublishArticle) job.payload()).channelAccountId())
                .containsExactlyInAnyOrder(devAccountId, xAccountId);
        assertThat(publicationJobs).extracting(io.contentpublisher.platform.domain.Job::scheduledAt)
                .containsOnly(scheduledAt);
        assertThat(publicationJobs).extracting(io.contentpublisher.platform.domain.Job::progressLabel)
                .containsOnly("等待定时执行");
        mockMvc.perform(get("/publishing?tab=batches").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("执行批次")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DEV 主账号")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("X 主账号")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2 个渠道")));
        mockMvc.perform(get("/channels?view=manual").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("打开创作页")));
        mockMvc.perform(get("/articles/" + articleId + "/manual/XIAOHONGSHU").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("打开官方登录/发布页")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("发布标签")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("#java")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("#发布")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PLAIN_TEXT")));

        mockMvc.perform(post("/articles/" + articleId + "/manual/XIAOHONGSHU").session(session).with(csrf())
                        .param("contentFormat", "PLAIN_TEXT")
                        .param("title", "小红书发布标题")
                        .param("content", "适配后的普通文本 #Java #发布")
                        .param("externalUrl", "https://www.xiaohongshu.com/explore/test-article"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/publishing/articles/" + articleId));

        assertThat(manualPublications.findByArticle("tenant-local", articleId)).singleElement()
                .satisfies(item -> assertThat(item.adaptedTitle()).isEqualTo("小红书发布标题"));
        mockMvc.perform(get("/publishing?tab=coverage").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("文章 / 渠道覆盖分析")));
        mockMvc.perform(get("/publishing?tab=records").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("发布记录")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MANUAL")));
        mockMvc.perform(get("/api/v1/publications").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].articleId").value(articleId.toString()))
                .andExpect(jsonPath("$[0].method").value("MANUAL"))
                .andExpect(jsonPath("$[0].channelType").value("XIAOHONGSHU"));
    }

    @Test
    void shouldRenderViewerPortalWithoutWriteControls() throws Exception {
        UUID viewerId = UUID.randomUUID();
        String viewerUsername = "viewer-portal";
        String viewerPassword = "Viewer-password-001!";
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        UUID projectId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();
        jdbcTemplate.update("insert into local_users (id, tenant_id, username, password_hash, enabled, "
                        + "created_at, updated_at, must_change_password) values (?, ?, ?, ?, true, ?, ?, false)",
                viewerId, "tenant-local", viewerUsername, passwordEncoder.encode(viewerPassword), now, now);
        jdbcTemplate.update("insert into local_user_roles (user_id, role) values (?, 'VIEWER')", viewerId);
        projects.save(new Project(projectId, "tenant-local", "https://github.com/example/viewer.git", "viewer",
                "Viewer project", "main", "viewer123", List.of("Java"), "MIT", ProjectStatus.READY,
                "admin", "admin", now, now));
        Article article = new Article(articleId, "tenant-local", projectId, null, "Viewer 可读文章", "只读摘要",
                "# 只读正文", List.of("Viewer"), "zh-CN", "viewer123", 1, ArticleStatus.APPROVED,
                "admin", "admin", now, now);
        articles.saveWithVersion(article, new ArticleVersion("tenant-local", articleId, 1, article.title(),
                article.summary(), article.markdown(), article.keywords(), "admin", now));
        try {
            var login = mockMvc.perform(formLogin().user(viewerUsername).password(viewerPassword))
                    .andExpect(status().is3xxRedirection()).andReturn();
            MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

            mockMvc.perform(get("/projects").session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("data-source-workspace"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("action=\"/projects/imports\""))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("action=\"/articles/topic-generations\""))));
            mockMvc.perform(get("/channels").session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("添加发布账号"))));
            mockMvc.perform(get("/articles/" + articleId).session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("readonly")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("准备发布 →"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("提交 API 发布任务"))));
            mockMvc.perform(get("/articles/" + articleId + "/manual/XIAOHONGSHU").session(session))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/access-denied"));
            mockMvc.perform(get("/settings/ai").session(session))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/access-denied"));
            mockMvc.perform(get("/recycle-bin").session(session))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/access-denied"));
        } finally {
            jdbcTemplate.update("delete from local_users where id = ?", viewerId);
        }
    }

    @Test
    void shouldForceInitialPasswordChangeBeforeAllowingApplicationAccess() throws Exception {
        String initialPassword = "enterprise-password-001";
        jdbcTemplate.update("update local_users set password_hash = ?, must_change_password = true where username = 'admin'",
                passwordEncoder.encode(initialPassword));
        try {
            var login = mockMvc.perform(formLogin().user("admin").password(initialPassword))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/change-password"))
                    .andReturn();
            MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

            mockMvc.perform(get("/projects").session(session))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/change-password"));
            mockMvc.perform(get("/api/v1/projects/" + UUID.randomUUID()).session(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
            mockMvc.perform(get("/change-password").session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("首次登录，请修改初始密码")));

            mockMvc.perform(post("/change-password").session(session).with(csrf())
                            .param("currentPassword", initialPassword)
                            .param("newPassword", "OnlyLetters!")
                            .param("confirmPassword", "OnlyLetters!"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "必须同时包含大写字母、小写字母、数字和特殊字符")));

            mockMvc.perform(post("/change-password").session(session).with(csrf())
                            .param("currentPassword", initialPassword)
                            .param("newPassword", "Secure1!")
                            .param("confirmPassword", "Secure1!"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?passwordChanged"));
        } finally {
            jdbcTemplate.update("update local_users set password_hash = ?, must_change_password = false where username = 'admin'",
                    passwordEncoder.encode(initialPassword));
        }
    }

    private MockHttpSession login() throws Exception {
        var result = mockMvc.perform(formLogin().user("admin").password("enterprise-password-001"))
                .andExpect(status().is3xxRedirection()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
