package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Course;
import ch.uzh.ifi.access.model.projections.CourseOverview;
import ch.uzh.ifi.access.model.projections.CourseWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PostFilter;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends JpaRepository<Course, Long> {

    Optional<Course> getByUrl(String courseURL);

    Optional<CourseWorkspace> findByUrl(String courseURL);

    @PostFilter("hasRole(filterObject.url)")
    List<CourseOverview> findCoursesBy();

}