package ch.uzh.ifi.access.model.projections;

import ch.uzh.ifi.access.model.Course;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDate;

@Projection(types = {Course.class})
public interface CourseOverview {

    Long getId();

    String getUrl();

    String getTitle();

    String getUniversity();

    String getSemester();

    @JsonFormat(pattern = "dd-MM-yyyy")
    LocalDate getStartDate();

    @JsonFormat(pattern = "dd-MM-yyyy")
    LocalDate getEndDate();

    @Value("#{@courseService.countActiveAssignments(target.url)}")
    Integer getActiveAssignmentsCount();

    void setUserId(String userId);

    @Value("#{@courseService.calculateCoursePoints(target.assignments, target.userId)}")
    Double getPoints();

}
