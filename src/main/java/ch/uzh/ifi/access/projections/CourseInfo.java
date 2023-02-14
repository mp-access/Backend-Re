package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Course;
import ch.uzh.ifi.access.model.dto.StudentDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDate;
import java.util.List;

@Projection(types = {Course.class})
public interface CourseInfo {

    Long getId();

    String getUrl();

    String getTitle();

    String getUniversity();

    String getSemester();

    LocalDate getStartDate();

    LocalDate getEndDate();

    String getDuration();

    String getDescription();

    @Value("#{@courseService.getMaxPoints(target.url)}")
    Double getMaxPoints();

    @Value("#{@activityRegistry.getOnlineCount(target.url)}")
    Long getOnlineCount();

    Long getStudentsCount();

    @Value("#{@courseService.getTeamMembers(target.supervisors)}")
    List<MemberOverview> getSupervisors();

    @Value("#{@courseService.getTeamMembers(target.assistants)}")
    List<MemberOverview> getAssistants();

    @Value("#{@courseService.getStudents(target.url)}")
    List<StudentDTO> getStudents();

    List<AssignmentInfo> getAssignments();
}
