package ch.uzh.ifi.access.config;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authorization.AuthorityAuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.data.repository.query.SecurityEvaluationContextExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@AllArgsConstructor
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfigurer {

    private Environment env;

    private Collection<GrantedAuthority> parseAuthorities(List<String> authorities) {
        return CollectionUtils.collect(authorities, SimpleGrantedAuthority::new);
    }

    private Collection<GrantedAuthority> parseAuthorities(Jwt token) {
        return CollectionUtils.collect(token.getClaimAsStringList("enrollments"), SimpleGrantedAuthority::new);
    }

    private boolean isAuthorizedAPIKey(RequestAuthorizationContext context) {
        String apiKey = env.getProperty("API_KEY");
        if (apiKey == null) return false;
        return apiKey.equals(context.getRequest().getHeader("X-API-Key").lines().collect(Collectors.joining()));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.sessionManagement().maximumSessions(1).sessionRegistry(activityRegistry());
        http.csrf().disable();
        http.authorizeHttpRequests()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/courses/contact/**").permitAll()
                .requestMatchers("/courses/{course}/participants/**", "/courses/{course}/summary").access((authentication, context) ->
                        new AuthorityAuthorizationDecision(isAuthorizedAPIKey(context), parseAuthorities(List.of("supervisor"))))
                .anyRequest().authenticated().and()
                .oauth2ResourceServer().jwt().jwtAuthenticationConverter(source ->
                        new JwtAuthenticationToken(source, parseAuthorities(source), source.getClaimAsString("email")));
        return http.build();
    }

    @Bean
    GrantedAuthorityDefaults grantedAuthorityDefaults() {
        return new GrantedAuthorityDefaults("");
    }

    @Bean
    public ActivityRegistry activityRegistry() {
        return new ActivityRegistry();
    }

    @Bean
    public SecurityEvaluationContextExtension securityEvaluationContextExtension() {
        return new SecurityEvaluationContextExtension();
    }

    @Bean
    public ServletListenerRegistrationBean<HttpSessionEventPublisher> httpSessionEventPublisher() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    @Bean
    public CommonsRequestLoggingFilter logFilter() {
        CommonsRequestLoggingFilter filter = new CommonsRequestLoggingFilter();
        filter.setIncludeQueryString(true);
        filter.setIncludePayload(true);
        filter.setMaxPayloadLength(400);
        filter.setIncludeHeaders(false);
        return filter;
    }

    @Bean
    public Path workingDir() {
        return Path.of(env.getProperty("WORKING_DIR", "/workspace/data"));
    }

    @Bean
    public RealmResource accessRealm() {
        Keycloak keycloakClient = Keycloak.getInstance(
                env.getProperty("AUTH_SERVER_URL", "http://localhost:8080"), "master",
                env.getProperty("KEYCLOAK_ADMIN", "admin"),
                env.getProperty("KEYCLOAK_ADMIN_PASSWORD", "admin"),
                "admin-cli");
        return keycloakClient.realm("access");
    }
}