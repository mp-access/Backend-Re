package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.model.*;
import ch.uzh.ifi.access.model.constants.SubmissionType;
import ch.uzh.ifi.access.model.dto.*;
import ch.uzh.ifi.access.projections.*;
import ch.uzh.ifi.access.repository.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.dockerjava.api.DockerClient;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.Tika;
import org.eclipse.jgit.api.Git;
import org.keycloak.representations.idm.UserRepresentation;
import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.transaction.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

import static java.time.LocalDateTime.now;

@Slf4j
@Service
@AllArgsConstructor
public class CourseService {

    private CourseRepository courseRepository;

    private AssignmentRepository assignmentRepository;

    private TaskRepository taskRepository;

    private TaskFileRepository taskFileRepository;

    private SubmissionRepository submissionRepository;

    private SubmissionFileRepository submissionFileRepository;

    private DockerClient dockerClient;

    private ModelMapper modelMapper;

    private JsonMapper jsonMapper;

    public Course getCourseByURL(String courseURL) {
        return courseRepository.getByUrl(courseURL).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL " + courseURL));
    }

    public Task getTaskById(Long taskId) {
        return taskRepository.findById(taskId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No task found with the ID " + taskId));
    }

    public TaskFile getTaskFileById(Long fileId) {
        return taskFileRepository.findById(fileId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No task file found with the ID " + fileId));
    }

    public List<CourseOverview> getCourses() {
        return courseRepository.findCoursesBy();
    }

    public List<CourseFeature> getFeaturedCourses() {
        return courseRepository.findCoursesByRestrictedFalse();
    }

    public CourseWorkspace getCourse(String courseURL) {
        return courseRepository.findByUrl(courseURL).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL " + courseURL));
    }

    public List<AssignmentOverview> getAssignments(String courseURL) {
        return assignmentRepository.findByCourse_UrlAndEnabledTrueOrderByOrdinalNumDesc(courseURL);
    }

    public List<AssignmentWorkspace> getActiveAssignments(String courseURL) {
        return assignmentRepository.findByCourse_UrlAndEnabledTrueAndStartDateBeforeAndEndDateAfterOrderByEndDateAsc(courseURL, now(), now());
    }

    public List<AssignmentOverview> getPastAssignments(String courseURL) {
        return assignmentRepository.findByCourse_UrlAndEnabledTrueAndEndDateBeforeOrderByEndDateAsc(courseURL, now());
    }

    public AssignmentWorkspace getAssignment(String courseURL, String assignmentURL) {
        return assignmentRepository.findByCourse_UrlAndUrl(courseURL, assignmentURL)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No assignment found with the URL " + assignmentURL));
    }

    public TaskWorkspace getTask(String courseURL, String assignmentURL, String taskURL, String userId) {
        TaskWorkspace workspace = taskRepository.findByAssignment_Course_UrlAndAssignment_UrlAndUrl(courseURL, assignmentURL, taskURL)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No task found with the URL: %s/%s/%s".formatted(courseURL, assignmentURL, taskURL)));
        workspace.setUserId(userId);
        return workspace;
    }

    public List<TaskFile> getTaskFiles(Long taskId, String userId) {
        List<TaskFile> permittedFiles = taskFileRepository.findByTask_IdAndEnabledTrueOrderByIdAscPathAsc(taskId);
        permittedFiles.stream().filter(TaskFile::isEditable).forEach(file ->
                submissionFileRepository.findTopByTaskFile_IdAndSubmission_UserIdOrderByIdDesc(file.getId(), userId)
                        .ifPresent(latestSubmissionFile -> file.setContent(latestSubmissionFile.getContent())));
        return permittedFiles;
    }

    public List<Submission> getSubmissions(Long taskId, String userId) {
        List<Submission> unrestricted = submissionRepository.findByTask_IdAndUserId(taskId, userId);
        unrestricted.forEach(submission -> Optional.ofNullable(submission.getLogs()).ifPresent(submission::setOutput));
        List<Submission> restricted = submissionRepository
                .findByTask_IdAndUserIdAndIdNotIn(taskId, userId, unrestricted.stream().map(Submission::getId).toList());
        return Stream.concat(unrestricted.stream(), restricted.stream()).sorted(Comparator.comparingLong(Submission::getId).reversed()).toList();
    }

    public Integer getRemainingAttempts(Long taskId, Integer maxAttempts, Duration attemptWindow, String userId) {
        if (Objects.isNull(attemptWindow))
            return maxAttempts - submissionRepository.countByTask_IdAndUserIdAndTypeAndValidTrue(taskId, userId, SubmissionType.GRADE);
        LocalDateTime relevantWindowStart = now().minus(attemptWindow.multipliedBy(maxAttempts));
        LocalDateTime latestWindowStart = now().minus(attemptWindow);
        Integer priorToLatestCount = submissionRepository.countByTask_IdAndUserIdAndTypeAndValidTrueAndCreatedAtBetween(
                taskId, userId, SubmissionType.GRADE, relevantWindowStart, latestWindowStart);
        Integer latestCount = submissionRepository.countByTask_IdAndUserIdAndTypeAndValidTrueAndCreatedAtBetween(
                taskId, userId, SubmissionType.GRADE, latestWindowStart, now());
        return Math.min(maxAttempts, 2 * maxAttempts - 1 - priorToLatestCount) - latestCount;
    }

    public Integer getRemainingAttempts(Task task) {
        return getRemainingAttempts(task.getId(), task.getMaxAttempts(), task.getAttemptWindow(), task.getUserId());
    }

    public Submission createSubmission(SubmissionDTO submissionDTO) {
        Task task = getTaskById(submissionDTO.getTaskId());
        Submission newSubmission = modelMapper.map(submissionDTO, Submission.class);
        if (submissionDTO.isRestricted()) {
            if (!dockerClient.listContainersCmd().withLabelFilter(Map.of("userId", submissionDTO.getUserId())).exec().isEmpty())
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Submission rejected - another submission is currently running!");
            if (newSubmission.isGraded()) {
                if (getRemainingAttempts(task) <= 0)
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Submission rejected - no remaining attempts!");
                if (!task.getAssignment().isActive())
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Submission rejected - task is not active!");
            }
        }
        newSubmission.setTask(task);
        submissionDTO.getFiles().stream()
                .filter(submissionFile -> Objects.nonNull(submissionFile.getContent()))
                .forEach(submissionFile -> {
                    SubmissionFile newSubmissionFile = new SubmissionFile();
                    newSubmissionFile.setSubmission(newSubmission);
                    newSubmissionFile.setContent(submissionFile.getContent());
                    newSubmissionFile.setTaskFile(getTaskFileById(submissionFile.getTaskFileId()));
                    newSubmission.getFiles().add(newSubmissionFile);
                });
        if (newSubmission.isGraded()) {
            Optional<Submission> latestSubmission = submissionRepository.findTopByTask_IdAndUserIdAndTypeAndValidTrueOrderByCreatedAtAsc(
                    submissionDTO.getTaskId(), submissionDTO.getUserId(), SubmissionType.GRADE);
            newSubmission.setOrdinalNum(latestSubmission.map(Submission::getOrdinalNum).orElse(0) + 1);
            newSubmission.setNextAttemptAt(latestSubmission.map(Submission::getNextAttemptAt).orElse(now()).plus(1, ChronoUnit.HOURS));
        }
        return submissionRepository.save(newSubmission);
    }

    public Double calculateAvgTaskPoints(Long taskId) {
        return submissionRepository.calculateAvgTaskPoints(taskId).stream().filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    public Double calculateAvgAssignmentPoints(List<Task> tasks) {
        return tasks.stream().mapToDouble(task -> calculateAvgTaskPoints(task.getId())).average().orElse(0.0);
    }

    public Double calculateAvgCoursePoints(List<Assignment> assignments) {
        return assignments.stream().mapToDouble(assignment -> calculateAvgAssignmentPoints(assignment.getTasks())).average().orElse(0.0);
    }

    public Double calculateTaskPoints(Long taskId, String userId) {
        return Optional.ofNullable(submissionRepository.calculateTaskPoints(taskId, userId)).orElse(0.0);
    }

    public Double calculateAssignmentPoints(List<Task> tasks, String userId) {
        return tasks.stream().mapToDouble(task -> calculateTaskPoints(task.getId(), userId)).sum();
    }

    public Double calculateCoursePoints(List<Assignment> assignments, String userId) {
        return assignments.stream().mapToDouble(assignment -> calculateAssignmentPoints(assignment.getTasks(), userId)).sum();
    }

    public StudentDTO getStudent(String courseURL, UserRepresentation user) {
        Double coursePoints = calculateCoursePoints(getCourseByURL(courseURL).getAssignments(), user.getEmail());
        return new StudentDTO(user.getFirstName(), user.getLastName(), user.getEmail(), coursePoints);
    }

    @Transactional
    public void updateStudent(UserDTO updates) {
        submissionRepository.findByTask_IdAndUserIdAndTypeAndValidTrueOrderByCreatedAtAsc(updates.getTaskId(), updates.getUserId(), SubmissionType.GRADE)
                .stream().limit(updates.getAddAttempts()).forEach(submission -> {
                    submission.setValid(false);
                    submissionRepository.save(submission);
                });
    }

    @SneakyThrows
    public <T> T getConfigFile(Path localDirPath, Class<T> targetDTO) {
        return jsonMapper.readValue(localDirPath.resolve("config.json").toFile(), targetDTO);
    }

    @SneakyThrows
    private Path createCourseDir(String repository) {
        Path courseDir = Path.of("/tmp/course_" + Instant.now().toEpochMilli());
        Git.cloneRepository().setURI(repository).setDirectory(courseDir.toFile()).call();
        return courseDir;
    }

    private String readType(Path taskFilePath) {
        try {
            return new Tika().detect(taskFilePath);
        } catch (IOException e) {
            log.error("Failed to read file type at {}", taskFilePath);
            return FilenameUtils.getExtension(taskFilePath.toString());
        }
    }

    private TaskFile createOrUpdateTaskFile(Task task, Path parentPath, String filePath) {
        TaskFile taskFile = task.getFiles().stream()
                .filter(existing -> existing.getPath().equals(filePath)).findFirst()
                .orElseGet(() -> {
                    TaskFile newTaskFile = new TaskFile();
                    task.getFiles().add(newTaskFile);
                    newTaskFile.setTask(task);
                    newTaskFile.setPath(filePath);
                    return newTaskFile;
                });
        if (!taskFile.isEnabled()) {
            Path taskFilePath = parentPath.resolve(taskFile.getPath());
            taskFile.setMime(readType(taskFilePath));
            try {
                if (taskFile.isImage())
                    taskFile.setBytes(Files.readAllBytes(taskFilePath));
                else
                    taskFile.setTemplate(Files.readString(taskFilePath));
                taskFile.setEnabled(true);
            } catch (IOException e) {
                log.error("Failed to read file content at {}", taskFilePath);
            }
        }
        return taskFile;
    }

    public Course createOrUpdateCourseFromRepository(Course course) {
        Path coursePath = createCourseDir(course.getRepository());
        CourseDTO courseConfig = getConfigFile(coursePath, CourseDTO.class);
        modelMapper.map(courseConfig, course);
        course.getAssignments().forEach(assignment -> assignment.setEnabled(false));
        courseConfig.getAssignments().forEach(assignmentDir -> {
            Path assignmentPath = coursePath.resolve(assignmentDir);
            AssignmentDTO assignmentConfig = getConfigFile(assignmentPath, AssignmentDTO.class);
            Assignment assignment = course.getAssignments().stream()
                    .filter(existing -> existing.getUrl().equals(assignmentConfig.getUrl())).findFirst()
                    .orElseGet(() -> {
                        Assignment newAssignment = new Assignment();
                        course.getAssignments().add(newAssignment);
                        newAssignment.setCourse(course);
                        return newAssignment;
                    });
            modelMapper.map(assignmentConfig, assignment);
            assignment.setEnabled(true);
            assignment.getTasks().forEach(task -> task.setEnabled(false));
            assignmentConfig.getTasks().forEach(taskDir -> {
                Path taskPath = assignmentPath.resolve(taskDir);
                TaskDTO taskConfig = getConfigFile(taskPath, TaskDTO.class);
                Task task = assignment.getTasks().stream()
                        .filter(existing -> existing.getUrl().equals(taskConfig.getUrl())).findFirst()
                        .orElseGet(() -> {
                            Task newTask = new Task();
                            assignment.getTasks().add(newTask);
                            newTask.setAssignment(assignment);
                            return newTask;
                        });
                modelMapper.map(taskConfig, task);
                try {
                    task.setInstructions(Files.readString(taskPath.resolve(task.getInstructions())));
                    task.setEnabled(true);
                } catch (IOException e) {
                    log.error("Failed to read task instructions: {}", e.getMessage());
                }
                task.getFiles().forEach(file -> file.setEnabled(false));
                taskConfig.getEvaluator().getResources().forEach(filePath ->
                        createOrUpdateTaskFile(task, coursePath, filePath).setGrading(true));
                taskConfig.getFiles().getGrading().forEach(filePath ->
                        createOrUpdateTaskFile(task, taskPath, filePath).setGrading(true));
                taskConfig.getFiles().getEditable().forEach(filePath ->
                        createOrUpdateTaskFile(task, taskPath, filePath).setEditable(true));
                taskConfig.getFiles().getPublish().forEach((key, filePaths) -> {
                    LocalDateTime publishDate = switch (key) {
                        case "startDate" -> assignment.getStartDate();
                        case "endDate" -> assignment.getEndDate();
                        default -> LocalDateTime.parse(key);
                    };
                    filePaths.forEach(filePath ->
                            createOrUpdateTaskFile(task, taskPath, filePath).setPublishDate(publishDate));
                });
            });
        });
        course.getAssignments().stream()
                .flatMap(assignment -> assignment.getTasks().stream().map(task -> task.getEvaluator().getDockerImage()))
                .distinct().filter(StringUtils::isNotBlank).forEach(imageName -> {
                    try {
                        dockerClient.pullImageCmd(imageName).start().awaitCompletion().onComplete();
                    } catch (InterruptedException e) {
                        log.error("Failed to pull docker image {}", imageName);
                        Thread.currentThread().interrupt();
                    }
                });
        return courseRepository.save(course);
    }

    public Course createCourseFromRepository(String repository) {
        Course newCourse = new Course();
        newCourse.setRepository(repository);
        return createOrUpdateCourseFromRepository(newCourse);
    }

    @Transactional
    public Course updateCourseFromRepository(String courseURL) {
        Course existingCourse = getCourseByURL(courseURL);
        return createOrUpdateCourseFromRepository(existingCourse);
    }
}