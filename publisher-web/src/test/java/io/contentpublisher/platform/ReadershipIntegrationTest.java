package io.contentpublisher.platform;

import io.contentpublisher.platform.application.*;
import io.contentpublisher.platform.application.port.*;
import io.contentpublisher.platform.domain.*;
import io.contentpublisher.platform.infrastructure.channels.OfficialPublicationReadershipReader;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:readership;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "publisher.security.mode=LOCAL", "publisher.security.local.bootstrap-username=admin",
        "publisher.security.local.bootstrap-password=Readership-test-001!",
        "publisher.security.local.bootstrap-tenant=tenant-readership", "publisher.security.local.bootstrap-must-change-password=false",
        "publisher.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "publisher.jobs.worker-enabled=false", "publisher.automation.channel-health-enabled=false",
        "publisher.automation.webhook-enabled=false"
})
@AutoConfigureMockMvc
class ReadershipIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReadershipRepository repository;
    @Autowired ManualPublicationRepository manual;
    @Autowired ChannelAccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @MockitoSpyBean PublicationRepository publications;
    @MockitoBean OfficialPublicationReadershipReader reader;
    private static final String TENANT = "tenant-readership";
    private MockHttpSession session;
    private UUID articleId, publicationId;

    @BeforeEach void setup() throws Exception {
        session = login("admin", "Readership-test-001!");
        String location = mvc.perform(post("/articles/custom").session(session).with(csrf())
                        .param("title", "阅读统计验收文章").param("summary", "统计口径").param("markdown", "# 统计\n正文")
                        .param("language", "zh-CN"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        articleId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        publicationId = UUID.randomUUID();
        manual.save(new ManualPublication(publicationId, TENANT, articleId, ChannelType.ZHIHU, ContentFormat.PLAIN_TEXT,
                "统计", "正文", "https://zhuanlan.zhihu.com/p/123", "admin", Instant.now()));
    }

    @Test void persistsZeroAndDistinctReaderCountAndShowsSourceOnBothLists() throws Exception {
        String path = path(PublicationMethod.MANUAL);
        var first = page(path);
        assertThat(first.text()).contains("暂无阅读数据", "暂未接入自动统计", "无需手动填写");
        assertThat(first.selectFirst("details.readership-manual").hasAttr("open")).isFalse();
        assertThat(first.select(".readership-sync form")).isEmpty();
        mvc.perform(post(path).session(session).with(csrf()).param("version", "0").param("views", "0"))
                .andExpect(redirectedUrl(path));
        assertThat(page(path).text()).contains("阅读 / 浏览 0 次", "手动录入");
        mvc.perform(post(path).session(session).with(csrf()).param("version", "1").param("views", "125").param("uniqueReaders", "80"))
                .andExpect(redirectedUrl(path));
        assertThat(repository.find(TENANT, PublicationMethod.MANUAL, publicationId).orElseThrow().uniqueReaders()).isEqualTo(80);
        for (String list : List.of("/publishing?tab=records", "/publishing/articles/" + articleId)) {
            var document = page(list);
            assertThat(document.text()).contains("阅读 / 浏览 125 次", "去重 80 人", "手动录入", "暂未接入自动统计");
            assertThat(document.select(".readership-summary a").eachText()).contains("查看统计").doesNotContain("更新统计");
        }
        verifyNoInteractions(reader);
    }

    @Test void rejectsInvalidAndStaleInputsWithoutLosingTypedValues() throws Exception {
        String path = path(PublicationMethod.MANUAL);
        for (String value : List.of("-1", "1.5", "9223372036854775808"))
            mvc.perform(post(path).session(session).with(csrf()).param("version", "0").param("views", value))
                    .andExpect(status().isOk()).andExpect(view().name("publication-readership"));
        mvc.perform(post(path).session(session).with(csrf()).param("version", "0"))
                .andExpect(model().attribute("errorMessage", "请至少填写一项统计；未知数据请留空"));
        mvc.perform(post(path).session(session).with(csrf()).param("version", "0").param("views", "10").param("uniqueReaders", "11"))
                .andExpect(model().attributeExists("errorMessage"));
        assertThat(repository.find(TENANT, PublicationMethod.MANUAL, publicationId)).isEmpty();
        mvc.perform(post(path).session(session).with(csrf()).param("version", "0").param("views", "100"))
                .andExpect(status().is3xxRedirection());
        var conflict = mvc.perform(post(path).session(session).with(csrf()).param("version", "0").param("views", "200"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("errorMessage")).andReturn();
        assertThat(Jsoup.parse(conflict.getResponse().getContentAsString()).selectFirst("#views").val()).isEqualTo("200");
        assertThat(Jsoup.parse(conflict.getResponse().getContentAsString()).selectFirst("details.readership-manual").hasAttr("open")).isTrue();
        assertThat(repository.find(TENANT, PublicationMethod.MANUAL, publicationId).orElseThrow().views()).isEqualTo(100);
    }

    @Test void validatesArticleTenantAndSuccessfulRecordBeforeWriting() throws Exception {
        String path = path(PublicationMethod.MANUAL);
        mvc.perform(post(path.replace(articleId.toString(), UUID.randomUUID().toString())).session(session).with(csrf())
                        .param("version", "0").param("views", "100")).andExpect(status().isBadRequest());
        mvc.perform(get(path(PublicationMethod.API)).session(session)).andExpect(status().isBadRequest());
        jdbc.update("update articles set tenant_id='other-tenant' where id=?", articleId);
        mvc.perform(get(path).session(session)).andExpect(status().isBadRequest());
        mvc.perform(post(path).session(session).with(csrf()).param("version", "0").param("views", "100"))
                .andExpect(status().isBadRequest());
        assertThat(repository.find(TENANT, PublicationMethod.MANUAL, publicationId)).isEmpty();
    }

    @Test void syncsOfficialViewsAndPreventsImmediateRepeat() throws Exception {
        apiRecord(ChannelType.DEV);
        String path = path(PublicationMethod.API);
        var before = page(path);
        assertThat(before.text()).contains("支持接口同步", "无需自行填写");
        assertThat(before.selectFirst("details.readership-manual").hasAttr("open")).isFalse();
        assertThat(before.selectFirst(".readership-sync form").hasAttr("data-confirm")).isFalse();
        assertThat(before.selectFirst(".readership-sync button").text()).isEqualTo("同步阅读量");
        assertThat(before.html().indexOf("class=\"readership-sync\"")).isLessThan(before.html().indexOf("class=\"readership-manual"));
        when(reader.readViews(any(), eq("123"))).thenReturn(77L);
        mvc.perform(post(path + "/sync").session(session).with(csrf()).param("version", "0")).andExpect(redirectedUrl(path));
        var saved = repository.find(TENANT, PublicationMethod.API, publicationId).orElseThrow();
        assertThat(saved.views()).isEqualTo(77);
        assertThat(saved.uniqueReaders()).isNull();
        assertThat(page(path).text()).contains("自动读取", "阅读 / 浏览 77 次");
        mvc.perform(post(path + "/sync").session(session).with(csrf()).param("version", "2"))
                .andExpect(flash().attributeExists("errorMessage"));
        verify(reader, times(1)).readViews(any(), any());
    }

    @Test void failedSyncRetainsCountsAndPersistsSafeFailureState() throws Exception {
        apiRecord(ChannelType.DISCOURSE);
        String path = path(PublicationMethod.API);
        mvc.perform(post(path).session(session).with(csrf()).param("version", "0").param("views", "100").param("uniqueReaders", "80"))
                .andExpect(status().is3xxRedirection());
        assertThat(page(path).selectFirst(".readership-sync form").attr("data-confirm")).contains("替换手动统计");
        when(reader.readViews(any(), any())).thenThrow(new ApplicationException("READERSHIP_PERMISSION", "平台拒绝访问统计"));
        mvc.perform(post(path + "/sync").session(session).with(csrf()).param("version", "1"))
                .andExpect(redirectedUrl(path)).andExpect(flash().attributeExists("errorMessage"));
        assertThat(page(path).text()).contains("阅读 / 浏览 100 次", "去重 80 人", "手动录入", "同步失败", "已有统计保留");
        assertThat(repository.find(TENANT, PublicationMethod.API, publicationId).orElseThrow().version()).isEqualTo(3);
    }

    @Test void repositoryUsesTenantAndOptimisticVersionsForInsertAndUpdate() {
        var first = new PublicationReadership(TENANT, articleId, PublicationMethod.MANUAL, publicationId,
                1L, null, "MANUAL", Instant.now(), "admin", null, null, 1);
        assertThat(repository.saveIfVersionMatches(first, 0)).isTrue();
        assertThat(repository.saveIfVersionMatches(first, 0)).isFalse();
        assertThat(repository.find("other-tenant", PublicationMethod.MANUAL, publicationId)).isEmpty();
        assertThat(repository.findByArticles("other-tenant", List.of(articleId))).isEmpty();
        var second = new PublicationReadership(TENANT, articleId, PublicationMethod.MANUAL, publicationId,
                2L, null, "MANUAL", Instant.now(), "admin", null, null, 2);
        assertThat(repository.saveIfVersionMatches(second, 1)).isTrue();
        assertThat(repository.saveIfVersionMatches(second, 1)).isFalse();
        assertThat(repository.findByArticles(TENANT, List.of(articleId))).extracting(PublicationReadership::views).containsExactly(2L);
    }

    @Test void enforcesCsrfAndViewerReadOnlyWhileAllowingEditor() throws Exception {
        String path = path(PublicationMethod.MANUAL);
        mvc.perform(post(path).session(session).param("version", "0").param("views", "1"))
                .andExpect(redirectedUrl("/access-denied"));
        for (String role : List.of("VIEWER", "EDITOR")) {
            var user = user(role);
            var html = mvc.perform(get(path).session(user)).andExpect(status().isOk()).andReturn();
            var document = Jsoup.parse(html.getResponse().getContentAsString());
            assertThat(document.select("input[name=views]")).hasSize(role.equals("VIEWER") ? 0 : 1);
            var result = mvc.perform(post(path).session(user).with(csrf()).param("version", "0").param("views", "42"));
            result.andExpect(redirectedUrl(role.equals("VIEWER") ? "/access-denied" : path));
            if (role.equals("VIEWER")) mvc.perform(post(path + "/sync").session(user).with(csrf()).param("version", "0"))
                    .andExpect(redirectedUrl("/access-denied"));
        }
        assertThat(repository.find(TENANT, PublicationMethod.MANUAL, publicationId).orElseThrow().views()).isEqualTo(42);
    }

    @Test void clearlyExplainsCoverageForPersonalChannelsAndUnlinkedManualRecords() throws Exception {
        for (ChannelType type : List.of(ChannelType.CSDN, ChannelType.JUEJIN, ChannelType.XIAOHONGSHU, ChannelType.DEV)) {
            publicationId = UUID.randomUUID();
            manual.save(new ManualPublication(publicationId, TENANT, articleId, type, ContentFormat.PLAIN_TEXT,
                    "统计", "正文", "https://example.com/test", "admin", Instant.now()));
            var html = page(path(PublicationMethod.MANUAL));
            assertThat(html.select(".readership-sync form")).isEmpty();
            assertThat(html.selectFirst("details.readership-manual").hasAttr("open")).isFalse();
            if (type == ChannelType.DEV) assertThat(html.text()).contains("人工发布记录未关联 API 发布账号");
            else if (type == ChannelType.JUEJIN) assertThat(html.text()).contains("需要有效的掘金文章链接", "无需手动填写");
            else assertThat(html.text()).contains("暂未接入自动统计", "无需手动填写");
        }
        var records = page("/publishing/articles/" + articleId);
        assertThat(records.select(".readership-summary a").eachText()).containsOnly("查看说明");
        verifyNoInteractions(reader);
    }

    @Test void manualJuejinLinkCanSyncWithoutAnApiAccountAndGetNeverCallsPlatform() throws Exception {
        publicationId = UUID.randomUUID();
        String url = "https://juejin.cn/post/7698382188488048649";
        manual.save(new ManualPublication(publicationId, TENANT, articleId, ChannelType.JUEJIN, ContentFormat.MARKDOWN,
                "统计", "正文", url, "admin", Instant.now()));
        String path = path(PublicationMethod.MANUAL);
        var html = page(path);
        assertThat(html.text()).contains("支持链接读取", "无需配置账号");
        assertThat(html.select(".readership-sync form")).hasSize(1);
        assertThat(html.selectFirst("details.readership-manual").hasAttr("open")).isFalse();
        verifyNoInteractions(reader);
        when(reader.readPublicViews(ChannelType.JUEJIN, url)).thenReturn(321L);
        mvc.perform(post(path + "/sync").session(session).with(csrf()).param("version", "0"))
                .andExpect(redirectedUrl(path)).andExpect(flash().attributeExists("successMessage"));
        assertThat(page(path).text()).contains("阅读 / 浏览 321 次", "自动读取");
        for (String list : List.of("/publishing?tab=records", "/publishing/articles/" + articleId))
            assertThat(page(list).text()).contains("阅读 / 浏览 321 次", "自动读取");
        assertThat(repository.find(TENANT, PublicationMethod.MANUAL, publicationId).orElseThrow().uniqueReaders()).isNull();
        verify(reader, times(1)).readPublicViews(ChannelType.JUEJIN, url);
        verify(reader, never()).readViews(any(), any());
    }

    private org.jsoup.nodes.Document page(String path) throws Exception {
        return Jsoup.parse(mvc.perform(get(path).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private String path(PublicationMethod method) { return "/publishing/articles/" + articleId + "/readership/" + method + "/" + publicationId; }
    private MockHttpSession login(String username, String password) throws Exception {
        return (MockHttpSession) mvc.perform(formLogin().user(username).password(password))
                .andExpect(status().is3xxRedirection()).andReturn().getRequest().getSession(false);
    }
    private MockHttpSession user(String role) throws Exception {
        UUID id = UUID.randomUUID();
        String name = role.toLowerCase() + "-" + id;
        Instant now = Instant.now();
        jdbc.update("insert into local_users(id,tenant_id,username,password_hash,enabled,created_at,updated_at,must_change_password) values(?,?,?,?,true,?,?,false)",
                id, TENANT, name, encoder.encode("Readership-user-001!"), now, now);
        jdbc.update("insert into local_user_roles(user_id,role) values(?,?)", id, role);
        return login(name, "Readership-user-001!");
    }
    private void apiRecord(ChannelType type) {
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.now();
        accounts.save(new ChannelAccount(accountId, TENANT, type, "统计账号", "https://example.com", "isolated-test",
                "readership-" + accountId, "f".repeat(64), "fingerprint", 1, ChannelAccountStatus.ACTIVE, "admin", "admin", now, now));
        var p = new Publication(publicationId, TENANT, articleId, accountId, UUID.randomUUID(), type, null,
                PublicationStatus.PUBLISHED, "123", "https://example.com/article/123", null, null, now, now, now);
        doReturn(List.of(p)).when(publications).findApiByArticle(TENANT, articleId);
        doReturn(Optional.of(p)).when(publications).findPublicationById(TENANT, publicationId);
    }
}
