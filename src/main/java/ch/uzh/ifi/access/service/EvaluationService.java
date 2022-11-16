package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.model.Course;
import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.TaskEvaluator;
import ch.uzh.ifi.access.model.TaskFile;
import ch.uzh.ifi.access.model.dao.GradeResults;
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
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@Service
@AllArgsConstructor
public class EvaluationService {

    @Value("${WORKING_DIR:/tmp/submissions}")
    private String workingDir;

    private DockerClient dockerClient;

    private SubmissionRepository submissionRepository;

    public void createEvaluators(Course course) {
        course.getAssignments().stream().flatMap(assignment -> assignment.getTasks().stream()
                        .map(task -> task.getEvaluator().getDockerImage())).distinct().filter(StringUtils::isNotBlank)
                .forEach(imageName -> {
                    try {
                        dockerClient.pullImageCmd(imageName).start().awaitCompletion().onComplete();
                    } catch (InterruptedException e) {
                        log.error("Failed to pull docker image {}", imageName);
                        Thread.currentThread().interrupt();
                    }
                });
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

    private Path createLocalSubmissionDir(Submission submission) {
        Path submissionDir = Paths.get(workingDir, submission.getId().toString());
        if (submission.isGraded())
            submission.getTask().getFiles().stream().filter(TaskFile::isGrading)
                    .forEach(file -> createLocalFile(submissionDir, file.getPath(), file.getTemplate()));
        submission.getFiles().forEach(file -> createLocalFile(submissionDir, file.getTaskFile().getPath(), file.getContent()));
        return submissionDir;
    }

    private GradeResults readGradeResults(Path path) {
        try {
            return new Gson().fromJson(Files.newBufferedReader(path), GradeResults.class);
        } catch (IOException e) {
            log.info("Failed to read test results file at {}", path.toAbsolutePath());
            return new GradeResults();
        }
    }

    @Transactional
    public Submission evaluateSubmission(Submission submission) {
        TaskEvaluator evaluator = submission.getTask().getEvaluator();
        try (CreateContainerCmd containerCmd = dockerClient.createContainerCmd(evaluator.getDockerImage())) {
            Path submissionDir = createLocalSubmissionDir(submission);
            CreateContainerResponse container = containerCmd.withWorkingDir(submissionDir.toString())
                    .withCmd("/bin/bash", "-c", evaluator.formCommand(submission.getType()) + " &> logs.txt")
                    .withHostConfig(new HostConfig().withMemory(64000000L).withCpuQuota(100000L).withPrivileged(true)
                            .withBinds(Bind.parse(submissionDir + ":" + submissionDir))
                            .withUlimits(List.of(new Ulimit("cpu", 60L, 60L)))).exec();
            dockerClient.startContainerCmd(container.getId()).exec();
            Integer statusCode = dockerClient.waitContainerCmd(container.getId())
                    .exec(new WaitContainerResultCallback()).awaitStatusCode();
            log.info("Container {} finished with status {}", container.getId(), statusCode);
            Path logsPath = submissionDir.resolve("logs.txt");
            if (logsPath.toFile().exists())
                submission.setLogs(Files.readString(logsPath));
            if (submission.isGraded()) {
                GradeResults results = readGradeResults(submissionDir.resolve(evaluator.getGradeResults()));
                submission.setPoints(results.getPoints());
                submission.setOutput(results.getHints().stream().findFirst().orElse("Memory Limit Exceeded"));
            }
            submission.setValid(true);
        } catch (IOException exception) {
            submission.setLogs(StringUtils.join(submission.getLogs(), exception));
            submission.setValid(false);
        }
        return submissionRepository.save(submission);
    }
}
