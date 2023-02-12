package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Course;
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

    String getAvatar();

    LocalDate getStartDate();

    LocalDate getEndDate();

    String getDuration();

    String getDescription();

    @Value("#{@courseService.calculateCoursePoints(target.assignments, null)}")
    Double getPoints();

    @Value("#{@courseService.getMaxPoints(target.url)}")
    Double getMaxPoints();

    @Value("#{@activityRegistry.getOnlineCount(target.url)}")
    Long getOnlineCount();

    Long getStudentsCount();

    @Value("#{@courseService.getTeamMembers(target.supervisors)}")
    List<MemberOverview> getSupervisors();

    @Value("#{@courseService.getTeamMembers(target.assistants)}")
    List<MemberOverview> getAssistants();
}
