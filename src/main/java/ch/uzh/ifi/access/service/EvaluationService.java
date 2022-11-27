package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.model.Evaluator;
import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.TaskFile;
import ch.uzh.ifi.access.model.dao.GradeResults;
import ch.uzh.ifi.access.repository.SubmissionRepository;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.util.Strings;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class EvaluationService {

    private String submissionsDir;

    private DockerClient dockerClient;

    private SubmissionRepository submissionRepository;

    private void createLocalFile(Path submissionDir, String relativeFilePath, String content) {
        Path filePath = submissionDir.resolve(relativeFilePath);
        try {
            Files.createDirectories(filePath.getParent());
            if (!filePath.toFile().exists())
                Files.createFile(filePath);
            Files.writeString(filePath, content);
        } catch (IOException | NullPointerException e) {
            log.error("Failed to create file at {}", filePath.toAbsolutePath(), e);
        }
    }

    private Path createLocalSubmissionDir(Submission submission) {
        Path submissionDir = Paths.get(submissionsDir, submission.getId().toString());
        if (submission.isGraded())
            submission.getTask().getFiles().stream().filter(TaskFile::isEnabled).filter(TaskFile::isGrading)
                    .forEach(file -> createLocalFile(submissionDir, file.getPath(), file.getTemplate()));
        submission.getFiles().forEach(file -> createLocalFile(submissionDir, file.getTaskFile().getPath(), file.getContent()));
        return submissionDir;
    }

    @Transactional
    public Submission evaluateSubmission(Submission submission) {
        Evaluator evaluator = submission.getTask().getEvaluator();
        try (CreateContainerCmd containerCmd = dockerClient.createContainerCmd(evaluator.getDockerImage())) {
            Path submissionDir = createLocalSubmissionDir(submission);
            CreateContainerResponse container = containerCmd.withNetworkDisabled(true)
                    .withLabels(Map.of("userId", submission.getUserId())).withWorkingDir(submissionDir.toString())
                    .withCmd("/bin/bash", "-c", evaluator.formCommand(submission.getType()) + " &> logs.txt")
                    .withHostConfig(new HostConfig().withMemory(536870912L).withPrivileged(true).withAutoRemove(true)
                            .withBinds(Bind.parse(submissionDir + ":" + submissionDir))).exec();
            dockerClient.startContainerCmd(container.getId()).exec();
            Integer statusCode = dockerClient.waitContainerCmd(container.getId())
                    .exec(new WaitContainerResultCallback()).awaitStatusCode(2, TimeUnit.MINUTES);
            log.info("Container {} finished with status {}", container.getId(), statusCode);
            if (statusCode == 137) {
                submission.setOutput("Memory Limit Exceeded");
                submission.setPoints(0.0);
            }
            submission.setLogs(FileUtils.readLines(submissionDir.resolve("logs.txt").toFile(),
                    Charset.defaultCharset()).stream().limit(50).collect(Collectors.joining(Strings.LINE_SEPARATOR)));
            if (submission.isGraded()) {
                Path resultsPath = submissionDir.resolve(evaluator.getGradeResults());
                GradeResults results = new Gson().fromJson(Files.newBufferedReader(resultsPath), GradeResults.class);
                results.getHints().stream().findFirst().ifPresent(submission::setOutput);
                submission.setPoints(results.getPoints());
            }
            FileUtils.deleteDirectory(submissionDir.toFile());
        } catch (IOException exception) {
            log.error("Failed to read evaluation logs or results: {}", exception.getMessage());
        } catch (DockerClientException exception) {
            submission.setOutput("Execution error");
            if (exception.getMessage().contains("timeout")) {
                submission.setOutput("Time Limit Exceeded");
                submission.setPoints(0.0);
            }
        }
        submission.setValid(!submission.isGraded() || Objects.nonNull(submission.getPoints()));
        return submissionRepository.save(submission);
    }
}
