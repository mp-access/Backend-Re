package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.model.*;
import ch.uzh.ifi.access.model.constants.Command;
import ch.uzh.ifi.access.model.constants.Role;
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
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@AllArgsConstructor
public class CourseService {
    private Path workingDir;
    private CourseRepository courseRepository;
    private AssignmentRepository assignmentRepository;
    private TaskRepository taskRepository;
    private TaskFileRepository taskFileRepository;
    private SubmissionRepository submissionRepository;
    private SubmissionFileRepository submissionFileRepository;
    private TemplateFileRepository templateFileRepository;
    private EvaluationRepository evaluationRepository;
    private DockerClient dockerClient;
    private RealmResource accessRealm;
    private ModelMapper modelMapper;
    private JsonMapper jsonMapper;
    private Tika tika;

    private String verifyUserId(@Nullable String userId) {
        return Optional.ofNullable(userId).orElse(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    public void existsCourseByURL(String courseURL) {
        if (courseRepository.existsByUrl(courseURL))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The course URL is not available");
    }

    public Course getCourseByURL(String courseURL) {
        return courseRepository.getByUrl(courseURL).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL " + courseURL));
    }

    public void existsAssignmentByURL(String courseURL, String assignmentURL) {
        if (assignmentRepository.existsByCourse_UrlAndUrl(courseURL, assignmentURL))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The assignment URL is not available");
    }

    public Assignment getAssignmentByURL(String courseURL, String assignmentURL) {
        return assignmentRepository.getByCourse_UrlAndUrl(courseURL, assignmentURL).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No assignment found with the URL " + assignmentURL));
    }

    public void existsTaskByURL(String courseURL, String assignmentURL, String taskURL) {
        if (taskRepository.existsByAssignment_Course_UrlAndAssignment_UrlAndUrl(courseURL, assignmentURL, taskURL))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The task URL is not available");
    }

    public Task getTaskById(Long taskId) {
        return taskRepository.findById(taskId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No task found with the ID " + taskId));
    }

    public TaskFile getTaskFileById(Long fileId) {
        return taskFileRepository.findById(fileId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No task file found with the ID " + fileId));
    }

    public TemplateFile getTemplateFileByPath(String filePath) {
        return templateFileRepository.findByPath(filePath).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No template file found at " + filePath));
    }

    public List<TemplateFile> getTemplateFiles() {
        return templateFileRepository.findAll(Sort.by("path"));
    }

    public List<CourseOverview> getCourses() {
        return courseRepository.findCoursesBy();
    }

    public CourseWorkspace getCourse(String courseURL) {
        return courseRepository.findByUrl(courseURL).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL " + courseURL));
    }

    public List<AssignmentWorkspace> getAssignments(String courseURL) {
        return assignmentRepository.findByCourse_UrlOrderByOrdinalNumDesc(courseURL);
    }

    public AssignmentWorkspace getAssignment(String courseURL, String assignmentURL) {
        return assignmentRepository.findByCourse_UrlAndUrl(courseURL, assignmentURL)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No assignment found with the URL " + assignmentURL));
    }

    public TaskWorkspace getTask(String courseURL, String assignmentURL, String taskURL, String userId) {
        TaskWorkspace workspace = taskRepository.findByAssignment_Course_UrlAndAssignment_UrlAndUrl(
                courseURL, assignmentURL, taskURL).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No task found with the URL: %s/%s/%s".formatted(courseURL, assignmentURL, taskURL)));
        workspace.setUserId(userId);
        return workspace;
    }

    public List<TaskFileOverview> getTaskFiles(Long taskId) {
        return taskFileRepository.findByTask_IdOrderByIdAscPathAsc(taskId);
    }

    public List<TaskFile> getTaskFilesByContext(Long taskId, boolean isGrading) {
        return taskFileRepository.findByTask_Id(taskId).stream().filter(file -> file.inContext(isGrading)).toList();
    }

    public String getLatestContent(Long fileId, String userId) {
        return submissionFileRepository.findTopByTaskFile_IdAndSubmission_UserIdOrderByIdDesc(fileId, userId)
                .map(SubmissionFile::getContent).orElse(null);
    }

    public List<Submission> getSubmissions(Long taskId, String userId) {
        List<Submission> unrestricted = submissionRepository.findByEvaluation_Task_IdAndUserId(taskId, userId);
        unrestricted.forEach(submission -> Optional.ofNullable(submission.getLogs()).ifPresent(submission::setOutput));
        List<Submission> restricted = submissionRepository.findByEvaluation_Task_IdAndUserIdAndCommand(taskId, userId, Command.GRADE);
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

    public Event createEvent(Integer ordinalNum, LocalDateTime date, String category) {
        Event newEvent = new Event();
        newEvent.setDate(date);
        newEvent.setCategory(category);
        newEvent.setDescription("Assignment %s is %s.".formatted(ordinalNum, category));
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
        return getAssignments(courseURL).stream().mapToDouble(AssignmentWorkspace::getMaxPoints).sum();
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
        Task task = submission.getEvaluation().getTask();
        try (CreateContainerCmd containerCmd = dockerClient.createContainerCmd(task.getDockerImage())) {
            Path submissionDir = workingDir.resolve("submissions").resolve(submission.getId().toString());
            log.info("Abs: {}, root: {}, name: {}", submissionDir.toAbsolutePath(), submissionDir.getRoot(), submissionDir.getFileName());
            getTaskFilesByContext(task.getId(), submission.isGraded())
                    .forEach(file -> createLocalFile(submissionDir, file.getPath(), file.getTemplate().getContent()));
            submission.getFiles().forEach(file -> createLocalFile(submissionDir, file.getTaskFile().getPath(), file.getContent()));
            CreateContainerResponse container = containerCmd.withNetworkDisabled(true)
                    .withLabels(Map.of("userId", submission.getUserId())).withWorkingDir(submissionDir.toString())
                    .withCmd("/bin/bash", "-c", task.formCommand(submission.getCommand()) + " &> logs.txt")
                    .withHostConfig(new HostConfig().withMemory(536870912L).withPrivileged(true).withAutoRemove(true)
                            .withBinds(Bind.parse(submissionDir + ":" + submissionDir))).exec();
            dockerClient.startContainerCmd(container.getId()).exec();
            Integer statusCode = dockerClient.waitContainerCmd(container.getId())
                    .exec(new WaitContainerResultCallback()).awaitStatusCode(30, TimeUnit.SECONDS);
            log.info("Container {} finished with status {}", container.getId(), statusCode);
            submission.setLogs(FileUtils.readLines(submissionDir.resolve("logs.txt").toFile(),
                    Charset.defaultCharset()).stream().limit(50).collect(Collectors.joining(Strings.LINE_SEPARATOR)));
            if (submission.isGraded())
                submission.parseResults(readResultsFile(submissionDir));
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

    private Results readResultsFile(Path path) throws IOException {
        return jsonMapper.readValue(Files.readString(path.resolve("grade_results.json")), Results.class);
    }

    private String readLanguage(String filePath) {
        return Optional.of(FileNameUtils.getExtension(filePath))
                .map(extension -> extension.equals("py") ? "python" : extension).orElse("");
    }

    private String readImage(Path path) throws IOException {
        return Base64.encodeBase64String(Files.readAllBytes(path));
    }

    private void readContent(Path parentPath, String filePath) {
        TemplateFile file = templateFileRepository.findByPath(filePath).orElse(new TemplateFile());
        file.setPath(filePath);
        Path localPath = parentPath.resolve(filePath);
        try {
            String uri = "data:%s;base64,".formatted(tika.detect(localPath));
            file.setImage(StringUtils.contains(uri, "image"));
            file.setContent(file.isImage() ? uri + readImage(localPath) : Files.readString(localPath));
            file.setLanguage(readLanguage(filePath));
            file.setName(localPath.getFileName().toString());
            templateFileRepository.save(file);
        } catch (IOException e) {
            log.error("Failed to read file at: {}", localPath);
        }
    }

    public void createOrUpdateTemplateFiles(List<String> filePaths) {
        Path templatesPath = workingDir.resolve("templates");
        try (Git templatesRepo = Git.open(templatesPath.toFile())) {
            templatesRepo.pull().call();
            filePaths.forEach(filePath -> readContent(templatesPath, filePath));
        } catch (GitAPIException | IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to parse template file: " + e.getMessage());
        }
    }

    public void createOrUpdateTaskFile(Task task, TaskFileDTO fileDTO) {
        TaskFile taskFile = taskFileRepository.findByTask_IdAndTemplatePath(task.getId(), fileDTO.getTemplatePath())
                .orElseGet(() -> task.createFile(getTemplateFileByPath(fileDTO.getTemplatePath())));
        modelMapper.map(fileDTO, taskFile);
        taskFileRepository.save(taskFile);
    }

    public void createOrUpdateTask(String courseURL, String assignmentURL, TaskDTO taskDTO) {
        Task task = taskRepository.getByAssignment_Course_UrlAndAssignment_UrlAndUrl(courseURL, assignmentURL, taskDTO.getUrl())
                .orElseGet(getAssignmentByURL(courseURL, assignmentURL)::createTask);
        modelMapper.map(taskDTO, task);
        Task savedTask = taskRepository.save(task);
        taskDTO.getFiles().forEach(fileDTO -> createOrUpdateTaskFile(savedTask, fileDTO));
        pullDockerImage(task.getDockerImage());
    }

    public void createOrUpdateAssignment(String courseURL, AssignmentDTO assignmentDTO) {
        Assignment assignment = assignmentRepository.getByCourse_UrlAndUrl(courseURL, assignmentDTO.getUrl())
                .orElseGet(getCourseByURL(courseURL)::createAssignment);
        modelMapper.map(assignmentDTO, assignment);
        assignmentRepository.save(assignment);
        assignmentDTO.getTasks().forEach(taskDTO ->
                createOrUpdateTask(courseURL, assignment.getUrl(), taskDTO));
    }

    public void createOrUpdateCourse(CourseDTO courseDTO) {
        Course course = courseRepository.getByUrl(courseDTO.getUrl()).orElse(new Course());
        modelMapper.map(courseDTO, course);
        courseRepository.save(course);
    }

    public void importCourse(String courseURL, CourseDTO courseDTO) {
        if (!courseURL.equals(courseDTO.getUrl()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The course URL does not match the current course");
        createOrUpdateCourse(courseDTO);
        courseDTO.getAssignments().forEach(assignmentDTO -> createOrUpdateAssignment(courseDTO.getUrl(), assignmentDTO));
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

    public void createCourseRoles(String courseURL) {
        if (accessRealm.roles().list(Role.STUDENT.withCourse(courseURL), true).isEmpty()) {
            RoleRepresentation basicCourseRole = new RoleRepresentation();
            basicCourseRole.setName(courseURL);
            accessRealm.roles().create(basicCourseRole);
            accessRealm.roles().create(createCourseRole(courseURL, Role.STUDENT, null));
            accessRealm.roles().create(createCourseRole(courseURL, Role.ASSISTANT, null));
            accessRealm.roles().create(createCourseRole(courseURL, Role.SUPERVISOR, Role.ASSISTANT));
        }
    }

    public void registerCourseUser(String email, String roleName) {
        RoleRepresentation realmRole = accessRealm.roles().get(roleName).toRepresentation();
        accessRealm.users().search(email).stream().findFirst().ifPresent(user ->
                accessRealm.users().get(user.getId()).roles().realmLevel().add(List.of(realmRole)));
    }

    public void registerCourseSupervisor(String courseURL, String supervisor) {
        registerCourseUser(supervisor, Role.SUPERVISOR.withCourse(courseURL));
    }

    public List<StudentDTO> getStudentsByCourse(String courseURL) {
        return accessRealm.roles().get(Role.STUDENT.withCourse(courseURL)).getRoleUserMembers().stream()
                .map(student -> getStudent(courseURL, student)).toList();
    }

    public List<UserRepresentation> getAssistantsByCourse(String courseURL) {
        return accessRealm.roles().get(Role.ASSISTANT.withCourse(courseURL)).getRoleUserMembers()
                .stream().filter(user -> user.getRealmRoles().stream().noneMatch(roleName ->
                        roleName.equals(Role.SUPERVISOR.withCourse(courseURL)))).toList();
    }

    private void pullDockerImage(String imageName) {
        try {
            dockerClient.pullImageCmd(imageName).start().awaitCompletion().onComplete();
        } catch (InterruptedException e) {
            log.error("Failed to pull docker image {}", imageName);
            Thread.currentThread().interrupt();
        }
    }
}