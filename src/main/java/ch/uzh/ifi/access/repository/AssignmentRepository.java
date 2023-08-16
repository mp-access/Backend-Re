package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.projections.AssignmentWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PostFilter;

import java.util.List;
import java.util.Optional;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    @PostFilter("hasRole(#courseSlug + '-assistant') or (hasRole(#courseSlug) and filterObject.published)")
    List<AssignmentWorkspace> findByCourse_SlugOrderByOrdinalNumDesc(String courseSlug);

    @PostAuthorize("hasRole(#courseSlug + '-assistant') or (hasRole(#courseSlug) and returnObject.present and returnObject.get().published)")
    Optional<AssignmentWorkspace> findByCourse_SlugAndSlug(String courseSlug, String assignmentSlug);

    Optional<Assignment> getByCourse_SlugAndSlug(String courseSlug, String assignmentSlug);

    boolean existsByCourse_SlugAndSlug(String courseSlug, String assignmentSlug);

}