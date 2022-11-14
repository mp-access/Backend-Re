package ch.uzh.ifi.access.model.projections;

import ch.uzh.ifi.access.model.Course;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDate;
import java.util.List;

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

    @Value("#{@courseService.calculateAvgCoursePoints(target.assignments)}")
    Double getAvgPoints();

    @Value("#{@courseService.getActiveAssignments(target.url)}")
    List<AssignmentWorkspace> getActiveAssignments();

}
