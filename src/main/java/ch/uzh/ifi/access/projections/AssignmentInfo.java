package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.model.dao.Timer;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDateTime;
import java.util.List;

@Projection(types = {Assignment.class})
public interface AssignmentInfo {

    Long getId();

    String getUrl();

    String getTitle();

    Integer getOrdinalNum();

    String getDescription();

    LocalDateTime getStartDate();

    LocalDateTime getEndDate();

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Value("#{target.startDate}")
    LocalDateTime getPublishedDate();

    @JsonFormat(pattern = "HH:mm")
    @Value("#{target.startDate}")
    LocalDateTime getPublishedTime();

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Value("#{target.endDate}")
    LocalDateTime getDueDate();

    @JsonFormat(pattern = "HH:mm")
    @Value("#{target.endDate}")
    LocalDateTime getDueTime();

    String getDuration();

    List<Timer> getCountDown();

    boolean isPublished();

    boolean isPastDue();

    boolean isActive();

    Double getMaxPoints();

    List<TaskInfo> getTasks();
}
