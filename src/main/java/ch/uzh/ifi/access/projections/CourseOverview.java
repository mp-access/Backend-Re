package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Course;
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

    String getAvatar();

    LocalDate getStartDate();

    LocalDate getEndDate();

    String getDuration();

    @Value("#{@courseService.calculateCoursePoints(target.assignments, null)}")
    Double getPoints();

    @Value("#{@courseService.getMaxPoints(target.url)}")
    Double getMaxPoints();

    @Value("#{@activityRegistry.getOnlineCount(target.url)}")
    Long getOnlineCount();

    Long getStudentsCount();
}
