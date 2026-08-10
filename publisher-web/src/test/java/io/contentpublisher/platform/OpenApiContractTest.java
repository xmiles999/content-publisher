package io.contentpublisher.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:openapi;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "publisher.security.mode=DISABLED",
        "publisher.jobs.worker-enabled=false",
        "springdoc.api-docs.enabled=true"
})
@AutoConfigureMockMvc
class OpenApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void shouldMatchReviewedOpenApiSnapshot() throws Exception {
        String response = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode actual = objectMapper.readTree(response);
        Path snapshot = projectRoot().resolve("docs/openapi.json");

        if (Boolean.getBoolean("openapi.update")) {
            ObjectMapper pretty = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, pretty.writeValueAsString(actual) + System.lineSeparator());
        }

        assertThat(snapshot)
                .as("OpenAPI 快照不存在；执行 ./scripts/openapi update 生成并审阅")
                .exists();
        JsonNode expected = objectMapper.readTree(Files.readString(snapshot));
        assertThat(actual)
                .as("OpenAPI 契约发生变化；确认兼容性后执行 ./scripts/openapi update 更新快照")
                .isEqualTo(expected);
    }

    private Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("publisher-web/pom.xml"))
                    && Files.exists(current.resolve("docs"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位项目根目录");
    }
}
