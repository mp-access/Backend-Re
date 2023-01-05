package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.TemplateFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

@Projection(types = {TemplateFile.class})
public interface TemplateOverview {
    Long getId();

    String getPath();

    String getName();

    String getLanguage();

    boolean isImage();

    @Value("#{@environment.getProperty('TEMPLATES_REPO') + target.path}")
    String getLink();
}