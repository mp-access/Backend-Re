package ch.uzh.ifi.access.config;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.model.Course;
import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.model.dto.AssignmentDTO;
import ch.uzh.ifi.access.model.dto.CourseDTO;
import ch.uzh.ifi.access.model.dto.SubmissionDTO;
import ch.uzh.ifi.access.model.dto.TaskDTO;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class MapperConfigurer {

    @Bean
    public JavaTimeModule javaTimeModule() {
        return new JavaTimeModule();
    }

    @Bean
    public ModelMapper modelMapper() {
        ModelMapper modelMapper = new ModelMapper();
        modelMapper.getConfiguration()
                .setPropertyCondition(context -> ObjectUtils.isNotEmpty(context.getSource()))
                .setMatchingStrategy(MatchingStrategies.STRICT)
                .setSkipNullEnabled(true);
        modelMapper.typeMap(CourseDTO.class, Course.class)
                .addMappings(mapping -> mapping.skip(CourseDTO::getAssignments, Course::setAssignments));
        modelMapper.typeMap(AssignmentDTO.class, Assignment.class)
                .addMappings(mapping -> mapping.skip(AssignmentDTO::getTasks, Assignment::setTasks));
        modelMapper.typeMap(TaskDTO.class, Task.class)
                .addMappings(mapping -> mapping.skip(TaskDTO::getFiles, Task::setFiles));
        modelMapper.typeMap(SubmissionDTO.class, Submission.class)
                .addMappings(mapping -> mapping.skip(Submission::setTask))
                .addMappings(mapping -> mapping.skip(Submission::setFiles));
        return modelMapper;
    }
}
