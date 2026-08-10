package io.contentpublisher.platform.web.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableConfigurationProperties({SecurityProperties.class, LocalSecurityProperties.class})
public class SecurityConfiguration {
    @Bean
    @ConditionalOnProperty(name = "publisher.security.mode", havingValue = "DISABLED", matchIfMissing = true)
    SecurityFilterChain disabledSecurityFilterChain(HttpSecurity http) throws Exception {
        return stateless(http).authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
    }

    @Bean
    @ConditionalOnProperty(name = "publisher.security.mode", havingValue = "JWT")
    SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http, SecurityProperties properties) throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(properties.rolesClaim());
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        return stateless(http)
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
                    protectedRequests(authorize);
                })
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, exception) ->
                                writeSecurityError(response, 401, "AUTHENTICATION_REQUIRED", "需要有效的访问令牌")))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) ->
                        writeSecurityError(response, 403, "ACCESS_DENIED", "当前身份没有执行此操作的权限")))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "publisher.security.mode", havingValue = "LOCAL")
    PasswordEncoder localPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @ConditionalOnProperty(name = "publisher.security.mode", havingValue = "LOCAL")
    SecurityFilterChain localLoginSecurityFilterChain(HttpSecurity http) throws Exception {
        LoginUrlAuthenticationEntryPoint loginEntryPoint = new LoginUrlAuthenticationEntryPoint("/login");
        return http
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/login", "/error", "/assets/**", "/actuator/health/**", "/actuator/info")
                            .permitAll();
                    protectedRequests(authorize);
                })
                .formLogin(form -> form.loginPage("/login").loginProcessingUrl("/login")
                        .successHandler((request, response, authentication) -> {
                            if (authentication.getPrincipal() instanceof LocalUserPrincipal principal
                                    && principal.mustChangePassword()) {
                                response.sendRedirect("/change-password");
                            } else {
                                response.sendRedirect("/");
                            }
                        }).failureUrl("/login?error").permitAll())
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true).deleteCookies("JSESSIONID"))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'; "
                                + "object-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                + "script-src 'self'")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.migrateSession())
                        .maximumSessions(1).maxSessionsPreventsLogin(false))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            if (request.getRequestURI().startsWith("/api/")) {
                                writeSecurityError(response, 401, "AUTHENTICATION_REQUIRED", "请先登录");
                            } else {
                                loginEntryPoint.commence(request, response, exception);
                            }
                        })
                        .accessDeniedHandler((request, response, exception) ->
                                handleAccessDenied(request, response)))
                .httpBasic(basic -> basic.disable())
                .addFilterAfter(new PasswordChangeRequiredFilter(), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
    }

    private void protectedRequests(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize) {
        authorize.requestMatchers("/settings/**", "/automation", "/automation/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/actions", "/calendar")
                    .hasAnyRole("VIEWER", "EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/actions/notifications/**")
                    .hasAnyRole("VIEWER", "EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/recycle-bin").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/channels", "/channels/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/articles/*/delete", "/jobs/*/delete").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/articles/*/restore", "/jobs/*/restore").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/articles/*", "/api/v1/jobs/*").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/articles/*/restore", "/api/v1/jobs/*/restore")
                    .hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/channels", "/publishing/**")
                    .hasAnyRole("VIEWER", "EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/articles/*/publications",
                        "/articles/*/publication-batches", "/articles/*/manual/**",
                        "/jobs/*/publication-retry", "/jobs/*/replay", "/jobs/*/cancel")
                    .hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/articles/*/manual/**")
                    .hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/articles/*/approve", "/articles/*/reject")
                    .hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/projects/**", "/articles/*/edit", "/articles/*/versions/**")
                    .hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/articles/topic-generations")
                    .hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/articles/website-generations")
                    .hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/projects/**", "/content", "/jobs/**", "/articles/**")
                    .hasAnyRole("VIEWER", "EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/channel-accounts/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/automation/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/channel-accounts/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/channel-accounts/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/articles/*/approve", "/api/v1/articles/*/reject")
                    .hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/articles/**", "/api/v1/publications/**")
                    .hasAnyRole("VIEWER", "EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/generation-presets", "/api/v1/notifications")
                    .hasAnyRole("VIEWER", "EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/notifications/*/acknowledge")
                    .hasAnyRole("VIEWER", "EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/monitoring/**")
                    .hasAnyRole("VIEWER", "EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/channel-accounts/**").hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/articles/*/publications",
                        "/api/v1/articles/*/publication-batches", "/api/v1/jobs/*/publication-retry",
                        "/api/v1/jobs/*/cancel", "/api/v1/job-replays/**")
                    .hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/articles/topic-generations")
                    .hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/articles/website-generations")
                    .hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/articles/*").hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/articles/*/draft",
                        "/api/v1/articles/*/manual/*/progress").hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/articles/*/draft").hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/markdown/preview").hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/projects/**").hasAnyRole("VIEWER", "EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/jobs/**").hasAnyRole("VIEWER", "EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/projects/**").hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .anyRequest().authenticated();
    }

    private static void writeSecurityError(HttpServletResponse response, int status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code
                + "\",\"message\":\"" + message + "\"}");
    }

    private static void handleAccessDenied(jakarta.servlet.http.HttpServletRequest request,
                                           HttpServletResponse response) throws java.io.IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            writeSecurityError(response, 403, "ACCESS_DENIED", "当前账号没有执行此操作的权限");
        } else {
            response.sendRedirect("/access-denied");
        }
    }
}
