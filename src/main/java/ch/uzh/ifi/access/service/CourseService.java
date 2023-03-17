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
import com.github.dockerjava.api.exception.NotFoundException;
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
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
        return Optional.ofNullable(userId).orElseGet(() -> SecurityContextHolder.getContext().getAuthentication().getName());
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

    public Task getTaskByURL(String courseURL, String assignmentURL, String taskURL) {
        return taskRepository.getByAssignment_Course_UrlAndAssignment_UrlAndUrl(courseURL, assignmentURL, taskURL).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No task found with the URL " + taskURL));
    }

    public TaskFile getTaskFileById(Long fileId) {
        return taskFileRepository.findById(fileId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No task file found with the ID " + fileId));
    }

    public TemplateFile getTemplateFileById(Long templateId) {
        return templateFileRepository.findById(templateId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No template file found with the ID " + templateId));
    }

    public List<TemplateOverview> getTemplateFiles() {
        return templateFileRepository.findAllByOrderByPath();
    }

    public List<CourseOverview> getCourses() {
        return courseRepository.findCoursesBy();
    }

    public CourseSummary getCourseSummary(String courseURL) {
        return courseRepository.findCourseByUrl(courseURL).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL " + courseURL));
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

    public TaskInfo getTask(String courseURL, String assignmentURL, String taskURL) {
        return taskRepository.queryByAssignment_Course_UrlAndAssignment_UrlAndUrl(courseURL, assignmentURL, taskURL)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No task found with the URL " + taskURL));
    }

    public TaskWorkspace getTask(String courseURL, String assignmentURL, String taskURL, String userId) {
        TaskWorkspace workspace = taskRepository.findByAssignment_Course_UrlAndAssignment_UrlAndUrl(
                courseURL, assignmentURL, taskURL).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No task found with the URL: %s/%s/%s".formatted(courseURL, assignmentURL, taskURL)));
        workspace.setUserId(userId);
        return workspace;
    }

    public List<TaskFileOverview> getTaskFiles(Long taskId) {
        return taskFileRepository.findByTask_IdAndEnabledTrueOrderByIdAscPathAsc(taskId);
    }

    public List<TaskFileInfo> getTaskFilesInfo(Long taskId) {
        return taskFileRepository.getByTask_IdAndEnabledTrue(taskId);
    }

    public List<TaskFile> getTaskFilesByContext(Long taskId, boolean isGrading) {
        return taskFileRepository.findByTask_IdAndEnabledTrue(taskId).stream().filter(file -> file.inContext(isGrading)).toList();
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
        return evaluationRepository.getTopByTask_IdAndUserIdOrderById(taskId, verifyUserId(userId));
    }

    public Integer getRemainingAttempts(Long taskId, String userId, Integer maxAttempts) {
        return getEvaluation(taskId, verifyUserId(userId)).map(Evaluation::getRemainingAttempts).orElse(maxAttempts);
    }

    public LocalDateTime getNextAttemptAt(Long taskId, String userId) {
        return getEvaluation(taskId, verifyUserId(userId)).map(Evaluation::getNextAttemptAt).orElse(null);
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

    public List<MemberOverview> getTeamMembers(List<String> memberIds) {
        return memberIds.stream().map(memberId -> courseRepository.getTeamMemberName(memberId)).toList();
    }

    private Optional<EvaluationSummary> getTaskProgress(Task task, String userId) {
        return evaluationRepository.findTopByTask_IdAndUserIdOrderById(task.getId(), userId);
    }

    public EvaluationSummary getTaskProgress(String courseURL, String assignmentURL, String taskURL, String userId) {
        return getTaskProgress(getTaskByURL(courseURL, assignmentURL, taskURL), userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No submissions found for " + userId));
    }

    private List<EvaluationSummary> getTasksProgress(Assignment assignment, String userId) {
        return assignment.getTasks().stream().map(task ->
                getTaskProgress(task, userId).orElse(null)).filter(Objects::nonNull).toList();
    }

    public AssignmentProgressDTO getAssignmentProgress(String courseURL, String assignmentURL, String userId) {
        Assignment assignment = getAssignmentByURL(courseURL, assignmentURL);
        return new AssignmentProgressDTO(userId, assignmentURL, getTasksProgress(assignment, userId));
    }

    public CourseProgressDTO getCourseProgress(String courseURL, String userId) {
        Course course = getCourseByURL(courseURL);
        return new CourseProgressDTO(userId, course.getAssignments().stream().map(assignment ->
                new AssignmentProgressDTO(assignment.getUrl(), getTasksProgress(assignment, userId))).toList());
    }

    public StudentDTO getStudent(String courseURL, UserRepresentation user) {
        Double coursePoints = calculateCoursePoints(getCourseByURL(courseURL).getAssignments(), user.getEmail());
        return new StudentDTO(user.getFirstName(), user.getLastName(), user.getEmail(), coursePoints);
    }

    public List<StudentDTO> getStudents(String courseURL) {
        return accessRealm.roles().get(Role.STUDENT.withCourse(courseURL)).getRoleUserMembers(0, 1000)
                .stream().map(student -> getStudent(courseURL, student)).toList();
    }

    private void createSubmissionFile(Submission submission, SubmissionFileDTO fileDTO) {
        SubmissionFile newSubmissionFile = new SubmissionFile();
        newSubmissionFile.setSubmission(submission);
        newSubmissionFile.setContent(fileDTO.getContent());
        newSubmissionFile.setTaskFile(getTaskFileById(fileDTO.getTaskFileId()));
        submission.getFiles().add(newSubmissionFile);
        submissionRepository.saveAndFlush(submission);
    }

    public void createSubmission(String courseURL, String assignmentURL, String taskURL, SubmissionDTO submissionDTO) {
        Task task = getTaskByURL(courseURL, assignmentURL, taskURL);
        if (!task.hasCommand(submissionDTO.getCommand()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Submission rejected - no %s command!".formatted(submissionDTO.getCommand()));
        Evaluation evaluation = getEvaluation(task.getId(), submissionDTO.getUserId())
                .orElseGet(() -> task.createEvaluation(submissionDTO.getUserId()));
        Submission newSubmission = evaluation.addSubmission(modelMapper.map(submissionDTO, Submission.class));
        if (submissionDTO.isRestricted() && newSubmission.isGraded()) {
            if (!task.getAssignment().isActive())
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Submission rejected - assignment is not active!");
            if (evaluation.getRemainingAttempts() <= 0)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Submission rejected - no remaining attempts!");
        }
        Submission submission = submissionRepository.saveAndFlush(newSubmission);
        submissionDTO.getFiles().stream().filter(fileDTO -> Objects.nonNull(fileDTO.getContent()))
                .forEach(fileDTO -> createSubmissionFile(submission, fileDTO));
        submission.setValid(!submission.isGraded());
        try (CreateContainerCmd containerCmd = dockerClient.createContainerCmd(task.getDockerImage()).withEntrypoint("containerd")) {
            Path submissionDir = workingDir.resolve("submissions").resolve(submission.getId().toString());
            getTaskFilesByContext(task.getId(), submission.isGraded())
                    .forEach(file -> createLocalFile(submissionDir, file.getPath(), file.getTemplate().getContent()));
            submission.getFiles().forEach(file -> createLocalFile(submissionDir, file.getTaskFile().getPath(), file.getContent()));
            CreateContainerResponse container = containerCmd
                    .withLabels(Map.of("userId", submission.getUserId())).withWorkingDir(submissionDir.toString())
                    .withCmd("/bin/bash", "-c", task.formCommand(submission.getCommand()) + " &> logs.txt")
                    .withHostConfig(new HostConfig().withMemory(536870912L).withPrivileged(true)
                            .withBinds(Bind.parse(submissionDir + ":" + submissionDir))).exec();
            dockerClient.startContainerCmd(container.getId()).exec();
            Integer statusCode = dockerClient.waitContainerCmd(container.getId())
                    .exec(new WaitContainerResultCallback()).awaitStatusCode(Math.min(task.getTimeLimit(), 180), TimeUnit.SECONDS);
            log.info("Container {} finished with status {}", container.getId(), statusCode);
            submission.setLogs(readLogsFile(submissionDir));
            if (newSubmission.isGraded())
                newSubmission.parseResults(readResultsFile(submissionDir));
            FileUtils.deleteQuietly(submissionDir.toFile());
        } catch (Exception e) {
            newSubmission.setOutput(e.getMessage().contains("timeout") ? "Time limit exceeded" : e.getMessage());
        }
        submissionRepository.save(newSubmission);
    }

    @SneakyThrows
    private void createLocalFile(Path submissionDir, String relativeFilePath, String content) {
        Path filePath = submissionDir.resolve(relativeFilePath);
        Files.createDirectories(filePath.getParent());
        if (!filePath.toFile().exists())
            Files.createFile(filePath);
        Files.writeString(filePath, content);
    }

    private String readLogsFile(Path path) throws IOException {
        File logsFile = path.resolve("logs.txt").toFile();
        if (!logsFile.exists())
            return null;
        return FileUtils.readLines(logsFile, Charset.defaultCharset()).stream()
                .limit(50).collect(Collectors.joining(Strings.LINE_SEPARATOR));
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
        TemplateFile file = templateFileRepository.findByPath(filePath).orElseGet(TemplateFile::new);
        file.setPath(filePath);
        Path localPath = parentPath.resolve(filePath);
        try {
            String uri = "data:%s;base64,".formatted(tika.detect(localPath));
            file.setImage(StringUtils.contains(uri, "image"));
            file.setContent(file.isImage() ? uri + readImage(localPath) : Files.readString(localPath));
            file.setLanguage(readLanguage(filePath));
            file.setName(localPath.getFileName().toString());
            file.setUpdatedAt(LocalDateTime.now());
            templateFileRepository.save(file);
            log.info("Parsed file: {}", file.getPath());
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to parse template files: " + e.getMessage());
        }
    }

    public void createOrUpdateTaskFile(Task task, TaskFileDTO fileDTO) {
        TaskFile taskFile = taskFileRepository.findByTask_IdAndTemplate_Id(task.getId(), fileDTO.getTemplateId())
                .orElseGet(() -> task.createFile(getTemplateFileById(fileDTO.getTemplateId())));
        modelMapper.map(fileDTO, taskFile);
        taskFileRepository.save(taskFile);
    }

    public void createOrUpdateTask(String courseURL, String assignmentURL, String taskURL, TaskDTO taskDTO) {
        Task task = taskRepository.getByAssignment_Course_UrlAndAssignment_UrlAndUrl(courseURL, assignmentURL, taskURL)
                .orElseGet(getAssignmentByURL(courseURL, assignmentURL)::createTask);
        modelMapper.map(taskDTO, task);
        task.setTestCommand(taskDTO.getTestCommand());
        task.setAttemptWindow(Optional.ofNullable(taskDTO.getAttemptRefill()).filter(refill -> refill > 0)
                .map(refill -> Duration.of(refill, ChronoUnit.HOURS)).orElse(null));
        task.getFiles().forEach(file -> file.setEnabled(false));
        Task savedTask = taskRepository.save(task);
        taskDTO.getFiles().forEach(fileDTO -> createOrUpdateTaskFile(savedTask, fileDTO));
        pullDockerImage(task.getDockerImage());
    }

    public void createOrUpdateTask(String courseURL, String assignmentURL, TaskDTO taskDTO) {
        createOrUpdateTask(courseURL, assignmentURL, taskDTO.getUrl(), taskDTO);
    }

    public void createOrUpdateAssignment(String courseURL, String assignmentURL, AssignmentDTO assignmentDTO) {
        Assignment assignment = assignmentRepository.getByCourse_UrlAndUrl(courseURL, assignmentURL)
                .orElseGet(getCourseByURL(courseURL)::createAssignment);
        modelMapper.map(assignmentDTO, assignment);
        assignmentRepository.save(assignment);
    }

    public void createOrUpdateAssignment(String courseURL, AssignmentDTO assignmentDTO) {
        createOrUpdateAssignment(courseURL, assignmentDTO.getUrl(), assignmentDTO);
    }

    public void createOrUpdateCourse(CourseDTO courseDTO) {
        Course course = courseRepository.getByUrl(courseDTO.getUrl()).orElseGet(Course::new);
        modelMapper.map(courseDTO, course);
        course.setStudentRole(createCourseRoles(course.getUrl()));
        course.setAssistants(registerMember(courseDTO.getAssistants(), course.getUrl(), Role.ASSISTANT));
        course.setSupervisors(registerMember(courseDTO.getSupervisors(), course.getUrl(), Role.SUPERVISOR));
        courseRepository.save(course);
    }

    public void importCourse(String courseURL, CourseDTO courseDTO) {
        if (!courseURL.equals(courseDTO.getUrl()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The course URL does not match the current course");
        createOrUpdateCourse(courseDTO);
        courseDTO.getAssignments().forEach(assignmentDTO -> {
            createOrUpdateAssignment(courseDTO.getUrl(), assignmentDTO);
            assignmentDTO.getTasks().forEach(taskDTO -> createOrUpdateTask(courseURL, assignmentDTO.getUrl(), taskDTO));
        });
    }

    public String createCourseRoles(String courseURL) {
        String studentRole = Role.STUDENT.withCourse(courseURL);
        return accessRealm.roles().list(courseURL, true).stream()
                .filter(role -> role.getName().equals(studentRole)).findFirst().orElseGet(() -> {
                    RoleRepresentation basicCourseRole = new RoleRepresentation();
                    basicCourseRole.setName(courseURL);
                    accessRealm.roles().create(basicCourseRole);
                    Arrays.stream(Role.values()).forEach(role -> {
                        RoleRepresentation userRole = new RoleRepresentation();
                        userRole.setName(role.withCourse(courseURL));
                        userRole.setComposite(true);
                        RoleRepresentation.Composites userRoleComposites = new RoleRepresentation.Composites();
                        Set<String> associatedRoles = SetUtils.hashSet(courseURL, role.getName());
                        role.getSubRole().ifPresent(subRole -> associatedRoles.add(subRole.withCourse(courseURL)));
                        userRoleComposites.setRealm(associatedRoles);
                        userRole.setComposites(userRoleComposites);
                        accessRealm.roles().create(userRole);
                    });
                    return accessRealm.roles().get(studentRole).toRepresentation();
                }).getId();
    }

    public String registerMember(MemberDTO memberDTO, List<RoleRepresentation> rolesToAssign) {
        UserResource member = accessRealm.users().search(memberDTO.getEmail()).stream().findFirst().map(user -> {
                    UserResource userResource = accessRealm.users().get(user.getId());
                    Map<String, List<String>> attributes = Optional.ofNullable(user.getAttributes()).orElseGet(HashMap::new);
                    if (!attributes.containsKey("displayName")) {
                        attributes.put("displayName", List.of(memberDTO.getName()));
                        user.setAttributes(attributes);
                        userResource.update(user);
                    }
                    return userResource;
                })
                .orElseGet(() -> {
                    UserRepresentation newUser = new UserRepresentation();
                    newUser.setEmail(memberDTO.getEmail());
                    newUser.setEnabled(true);
                    newUser.setEmailVerified(true);
                    newUser.setAttributes(Map.of("displayName", List.of(memberDTO.getName())));
                    return accessRealm.users().get(CreatedResponseUtil.getCreatedId(accessRealm.users().create(newUser)));
                });
        member.roles().realmLevel().add(rolesToAssign);
        return memberDTO.getEmail();
    }

    public void registerMember(String email, List<RoleRepresentation> rolesToAssign) {
        UserResource member = accessRealm.users().search(email).stream().findFirst()
                .map(user -> accessRealm.users().get(user.getId()))
                .orElseGet(() -> {
                    UserRepresentation newUser = new UserRepresentation();
                    newUser.setEmail(email);
                    newUser.setEnabled(true);
                    newUser.setEmailVerified(true);
                    return accessRealm.users().get(CreatedResponseUtil.getCreatedId(accessRealm.users().create(newUser)));
                });
        member.roles().realmLevel().add(rolesToAssign);
    }

    public List<String> registerMember(List<MemberDTO> newMembers, String courseURL, Role role) {
        RoleResource realmRole = accessRealm.roles().get(role.withCourse(courseURL));
        Set<UserRepresentation> existingMembers = realmRole.getRoleUserMembers();
        List<RoleRepresentation> rolesToAssign = List.of(realmRole.toRepresentation());
        return new ArrayList<>(newMembers.stream().map(memberDTO -> existingMembers.stream()
                .map(UserRepresentation::getEmail).filter(email -> email.equals(memberDTO.getEmail())).findFirst()
                .orElseGet(() -> registerMember(memberDTO, rolesToAssign))).toList());
    }

    public void registerSupervisor(String courseURL, String supervisor) {
        RoleRepresentation role = accessRealm.roles().get(Role.SUPERVISOR.withCourse(courseURL)).toRepresentation();
        registerMember(new MemberDTO(supervisor, supervisor), List.of(role));
    }

    public void registerParticipants(String courseURL, List<String> students) {
        RoleResource role = accessRealm.roles().get(Role.STUDENT.withCourse(courseURL));
        List<RoleRepresentation> rolesToAdd = List.of(role.toRepresentation());
        role.getRoleUserMembers().stream()
                .filter(member -> students.stream().noneMatch(student -> student.equals(member.getEmail())))
                .forEach(member -> accessRealm.users().get(member.getId()).roles().realmLevel().remove(rolesToAdd));
        students.forEach(student -> registerMember(student, rolesToAdd));
    }

    private void pullDockerImage(String imageName) {
        try {
            dockerClient.pullImageCmd(imageName).start().awaitCompletion().onComplete();
        } catch (NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Docker image not found, please try again");
        } catch (Exception e) {
            log.error("Failed to pull docker image due to unexpected exception", e);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Failed to pull docker image, please try again");
        }
    }

    public String encodeContent(String content) {
        return Base64.encodeBase64String(content.getBytes());
    }

    public void sendMessage(ContactDTO contactDTO) {
        createLocalFile(workingDir.resolve("contact"),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), contactDTO.formatContent());
    }
}
