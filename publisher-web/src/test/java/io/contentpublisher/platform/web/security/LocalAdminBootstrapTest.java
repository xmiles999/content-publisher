package io.contentpublisher.platform.web.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAdminBootstrapTest {

    @Test
    void shouldAllowRestartWithoutBootstrapCredentialsWhenLocalUserAlreadyExists() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select count(*) from local_users", Integer.class)).thenReturn(1);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        LocalAdminBootstrap bootstrap = new LocalAdminBootstrap(jdbc, passwordEncoder,
                new LocalSecurityProperties("", "", "local", true), Clock.systemUTC());

        bootstrap.run(new DefaultApplicationArguments());

        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Object[]>any());
        verify(passwordEncoder, never()).encode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldRejectEmptyDatabaseWithoutBootstrapCredentials() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select count(*) from local_users", Integer.class)).thenReturn(0);
        when(jdbc.queryForObject("select count(*) from local_users where username = ?", Integer.class, ""))
                .thenReturn(0);
        LocalAdminBootstrap bootstrap = new LocalAdminBootstrap(jdbc, mock(PasswordEncoder.class),
                new LocalSecurityProperties("", "", "local", true), Clock.systemUTC());

        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("本地管理员用户名格式无效");
    }
}
