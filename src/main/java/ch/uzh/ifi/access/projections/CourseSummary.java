package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Course;
import ch.uzh.ifi.access.model.CourseInformation;
import ch.uzh.ifi.access.model.dto.StudentDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Projection(types = {Course.class})
public interface CourseSummary {
    String getSlug();

    LocalDate getStartDate();

    LocalDate getEndDate();

    Map<String,CourseInformation> getInformation();

    @Value("#{@courseService.getMaxPoints(target.slug)}")
    Double getMaxPoints();

    Long getStudentsCount();

    @Value("#{@courseService.getTeamMembers(target.supervisors)}")
    List<MemberOverview> getSupervisors();

    @Value("#{@courseService.getTeamMembers(target.assistants)}")
    List<MemberOverview> getAssistants();

    @Value("#{@courseService.getStudents(target.slug)}")
    List<StudentDTO> getStudents();

    List<AssignmentSummary> getAssignments();
}