package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.TaskFile;
import ch.uzh.ifi.access.model.constants.Context;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

@Projection(types = {TaskFile.class})
public interface TaskFileInfo {
    Long getId();

    Context getContext();

    boolean isEditable();

    @Value("#{target.template.id}")
    Long getTemplateId();

    @Value("#{target.template.path}")
    String getTemplatePath();
}