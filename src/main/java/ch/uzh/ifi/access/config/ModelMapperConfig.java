package ch.uzh.ifi.access.config;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.model.Course;
import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.model.constants.Visibility;
import ch.uzh.ifi.access.model.dto.*;
import org.apache.commons.lang3.ObjectUtils;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Objects;

@Configuration
public class ModelMapperConfig {
    // TODO: convert to Kotlin. This is very tricky, because ModelMapper is very Java-specific. Maybe replace it.
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
                .addMappings(mapper -> {
                    mapper.skip(Task::setLlmSubmission);
                    mapper.skip(Task::setLlmSolution);
                    mapper.skip(Task::setLlmRubrics);
                    mapper.skip(Task::setLlmCot);
                    mapper.skip(Task::setLlmVoting);
                    mapper.skip(Task::setLlmExamples);
                    mapper.skip(Task::setLlmPrompt);
                    mapper.skip(Task::setLlmPre);
                    mapper.skip(Task::setLlmPost);
                    mapper.skip(Task::setLlmModel);
                    mapper.skip(Task::setLlmTemperature);
                    mapper.skip(Task::setLlmMaxPoints);
                });
        modelMapper.typeMap(SubmissionDTO.class, Submission.class)
                .addMappings(mapping -> mapping.skip(Submission::setFiles));
        modelMapper.createTypeMap(String.class, Visibility.class)
                .setConverter(context -> Visibility.valueOf(context.getSource().toUpperCase()));
        modelMapper.addConverter((context) -> {
            TaskDTO source = (TaskDTO) context.getSource();
            Task destination = (Task) context.getDestination();
            
            if (source.getLlm() != null) {
                LLMConfigDTO llm = source.getLlm();
                destination.setLlmSubmission(llm.getSubmission());
                destination.setLlmSolution(llm.getSolution());
                destination.setLlmRubrics(llm.getRubrics());
                destination.setLlmCot(llm.getCot());
                destination.setLlmVoting(llm.getVoting());
                destination.setLlmExamples(llm.getExamples());
                destination.setLlmPrompt(llm.getPrompt());
                destination.setLlmPre(llm.getPre());
                destination.setLlmPost(llm.getPost());
                destination.setLlmModel(llm.getModel());
                destination.setLlmTemperature(llm.getTemperature());
                destination.setLlmMaxPoints(llm.getMaxPoints());
            }
            
            return destination;
        }, TaskDTO.class, Task.class);
        return modelMapper;
    }
}
