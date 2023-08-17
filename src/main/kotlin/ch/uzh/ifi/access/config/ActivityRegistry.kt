package ch.uzh.ifi.access.config

import org.springframework.context.ApplicationListener
import org.springframework.security.core.session.*
import org.springframework.security.oauth2.jwt.Jwt
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

class ActivityRegistry : SessionRegistry, ApplicationListener<AbstractSessionEvent> {
    private val sessions: MutableMap<String, SessionInformation>

    init {
        sessions = ConcurrentHashMap()
    }

    private fun getPrincipalEmail(principal: Any): String {
        return (principal as Jwt).getClaimAsString("email")
    }

    private fun getPrincipalRoles(principal: Any): List<String> {
        return (principal as Jwt).getClaimAsStringList("enrollments")
    }

    override fun onApplicationEvent(event: AbstractSessionEvent) {
        if (event is SessionDestroyedEvent) removeSessionInformation(event.id) else if (event is SessionIdChangedEvent) replaceSession(
            event.oldSessionId,
            event.newSessionId
        )
    }

    override fun getAllPrincipals(): List<Any> {
        return sessions.values.stream().map { obj: SessionInformation -> obj.principal }.toList()
    }

    override fun getAllSessions(principal: Any, includeExpiredSessions: Boolean): List<SessionInformation> {
        val email = getPrincipalEmail(principal)
        return ArrayList(
            sessions.values.stream().filter { info: SessionInformation -> email == getPrincipalEmail(info.principal) }
                .toList())
    }

    override fun getSessionInformation(sessionId: String): SessionInformation? {
        return sessions.getOrDefault(sessionId, null)
    }

    override fun refreshLastRequest(sessionId: String) {
        getSessionInformation(sessionId)?.refreshLastRequest()
    }

    override fun registerNewSession(sessionId: String, principal: Any) {
        getAllSessions(
            principal,
            true
        ).forEach(Consumer { info: SessionInformation -> sessions.remove(info.sessionId) })
        sessions[sessionId] = SessionInformation(principal, sessionId, Date())
    }

    fun replaceSession(newSessionId: String, oldSessionId: String) {
        getSessionInformation(oldSessionId)?.also { info -> registerNewSession(newSessionId, info.principal) }
    }

    override fun removeSessionInformation(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun getOnlineCount(courseSlug: String): Long {
        return allPrincipals.stream().filter { principal: Any -> getPrincipalRoles(principal).contains(courseSlug) }
            .count()
    }
}
