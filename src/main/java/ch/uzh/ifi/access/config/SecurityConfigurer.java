package ch.uzh.ifi.access.config;

import ch.uzh.ifi.access.model.constants.Role;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.adapters.KeycloakConfigResolver;
import org.keycloak.adapters.springboot.KeycloakSpringBootConfigResolver;
import org.keycloak.adapters.springboot.KeycloakSpringBootProperties;
import org.keycloak.adapters.springsecurity.KeycloakConfiguration;
import org.keycloak.adapters.springsecurity.authentication.KeycloakAuthenticationProvider;
import org.keycloak.adapters.springsecurity.config.KeycloakWebSecurityConfigurerAdapter;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.mapping.SimpleAuthorityMapper;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.data.repository.query.SecurityEvaluationContextExtension;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

import javax.ws.rs.NotFoundException;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

@Slf4j
@AllArgsConstructor
@KeycloakConfiguration
@EnableGlobalMethodSecurity(prePostEnabled = true)
@EnableConfigurationProperties(KeycloakSpringBootProperties.class)
@ComponentScan(basePackageClasses = KeycloakSpringBootConfigResolver.class)
public class SecurityConfigurer extends KeycloakWebSecurityConfigurerAdapter {

    private Environment environment;
    private KeycloakSpringBootProperties keycloakProperties;

    @Autowired
    public void configureGlobal(AuthenticationManagerBuilder auth) {
        KeycloakAuthenticationProvider keycloakAuthenticationProvider = keycloakAuthenticationProvider();
        keycloakAuthenticationProvider.setGrantedAuthoritiesMapper(new SimpleAuthorityMapper());
        auth.authenticationProvider(keycloakAuthenticationProvider);
    }

    @Bean
    public ServletListenerRegistrationBean<HttpSessionEventPublisher> httpSessionEventPublisher() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    @Bean
    @Override
    protected SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new RegisterSessionAuthenticationStrategy(buildSessionRegistry());
    }

    @Bean
    protected SessionRegistry buildSessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public KeycloakConfigResolver keycloakConfigResolver() {
        return new KeycloakSpringBootConfigResolver();
    }

    @Override
    public void configure(final HttpSecurity http) throws Exception {
        super.configure(http);
        http.csrf().disable()
                .authorizeRequests()
                .antMatchers("/v3/api-docs/**", "/swagger-ui/**")
                .permitAll()
                .anyRequest()
                .authenticated();
    }

    @Bean
    public SecurityEvaluationContextExtension securityEvaluationContextExtension() {
        return new SecurityEvaluationContextExtension();
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
    public RealmResource keycloakRealm() {
        String realmName = keycloakProperties.getRealm();
        log.info("Initialising Keycloak realm '{}' at URL {}", realmName, keycloakProperties.getAuthServerUrl());
        Keycloak keycloakClient = Keycloak.getInstance(keycloakProperties.getAuthServerUrl(), "master",
                environment.getProperty("KEYCLOAK_ADMIN", "admin"),
                environment.getProperty("KEYCLOAK_ADMIN_PASSWORD", "admin"),
                "admin-cli");

        try {
            keycloakClient.realm(realmName).toRepresentation();
        } catch (NotFoundException exception) {
            log.info("Creating a new realm with the name '{}'...", realmName);
            RolesRepresentation basicUserRoles = new RolesRepresentation();
            basicUserRoles.setRealm(List.of(
                    new RoleRepresentation(Role.STUDENT.getName(), "Basic student role", false),
                    new RoleRepresentation(Role.ASSISTANT.getName(), "Basic assistant role", false),
                    new RoleRepresentation(Role.SUPERVISOR.getName(), "Basic supervisor role", false)));
            ClientRepresentation backendClient = new ClientRepresentation();
            backendClient.setId(realmName + "-backend");
            backendClient.setEnabled(true);
            backendClient.setBearerOnly(true);
            ClientRepresentation frontendClient = new ClientRepresentation();
            frontendClient.setId(realmName + "-frontend");
            frontendClient.setEnabled(true);
            frontendClient.setPublicClient(true);
            frontendClient.setRedirectUris(List.of("*"));
            frontendClient.setWebOrigins(List.of("*"));
            RealmRepresentation newRealm = new RealmRepresentation();
            newRealm.setRealm(realmName);
            newRealm.setEnabled(true);
            newRealm.setRegistrationEmailAsUsername(true);
            newRealm.setRoles(basicUserRoles);
            newRealm.setClients(List.of(backendClient, frontendClient));
            newRealm.setLoginTheme("access");
            newRealm.setDisplayNameHtml("ACCESS");
            newRealm.setAccessCodeLifespan(7200);
            newRealm.setAccessTokenLifespanForImplicitFlow(7200);
            newRealm.setAccessCodeLifespan(300);
            newRealm.setSmtpServer(Map.of(
                    "host", "idsmtp.uzh.ch",
                    "from", "noreply@uzh.ch",
                    "fromDisplayName", "ACCESS"));
            IdentityProviderRepresentation switchEduId = new IdentityProviderRepresentation();
            switchEduId.setEnabled(true);
            switchEduId.setAlias("switch-edu-id");
            switchEduId.setDisplayName("SWITCH edu-ID");
            switchEduId.setProviderId("oidc");
            switchEduId.setTrustEmail(true);
            switchEduId.setConfig(Map.ofEntries(
                    entry("clientId", "uzh_info1_staging"),
                    entry("clientSecret", environment.getProperty("CLIENT_SECRET", "")),
                    entry("clientAuthMethod", "client_secret_basic"),
                    entry("defaultScope", "openid,profile,email"),
                    entry("validateSignature", "true"),
                    entry("useJwksUrl", "true"),
                    entry("userInfoUrl", "https://login.eduid.ch/idp/profile/oidc/userinfo"),
                    entry("tokenUrl", "https://login.eduid.ch/idp/profile/oidc/token"),
                    entry("authorizationUrl", "https://login.eduid.ch/idp/profile/oidc/authorize"),
                    entry("jwksUrl", "https://login.eduid.ch/idp/profile/oidc/keyset"),
                    entry("issuer", "https://login.eduid.ch/")));
            newRealm.addIdentityProvider(switchEduId);
            keycloakClient.realms().create(newRealm);
        }

        return keycloakClient.realm(keycloakProperties.getRealm());
    }
}