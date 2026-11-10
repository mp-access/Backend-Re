package ch.uzh.ifi.access.config

import ch.uzh.ifi.access.model.constants.KeycloakAttributes
import ch.uzh.ifi.access.service.CourseApiKeyService
import ch.uzh.ifi.access.service.RoleAssignmentService
import io.github.oshai.kotlinlogging.KotlinLogging
import lombok.AllArgsConstructor
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.RealmResource
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.core.env.Environment
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.authorization.AuthorityAuthorizationDecision
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.core.GrantedAuthorityDefaults
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.data.repository.query.SecurityEvaluationContextExtension
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.stereotype.Component
import org.springframework.web.filter.CommonsRequestLoggingFilter
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


@AllArgsConstructor
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(private val env: Environment) {

    private val logger = KotlinLogging.logger {}

    private fun parseAuthorities(authorities: List<String>): Collection<GrantedAuthority> {
        return authorities.map { role: String? -> SimpleGrantedAuthority(role) }
    }


    private fun parseAuthorities(token: Jwt): Collection<GrantedAuthority> {
        return token.getClaimAsStringList("enrollments").map { role -> SimpleGrantedAuthority(role) }
    }

    /**
     * The X-API-Key is bound to a single course: it is only accepted for the course
     * given by the {course} path variable of the matched route.
     */
    private fun isAuthorizedCourseAPIKey(
        context: RequestAuthorizationContext,
        courseApiKeys: CourseApiKeyService,
    ): Boolean {
        val course = context.variables["course"]
        val headerKey = context.request.getHeader("X-API-Key")?.lines()?.joinToString("")
        if (headerKey == null) {
            logger.debug { "${context.request.requestURI}: Missing X-API-Key" }
            return false
        }
        val valid = courseApiKeys.isValid(course, headerKey)
        if (!valid) {
            logger.debug { "${context.request.requestURI}: X-API-Key is not valid for course $course" }
        }
        return valid
    }


    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        @Lazy courseApiKeys: CourseApiKeyService,
    ): SecurityFilterChain {
        http.sessionManagement {
            it.sessionConcurrency {
                it.maximumSessions(1)
            }
        }
            .csrf {
                it.ignoringRequestMatchers(
                    "/courses/{course}/participants/**",
                    "/courses/{course}/assistants/**",
                    "/courses/{course}/supervisors/**",
                    "/courses/{course}/delete",
                    "/webhooks/**"
                )
            }
            .authorizeHttpRequests { authorize ->
                authorize
                    .requestMatchers(
                        "/error",
                        "/webhooks/**"
                    ).permitAll()
                    .requestMatchers(
                        "/courses/{course}/participants/**",
                        "/courses/{course}/assistants/**",
                        "/courses/{course}/supervisors/**",
                        "/courses/{course}/summary",
                        "/courses/{course}/delete"
                    ).access { _, context ->
                        AuthorityAuthorizationDecision(
                            isAuthorizedCourseAPIKey(context, courseApiKeys),
                            parseAuthorities(listOf("supervisor"))
                        )
                    }

                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer {
                it.jwt {
                    it.jwtAuthenticationConverter { source: Jwt ->
                        JwtAuthenticationToken(
                            source,
                            parseAuthorities(source),
                            source.getClaimAsString("email")
                        )
                    }
                }
            }
        return http.build()
    }

    @Bean
    fun grantedAuthorityDefaults(): GrantedAuthorityDefaults {
        return GrantedAuthorityDefaults("")
    }

    @Bean
    fun securityEvaluationContextExtension(): SecurityEvaluationContextExtension {
        return SecurityEvaluationContextExtension()
    }

    @Bean
    fun logFilter(): CommonsRequestLoggingFilter {
        val filter = CommonsRequestLoggingFilter()
        filter.setIncludeQueryString(true)
        filter.setIncludePayload(true)
        filter.setMaxPayloadLength(400)
        filter.setIncludeHeaders(false)
        return filter
    }

    @Bean
    fun workingDir(): Path {
        return Path.of(env.getProperty("WORKING_DIR", "/workspace/data"))
    }

    @Bean
    fun accessRealm(): RealmResource {
        val keycloakClient = Keycloak.getInstance(
            env.getProperty("AUTH_SERVER_URL", "http://0.0.0.0:8080"), "master",
            env.getProperty("KEYCLOAK_ADMIN", "admin"),
            env.getProperty("KEYCLOAK_ADMIN_PASSWORD", "admin"),
            "admin-cli"
        )
        return keycloakClient.realm("access")
    }

    @Component
    class AuthenticationSuccessListener(
        val roleAssignmentService: RoleAssignmentService,
    ) : ApplicationListener<AuthenticationSuccessEvent> {
        private val logger = KotlinLogging.logger {}
        private val initializedAt = ConcurrentHashMap<String, Long>()
        private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override fun onApplicationEvent(event: AuthenticationSuccessEvent) {
            // This ensures the necessary roles are added to the Keycloak account when the user first logs in
            // because at that point, the roles_initialized_at attribute will yet be missing
            val token = (event.authentication as? JwtAuthenticationToken)?.token ?: return
            if (token.getClaimAsString(KeycloakAttributes.ROLES_INITIALIZED_AT) != null) return
            val username = event.authentication.name
            val previous = initializedAt[username]
            if (previous != null) {
                val minutesAgo = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - previous)
                if (minutesAgo >= 10 && warned.add(username)) {
                    logger.warn {
                        "Token of $username still has no roles_initialized_at claim $minutesAgo minutes after " +
                        "initialization: check the roles_initialized_at protocol mapper of client access-client"
                    }
                }
                return
            }
            roleAssignmentService.initializeUserRoles(username)
            initializedAt[username] = System.currentTimeMillis()
        }

    }
}
