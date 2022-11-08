package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.TestingUtils;
import ch.uzh.ifi.access.config.DockerConfigurer;
import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.SubmissionFile;
import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.model.TaskFile;
import ch.uzh.ifi.access.model.constants.SubmissionType;
import ch.uzh.ifi.access.repository.SubmissionRepository;
import ch.uzh.ifi.access.repository.TaskFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@SpringBootTest(classes = {EvaluationService.class, DockerConfigurer.class})
class EvaluationServiceTests {

    @Autowired
    private EvaluationService evaluationService;

    private Task testTask;

    private Submission testSubmission;

    private SubmissionFile testSubmissionFile;

    @MockBean
    private TaskFileRepository taskFileRepository;

    @MockBean
    private SubmissionRepository submissionRepository;

    @BeforeEach
    void setUp() {
        testSubmission = new Submission();
        testTask = TestingUtils.createTask();
        testSubmission.setTask(testTask);
        testSubmissionFile = new SubmissionFile();
        testSubmissionFile.setTaskFile(testTask.getFiles().get(0));
        testSubmissionFile.setSubmission(testSubmission);
        testSubmission.getFiles().add(testSubmissionFile);
        given(submissionRepository.save(any(Submission.class))).willAnswer(returnsFirstArg());
        given(taskFileRepository.save(any(TaskFile.class))).willAnswer(returnsFirstArg());
        given(taskFileRepository.findByTask_IdOrderByIdAscPathAsc(any())).willReturn(testTask.getFiles());
    }

    @Test
    void gradedSubmissionWithCorrectSolutionTest() {
        testSubmission.setId(10L);
        testSubmission.setType(SubmissionType.GRADE);
        testSubmissionFile.setContent(TestingUtils.SOLUTION_FILE);
        Submission returnedSubmission = evaluationService.evaluateSubmission(testSubmission);
        assertEquals(testSubmission.getId(), returnedSubmission.getId());
        assertEquals(testSubmission.getTask().getMaxPoints(), returnedSubmission.getPoints());
        assertEquals("All tests passed!", returnedSubmission.getOutput());
    }

    @Test
    void gradedSubmissionWithPartialSolutionTest() {
        testSubmission.setId(11L);
        testSubmission.setType(SubmissionType.GRADE);
        testSubmissionFile.setContent(TestingUtils.PARTIAL_SOLUTION_FILE);
        Submission returnedSubmission = evaluationService.evaluateSubmission(testSubmission);
        assertEquals(testSubmission.getId(), returnedSubmission.getId());
        assertEquals("The calculation of fac(0) is not correct!", returnedSubmission.getOutput());
    }

    @Test
    void gradedSubmissionWithoutRunnableContentTest() {
        testSubmission.setId(12L);
        testSubmission.setType(SubmissionType.GRADE);
        testSubmissionFile.setContent("Submission without runnable content");
        Submission returnedSubmission = evaluationService.evaluateSubmission(testSubmission);
        assertEquals(testSubmission.getId(), returnedSubmission.getId());
        assertEquals(0.0, returnedSubmission.getPoints());
        assertEquals("SyntaxError: invalid syntax", returnedSubmission.getOutput());
    }

    @Test
    void ungradedSubmissionWithPartialSolutionTest() {
        testSubmission.setId(20L);
        testSubmission.setType(SubmissionType.RUN);
        testSubmissionFile.setContent(TestingUtils.PARTIAL_SOLUTION_FILE);
        Submission returnedSubmission = evaluationService.evaluateSubmission(testSubmission);
        assertEquals(testSubmission.getId(), returnedSubmission.getId());
        assertEquals("fac(8) = 10\n", returnedSubmission.getLogs());
    }

    @Test
    void ungradedSubmissionTimeoutTest() {
        testSubmission.setId(20L);
        testSubmission.setType(SubmissionType.RUN);
        testSubmissionFile.setContent(TestingUtils.TIMEOUT_SOLUTION_FILE);
        Submission returnedSubmission = evaluationService.evaluateSubmission(testSubmission);
        assertEquals(testSubmission.getId(), returnedSubmission.getId());
        assertEquals("Memory Limit Exceeded", returnedSubmission.getOutput());
    }

}
