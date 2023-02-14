package ch.uzh.ifi.access.config;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.model.Course;
import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.model.dto.AssignmentDTO;
import ch.uzh.ifi.access.model.dto.CourseDTO;
import ch.uzh.ifi.access.model.dto.SubmissionDTO;
import ch.uzh.ifi.access.model.dto.TaskDTO;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.tika.Tika;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MapperConfigurer {

    @Bean
    public Tika tika() {
        return new Tika();
    }

    @Bean
    public JsonMapper jsonMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule())
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    @Bean
    public ModelMapper modelMapper() {
        ModelMapper modelMapper = new ModelMapper();
        modelMapper.getConfiguration()
                .setPropertyCondition(context -> ObjectUtils.isNotEmpty(context.getSource()))
                .setMatchingStrategy(MatchingStrategies.STRICT)
                .setSkipNullEnabled(true);
        modelMapper.typeMap(CourseDTO.class, Course.class)
                .addMappings(mapping -> mapping.skip(Course::setAssignments));
        modelMapper.typeMap(AssignmentDTO.class, Assignment.class)
                .addMappings(mapping -> mapping.skip(Assignment::setTasks));
        modelMapper.typeMap(TaskDTO.class, Task.class)
                .addMappings(mapping -> mapping.skip(Task::setFiles));
        modelMapper.typeMap(SubmissionDTO.class, Submission.class)
                .addMappings(mapping -> mapping.skip(Submission::setFiles));
        return modelMapper;
    }
}
