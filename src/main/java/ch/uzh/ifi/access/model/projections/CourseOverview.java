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

    Double getMaxPoints();

    @Value("#{@courseService.calculateCoursePoints(target.assignments, null)}")
    Double getPoints();

    @Value("#{@courseService.getActiveAssignment(target.url)}")
    AssignmentWorkspace getActiveAssignment();

}
