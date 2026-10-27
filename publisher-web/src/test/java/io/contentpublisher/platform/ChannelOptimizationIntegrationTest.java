package io.contentpublisher.platform;

import io.contentpublisher.platform.application.*;
import io.contentpublisher.platform.application.port.*;
import io.contentpublisher.platform.domain.*;
import io.contentpublisher.platform.web.dto.PublicationMatrixRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.jsoup.Jsoup;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:channel_optimization;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "publisher.security.mode=DISABLED", "publisher.jobs.worker-enabled=false",
        "publisher.automation.channel-health-enabled=false", "publisher.automation.webhook-enabled=false"
})
@AutoConfigureMockMvc
class ChannelOptimizationIntegrationTest {
    private static final ActorContext ACTOR = new ActorContext("local", "local-developer");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ManualChannelProfileApplicationService profiles;
    @Autowired ManualChannelProfileRepository profileRepository;
    @Autowired ChannelAccountRepository accounts;
    @Autowired ManualPublicationRepository manualPublications;
    @MockitoSpyBean PublicationRepository publications;

    @BeforeEach void resetProfiles() { jdbc.update("delete from manual_channel_profiles"); }

    @Test void shouldShowPublishedPerChannelAndPreserveSuccessAfterLaterFailure() throws Exception {
        String article = create();
        UUID id = UUID.fromString(article.substring(article.lastIndexOf('/') + 1));
        mvc.perform(post(article + "/confirm")).andExpect(status().is3xxRedirection());
        for (ChannelType type : List.of(ChannelType.ZHIHU, ChannelType.CSDN, ChannelType.DEV)) {
            profiles.saveProfile(ACTOR, type, 0, true, null, List.of(), null, null, 10);
        }
        Instant now = Instant.now();
        manualPublications.save(new ManualPublication(UUID.randomUUID(), "local", id, ChannelType.ZHIHU,
                ContentFormat.PLAIN_TEXT, "旧标题", "旧内容", "https://zhuanlan.zhihu.com/p/old", "owner", now.minusSeconds(60)));
        manualPublications.save(new ManualPublication(UUID.randomUUID(), "local", id, ChannelType.ZHIHU,
                ContentFormat.PLAIN_TEXT, "新标题", "新内容", "https://zhuanlan.zhihu.com/p/new", "owner", now.minusSeconds(30)));
        UUID account = account(ChannelType.DEV);
        var success = new Publication(UUID.randomUUID(), "local", id, account, UUID.randomUUID(), ChannelType.DEV,
                null, PublicationStatus.PUBLISHED, "dev-success", "https://dev.to/owner/success", null, null, now.minusSeconds(40), now.minusSeconds(40), now.minusSeconds(40));
        var failure = new Publication(UUID.randomUUID(), "local", id, account, UUID.randomUUID(), ChannelType.DEV,
                null, PublicationStatus.FAILED, null, null, "FAILED", "失败", null, now, now);
        doReturn(List.of(failure, success)).when(publications).findApiByArticle("local", id);
        jdbc.update("update articles set status='PUBLISHED' where id=?", id);
        var page = mvc.perform(get("/publishing" + article)).andExpect(status().isOk()).andReturn();
        var html = Jsoup.parse(page.getResponse().getContentAsString());
        var rows = html.select(".manual-channel-row");
        var zhihu = rows.stream().filter(row -> row.text().contains("知乎")).findFirst().orElseThrow();
        assertThat(zhihu.text()).contains("已发布", "查看结果", "再次发布").doesNotContain("准备发布");
        assertThat(zhihu.select("a[href='https://zhuanlan.zhihu.com/p/new']")).hasSize(1);
        var dev = rows.stream().filter(row -> row.text().contains("DEV Community")).findFirst().orElseThrow();
        assertThat(dev.text()).contains("已发布").doesNotContain("准备发布");
        assertThat(dev.select("a[href='https://dev.to/owner/success']")).hasSize(1);
        var csdn = rows.stream().filter(row -> row.text().contains("CSDN")).findFirst().orElseThrow();
        assertThat(csdn.text()).contains("准备发布").doesNotContain("已发布");
        assertThat(html.text()).contains("历史成功记录", "修订后是否已同步");
    }

    @Test void shouldDefaultToPersonalChannelsAndSaveSelectionWithoutLosingPreferences() throws Exception {
        var page = mvc.perform(get("/channels")).andExpect(status().isOk())
                .andExpect(model().attribute("selectedChannelView", "manual")).andReturn();
        var html = Jsoup.parse(page.getResponse().getContentAsString());
        assertThat(html.select("input[form=channel-selection][name=enabled]")).hasSize(24);
        assertThat(html.select("input[form=channel-selection][name=enabled][checked]")).isEmpty();
        profiles.saveProfile(ACTOR, ChannelType.CSDN, 0, true, "个人账号", List.of("java"), "后端", "保留备注", 40);
        var request = post("/channels/manual/selection").param("enabled", "JUEJIN");
        versions().forEach((type, version) -> request.param("versions[" + type + "]", version.toString()));
        mvc.perform(request).andExpect(redirectedUrl("/channels?view=manual"))
                .andExpect(flash().attributeExists("success"));
        assertThat(profiles.isEnabled(ACTOR, ChannelType.JUEJIN)).isTrue();
        var csdn = profiles.findProfile(ACTOR, ChannelType.CSDN).orElseThrow();
        assertThat(csdn.enabled()).isFalse();
        assertThat(csdn.accountAlias()).isEqualTo("个人账号");
        assertThat(csdn.defaultTags()).containsExactly("java");
        assertThat(csdn.notes()).isEqualTo("保留备注");
        assertThat(csdn.sortOrder()).isEqualTo(40);
        var stale = post("/channels/manual/selection").param("enabled", "CSDN");
        versions().forEach((type, version) -> stale.param("versions[" + type + "]", "0"));
        mvc.perform(stale).andExpect(flash().attributeExists("error"));
        assertThat(profiles.isEnabled(ACTOR, ChannelType.JUEJIN)).isTrue();
        assertThat(profiles.isEnabled(ACTOR, ChannelType.CSDN)).isFalse();
    }

    @Test void shouldRollBackEntireSelectionWhenALaterVersionConflicts() {
        var first = profiles.saveProfile(ACTOR, ChannelType.CSDN, 0, true, null, List.of(), null, null, 10);
        var second = profiles.saveProfile(ACTOR, ChannelType.JUEJIN, 0, true, null, List.of(), null, null, 20);
        var persistedFirst = profiles.findProfile(ACTOR, ChannelType.CSDN).orElseThrow();
        var persistedSecond = profiles.findProfile(ACTOR, ChannelType.JUEJIN).orElseThrow();
        assertThatThrownBy(() -> profileRepository.saveAllIfVersionsMatch(
                List.of(disabled(first), disabled(second)), Map.of(ChannelType.CSDN, 1, ChannelType.JUEJIN, 99)))
                .isInstanceOf(ApplicationException.class);
        assertThat(profiles.findProfile(ACTOR, ChannelType.CSDN).orElseThrow()).isEqualTo(persistedFirst);
        assertThat(profiles.findProfile(ACTOR, ChannelType.JUEJIN).orElseThrow()).isEqualTo(persistedSecond);
    }

    @Test void shouldIncludeApiOnlyChannelsDeduplicatePlatformsAndKeepSuccessfulLinks() throws Exception {
        String article = create();
        UUID id = UUID.fromString(article.substring(article.lastIndexOf('/') + 1));
        UUID dev = account(ChannelType.DEV);
        account(ChannelType.DEV);
        account(ChannelType.GHOST);
        profiles.saveProfile(ACTOR, ChannelType.DEV, 0, true, null, List.of(), null, null, 10);
        Instant now = Instant.now();
        manualPublications.save(new ManualPublication(UUID.randomUUID(), "local", id, ChannelType.DEV,
                ContentFormat.MARKDOWN, "成功标题", "成功正文", "https://dev.to/owner/success", "owner", now.minusSeconds(60)));
        var failed = new Publication(UUID.randomUUID(), "local", id, dev, UUID.randomUUID(), ChannelType.DEV,
                null, PublicationStatus.FAILED, null, null, "FAILED", "测试失败", null, now, now);
        doReturn(List.of(failed)).when(publications).findApiByArticles("local", List.of(id));
        var page = mvc.perform(get("/publishing").param("tab", "coverage").param("q", "渠道回归文稿"))
                .andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked")
        var rows = (List<PublicationMatrixRow>) page.getModelAndView().getModel().get("publicationMatrix");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).cells().stream().map(cell -> cell.channel().type()))
                .contains(ChannelType.GHOST).containsOnlyOnce(ChannelType.DEV);
        var html = Jsoup.parse(page.getResponse().getContentAsString());
        assertThat(html.select("a[href='https://dev.to/owner/success']")).hasSize(1);
        assertThat(html.text()).contains("最近尝试失败，原发布记录保留");
    }

    @Test void shouldAllowOriginalAndExplicitEnglishAndSanitizeRichText() throws Exception {
        String article = create();
        UUID id = UUID.fromString(article.substring(article.lastIndexOf('/') + 1));
        profiles.saveProfile(ACTOR, ChannelType.ZHIHU, 0, true, null, List.of(), null, null, 10);
        jdbc.update("update articles set title_en='English title',summary_en='English summary',markdown_en='## English body' where id=?", id);
        var original = mvc.perform(get(article + "/manual/ZHIHU")).andExpect(status().isOk()).andReturn();
        var html = Jsoup.parse(original.getResponse().getContentAsString());
        assertThat(html.select("#manual-title").val()).isEqualTo("渠道回归文稿");
        assertThat(html.select("template#manual-rich-content").html()).contains("<strong>粗体</strong>")
                .doesNotContain("<script>", "javascript:");
        mvc.perform(get(article + "/manual/ZHIHU").param("language", "en"))
                .andExpect(status().isOk()).andExpect(model().attribute("selectedLanguage", "en"));
        var invalid = mvc.perform(post(article + "/manual/ZHIHU").param("language", "en")
                .param("contentFormat", "PLAIN_TEXT").param("title", "English edited")
                .param("content", "English edited body").param("externalUrl", ""))
                .andExpect(status().isOk()).andReturn();
        assertThat(Jsoup.parse(invalid.getResponse().getContentAsString()).select("#manual-title").val()).isEqualTo("English edited");
        assertThat(invalid.getModelAndView().getModel().get("selectedLanguage")).isEqualTo("en");
    }

    private Map<ChannelType, Integer> versions() {
        Map<ChannelType, Integer> result = ManualChannelProfileApplicationService.configurableChannels().stream()
                .collect(Collectors.toMap(ChannelCatalog.ChannelDefinition::type, channel -> 0));
        profiles.listProfiles(ACTOR).forEach(profile -> result.put(profile.channelType(), profile.version()));
        return result;
    }
    private String create() throws Exception {
        return mvc.perform(post("/articles/custom").param("title", "渠道回归文稿")
                .param("summary", "摘要").param("markdown", "## 正文\n\n**粗体**\n\n<script>alert(1)</script>\n\n[坏链接](javascript:alert)")
                .param("language", "zh-CN")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }
    private UUID account(ChannelType type) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        accounts.save(new ChannelAccount(id, "local", type, "测试 " + type, "https://example.com", "test-only",
                "channel-test-" + id, "c".repeat(64), "test", 1, ChannelAccountStatus.ACTIVE, "owner", "owner", now, now));
        return id;
    }
    private ManualChannelProfile disabled(ManualChannelProfile p) {
        return new ManualChannelProfile(p.id(), p.tenantId(), p.channelType(), false, p.accountAlias(), p.defaultTags(),
                p.defaultSection(), p.notes(), p.sortOrder(), p.loginConfirmedAt(), p.version() + 1,
                p.createdBy(), p.updatedBy(), p.createdAt(), Instant.now());
    }
}
