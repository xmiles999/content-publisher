package io.contentpublisher.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contentpublisher.platform.application.*;
import io.contentpublisher.platform.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:workspace_durability;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "publisher.security.mode=DISABLED", "publisher.jobs.worker-enabled=false",
    "publisher.channels.enabled=false", "publisher.ai.enabled=false",
    "publisher.channels.health-check-enabled=false", "publisher.automation.webhook-enabled=false"
})
@AutoConfigureMockMvc
class WorkspaceDurabilityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired WorkspaceDraftService drafts;
    @Autowired PublishingApplicationService publishing;
    @Autowired AutomationApplicationService automation;
    @Autowired JdbcTemplate jdbc;
    private final ActorContext actor = new ActorContext("local", "local-developer");

    private Article article() {
        return publishing.createCustomArticle(actor, "持久草稿 " + UUID.randomUUID(), "摘要", "正文",
            List.of("test"), List.of("test"), "", "", "", List.of(), List.of(), "zh-CN");
    }
    @Test void newDraftSurvivesReloadAndRejectsStaleWrites() throws Exception {
        jdbc.update("delete from workspace_drafts where draft_key='new'");
        mvc.perform(put("/articles/workspace-drafts/new").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision",0,"fields", Map.of("title","未完成新稿","markdown","")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        mvc.perform(get("/projects")).andExpect(status().isOk())
            .andExpect(content().string(containsString("未完成新稿")));
        mvc.perform(put("/articles/workspace-drafts/new").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("revision",0,"fields",Map.of("title","旧页覆盖")))))
            .andExpect(status().isConflict());
        assertThat(drafts.find(actor,"new").orElseThrow().fields().get("title")).isEqualTo("未完成新稿");
        assertThat(drafts.find(new ActorContext("local","another"),"new")).isEmpty();
        mvc.perform(post("/articles/custom").param("title","旧页").param("markdown","正文")
                .param("language","zh-CN").param("draftRevision","0"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("其他页面已保存")));
        drafts.discard(actor,"new",1);
        assertThat(drafts.find(actor,"new").orElseThrow().revision()).isEqualTo(2);
        assertThatThrownBy(() -> drafts.save(actor,"new",1,Map.of("title","已关闭的旧页")))
            .isInstanceOf(ApplicationException.class);
    }
    @Test void rejectsUnknownDraftFieldsAndForeignArticle() throws Exception {
        mvc.perform(put("/articles/workspace-drafts/new").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("revision",0,"fields",Map.of("admin","true")))))
            .andExpect(status().isBadRequest());
        assertThatThrownBy(() -> drafts.find(new ActorContext("other","owner"),
            WorkspaceDraftService.manualKey(article().id(),ChannelType.CSDN,"original",1)))
            .isInstanceOf(ApplicationException.class);
    }
    @Test void manualPreparationSurvivesReloadWithoutReoptimizing() throws Exception {
        var article = article();
        publishing.confirmArticle(actor, article.id());
        mvc.perform(post("/channels/manual/CSDN/profile").param("expectedVersion","0")
            .param("enabled","true").param("sortOrder","10")).andExpect(status().is3xxRedirection());
        String key = WorkspaceDraftService.manualKey(article.id(), ChannelType.CSDN,"original",1);
        drafts.save(actor,key,0,Map.of("title","平台专用标题","content","只为此平台改写","externalUrl",""));
        mvc.perform(get("/articles/"+article.id()+"/manual/CSDN"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("只为此平台改写")))
            .andExpect(content().string(containsString("data-auto-optimize=\"false\"")));
        assertThatThrownBy(() -> drafts.save(actor,key,0,Map.of("title","旧页")))
            .isInstanceOf(ApplicationException.class);
    }
    @Test void articleDraftUsesIndependentRevision() throws Exception {
        var article=article();
        var content=new AutomationApplicationService.DraftContent("标题","摘要","正文",List.of(),List.of(),"","","",List.of(),List.of());
        var first=automation.saveDraft(actor,article.id(),1,0L,content);
        assertThat(first.revision()).isEqualTo(1);
        assertThatThrownBy(() -> automation.saveDraft(actor,article.id(),1,0L,content)).isInstanceOf(ApplicationException.class);
        assertThat(automation.saveDraft(actor,article.id(),1,1L,content).revision()).isEqualTo(2);
    }
    @Test void exportsOwnedManuscriptAndRejectsForeignTenant() throws Exception {
        var article=article();
        byte[] data=mvc.perform(get("/articles/"+article.id()+"/export"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andReturn().getResponse().getContentAsByteArray();
        Map<String,String> entries=new HashMap<>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(data), java.nio.charset.StandardCharsets.UTF_8)) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) entries.put(entry.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThat(entries).containsEntry("article.md","正文");
        assertThat(entries.get("发布说明.txt")).contains("版本：1");
        assertThatThrownBy(() -> publishing.getArticle(new ActorContext("other","owner"),article.id()))
            .isInstanceOf(ApplicationException.class);
    }
}
