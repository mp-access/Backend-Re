package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.model.*;
import ch.uzh.ifi.access.model.constants.Extension;
import ch.uzh.ifi.access.model.constants.SubmissionType;
import ch.uzh.ifi.access.model.dto.*;
import ch.uzh.ifi.access.model.projections.AssignmentOverview;
import ch.uzh.ifi.access.model.projections.CourseOverview;
import ch.uzh.ifi.access.model.projections.TaskOverview;
import ch.uzh.ifi.access.model.projections.TaskWorkspace;
import ch.uzh.ifi.access.repository.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.base.CharMatcher;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.keycloak.representations.idm.UserRepresentation;
import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.security.data.repository.query.SecurityEvaluationContextExtension;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.transaction.Transactional;
import javax.ws.rs.NotAllowedException;
import javax.ws.rs.NotFoundException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    public CourseOverview getCourse(String courseURL) {
        return courseRepository.findByUrl(courseURL).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL " + courseURL));
    }

    public List<AssignmentOverview> getAssignments(String courseURL) {
        return assignmentRepository.findByCourse_UrlOrderByOrdinalNumDesc(courseURL);
    }

    public AssignmentOverview getAssignment(String courseURL, String assignmentURL) {
        return assignmentRepository.findByCourse_UrlAndUrl(courseURL, assignmentURL)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No assignment found with the URL " + assignmentURL));
    }

    public List<TaskOverview> getTasks(String courseURL, String assignmentURL) {
        return taskRepository.findByAssignment_Course_UrlAndAssignment_UrlOrderByOrdinalNum(courseURL, assignmentURL);
    }

    public TaskWorkspace getTask(String courseURL, String assignmentURL, String taskURL) {
        return taskRepository.findByAssignment_Course_UrlAndAssignment_UrlAndUrl(courseURL, assignmentURL, taskURL)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No task found with the URL: %s/%s/%s".formatted(courseURL, assignmentURL, taskURL)));
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

    public List<TaskFile> getTaskFiles(Long taskId, Long submissionId, String userId) {
        List<TaskFile> permittedFiles = taskFileRepository.findByTask_IdOrderByPermissionAscNameAsc(taskId);
        permittedFiles.forEach(file ->
                Optional.ofNullable(submissionId)
                        .map(id -> submissionFileRepository.findByTaskFile_IdAndSubmission_Id(file.getId(), submissionId))
                        .orElseGet(() -> submissionFileRepository.findTopByTaskFile_IdAndSubmission_UserIdOrderByIdDesc(file.getId(), verifyUserId(userId)))
                        .ifPresent(latestSubmissionFile -> file.setContent(latestSubmissionFile.getContent())));
        return permittedFiles;
    }

    public boolean isSubmissionOwner(Long submissionId, String userId) {
        Submission submission = submissionRepository.findById(submissionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No submission found with the ID " + submissionId));
        return submission.getUserId().equals(userId);
    }

    public List<Submission> getSubmissions(String courseURL, Long taskId, String userId) {
        boolean isAssistant = isAssistant(courseURL);
        List<Submission> submissions = submissionRepository.findByTask_IdAndUserIdOrderByCreatedAtDesc(taskId, verifyUserId(userId));
        submissions.stream().filter(submission -> isAssistant || !submission.isGraded())
                .forEach(submission -> Optional.ofNullable(submission.getLogs()).ifPresent(submission::setHint));
        return submissions;
    }

    public Integer getSubmissionsCount(Long taskId, String userId) {
        return submissionRepository.countByTask_IdAndUserIdAndTypeAndValidTrue(taskId, verifyUserId(userId), SubmissionType.GRADE);
    }

    public Integer getRemainingAttempts(Task task, String userId) {
        return task.getMaxAttempts() - getSubmissionsCount(task.getId(), userId);
    }

    public boolean isSubmissionAllowed(Long taskId) {
        Task task = getTaskById(taskId);
        return task.getAssignment().isActive() && (!task.isGraded() || getRemainingAttempts(task, null) > 0);
    }

    public Integer countActiveAssignments(String courseURL) {
        return assignmentRepository.countByCourse_UrlAndStartDateBeforeAndEndDateAfter(courseURL, now(), now());
    }

    public Double calculateTaskPoints(Long taskId, String userId) {
        return submissionRepository.findFirstByTask_IdAndUserIdAndPointsNotNullOrderByPointsDesc(
                taskId, verifyUserId(userId)).map(Submission::getPoints).orElse(0.0);
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

    public Submission createSubmission(SubmissionDTO submissionDTO) {
        Submission newSubmission = modelMapper.map(submissionDTO, Submission.class);
        newSubmission.setUserId(getUserId());
        newSubmission.setTask(getTaskById(submissionDTO.getTaskId()));
        if (newSubmission.getTask().isText())
            throw new NotAllowedException("Text tasks can only be evaluated for grading");
        if (newSubmission.getType().equals(SubmissionType.RUN)) {
            if (ObjectUtils.isEmpty(submissionDTO.getCurrentFileId()))
                throw new NotFoundException("Submission of 'RUN' type must include the ID of the task file to run");
            newSubmission.setExecutableFile(getTaskFileById(submissionDTO.getCurrentFileId()));
        }
        if (newSubmission.isGraded())
            newSubmission.setOrdinalNum(getSubmissionsCount(submissionDTO.getTaskId(), null) + 1);
        submissionDTO.getFiles().forEach(submissionFile -> {
            if (Objects.nonNull(submissionFile.getContent())) {
                SubmissionFile newSubmissionFile = new SubmissionFile();
                newSubmissionFile.setSubmission(newSubmission);
                newSubmissionFile.setContent(submissionFile.getContent());
                newSubmissionFile.setTaskFile(getTaskFileById(submissionFile.getTaskFileId()));
                newSubmission.getFiles().add(newSubmissionFile);
            }
        });
        return submissionRepository.save(newSubmission);
    }

    @SneakyThrows
    private String readContent(Path localFile, boolean isImage) {
        return isImage ? Base64.getEncoder().encodeToString(Files.readAllBytes(localFile)) : Files.readString(localFile);
    }

    @SneakyThrows
    public <T> T getConfigFile(File localDir, Class<T> targetDTO) {
        return jsonMapper.readValue(localDir.toPath().resolve("config.json").toFile(), targetDTO);
    }

    @SneakyThrows
    private List<File> listDirectories(File parentDir) {
        try (Stream<Path> parentDirContent = Files.list(parentDir.toPath())) {
            return parentDirContent.map(Path::toFile).filter(file ->
                    file.isDirectory() && StringUtils.isAlpha(file.getName().substring(0, 1))).toList();
        }
    }

    @SneakyThrows
    private File createCourseDir(String repository) {
        File courseDir = Path.of("/tmp/course_" + Instant.now().toEpochMilli()).toFile();
        Git.cloneRepository().setURI(repository).setDirectory(courseDir).call();
        return courseDir;
    }

    private String asURL(String title) {
        return StringUtils.normalizeSpace(CharMatcher.anyOf("(),/").removeFrom(title.toLowerCase()))
                .replace(" ", "-");
    }

    private Integer parseNum(File dir) {
        return Integer.valueOf(StringUtils.substringBefore(StringUtils.substringAfter(dir.getName(), "_"), "_"));
    }

    private TaskFile createOrUpdateTaskFile(Task task, File taskDir, File localFile) {
        log.info("Reading file: {}", localFile.getAbsolutePath());
        Path relativePath = taskDir.toPath().relativize(localFile.toPath());
        TaskFile taskFile = taskFileRepository.findByTask_IdAndPath(task.getId(), relativePath.toString()).orElse(new TaskFile());
        taskFile.parsePath(relativePath);
        taskFile.setTemplate(readContent(localFile.toPath(), taskFile.isImage()));
        taskFile.setTask(task);
        return taskFile;
    }

    private Task createOrUpdateTask(Assignment assignment, File taskDir) {
        Integer ordinalNum = parseNum(taskDir);
        Task task = taskRepository.findByAssignment_IdAndOrdinalNum(assignment.getId(), ordinalNum).orElse(new Task());
        TaskDTO taskConfig = getConfigFile(taskDir, TaskDTO.class);
        modelMapper.map(taskConfig, task);
        task.setDescription(readContent(taskDir.toPath().resolve("description.md"), false));
        task.setExtension(Extension.fromLanguage(taskConfig.getLanguage()));
        task.setUrl(asURL(task.getTitle()));
        task.setOrdinalNum(ordinalNum);
        task.setAssignment(assignment);
        if (ObjectUtils.isNotEmpty(taskConfig.getSolutions()))
            task.setSolution(StringUtils.join(taskConfig.getSolutions(), "|"));
        if (ObjectUtils.isNotEmpty(taskConfig.getHints()))
            task.setHint(StringUtils.join(taskConfig.getHints(), "\n"));
        return task;
    }

    private Assignment createOrUpdateAssignment(Course course, File assignmentDir) {
        Integer ordinalNum = parseNum(assignmentDir);
        Assignment assignment = assignmentRepository.findByCourse_UrlAndOrdinalNum(course.getUrl(), ordinalNum).orElse(new Assignment());
        AssignmentDTO assignmentConfig = getConfigFile(assignmentDir, AssignmentDTO.class);
        modelMapper.map(assignmentConfig, assignment);
        assignment.setUrl(asURL(assignment.getTitle()));
        assignment.setOrdinalNum(ordinalNum);
        assignment.setCourse(course);
        return assignment;
    }

    public Course createCourseFromRepository(String repository) {
        File courseDir = createCourseDir(repository);
        CourseDTO courseConfig = getConfigFile(courseDir, CourseDTO.class);
        Course newCourse = modelMapper.map(courseConfig, Course.class);
        newCourse.setUrl(asURL(newCourse.getTitle()));
        newCourse.setRepository(repository);
        listDirectories(courseDir).forEach(assignmentDir -> {
            Assignment newAssignment = createOrUpdateAssignment(newCourse, assignmentDir);
            newCourse.getAssignments().add(newAssignment);
            listDirectories(assignmentDir).forEach(taskDir -> {
                Task newTask = createOrUpdateTask(newAssignment, taskDir);
                newAssignment.getTasks().add(newTask);
                listDirectories(taskDir).forEach(taskSubDir ->
                        FileUtils.listFiles(taskSubDir, Extension.listSupported(), true).forEach(localFile ->
                                newTask.getFiles().add(createOrUpdateTaskFile(newTask, taskDir, localFile))));
            });
        });
        return courseRepository.save(newCourse);
    }

    @Transactional
    public Course updateCourseFromRepository(String courseURL) {
        Course existingCourse = getCourseByURL(courseURL);
        File courseDir = createCourseDir(existingCourse.getRepository());
        CourseDTO courseConfig = getConfigFile(courseDir, CourseDTO.class);
        modelMapper.map(courseConfig, existingCourse);
        Course updatedCourse = courseRepository.save(existingCourse);
        listDirectories(courseDir).forEach(assignmentDir -> {
            Assignment updatedAssignment = assignmentRepository.save(createOrUpdateAssignment(existingCourse, assignmentDir));
            listDirectories(assignmentDir).forEach(taskDir -> {
                Task updatedTask = taskRepository.save(createOrUpdateTask(updatedAssignment, taskDir));
                listDirectories(taskDir).forEach(taskFilesDir ->
                        FileUtils.listFiles(taskFilesDir, Extension.listSupported(), true).forEach(localFile ->
                                taskFileRepository.save(createOrUpdateTaskFile(updatedTask, taskDir, localFile))));
            });
        });
        return updatedCourse;
    }
}