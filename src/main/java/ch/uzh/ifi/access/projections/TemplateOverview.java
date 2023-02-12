package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.TemplateFile;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDateTime;

@Projection(types = {TemplateFile.class})
public interface TemplateOverview {
    Long getId();

    String getPath();

    String getName();

    String getLanguage();

    boolean isImage();

    @JsonFormat(pattern = "dd-MM-yyyy, HH:mm")
    LocalDateTime getUpdatedAt();

    @Value("#{@environment.getProperty('TEMPLATES_REPO') + target.path}")
    String getLink();
}