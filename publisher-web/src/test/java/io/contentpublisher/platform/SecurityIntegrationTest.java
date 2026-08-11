package io.contentpublisher.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "publisher.security.mode=JWT",
        "publisher.jobs.worker-enabled=false",
        "publisher.channels.enabled=true",
        "publisher.channels.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1/unused"
})
@AutoConfigureMockMvc
class SecurityIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @Test
    void shouldRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowViewerToReadWithinTenantContext() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + UUID.randomUUID())
                        .with(jwt().jwt(token -> token.subject("reader")
                                .claim("tenant_id", "tenant-a")
                                .claim("roles", List.of("VIEWER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldValidateOfficialPublishedLinkForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/v1/publications/link-validation")
                        .param("channelType", "XIAOHONGSHU")
                        .param("url", "https://www.rednote.com/explore/example")
                        .with(jwt().jwt(token -> token.subject("editor")
                                        .claim("tenant_id", "tenant-link-validation")
                                        .claim("roles", List.of("EDITOR")))
                                .authorities(new SimpleGrantedAuthority("ROLE_EDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.normalizedUrl").value("https://www.rednote.com/explore/example"))
                .andExpect(jsonPath("$.allowedHosts").isArray());
    }

    @Test
    void shouldDenyViewerFromImportingRepository() throws Exception {
        mockMvc.perform(post("/api/v1/projects/imports")
                        .contentType("application/json")
                        .content("{\"gitUrl\":\"https://github.com/example/repository.git\"}")
                        .with(jwt().jwt(token -> token.subject("reader")
                                .claim("tenant_id", "tenant-a")
                                .claim("roles", List.of("VIEWER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyViewerFromSubmittingPublicationBatch() throws Exception {
        String body = "{\"channelAccountIds\":[\"" + UUID.randomUUID() + "\"]}";
        mockMvc.perform(post("/api/v1/articles/" + UUID.randomUUID() + "/publication-batches")
                        .header("Idempotency-Key", "security-publication-batch-001")
                        .contentType("application/json").content(body)
                        .with(jwt().jwt(token -> token.subject("reader")
                                        .claim("tenant_id", "tenant-a")
                                        .claim("roles", List.of("VIEWER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyViewerFromRetryingFailedPublication() throws Exception {
        mockMvc.perform(post("/api/v1/jobs/" + UUID.randomUUID() + "/publication-retry")
                        .header("Idempotency-Key", "security-publication-retry-001")
                        .with(jwt().jwt(token -> token.subject("reader")
                                        .claim("tenant_id", "tenant-a")
                                        .claim("roles", List.of("VIEWER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowOnlyAdminToDeleteOrRestoreContentAndQueueRecords() throws Exception {
        var editor = jwt().jwt(token -> token.subject("editor").claim("tenant_id", "tenant-delete-security")
                        .claim("roles", List.of("EDITOR")))
                .authorities(new SimpleGrantedAuthority("ROLE_EDITOR"));

        mockMvc.perform(delete("/api/v1/articles/" + UUID.randomUUID()).with(editor))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/jobs/" + UUID.randomUUID()).with(editor))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/articles/" + UUID.randomUUID() + "/restore").with(editor))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/jobs/" + UUID.randomUUID() + "/restore").with(editor))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAcceptEditorJobSubmission() throws Exception {
        mockMvc.perform(post("/api/v1/projects/imports")
                        .header("Idempotency-Key", "security-test-import-001")
                        .contentType("application/json")
                        .content("{\"gitUrl\":\"https://github.com/contentpublisher/platform.git\"}")
                        .with(jwt().jwt(token -> token.subject("editor")
                                        .claim("tenant_id", "tenant-a")
                                        .claim("roles", List.of("EDITOR")))
                                .authorities(new SimpleGrantedAuthority("ROLE_EDITOR"))))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/jobs/")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.type").value("IMPORT_PROJECT"));
    }

    @Test
    void shouldRejectWriteRequestWithoutIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/projects/imports")
                        .contentType("application/json")
                        .content("{\"gitUrl\":\"https://github.com/contentpublisher/platform.git\"}")
                        .with(jwt().jwt(token -> token.subject("editor")
                                        .claim("tenant_id", "tenant-missing-header")
                                        .claim("roles", List.of("EDITOR")))
                                .authorities(new SimpleGrantedAuthority("ROLE_EDITOR"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_HEADER_MISSING"));
    }

    @Test
    void shouldAllowOnlyAdminToCreateEncryptedChannelAccount() throws Exception {
        String body = """
                {"type":"DEV","displayName":"DEV Main","credentials":{"apiKey":"secret-value"}}
                """;
        mockMvc.perform(post("/api/v1/channel-accounts")
                        .header("Idempotency-Key", "channel-account-001")
                        .contentType("application/json").content(body)
                        .with(jwt().jwt(token -> token.subject("editor").claim("tenant_id", "tenant-channel")
                                        .claim("roles", List.of("EDITOR")))
                                .authorities(new SimpleGrantedAuthority("ROLE_EDITOR"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/channel-accounts")
                        .header("Idempotency-Key", "channel-account-001")
                        .contentType("application/json").content(body)
                        .with(jwt().jwt(token -> token.subject("admin").claim("tenant_id", "tenant-channel")
                                        .claim("roles", List.of("ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEV"))
                .andExpect(jsonPath("$.baseUrl").value("https://dev.to"))
                .andExpect(jsonPath("$.credentials").doesNotExist());

        String encrypted = jdbcTemplate.queryForObject(
                "select encrypted_credentials from channel_accounts where tenant_id = ?", String.class,
                "tenant-channel");
        assertThat(encrypted).startsWith("v1:").doesNotContain("secret-value");
    }

    @Test
    void shouldRestrictLifecycleOperationsAndEncryptRotatedCredentials() throws Exception {
        String tenant = "tenant-channel-lifecycle";
        var admin = jwt().jwt(token -> token.subject("admin").claim("tenant_id", tenant)
                        .claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var editor = jwt().jwt(token -> token.subject("editor").claim("tenant_id", tenant)
                        .claim("roles", List.of("EDITOR")))
                .authorities(new SimpleGrantedAuthority("ROLE_EDITOR"));
        String createBody = """
                {"type":"DEV","displayName":"DEV Lifecycle","credentials":{"apiKey":"initial-secret"}}
                """;
        String response = mockMvc.perform(post("/api/v1/channel-accounts")
                        .header("Idempotency-Key", "channel-lifecycle-001")
                        .contentType("application/json").content(createBody).with(admin))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID accountId = UUID.fromString(objectMapper.readTree(response).path("id").asText());

        String rotateBody = """
                {"expectedVersion":1,"credentials":{"apiKey":"rotated-secret-value"}}
                """;
        mockMvc.perform(put("/api/v1/channel-accounts/{accountId}/credentials", accountId)
                        .contentType("application/json").content(rotateBody).with(editor))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/channel-accounts/{accountId}/status", accountId)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1,\"status\":\"DISABLED\"}").with(editor))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/channel-accounts/{accountId}/credentials", accountId)
                        .contentType("application/json").content(rotateBody).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        String encrypted = jdbcTemplate.queryForObject(
                "select encrypted_credentials from channel_accounts where id = ?", String.class, accountId);
        assertThat(encrypted).startsWith("v1:").doesNotContain("rotated-secret-value");

        mockMvc.perform(delete("/api/v1/channel-accounts/{accountId}", accountId)
                        .queryParam("expectedVersion", "2").with(editor))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/channel-accounts/{accountId}", accountId)
                        .queryParam("expectedVersion", "2").with(admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/channel-accounts/{accountId}", accountId).with(admin))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForMap("""
                select status, encrypted_credentials, credential_fingerprint, deleted_by, deleted_at
                from channel_accounts where id = ?
                """, accountId))
                .containsEntry("status", "DISABLED")
                .containsEntry("encrypted_credentials", "DELETED")
                .containsEntry("credential_fingerprint", "DELETED")
                .containsEntry("deleted_by", "admin");
    }
}
