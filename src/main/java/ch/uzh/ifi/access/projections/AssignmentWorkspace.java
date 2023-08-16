package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.model.AssignmentInformation;
import ch.uzh.ifi.access.model.dao.Timer;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Projection(types = {Assignment.class})
public interface AssignmentWorkspace {

    Long getId();

    String getSlug();

    Integer getOrdinalNum();

    Map<String, AssignmentInformation> getInformation();

    LocalDateTime getStart();

    LocalDateTime getEnd();

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Value("#{target.start}")
    LocalDateTime getPublishedDate();

    @JsonFormat(pattern = "HH:mm")
    @Value("#{target.start}")
    LocalDateTime getPublishedTime();

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Value("#{target.end}")
    LocalDateTime getDueDate();

    @JsonFormat(pattern = "HH:mm")
    @Value("#{target.end}")
    LocalDateTime getDueTime();

    List<Timer> getCountDown();

    boolean isPublished();

    boolean isPastDue();

    boolean isActive();

    Double getMaxPoints();

    @Value("#{@courseService.calculateAssignmentPoints(target.tasks, null)}")
    Double getPoints();

    List<TaskOverview> getTasks();
}