package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.model.*;
import ch.uzh.ifi.access.model.constants.SubmissionType;
import ch.uzh.ifi.access.model.dto.*;
import ch.uzh.ifi.access.model.projections.*;
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
import org.springframework.lang.Nullable;
import org.springframework.security.data.repository.query.SecurityEvaluationContextExtension;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.transaction.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    private SecurityEvaluationContextExtension security;

    private String getUserId() {
        return Objects.requireNonNull(security.getRootObject()).getAuthentication().getName();
    }

    private String verifyUserId(@Nullable String userId) {
        return Optional.ofNullable(userId).orElseGet(this::getUserId);
    }

    private boolean isAssistant(String courseURL) {
        return Objects.requireNonNull(security.getRootObject()).hasRole(courseURL + "-assistant");
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

    public CourseWorkspace getCourse(String courseURL) {
        return courseRepository.findByUrl(courseURL).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL " + courseURL));
    }

    public List<AssignmentOverview> getAssignments(String courseURL) {
        return assignmentRepository.findByCourse_UrlAndEnabledTrueOrderByOrdinalNumDesc(courseURL);
    }

    public AssignmentWorkspace getAssignment(String courseURL, String assignmentURL) {
        return assignmentRepository.findByCourse_UrlAndUrl(courseURL, assignmentURL)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No assignment found with the URL " + assignmentURL));
    }

    public List<TaskOverview> getTasks(String courseURL, String assignmentURL) {
        return taskRepository.findByAssignment_Course_UrlAndAssignment_UrlAndEnabledTrueOrderByOrdinalNum(courseURL, assignmentURL);
    }

    public TaskWorkspace getTask(String courseURL, String assignmentURL, String taskURL) {
        TaskWorkspace workspace = taskRepository.findByAssignment_Course_UrlAndAssignment_UrlAndUrl(courseURL, assignmentURL, taskURL)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No task found with the URL: %s/%s/%s".formatted(courseURL, assignmentURL, taskURL)));
        workspace.setUserId(getUserId());
        return workspace;
    }

    public TaskWorkspace getTask(String courseURL, String assignmentURL, String taskURL, String userId) {
        TaskWorkspace workspace = getTask(courseURL, assignmentURL, taskURL);
        workspace.setUserId(userId);
        return workspace;
    }

    public TaskWorkspace getTask(String courseURL, String assignmentURL, String taskURL, String userId, Long submissionId) {
        TaskWorkspace workspace = getTask(courseURL, assignmentURL, taskURL, userId);
        workspace.setSubmissionId(submissionId);
        return workspace;
    }

    public List<TaskFile> getTaskFiles(Task task) {
        List<TaskFile> permittedFiles = taskFileRepository.findByTask_IdAndEnabledTrueOrderByIdAscPathAsc(task.getId());
        permittedFiles.stream().filter(TaskFile::isEditable).forEach(file ->
                Optional.ofNullable(task.getSubmissionId())
                        .map(submissionId -> submissionFileRepository.findByTaskFile_IdAndSubmission_Id(file.getId(), submissionId))
                        .orElseGet(() -> submissionFileRepository.findTopByTaskFile_IdAndSubmission_UserIdOrderByIdDesc(file.getId(), task.getUserId()))
                        .ifPresent(latestSubmissionFile -> file.setContent(latestSubmissionFile.getContent())));
        return permittedFiles;
    }

    public List<Submission> getSubmissions(Task task) {
        boolean isAssistant = isAssistant(task.getAssignment().getCourse().getUrl());
        List<Submission> submissions = submissionRepository.findByTask_IdAndUserIdOrderByCreatedAtDesc(task.getId(), task.getUserId());
        submissions.stream().filter(submission -> isAssistant || !submission.isGraded())
                .forEach(submission -> Optional.ofNullable(submission.getLogs()).ifPresent(submission::setOutput));
        return submissions;
    }

    public boolean isSubmissionOwner(Long submissionId, String userId) {
        Submission submission = submissionRepository.findById(submissionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No submission found with the ID " + submissionId));
        return submission.getUserId().equals(userId);
    }

    public Integer getSubmissionsCount(Task task) {
        return submissionRepository.countByTask_IdAndUserIdAndTypeAndValidTrue(task.getId(), verifyUserId(task.getUserId()), SubmissionType.GRADE);
    }

    public Integer getRemainingAttempts(Task task) {
        return task.getMaxAttempts() - getSubmissionsCount(task);
    }

    public boolean isSubmissionAllowed(Long taskId) {
        Task task = getTaskById(taskId);
        return task.getAssignment().isActive() && (getRemainingAttempts(task) > 0);
    }

    public List<AssignmentWorkspace> getActiveAssignments(String courseURL) {
        return assignmentRepository.findByCourse_UrlAndEnabledTrueAndStartDateBeforeAndEndDateAfterOrderByEndDateAsc(
                courseURL, now(), now());
    }

    public Double calculateTaskPoints(Long taskId, String userId) {
        return submissionRepository.findFirstByTask_IdAndUserIdAndPointsNotNullOrderByPointsDesc(
                taskId, verifyUserId(userId)).map(Submission::getPoints).orElse(0.0);
    }

    public Double calculateAvgTaskPoints(Long taskId) {
        return submissionRepository.calculateAvgTaskPoints(taskId).stream().filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    public Double calculateAssignmentPoints(List<Task> tasks, String userId) {
        return tasks.stream().mapToDouble(task -> calculateTaskPoints(task.getId(), userId)).sum();
    }

    public Double calculateAvgAssignmentPoints(List<Task> tasks) {
        return tasks.stream().mapToDouble(task -> calculateAvgTaskPoints(task.getId())).average().orElse(0.0);
    }

    public Double calculateCoursePoints(List<Assignment> assignments, String userId) {
        return assignments.stream().mapToDouble(assignment -> calculateAssignmentPoints(assignment.getTasks(), userId)).sum();
    }

    public Double calculateAvgCoursePoints(List<Assignment> assignments) {
        return assignments.stream().mapToDouble(assignment -> calculateAvgAssignmentPoints(assignment.getTasks())).average().orElse(0.0);
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

    public Submission createSubmission(SubmissionDTO submissionDTO) {
        Submission newSubmission = modelMapper.map(submissionDTO, Submission.class);
        newSubmission.setUserId(getUserId());
        newSubmission.setTask(getTaskById(submissionDTO.getTaskId()));
        submissionDTO.getFiles().stream()
                .filter(submissionFile -> Objects.nonNull(submissionFile.getContent()))
                .forEach(submissionFile -> {
                    SubmissionFile newSubmissionFile = new SubmissionFile();
                    newSubmissionFile.setSubmission(newSubmission);
                    newSubmissionFile.setContent(submissionFile.getContent());
                    newSubmissionFile.setTaskFile(getTaskFileById(submissionFile.getTaskFileId()));
                    newSubmission.getFiles().add(newSubmissionFile);
                });
        return submissionRepository.save(newSubmission);
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

    private String readContent(Path taskFilePath) {
        try {
            return Files.readString(taskFilePath);
        } catch (IOException e) {
            log.error("Failed to read file at {}", taskFilePath);
            return null;
        }
    }

    private byte[] readImage(Path taskFilePath) {
        try {
            return Files.readAllBytes(taskFilePath);
        } catch (IOException e) {
            log.error("Failed to read image at {}", taskFilePath);
            return new byte[0];
        }
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
            taskFile.setEnabled(true);
            Path taskFilePath = parentPath.resolve(taskFile.getPath());
            taskFile.setMime(readType(taskFilePath));
            if (taskFile.isImage())
                taskFile.setBytes(readImage(taskFilePath));
            else
                taskFile.setTemplate(readContent(taskFilePath));
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
                task.setEnabled(true);
                task.setInstructions(readContent(taskPath.resolve(task.getInstructions())));
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