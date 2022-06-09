package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.config.AccessProperties;
import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.SubmissionFile;
import ch.uzh.ifi.access.model.TaskFile;
import ch.uzh.ifi.access.model.constants.Extension;
import ch.uzh.ifi.access.model.constants.FilePermission;
import ch.uzh.ifi.access.model.dao.TestResults;
import ch.uzh.ifi.access.repository.SubmissionRepository;
import ch.uzh.ifi.access.repository.TaskFileRepository;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ulimit;
import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.transaction.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
@AllArgsConstructor
public class EvaluationService {

    private AccessProperties accessProperties;

    private DockerClient dockerClient;

    private TaskFileRepository taskFileRepository;

    private SubmissionRepository submissionRepository;

    @Transactional
    public Submission initEvaluation(Submission submission) {
        Submission evaluatedSubmission = switch (submission.getTask().getType()) {
            case CODE -> runCode(submission);
            case TEXT -> gradeTextAnswer(submission);
            default -> throw new NotImplementedException();
        };
        return submissionRepository.save(evaluatedSubmission);
    }

    public void createLocalFile(Path filePath, String content) {
        try {
            Files.createDirectories(filePath.getParent());
            if (!filePath.toFile().exists())
                Files.createFile(filePath);
            Files.writeString(filePath, content);
        } catch (IOException e) {
            log.info("Failed to write file at {}", filePath.toAbsolutePath());
        }
    }

    public void createLocalEvaluationFiles(Long taskId, Path submissionDir) {
        taskFileRepository.findByTask_IdAndPermissionIn(taskId, List.of(FilePermission.GRADING, FilePermission.READ_ONLY))
                .stream().filter(file -> !file.isImage()).forEach(file ->
                        createLocalFile(submissionDir.resolve(file.getPath()), file.getTemplate()));
    }

    public Path createLocalSubmissionDir(Long submissionId, List<SubmissionFile> files) {
        Path submissionDir = Paths.get(accessProperties.getWorkingDir(), submissionId.toString());
        files.forEach(file -> createLocalFile(submissionDir.resolve(file.getTaskFile().getPath()), file.getContent()));
        return submissionDir;
    }

    private String createGradingSetup(Path submissionDir) {
        String scriptFilename = "grading_setup.py";
        String script = """
from unittest import TestCase, TextTestRunner, TestLoader
import json

with open('test_results.json', 'w') as statsLog:
    results = TextTestRunner(verbosity=2).run(TestLoader().discover(start_dir='private'))
    json.dump(vars(results), statsLog, default=lambda o: None)
""";
        createLocalFile(submissionDir.resolve(scriptFilename), script);
        return "python " + scriptFilename;
    }

    private TestResults readTestResults(Path path) {
        try {
            return new Gson().fromJson(Files.newBufferedReader(path), TestResults.class);
        } catch (IOException e) {
            log.info("Failed to read test results file at {}", path.toAbsolutePath());
            return null;
        }
    }

    public Submission runCode(Submission submission) {
        Extension taskExtension = submission.getTask().getExtension();
        try (CreateContainerCmd containerCmd = dockerClient.createContainerCmd(taskExtension.getDockerImage())) {
            Path submissionDir = createLocalSubmissionDir(submission.getId(), submission.getFiles());
            if (submission.isGraded()) {
                submission.setPoints(0.0);
                createLocalEvaluationFiles(submission.getTask().getId(), submissionDir);
            }
            String command = Optional.ofNullable(submission.formCommand())
                    .orElseGet(() -> createGradingSetup(submissionDir));
            CreateContainerResponse container = containerCmd.withWorkingDir(submissionDir.toString())
                    .withCmd("/bin/bash", "-c", command + " &> stdout.txt")
                    .withHostConfig(new HostConfig().withMemory(64000000L).withCpuQuota(100000L)
                            .withBinds(Bind.parse(submissionDir + ":" + submissionDir))
                            .withUlimits(List.of(new Ulimit("cpu", 60L, 60L)))).exec();
            dockerClient.startContainerCmd(container.getId()).exec();
            Integer statusCode = dockerClient.waitContainerCmd(container.getId())
                    .exec(new WaitContainerResultCallback()).awaitStatusCode();
            log.info("Container {} finished with status {}", container.getId(), statusCode);
            Path stdOutPath = submissionDir.resolve("stdout.txt");
            Path testResultsPath = submissionDir.resolve("test_results.json");
            if (statusCode == 137)
                submission.setHint("Memory Limit Exceeded");
            else {
                if (stdOutPath.toFile().exists())
                    submission.parseStdOut(Files.readString(stdOutPath));
                if (submission.isGraded())
                    submission.calculatePoints(readTestResults(testResultsPath));
            }
        } catch (DockerClientException | IOException exception) {
            submission.parseException(exception);
        }
        submission.setValid(true);
        return submission;
    }

    private TaskFile getTextTaskSolutions(Long taskId) {
        return taskFileRepository.findTopByTask_IdAndPermissionOrderByIdDesc(taskId, FilePermission.SOLUTION)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No solutions found for task ID " + taskId));
    }

    public Submission gradeTextAnswer(Submission submission) {
        TaskFile solutionsFile = getTextTaskSolutions(submission.getTask().getId());
        Pattern patternSolution = Pattern.compile(StringUtils.join(solutionsFile.getSolutions(), "|"));
        String answer = StringUtils.trimToEmpty(submission.getAnswer());
        if (patternSolution.matcher(answer).matches())
            submission.setPoints(submission.getTask().getMaxPoints());
        else {
            submission.setPoints(0.0);
            submission.setHint(solutionsFile.getHints().stream().findFirst().orElse("Incorrect answer"));
        }
        submission.setValid(true);
        return submission;
    }
}
