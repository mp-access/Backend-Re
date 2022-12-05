package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.model.*;
import ch.uzh.ifi.access.model.constants.SubmissionType;
import ch.uzh.ifi.access.model.dao.GradeResults;
import ch.uzh.ifi.access.model.dto.*;
import ch.uzh.ifi.access.projections.*;
import ch.uzh.ifi.access.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.apache.tika.Tika;
import org.eclipse.jgit.api.Git;
import org.keycloak.representations.idm.UserRepresentation;
import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.time.LocalDateTime.now;

@Slf4j
@Service
@AllArgsConstructor
public class CourseService {

    private String workingDir;

    private CourseRepository courseRepository;

    private AssignmentRepository assignmentRepository;

    private TaskRepository taskRepository;

    private TaskFileRepository taskFileRepository;

    private SubmissionRepository submissionRepository;

    private SubmissionFileRepository submissionFileRepository;

    private DockerClient dockerClient;

    private ModelMapper modelMapper;

    private ObjectMapper objectMapper;

    private String verifyUserId(@Nullable String userId) {
        return Optional.ofNullable(userId).orElse(SecurityContextHolder.getContext().getAuthentication().getName());
    }

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
        List<Submission> restricted = submissionRepository.findByTask_IdAndUserIdAndType(taskId, userId, SubmissionType.GRADE);
        return Stream.concat(unrestricted.stream(), restricted.stream()).sorted(Comparator.comparingLong(Submission::getId).reversed()).toList();
    }

    private Optional<Submission> getEarliestGradedSubmission(Long taskId, String userId, LocalDateTime start) {
        return submissionRepository.findTopByTask_IdAndUserIdAndValidTrueAndTypeAndCreatedAtAfterOrderByCreatedAtAsc(taskId, userId, SubmissionType.GRADE, start);
    }

    private Optional<Submission> getLatestGradedSubmission(Long taskId, String userId) {
        return submissionRepository.findTopByTask_IdAndUserIdAndValidTrueAndTypeAndNextAttemptAtAfterOrderByNextAttemptAtDesc(
                taskId, userId, SubmissionType.GRADE, now());
    }

    private Integer countGradedSubmissions(Long taskId, String userId) {
        return submissionRepository.countByTask_IdAndUserIdAndType(taskId, userId, SubmissionType.GRADE);
    }

    private Integer countValidGradedSubmissions(Long taskId, String userId) {
        return submissionRepository.countByTask_IdAndUserIdAndValidTrueAndType(taskId, userId, SubmissionType.GRADE);
    }

    private Integer countValidGradedSubmissions(Long taskId, String userId, LocalDateTime start, LocalDateTime end) {
        return submissionRepository.countByTask_IdAndUserIdAndValidTrueAndTypeAndCreatedAtBetween(taskId, userId, SubmissionType.GRADE, start, end);
    }

    public Integer getRemainingAttempts(Long taskId, Integer maxAttempts, Duration attemptWindow, @Nullable String userId) {
        String verifiedUserId = verifyUserId(userId);
        if (Objects.isNull(attemptWindow))
            return maxAttempts - countValidGradedSubmissions(taskId, verifiedUserId);
        LocalDateTime relevantWindowStart = now().minus(attemptWindow.multipliedBy(maxAttempts));
        LocalDateTime latestWindowStart = now().minus(attemptWindow);
        Integer priorToLatestCount = countValidGradedSubmissions(taskId, verifiedUserId, relevantWindowStart, latestWindowStart);
        Integer latestCount = countValidGradedSubmissions(taskId, verifiedUserId, latestWindowStart, now());
        Integer maxRefills = getEarliestGradedSubmission(taskId, verifiedUserId, relevantWindowStart)
                .map(submission -> Duration.between(submission.getCreatedAt(), now()).dividedBy(attemptWindow))
                .orElse(0L).intValue();
        return Math.min(maxAttempts, maxAttempts + maxRefills - priorToLatestCount) - latestCount;
    }

    public Event createEvent(Integer ordinalNum, LocalDateTime date, String type) {
        Event newEvent = new Event();
        newEvent.setDate(date);
        newEvent.setType(type);
        newEvent.setDescription("Assignment %s is %s.".formatted(ordinalNum, type));
        return newEvent;
    }

    public List<Event> getEvents(String courseURL) {
        return getAssignments(courseURL).stream().flatMap(assignment -> Stream.of(
                createEvent(assignment.getOrdinalNum(), assignment.getStartDate(), "published"),
                createEvent(assignment.getOrdinalNum(), assignment.getEndDate(), "due"))).toList();
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
        return submissionRepository.findTopByTask_IdAndUserIdAndValidTrueAndTypeOrderByPointsDesc(
                taskId, verifyUserId(userId), SubmissionType.GRADE).map(Submission::getPoints).orElse(0.0);
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

    public boolean isCurrentlySubmitting(String userId) {
        return !dockerClient.listContainersCmd().withLabelFilter(Map.of("userId", userId)).exec().isEmpty();
    }

    @Transactional
    public Submission createSubmission(SubmissionDTO submissionDTO) {
        Task task = getTaskById(submissionDTO.getTaskId());
        Submission newSubmission = modelMapper.map(submissionDTO, Submission.class);
        if (submissionDTO.isRestricted() && isCurrentlySubmitting(newSubmission.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Submission rejected - another submission is currently running!");
        }
        if (newSubmission.isGraded()) {
            newSubmission.setOrdinalNum(countGradedSubmissions(task.getId(), newSubmission.getUserId()) + 1);
            if (submissionDTO.isRestricted()) {
                if (!task.getAssignment().isActive())
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Submission rejected - task is not active!");
                if (getRemainingAttempts(task.getId(), task.getMaxAttempts(), task.getAttemptWindow(), newSubmission.getUserId()) <= 0)
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Submission rejected - no remaining attempts!");
                if (Objects.nonNull(task.getAttemptWindow()))
                    newSubmission.setNextAttemptAt(getLatestGradedSubmission(task.getId(), newSubmission.getUserId())
                            .map(Submission::getNextAttemptAt).orElse(now()).plus(task.getAttemptWindow()));
            }
        }
        newSubmission.setTask(task);
        newSubmission.setValid(!newSubmission.isGraded());
        submissionDTO.getFiles().stream()
                .filter(submissionFile -> Objects.nonNull(submissionFile.getContent()))
                .forEach(submissionFile -> {
                    SubmissionFile newSubmissionFile = new SubmissionFile();
                    newSubmissionFile.setSubmission(newSubmission);
                    newSubmissionFile.setContent(submissionFile.getContent());
                    newSubmissionFile.setTaskFile(getTaskFileById(submissionFile.getTaskFileId()));
                    newSubmission.getFiles().add(newSubmissionFile);
                });
        Submission savedSubmission = submissionRepository.saveAndFlush(newSubmission);
        return evaluateSubmission(savedSubmission);
    }

    @Transactional
    public Submission evaluateSubmission(Submission submission) {
        Evaluator evaluator = submission.getTask().getEvaluator();
        try (CreateContainerCmd containerCmd = dockerClient.createContainerCmd(evaluator.getDockerImage())) {
            Path submissionDir = Paths.get(workingDir, "submissions", submission.getId().toString());
            if (submission.isGraded())
                taskFileRepository.findByTask_IdAndEnabledTrueAndGradingTrue(submission.getTask().getId())
                        .forEach(file -> createLocalFile(submissionDir, file.getPath(), file.getTemplate()));
            submission.getFiles().forEach(file -> createLocalFile(submissionDir, file.getTaskFile().getPath(), file.getContent()));
            CreateContainerResponse container = containerCmd.withNetworkDisabled(true)
                    .withLabels(Map.of("userId", submission.getUserId())).withWorkingDir(submissionDir.toString())
                    .withCmd("/bin/bash", "-c", evaluator.formCommand(submission.getType()) + " &> logs.txt")
                    .withHostConfig(new HostConfig().withMemory(536870912L).withPrivileged(true).withAutoRemove(true)
                            .withBinds(Bind.parse(submissionDir + ":" + submissionDir))).exec();
            dockerClient.startContainerCmd(container.getId()).exec();
            Integer statusCode = dockerClient.waitContainerCmd(container.getId())
                    .exec(new WaitContainerResultCallback()).awaitStatusCode(evaluator.getTimeLimit(), TimeUnit.MINUTES);
            log.info("Container {} finished with status {}", container.getId(), statusCode);
            if (statusCode == 137)
                submission.setOutput("Memory Limit Exceeded");
            submission.setLogs(FileUtils.readLines(submissionDir.resolve("logs.txt").toFile(),
                    Charset.defaultCharset()).stream().limit(50).collect(Collectors.joining(Strings.LINE_SEPARATOR)));
            if (submission.isGraded()) {
                GradeResults results = readJsonFile(submissionDir, evaluator.getGradeResults(), GradeResults.class);
                results.getHints().stream().findFirst().ifPresent(submission::setOutput);
                submission.setPoints(results.getPoints());
                submission.setValid(Objects.nonNull(submission.getPoints()));
            }
            FileUtils.deleteQuietly(submissionDir.toFile());
        } catch (IOException | NullPointerException e) {
            log.error("Failed to read or write file: {}", e.toString());
        } catch (DockerClientException e) {
            submission.setOutput(e.getMessage());
        }
        return submissionRepository.save(submission);
    }

    @SneakyThrows
    private void createLocalFile(Path submissionDir, String relativeFilePath, String content) {
        Path filePath = submissionDir.resolve(relativeFilePath);
        Files.createDirectories(filePath.getParent());
        if (!filePath.toFile().exists())
            Files.createFile(filePath);
        Files.writeString(filePath, content);
    }

    @SneakyThrows
    public <T> T readJsonFile(Path path, String filename, Class<T> targetDTO) {
        return objectMapper.readValue(Files.readString(path.resolve(filename)), targetDTO);
    }

    @SneakyThrows
    public <T> T readConfig(Path localDirPath, Class<T> targetDTO) {
        return readJsonFile(localDirPath, "config.json", targetDTO);
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
            taskFile.setName(taskFilePath.getFileName().toString());
            try {
                taskFile.setImage(StringUtils.contains(new Tika().detect(taskFilePath), "image"));
                if (taskFile.isImage())
                    taskFile.setBytes(Files.readAllBytes(taskFilePath));
                else
                    taskFile.setTemplate(Files.readString(taskFilePath));
                taskFile.setEnabled(true);
            } catch (IOException e) {
                log.error("Failed to parse file at {}", taskFilePath);
            }
        }
        return taskFile;
    }

    @SneakyThrows
    public Course createOrUpdateCourseFromRepository(Course course) {
        Path coursePath = Path.of(workingDir, "courses", "course_" + Instant.now().toEpochMilli());
        Git.cloneRepository().setURI(course.getRepository()).setDirectory(coursePath.toFile()).call();
        CourseDTO courseConfig = readConfig(coursePath, CourseDTO.class);
        modelMapper.map(courseConfig, course);
        course.getAssignments().forEach(assignment -> assignment.setEnabled(false));
        courseConfig.getAssignments().forEach(assignmentDir -> {
            Path assignmentPath = coursePath.resolve(assignmentDir);
            AssignmentDTO assignmentConfig = readConfig(assignmentPath, AssignmentDTO.class);
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
                TaskDTO taskConfig = readConfig(taskPath, TaskDTO.class);
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