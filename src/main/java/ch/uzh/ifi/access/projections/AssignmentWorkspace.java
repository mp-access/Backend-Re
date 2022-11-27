package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Assignment;
import org.springframework.data.rest.core.config.Projection;

import java.util.List;

@Projection(types = {Assignment.class})
public interface AssignmentWorkspace extends AssignmentOverview {

    List<TaskOverview> getTasks();
}
