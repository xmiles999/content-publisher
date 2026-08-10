package io.contentpublisher.platform.web.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "publisher.security.mode", havingValue = "LOCAL")
public class LocalAdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final LocalSecurityProperties properties;
    private final Clock clock;

    public LocalAdminBootstrap(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
                               LocalSecurityProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        String username = properties.bootstrapUsername().toLowerCase(Locale.ROOT);
        if (username.isBlank() && properties.bootstrapPassword().isBlank()) {
            Integer existingUsers = jdbcTemplate.queryForObject(
                    "select count(*) from local_users", Integer.class);
            if (existingUsers != null && existingUsers > 0) return;
        }
        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from local_users where username = ?", Integer.class, username);
        if (existing != null && existing > 0) return;
        validateBootstrap(username, properties.bootstrapPassword(), properties.bootstrapTenant());
        UUID userId = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update("""
                        insert into local_users(id, tenant_id, username, password_hash, enabled, must_change_password,
                                                created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """, userId, properties.bootstrapTenant(), username,
                passwordEncoder.encode(properties.bootstrapPassword()), true,
                properties.bootstrapMustChangePassword(), now, now);
        jdbcTemplate.update("insert into local_user_roles(user_id, role) values (?, ?)", userId, "ADMIN");
    }

    private void validateBootstrap(String username, String password, String tenantId) {
        if (!username.matches("[a-z0-9._@-]{3,100}")) {
            throw new IllegalStateException("本地管理员用户名格式无效");
        }
        if (password.length() < 16 || password.length() > 200) {
            throw new IllegalStateException("本地管理员初始密码必须为 16 到 200 个字符");
        }
        if (!tenantId.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw new IllegalStateException("本地管理员租户标识格式无效");
        }
    }
}
