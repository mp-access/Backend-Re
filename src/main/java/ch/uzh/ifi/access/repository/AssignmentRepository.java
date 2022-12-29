package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.projections.AssignmentWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PostFilter;

import java.util.List;
import java.util.Optional;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    @PostFilter("hasRole(#courseURL + '-assistant') or (hasRole(#courseURL) and filterObject.published)")
    List<AssignmentWorkspace> findByCourse_UrlOrderByOrdinalNumDesc(String courseURL);

    @PostAuthorize("hasRole(#courseURL + '-assistant') or (hasRole(#courseURL) and returnObject.present and returnObject.get().published)")
    Optional<AssignmentWorkspace> findByCourse_UrlAndUrl(String courseURL, String assignmentURL);

    Optional<Assignment> getByCourse_UrlAndUrl(String courseURL, String assignmentURL);

    boolean existsByCourse_UrlAndUrl(String courseURL, String assignmentURL);

}
