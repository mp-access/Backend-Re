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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.mapping.SimpleAuthorityMapper;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.data.repository.query.SecurityEvaluationContextExtension;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

import javax.ws.rs.NotFoundException;
import java.util.List;
import java.util.Map;

@Slf4j
@AllArgsConstructor
@KeycloakConfiguration
@EnableGlobalMethodSecurity(prePostEnabled = true)
@EnableConfigurationProperties(KeycloakSpringBootProperties.class)
@ComponentScan(basePackageClasses = KeycloakSpringBootConfigResolver.class)
public class SecurityConfigurer extends KeycloakWebSecurityConfigurerAdapter {

    private AccessProperties accessProperties;

    private KeycloakSpringBootProperties keycloakProperties;

    /**
     * Register Keycloak with the authentication manager and set up a mapping from Keycloak role names to
     * Spring Security's default role naming scheme (with the prefix "ROLE_"). This allows referring to
     * role names exactly as they appear in Keycloak, without having to add the "ROLE_" prefix.
     * @see "<a href="https://keycloak.org/docs/latest/securing_apps/index.html#naming-security-roles">Docs</a>"
     */
    @Autowired
    public void configureGlobal(AuthenticationManagerBuilder auth) {
        KeycloakAuthenticationProvider keycloakAuthenticationProvider = keycloakAuthenticationProvider();
        keycloakAuthenticationProvider.setGrantedAuthoritiesMapper(new SimpleAuthorityMapper());
        auth.authenticationProvider(keycloakAuthenticationProvider);
    }

    @Bean
    @Override
    protected SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new RegisterSessionAuthenticationStrategy(new SessionRegistryImpl());
    }

    /**
     * Use the Spring properties defined in application.properties instead of searching for a "keycloak.json" file.
     * @see <a href="https://keycloak.org/docs/latest/securing_apps/index.html#using-spring-boot-configuration">Docs</a>
     */
    @Bean
    public KeycloakConfigResolver keycloakConfigResolver() {
        return new KeycloakSpringBootConfigResolver();
    }

    @Override
    public void configure(final HttpSecurity http) throws Exception {
        super.configure(http);
        http.csrf()
                .disable()
                .authorizeRequests()
                .antMatchers("/courses/new", "/info", "/v3/api-docs/**", "/swagger-ui/**")
                .permitAll()
                .antMatchers("/**")
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
        Keycloak keycloakClient = Keycloak.getInstance(
                keycloakProperties.getAuthServerUrl(),
                "master",
                accessProperties.getAdminCLIUsername(),
                accessProperties.getAdminCLIPassword(),
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
            IdentityProviderRepresentation switchAAI = new IdentityProviderRepresentation();
            switchAAI.setEnabled(true);
            switchAAI.setAlias("switch-aai");
            switchAAI.setDisplayName("SWITCH-AAI");
            switchAAI.setProviderId("saml");
            switchAAI.setConfig(Map.of(
                    "entityId", "access",
                    "singleSignOnServiceUrl", "https://aai-idp.uzh.ch/idp/profile/SAML2/Redirect/SSO"));
            newRealm.addIdentityProvider(switchAAI);
            keycloakClient.realms().create(newRealm);
        }

        return keycloakClient.realm(keycloakProperties.getRealm());
    }
}