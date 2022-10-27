package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.config.AccessProperties;
import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.constants.Extension;
import ch.uzh.ifi.access.model.dao.TestResults;
import ch.uzh.ifi.access.repository.SubmissionRepository;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ulimit;
import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

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

    private SubmissionRepository submissionRepository;

    @Transactional
    public Submission initEvaluation(Submission submission) {
        Submission evaluatedSubmission = switch (submission.getTask().getType()) {
            case CODE -> evaluateCode(submission);
            case TEXT -> evaluateTextAnswer(submission);
            default -> throw new NotImplementedException();
        };
        return submissionRepository.save(evaluatedSubmission);
    }

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

    private String createGradingScript(Path submissionDir, Extension extension) {
        String scriptFilename = "default_grading_file.".concat(extension.getName());
        createLocalFile(submissionDir, scriptFilename, extension.getDefaultGradingScript());
        return extension.formGradingCommand(scriptFilename);
    }

    private Path createLocalSubmissionDir(Submission submission) {
        Path submissionDir = Paths.get(accessProperties.getWorkingDir(), submission.getId().toString());
        submission.getTask().getFiles().stream().filter(file -> submission.isGraded() || !file.isRestricted())
                .forEach(file -> createLocalFile(submissionDir, file.getPath(), file.getTemplate()));
        submission.getFiles().forEach(file -> createLocalFile(submissionDir, file.getTaskFile().getPath(), file.getContent()));
        submission.setCommand(Optional.ofNullable(submission.formCommand())
                .orElseGet(() -> createGradingScript(submissionDir, submission.getExtension())));
        return submissionDir;
    }

    private Double readTestResults(Path path) {
        try {
            TestResults results = new Gson().fromJson(Files.newBufferedReader(path), TestResults.class);
            return results.calculateTestsPassedRatio();
        } catch (IOException e) {
            log.info("Failed to read test results file at {}", path.toAbsolutePath());
            return 0.0;
        }
    }

    public Submission evaluateCode(Submission submission) {
        try (CreateContainerCmd containerCmd = dockerClient.createContainerCmd(submission.getExtension().getDockerImage())) {
            Path submissionDir = createLocalSubmissionDir(submission);
            CreateContainerResponse container = containerCmd.withWorkingDir(submissionDir.toString())
                    .withCmd("/bin/bash", "-c", submission.getCommand() + " &> logs.txt")
                    .withHostConfig(new HostConfig().withMemory(64000000L).withCpuQuota(100000L)
                            .withBinds(Bind.parse(submissionDir + ":" + submissionDir))
                            .withUlimits(List.of(new Ulimit("cpu", 60L, 60L)))).exec();
            dockerClient.startContainerCmd(container.getId()).exec();
            Integer statusCode = dockerClient.waitContainerCmd(container.getId())
                    .exec(new WaitContainerResultCallback()).awaitStatusCode();
            log.info("Container {} finished with status {}", container.getId(), statusCode);
            Path logsPath = submissionDir.resolve("logs.txt");
            Path testResultsPath = submissionDir.resolve("test_results.json");
            if (statusCode == 137)
                submission.setHint("Memory Limit Exceeded");
            else {
                if (logsPath.toFile().exists())
                    submission.setLogs(Files.readString(logsPath));
                if (submission.isGraded())
                    submission.calculatePoints(readTestResults(testResultsPath));
            }
            submission.setValid(true);
        } catch (IOException exception) {
            submission.setLogs(StringUtils.join(submission.getLogs(), exception));
            submission.setValid(false);
        }
        return submission;
    }

    public Submission evaluateTextAnswer(Submission submission) {
        Pattern patternSolution = Pattern.compile(submission.getTask().getSolution());
        String answer = StringUtils.trimToEmpty(submission.getAnswer());
        if (patternSolution.matcher(answer).matches())
            submission.setPoints(submission.getTask().getMaxPoints());
        else {
            submission.setPoints(0.0);
            submission.setHint(Optional.of(submission.getTask().getHint()).orElse("Incorrect answer"));
        }
        submission.setValid(true);
        return submission;
    }
}
