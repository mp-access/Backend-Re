package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Course;
import ch.uzh.ifi.access.projections.CourseOverview;
import ch.uzh.ifi.access.projections.CourseWorkspace;
import ch.uzh.ifi.access.projections.MemberOverview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.access.prepost.PostFilter;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends JpaRepository<Course, Long> {

    boolean existsByUrl(String courseURL);

    Optional<Course> getByUrl(String courseURL);

    Optional<CourseWorkspace> findByUrl(String courseURL);

    @PostFilter("hasRole(filterObject.url)")
    List<CourseOverview> findCoursesBy();

    @Query(nativeQuery = true, value = "SELECT a.value AS name, :email AS email FROM user_attribute a " +
            "WHERE a.name='displayName' AND a.user_id=(SELECT e.id FROM user_entity e WHERE e.email=:email)")
    MemberOverview getTeamMemberName(String email);

}