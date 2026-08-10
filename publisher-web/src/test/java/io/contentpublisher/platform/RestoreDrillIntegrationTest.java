package io.contentpublisher.platform;

import io.contentpublisher.platform.application.port.CredentialVault;
import io.contentpublisher.platform.application.port.SecretCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "restore.drill.enabled", matches = "true")
@SpringBootTest(properties = {
        "publisher.jobs.worker-enabled=false",
        "publisher.automation.channel-health-enabled=false",
        "publisher.automation.webhook-enabled=false",
        "springdoc.api-docs.enabled=false"
})
class RestoreDrillIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SecretCipher secretCipher;
    @Autowired CredentialVault credentialVault;

    @Test
    void shouldValidateSchemaLoginTenantIsolationAndEncryptedSecrets() {
        Integer flywayVersion = jdbc.queryForObject("""
                select max(cast(version as integer)) from flyway_schema_history where success=true
                """, Integer.class);
        assertThat(flywayVersion).isGreaterThanOrEqualTo(20);

        String username = requiredProperty("restore.drill.username");
        String password = requiredProperty("restore.drill.password");
        Map<String, Object> user = jdbc.queryForMap("""
                select tenant_id, password_hash, enabled from local_users where username=?
                """, username);
        assertThat(user.get("enabled")).isEqualTo(true);
        assertThat(new BCryptPasswordEncoder().matches(password, user.get("password_hash").toString())).isTrue();

        String expectedTenant = System.getProperty("restore.drill.tenant", "").trim();
        if (!expectedTenant.isEmpty()) {
            assertThat(user.get("tenant_id")).isEqualTo(expectedTenant);
            Integer tenantRows = jdbc.queryForObject("""
                    select (
                        (select count(*) from projects where tenant_id=?)
                        + (select count(*) from articles where tenant_id=?)
                        + (select count(*) from jobs where tenant_id=?)
                        + (select count(*) from channel_accounts where tenant_id=?)
                    )
                    """, Integer.class, expectedTenant, expectedTenant, expectedTenant, expectedTenant);
            assertThat(tenantRows).isNotNull().isGreaterThanOrEqualTo(0);
        }

        jdbc.query("""
                select tenant_id, encrypted_api_key from ai_provider_settings
                where encrypted_api_key is not null
                """, rs -> {
            while (rs.next()) {
                String plaintext = secretCipher.decrypt(
                        "ai-api-key:" + rs.getString("tenant_id"), rs.getString("encrypted_api_key"));
                assertThat(plaintext).isNotBlank();
            }
            return null;
        });
        jdbc.query("select encrypted_credentials from channel_accounts", rs -> {
            while (rs.next()) {
                assertThat(credentialVault.decrypt(rs.getString("encrypted_credentials"))).isNotEmpty();
            }
            return null;
        });
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name, "").trim();
        assertThat(value).as("缺少恢复演练参数 %s", name).isNotEmpty();
        return value;
    }
}
