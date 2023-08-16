package ch.uzh.ifi.access.config;

import org.springframework.context.ApplicationListener;
import org.springframework.security.core.session.*;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ActivityRegistry implements SessionRegistry, ApplicationListener<AbstractSessionEvent> {

    private final Map<String, SessionInformation> sessions;

    public ActivityRegistry() {
        this.sessions = new ConcurrentHashMap<>();
    }

    private String getPrincipalEmail(Object principal) {
        return ((Jwt) principal).getClaimAsString("email");
    }

    private List<String> getPrincipalRoles(Object principal) {
        return ((Jwt) principal).getClaimAsStringList("enrollments");
    }

    @Override
    public void onApplicationEvent(AbstractSessionEvent event) {
        if (event instanceof SessionDestroyedEvent destroyed)
            removeSessionInformation(destroyed.getId());
        else if (event instanceof SessionIdChangedEvent changed)
            replaceSession(changed.getOldSessionId(), changed.getNewSessionId());
    }

    @Override
    public List<Object> getAllPrincipals() {
        return sessions.values().stream().map(SessionInformation::getPrincipal).toList();
    }

    @Override
    public List<SessionInformation> getAllSessions(Object principal, boolean includeExpiredSessions) {
        String email = getPrincipalEmail(principal);
        return new ArrayList<>(sessions.values().stream().filter(info ->
                email.equals(getPrincipalEmail(info.getPrincipal()))).toList());
    }

    @Override
    public SessionInformation getSessionInformation(String sessionId) {
        return sessions.getOrDefault(sessionId, null);
    }

    @Override
    public void refreshLastRequest(String sessionId) {
        Optional.ofNullable(getSessionInformation(sessionId)).ifPresent(SessionInformation::refreshLastRequest);
    }

    @Override
    public void registerNewSession(String sessionId, Object principal) {
        getAllSessions(principal, true).forEach(info -> sessions.remove(info.getSessionId()));
        sessions.put(sessionId, new SessionInformation(principal, sessionId, new Date()));
    }

    public void replaceSession(String newSessionId, String oldSessionId) {
        Optional.ofNullable(getSessionInformation(oldSessionId)).ifPresent(info ->
                registerNewSession(newSessionId, info.getPrincipal()));
    }

    @Override
    public void removeSessionInformation(String sessionId) {
        sessions.remove(sessionId);
    }

    public Long getOnlineCount(String courseSlug) {
        return getAllPrincipals().stream().filter(principal -> getPrincipalRoles(principal).contains(courseSlug)).count();
    }
}
