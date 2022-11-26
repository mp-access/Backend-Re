package ch.uzh.ifi.access.model.projections;

import ch.uzh.ifi.access.model.Course;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDate;

@Projection(types = {Course.class})
public interface CourseFeature {

    Long getId();

    String getUrl();

    String getTitle();

    String getUniversity();

    String getSemester();

    String getDescription();

    String getRestricted();

    @JsonFormat(pattern = "dd-MM-yyyy")
    LocalDate getStartDate();

    @JsonFormat(pattern = "dd-MM-yyyy")
    LocalDate getEndDate();
}
