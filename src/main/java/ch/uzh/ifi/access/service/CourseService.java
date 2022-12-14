package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.model.*;
import ch.uzh.ifi.access.model.constants.Role;
import ch.uzh.ifi.access.model.constants.SubmissionType;
import ch.uzh.ifi.access.model.dao.Rank;
import ch.uzh.ifi.access.model.dao.Results;
import ch.uzh.ifi.access.model.dto.*;
import ch.uzh.ifi.access.projections.*;
import ch.uzh.ifi.access.repository.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.collections4.SetUtils;
import org.apache.commons.compress.utils.FileNameUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Precision;
import org.apache.logging.log4j.util.Strings;
import org.apache.tika.Tika;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private EvaluationRepository evaluationRepository;
    private DockerClient dockerClient;
    private RealmResource accessRealm;
    private ModelMapper modelMapper;
    private JsonMapper jsonMapper;
    private Tika tika;

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

    public List<TaskFile> getTaskFilesByType(Long taskId, boolean isGrading) {
        return taskFileRepository.findByTask_IdAndEnabledTrue(taskId)
                .stream().filter(file -> file.isPublished() || (file.isGrading() && isGrading)).toList();
    }

    public List<Submission> getSubmissions(Long taskId, String userId) {
        List<Submission> unrestricted = submissionRepository.findByEvaluation_Task_IdAndUserId(taskId, userId);
        unrestricted.forEach(submission -> Optional.ofNullable(submission.getLogs()).ifPresent(submission::setOutput));
        List<Submission> restricted = submissionRepository.findByEvaluation_Task_IdAndUserIdAndType(taskId, userId, SubmissionType.GRADE);
        return Stream.concat(unrestricted.stream(), restricted.stream()).sorted(Comparator.comparingLong(Submission::getId).reversed()).toList();
    }

    public Optional<Evaluation> getEvaluation(Long taskId, String userId) {
        return evaluationRepository.findTopByTask_IdAndUserIdOrderById(taskId, userId);
    }

    public Integer getRemainingAttempts(Long taskId, String userId, Integer maxAttempts) {
        return getEvaluation(taskId, verifyUserId(userId))
                .map(Evaluation::getRemainingAttempts).orElse(maxAttempts);
    }

    public LocalDateTime getNextAttemptAt(Long taskId, String userId) {
        return getEvaluation(taskId, verifyUserId(userId))
                .map(Evaluation::getNextAttemptAt).orElse(null);
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
        return Precision.round(evaluationRepository.findByTask_IdAndBestScoreNotNull(taskId)
                .stream().mapToDouble(Evaluation::getBestScore).average().orElse(0.0), 2);
    }

    public Double calculateTaskPoints(Long taskId, String userId) {
        return getEvaluation(taskId, verifyUserId(userId)).map(Evaluation::getBestScore).orElse(0.0);
    }

    public Double calculateAssignmentPoints(List<Task> tasks, String userId) {
        return tasks.stream().mapToDouble(task -> calculateTaskPoints(task.getId(), userId)).sum();
    }

    public Double calculateCoursePoints(List<Assignment> assignments, String userId) {
        return assignments.stream().mapToDouble(assignment -> calculateAssignmentPoints(assignment.getTasks(), userId)).sum();
    }

    public Double getMaxPoints(String courseURL) {
        return getAssignments(courseURL).stream().mapToDouble(AssignmentOverview::getMaxPoints).sum();
    }

    public Integer getRank(Long courseId) {
        String userId = verifyUserId(null);
        return ListUtils.indexOf(getLeaderboard(courseId), rank -> rank.getEmail().equals(userId)) + 1;
    }

    public List<Rank> getLeaderboard(Long courseId) {
        return evaluationRepository.getCourseRanking(courseId).stream()
                .sorted(Comparator.comparingDouble(Rank::getScore).reversed()).toList();
    }

    public StudentDTO getStudent(String courseURL, UserRepresentation user) {
        Double coursePoints = calculateCoursePoints(getCourseByURL(courseURL).getAssignments(), user.getEmail());
        return new StudentDTO(user.getFirstName(), user.getLastName(), user.getEmail(), coursePoints);
    }

    private Evaluation createEvaluation(Long taskId, String userId) {
        Evaluation newEvaluation = getTaskById(taskId).createEvaluation();
        newEvaluation.setUserId(userId);
        return evaluationRepository.save(newEvaluation);
    }

    private void createSubmissionFile(Submission submission, SubmissionFileDTO fileDTO) {
        SubmissionFile newSubmissionFile = new SubmissionFile();
        newSubmissionFile.setSubmission(submission);
        newSubmissionFile.setContent(fileDTO.getContent());
        newSubmissionFile.setTaskFile(getTaskFileById(fileDTO.getTaskFileId()));
        submission.getFiles().add(newSubmissionFile);
    }

    public Submission createSubmission(SubmissionDTO submissionDTO) {
        Evaluation evaluation = getEvaluation(submissionDTO.getTaskId(), submissionDTO.getUserId())
                .orElseGet(() -> createEvaluation(submissionDTO.getTaskId(), submissionDTO.getUserId()));
        Submission newSubmission = evaluation.addSubmission(modelMapper.map(submissionDTO, Submission.class));
        if (submissionDTO.isRestricted() && newSubmission.isGraded()) {
            if (!evaluation.isActive())
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Submission rejected - task is not active!");
            if (evaluation.getRemainingAttempts() <= 0)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Submission rejected - no remaining attempts!");
        }
        submissionDTO.getFiles().stream().filter(fileDTO -> Objects.nonNull(fileDTO.getContent()))
                .forEach(fileDTO -> createSubmissionFile(newSubmission, fileDTO));
        return submissionRepository.saveAndFlush(newSubmission);
    }

    public void evaluateSubmission(Long taskId, String userId, Long submissionId) {
        Evaluation evaluation = getEvaluation(taskId, userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No evaluation found for " + userId));
        Submission submission = evaluation.getSubmissions().stream().filter(saved -> saved.getId().equals(submissionId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No submission found with ID " + submissionId));
        submission.setValid(!submission.isGraded());
        Evaluator evaluator = submission.getEvaluation().getTask().getEvaluator();
        try (CreateContainerCmd containerCmd = dockerClient.createContainerCmd(evaluator.getDockerImage())) {
            Path submissionDir = Paths.get(workingDir, "submissions", submission.getId().toString());
            getTaskFilesByType(submission.getEvaluation().getTask().getId(), submission.isGraded())
                    .forEach(file -> createLocalFile(submissionDir, file.getPath(), file.getTemplate()));
            submission.getFiles().forEach(file -> createLocalFile(submissionDir, file.getTaskFile().getPath(), file.getContent()));
            CreateContainerResponse container = containerCmd.withNetworkDisabled(true)
                    .withLabels(Map.of("userId", submission.getUserId())).withWorkingDir(submissionDir.toString())
                    .withCmd("/bin/bash", "-c", evaluator.formCommand(submission.getType()) + " &> logs.txt")
                    .withHostConfig(new HostConfig().withMemory(536870912L).withPrivileged(true).withAutoRemove(true)
                            .withBinds(Bind.parse(submissionDir + ":" + submissionDir))).exec();
            dockerClient.startContainerCmd(container.getId()).exec();
            Integer statusCode = dockerClient.waitContainerCmd(container.getId())
                    .exec(new WaitContainerResultCallback()).awaitStatusCode(30, TimeUnit.SECONDS);
            log.info("Container {} finished with status {}", container.getId(), statusCode);
            submission.setLogs(FileUtils.readLines(submissionDir.resolve("logs.txt").toFile(),
                    Charset.defaultCharset()).stream().limit(50).collect(Collectors.joining(Strings.LINE_SEPARATOR)));
            if (submission.isGraded())
                submission.parseResults(readJsonFile(submissionDir, evaluator.getResultsFile(), Results.class));
            FileUtils.deleteQuietly(submissionDir.toFile());
        } catch (Exception e) {
            submission.setOutput(e.getMessage().contains("timeout") ? "Time limit exceeded" : e.getMessage());
        }
        evaluationRepository.saveAndFlush(evaluation);
    }

    @SneakyThrows
    private void createLocalFile(Path submissionDir, String relativeFilePath, String content) {
        Path filePath = submissionDir.resolve(relativeFilePath);
        Files.createDirectories(filePath.getParent());
        if (!filePath.toFile().exists())
            Files.createFile(filePath);
        Files.writeString(filePath, content);
    }

    private Path createLocalRepository(String repository) {
        Path coursePath = Path.of(workingDir, "courses", "course_" + Instant.now().toEpochMilli());
        try {
            Git.cloneRepository().setURI(repository).setDirectory(coursePath.toFile()).call();
            return coursePath;
        } catch (GitAPIException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to clone repository");
        }
    }

    private void pullDockerImage(String imageName) {
        try {
            dockerClient.pullImageCmd(imageName).start().awaitCompletion().onComplete();
        } catch (InterruptedException e) {
            log.error("Failed to pull docker image {}", imageName);
            Thread.currentThread().interrupt();
        }
    }

    private String formatActiveDuration(LocalDateTime startDate, LocalDateTime endDate) {
        String start = startDate.format(DateTimeFormatter.ofPattern("MMM. d, HH:mm"));
        String end = endDate.format(DateTimeFormatter.ofPattern("MMM. d, HH:mm"));
        return "%s ~ %s".formatted(start, end);
    }

    @SneakyThrows
    private <T> T readJsonFile(Path path, String filename, Class<T> targetDTO) {
        return jsonMapper.readValue(Files.readString(path.resolve(filename)), targetDTO);
    }

    private <T> T readConfig(Path localDirPath, Class<T> targetDTO) {
        return readJsonFile(localDirPath, "config.json", targetDTO);
    }

    private String readLanguage(String filePath) {
        String extension = FileNameUtils.getExtension(filePath);
        if (extension.equals("py"))
            return "python";
        return extension;
    }

    private TaskFileDTO readContent(Path path) {
        TaskFileDTO file = new TaskFileDTO();
        try {
            String uri = "data:%s;base64,".formatted(tika.detect(path));
            file.setImage(StringUtils.contains(uri, "image"));
            file.setTemplate(file.isImage() ? uri + Base64.encodeBase64String(Files.readAllBytes(path)) : Files.readString(path));
        } catch (IOException e) {
            log.error("Failed to read file at: {}", path);
        }
        return file;
    }

    private TaskFile createOrUpdateTaskFile(Task task, Path parentPath, String filePath) {
        TaskFile taskFile = task.getFiles().stream()
                .filter(existing -> existing.getPath().equals(filePath)).findFirst()
                .orElseGet(task::createFile);
        if (!taskFile.isEnabled()) {
            Path taskFilePath = parentPath.resolve(filePath);
            modelMapper.map(readContent(taskFilePath), taskFile);
            taskFile.setName(taskFilePath.getFileName().toString());
            taskFile.setLanguage(readLanguage(filePath));
            taskFile.setPath(filePath);
            taskFile.setEnabled(true);
        }
        return taskFile;
    }

    public Course createOrUpdateCourseFromRepository(Course course) {
        Path coursePath = createLocalRepository(course.getRepository());
        CourseDTO courseConfig = readConfig(coursePath, CourseDTO.class);
        modelMapper.map(courseConfig, course);
        course.setRole(createCourseRoles(course.getUrl()));
        course.getAssignments().forEach(assignment -> assignment.setEnabled(false));
        courseConfig.getAssignments().forEach(assignmentDir -> {
            Path assignmentPath = coursePath.resolve(assignmentDir);
            AssignmentDTO assignmentConfig = readConfig(assignmentPath, AssignmentDTO.class);
            Assignment assignment = course.getAssignments().stream()
                    .filter(existing -> existing.getUrl().equals(assignmentConfig.getUrl())).findFirst()
                    .orElseGet(course::createAssignment);
            modelMapper.map(assignmentConfig, assignment);
            assignment.setEnabled(true);
            assignment.setDuration(formatActiveDuration(assignment.getStartDate(), assignment.getEndDate()));
            assignment.getTasks().forEach(task -> task.setEnabled(false));
            assignmentConfig.getTasks().forEach(taskDir -> {
                Path taskPath = assignmentPath.resolve(taskDir);
                TaskDTO taskConfig = readConfig(taskPath, TaskDTO.class);
                Task task = assignment.getTasks().stream()
                        .filter(existing -> existing.getUrl().equals(taskConfig.getUrl())).findFirst()
                        .orElseGet(assignment::createTask);
                pullDockerImage(taskConfig.getEvaluator().getDockerImage());
                modelMapper.map(taskConfig, task);
                task.setEnabled(true);
                task.setInstructions(readContent(taskPath.resolve(taskConfig.getInstructions())).getTemplate());
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
            assignment.setMaxPoints(assignment.getTasks().stream().filter(Task::isEnabled).mapToDouble(Task::getMaxPoints).sum());
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

    private RoleRepresentation createCourseRole(String courseURL, Role mainRole, @Nullable Role subRole) {
        RoleRepresentation userRole = new RoleRepresentation();
        userRole.setName(mainRole.withCourse(courseURL));
        userRole.setComposite(true);
        RoleRepresentation.Composites userRoleComposites = new RoleRepresentation.Composites();
        Set<String> associatedRoles = SetUtils.hashSet(courseURL, mainRole.getName());
        Optional.ofNullable(subRole).ifPresent(role -> associatedRoles.add(role.withCourse(courseURL)));
        userRoleComposites.setRealm(associatedRoles);
        userRole.setComposites(userRoleComposites);
        return userRole;
    }

    public String createCourseRoles(String courseURL) {
        return accessRealm.roles().list(Role.STUDENT.withCourse(courseURL), true)
                .stream().findFirst().orElseGet(() -> {
                    RoleRepresentation basicCourseRole = new RoleRepresentation();
                    basicCourseRole.setName(courseURL);
                    accessRealm.roles().create(basicCourseRole);
                    accessRealm.roles().create(createCourseRole(courseURL, Role.STUDENT, null));
                    accessRealm.roles().create(createCourseRole(courseURL, Role.ASSISTANT, null));
                    accessRealm.roles().create(createCourseRole(courseURL, Role.SUPERVISOR, Role.ASSISTANT));
                    return accessRealm.roles().get(Role.STUDENT.withCourse(courseURL)).toRepresentation();
                }).getId();
    }

    public void registerCourseUser(String email, String roleName) {
        RoleRepresentation realmRole = accessRealm.roles().get(roleName).toRepresentation();
        accessRealm.users().search(email).stream().findFirst().ifPresent(user ->
                accessRealm.users().get(user.getId()).roles().realmLevel().add(List.of(realmRole)));
    }

    public void registerCourseStudent(String courseURL, String student) {
        registerCourseUser(student, Role.STUDENT.withCourse(courseURL));
    }

    public void registerCourseAssistant(String courseURL, String assistant) {
        registerCourseUser(assistant, Role.ASSISTANT.withCourse(courseURL));
    }

    public void registerCourseSupervisor(String courseURL, String supervisor) {
        registerCourseUser(supervisor, Role.SUPERVISOR.withCourse(courseURL));
    }

    public Set<UserRepresentation> getStudentsByCourse(String courseURL) {
        return accessRealm.roles().get(Role.STUDENT.withCourse(courseURL)).getRoleUserMembers();
    }

    public List<UserRepresentation> getAssistantsByCourse(String courseURL) {
        return accessRealm.roles().get(Role.ASSISTANT.withCourse(courseURL)).getRoleUserMembers()
                .stream().filter(user -> user.getRealmRoles().stream().noneMatch(roleName ->
                        roleName.equals(Role.SUPERVISOR.withCourse(courseURL)))).toList();
    }
}