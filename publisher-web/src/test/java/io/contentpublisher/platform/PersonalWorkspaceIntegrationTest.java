package io.contentpublisher.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import io.contentpublisher.platform.application.PagedResult;
import io.contentpublisher.platform.domain.Article;
import org.jsoup.Jsoup;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:personal_workspace;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "publisher.security.mode=DISABLED",
        "publisher.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class PersonalWorkspaceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void publishingSearchAndPaginationReachBeyondTheLatestFiftyArticles() throws Exception {
        String prefix = "coverage-" + UUID.randomUUID();
        for (int i = 0; i < 55; i++) create(prefix + "-" + i);
        var page = mvc.perform(get("/publishing").param("q", prefix).param("size", "20").param("page", "2"))
                .andExpect(status().isOk()).andReturn();
        var data = (PagedResult<?>) page.getModelAndView().getModel().get("articlePage");
        org.assertj.core.api.Assertions.assertThat(data.totalItems()).isEqualTo(55);
        org.assertj.core.api.Assertions.assertThat(data.items()).hasSize(15);
        var html = Jsoup.parse(page.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(html.select(".coverage-article")).hasSize(15);
        org.assertj.core.api.Assertions.assertThat(html.select("nav[aria-label=覆盖矩阵分页]")).hasSize(1);
        var filtered = mvc.perform(get("/publishing").param("q", prefix + "-0"))
                .andExpect(status().isOk()).andReturn();
        org.assertj.core.api.Assertions.assertThat(
                Jsoup.parse(filtered.getResponse().getContentAsString()).select(".coverage-article")).hasSize(1);
        var empty = mvc.perform(get("/publishing").param("q", prefix + "-missing"))
                .andExpect(status().isOk()).andReturn();
        org.assertj.core.api.Assertions.assertThat(
                Jsoup.parse(empty.getResponse().getContentAsString()).select(".coverage-article")).isEmpty();
    }

    @Test
    void pendingPublicationFiltersBeforePaginationAndExcludesPublishedAndDeletedArticles() throws Exception {
        String prefix = "pending-" + UUID.randomUUID();
        String ready = create(prefix + "-ready");
        String legacy = create(prefix + "-legacy");
        String published = create(prefix + "-published");
        String deleted = create(prefix + "-deleted");
        jdbc.update("update articles set status='READY' where id=?", id(ready));
        jdbc.update("update articles set status='APPROVED' where id=?", id(legacy));
        jdbc.update("update articles set status='PUBLISHED' where id=?", id(published));
        jdbc.update("update articles set status='READY', deleted_at=current_timestamp where id=?", id(deleted));
        // Newer drafts must not push the two pending manuscripts out of the query window.
        for (int i = 0; i < 51; i++) create(prefix + "-draft-" + i);
        var result = mvc.perform(get("/publishing").param("tab", "queue").param("q", prefix))
                .andExpect(status().isOk()).andReturn();
        var data = (PagedResult<?>) result.getModelAndView().getModel().get("articlePage");
        org.assertj.core.api.Assertions.assertThat(data.totalItems()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(data.items().stream().map(item -> ((Article) item).title()))
                .containsExactlyInAnyOrder(prefix + "-ready", prefix + "-legacy");
    }

    private String create(String title) throws Exception {
        return mvc.perform(post("/articles/custom").param("title", title).param("summary", "测试摘要")
                        .param("markdown", "测试正文").param("language", "zh-CN"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
    }

    private UUID id(String articleUrl) {
        return UUID.fromString(articleUrl.substring(articleUrl.lastIndexOf('/') + 1));
    }

    @Test
    void localWorkspaceWorksWithoutAuthenticationAndKeepsDiagnostics() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("我的文稿")))
                .andExpect(content().string(containsString("local-developer")))
                .andExpect(content().string(containsString("任务进度与重试")))
                .andExpect(content().string(containsString("渠道连接与巡检")))
                .andExpect(content().string(containsString("运行监控")))
                .andExpect(content().string(containsString("noindex,nofollow")))
                .andExpect(content().string(not(containsString("服务运行中"))))
                .andExpect(content().string(not(containsString("修改密码"))));
    }

    @Test
    void failedFormalSaveKeepsTheSubmittedFields() throws Exception {
        String articleUrl = mvc.perform(post("/articles/custom")
                        .param("title", "原始标题").param("summary", "原始摘要")
                        .param("markdown", "原始正文").param("language", "zh-CN"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        mvc.perform(get(articleUrl)).andExpect(status().isOk())
                .andExpect(content().string(containsString("保存并准备发布")))
                .andExpect(content().string(containsString("data-draft-editable=\"true\"")))
                .andExpect(content().string(not(containsString("确认已保存版本"))));

        var failed = mvc.perform(post(articleUrl + "/edit")
                        .param("expectedVersion", "1").param("title", "尚未保存的新标题")
                        .param("summary", "").param("markdown", "必须保留的新正文"))
                .andExpect(status().is3xxRedirection()).andReturn();
        mvc.perform(get(articleUrl).flashAttrs(failed.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("尚未保存的新标题")))
                .andExpect(content().string(containsString("必须保留的新正文")));
    }

    @Test
    void versionConflictKeepsInputWithoutSilentlyRebasingIt() throws Exception {
        String articleUrl = mvc.perform(post("/articles/custom")
                        .param("title", "冲突测试").param("summary", "原始摘要")
                        .param("markdown", "原始正文").param("language", "zh-CN"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        var failed = mvc.perform(post(articleUrl + "/edit")
                        .param("expectedVersion", "2").param("title", "冲突中的输入")
                        .param("summary", "保留摘要").param("markdown", "保留正文"))
                .andExpect(status().is3xxRedirection()).andReturn();
        mvc.perform(get(articleUrl).flashAttrs(failed.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("冲突中的输入")))
                .andExpect(content().string(containsString("保留正文")));
        mvc.perform(get("/api/v1" + articleUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("冲突测试"))
                .andExpect(jsonPath("$.currentVersion").value(1));
    }
}
