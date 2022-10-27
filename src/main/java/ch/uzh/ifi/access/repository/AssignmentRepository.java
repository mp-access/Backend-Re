package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.model.projections.AssignmentOverview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PostFilter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    @PostFilter("hasRole(#courseURL + '-assistant') or (hasRole(#courseURL) and filterObject.published)")
    List<AssignmentOverview> findByCourse_UrlOrderByOrdinalNumDesc(String courseURL);

    @PostAuthorize("hasRole(#courseURL + '-assistant') or (returnObject.present and returnObject.get().published)")
    Optional<AssignmentOverview> findByCourse_UrlAndUrl(String courseURL, String assignmentURL);

    Integer countByCourse_UrlAndStartDateBeforeAndEndDateAfter(String courseURL, LocalDateTime start, LocalDateTime end);

    Optional<Assignment> findByCourse_UrlAndOrdinalNum(String courseURL, Integer ordinalNum);

}
