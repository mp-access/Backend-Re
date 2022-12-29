package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.TaskFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

@Projection(types = {TaskFile.class})
public interface TaskFileOverview {
    Long getId();

    String getPath();

    @Value("#{target.template.name}")
    String getName();

    @Value("#{target.template.language}")
    String getLanguage();

    @Value("#{target.template.content}")
    String getTemplate();

    @Value("#{target.editable ? @courseService.getLatestContent(target.id, target.task.userId) : null}")
    String getLatest();

    boolean isEditable();

    @Value("#{target.template.image}")
    boolean isImage();

    boolean isReleased();

    @Value("#{target.task.assignment.course.url}")
    String getCourseURL();
}