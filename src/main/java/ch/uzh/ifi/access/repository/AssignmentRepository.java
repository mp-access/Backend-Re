package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.model.projections.AssignmentOverview;
import ch.uzh.ifi.access.model.projections.AssignmentWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PostFilter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    @PostFilter("hasRole(#courseURL + '-assistant') or (hasRole(#courseURL) and filterObject.published)")
    List<AssignmentOverview> findByCourse_UrlAndEnabledTrueOrderByOrdinalNumDesc(String courseURL);

    @PostFilter("hasRole(#courseURL + '-assistant') or (hasRole(#courseURL) and filterObject.published)")
    List<AssignmentOverview> findByCourse_UrlAndEnabledTrueAndEndDateBeforeOrderByEndDateAsc(String courseURL, LocalDateTime end);

    @PostFilter("hasRole(#courseURL)")
    List<AssignmentWorkspace> findByCourse_UrlAndEnabledTrueAndStartDateBeforeAndEndDateAfterOrderByEndDateAsc(String courseURL, LocalDateTime start, LocalDateTime end);

    @PostAuthorize("hasRole(#courseURL + '-assistant') or (hasRole(#courseURL) and returnObject.present and returnObject.get().published)")
    Optional<AssignmentWorkspace> findByCourse_UrlAndUrl(String courseURL, String assignmentURL);

    Optional<Assignment> findByCourse_UrlAndOrdinalNum(String courseURL, Integer ordinalNum);

}
