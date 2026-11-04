package ch.uzh.ifi.access.example

import ch.uzh.ifi.access.config.ExampleProperties
import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.constants.EmitterType
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.repository.EvaluationRepository
import ch.uzh.ifi.access.repository.ExampleRepository
import ch.uzh.ifi.access.repository.InteractiveSubmissionStats
import ch.uzh.ifi.access.repository.SubmissionRepository
import ch.uzh.ifi.access.service.EmitterService
import ch.uzh.ifi.access.service.ExampleService
import ch.uzh.ifi.access.service.ExampleStatisticsService
import ch.uzh.ifi.access.service.ExampleSubmissionService
import ch.uzh.ifi.access.service.ExampleVisibilityService
import ch.uzh.ifi.access.service.InteractiveSubmissionCounter
import ch.uzh.ifi.access.service.CourseAuthorizationService
import ch.uzh.ifi.access.service.UserIdentityService
import ch.uzh.ifi.access.service.SubmissionService
import ch.uzh.ifi.access.service.VisitQueueService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

/**
 * Tests for the example services: the rules for example submissions ([ExampleSubmissionService]) and the numbers
 * shown to supervisors ([ExampleStatisticsService]). Written as characterization tests before ExampleService was
 * split up (refactoring plan ExampleService/SubmissionService, step 0) and kept unchanged through the split.
 * Pure unit tests: real services, mocked repositories and collaborators.
 *
 * The lookup of a student's earlier submissions is stubbed in one place, [previousSubmissions].
 */
class ExampleServiceTests {

    companion object {
        private const val COURSE = "course"
        private const val EXAMPLE = "example"
        private const val USER = "student@uzh.ch"
        private const val GRACE_SECONDS = 5L
        private val START: LocalDateTime = LocalDateTime.of(2026, 11, 3, 10, 0)
        private val END: LocalDateTime = START.plusMinutes(30)
    }

    private val submissionService = mockk<SubmissionService>()
    private val userIdentityService = mockk<UserIdentityService>()
    private val courseAuthorizationService = mockk<CourseAuthorizationService>()
    private val courseRepository = mockk<CourseRepository>()
    private val emitterService = mockk<EmitterService>(relaxed = true)
    private val exampleRepository = mockk<ExampleRepository>()
    private val submissionRepository = mockk<SubmissionRepository>()
    private val visitQueueService = mockk<VisitQueueService>()

    private var admin = false
    private var previous: List<Submission> = emptyList()

    private val example = Task().apply {
        id = 1L
        slug = EXAMPLE
        start = START
        end = END
        testNames = mutableListOf("t1", "t2", "t3")
    }

    private val counter = InteractiveSubmissionCounter()

    private val exampleService = ExampleService(
        userIdentityService = userIdentityService,
        courseAuthorizationService = courseAuthorizationService,
        exampleRepository = exampleRepository,
        evaluationRepository = mockk<EvaluationRepository>(relaxed = true),
        courseRepository = courseRepository,
        visibility = mockk<ExampleVisibilityService>(relaxed = true),
        submissionCounter = counter,
        exampleProperties = ExampleProperties(gracePeriod = GRACE_SECONDS),
    )

    private val statistics = ExampleStatisticsService(
        exampleService = exampleService,
        exampleRepository = exampleRepository,
        submissionRepository = submissionRepository,
        courseRepository = courseRepository,
        visitQueueService = visitQueueService,
        emitterService = emitterService,
        submissionCounter = counter,
        exampleProperties = ExampleProperties(gracePeriod = GRACE_SECONDS),
    )

    private fun newExampleSubmissionService(lockHours: Long = 2) = ExampleSubmissionService(
        exampleService = exampleService,
        submissionService = submissionService,
        userIdentityService = userIdentityService,
        courseAuthorizationService = courseAuthorizationService,
        submissionRepository = submissionRepository,
        emitterService = emitterService,
        statistics = statistics,
        exampleProperties = ExampleProperties(gracePeriod = GRACE_SECONDS, submissionLockHours = lockHours),
    )

    private val submissions = newExampleSubmissionService()

    init {
        every { exampleRepository.getByCourse_SlugAndSlug(COURSE, EXAMPLE) } answers { example }
        every { userIdentityService.getRegistrationIDCandidates(any<String>()) } returns listOf(USER)
        every { courseAuthorizationService.getUserRoles(any()) } returns emptyList()
        every { courseAuthorizationService.isAdmin(any(), any()) } answers { admin }
        previousSubmissions()
        every { submissionService.createSubmission(any(), any(), any(), any(), any()) } answers {
            Submission().apply {
                id = 42L
                userId = USER
                command = arg<SubmissionDTO>(3).command
                createdAt = arg<LocalDateTime?>(4)
            }
        }
    }

    /**
     * The single place that stubs how earlier submissions of the student are looked up: the latest creation time
     * among [previous] with the requested command (like the real query).
     */
    private fun previousSubmissions() {
        every { submissionRepository.findLatestCreatedAt(1L, USER, any()) } answers {
            previous.filter { it.command == thirdArg<Command>() }.mapNotNull { it.createdAt }.maxOrNull()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Validation of example submissions (createExampleSubmission)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `students cannot submit to an unpublished example`() {
        example.start = null
        example.end = null
        assertRejected("not published") { submit(Command.RUN, START) }
    }

    @Test
    fun `admins can submit to an unpublished example`() {
        example.start = null
        example.end = null
        admin = true
        assertAccepted { submit(Command.GRADE, START) }
    }

    @Test
    fun `a first GRADE during the interactive period is accepted`() {
        assertAccepted { submit(Command.GRADE, START.plusMinutes(10)) }
    }

    @Test
    fun `a GRADE within two hours of the previous GRADE is rejected`() {
        previous = listOf(earlier(Command.GRADE, START.plusMinutes(5)))
        assertRejected("wait for 2 hours") { submit(Command.GRADE, START.plusMinutes(10)) }
    }

    @Test
    fun `the lock is measured from the latest previous GRADE`() {
        previous = listOf(
            earlier(Command.GRADE, START.minusHours(5)),
            earlier(Command.GRADE, START.plusMinutes(5)),
        )
        assertRejected("wait for 2 hours") { submit(Command.GRADE, START.plusMinutes(10)) }
    }

    @Test
    fun `the lock duration is configurable`() {
        val threeHours = newExampleSubmissionService(lockHours = 3)
        previous = listOf(earlier(Command.GRADE, START))
        assertRejected("wait for 3 hours") {
            threeHours.createExampleSubmission(COURSE, EXAMPLE, dto(Command.GRADE), START.plusHours(2).plusMinutes(30))
        }
    }

    @Test
    fun `a GRADE exactly two hours after the previous GRADE is accepted`() {
        previous = listOf(earlier(Command.GRADE, START))
        assertAccepted { submit(Command.GRADE, START.plusHours(2)) }
    }

    @Test
    fun `a first GRADE within the grace period is accepted`() {
        assertAccepted { submit(Command.GRADE, END.plusSeconds(GRACE_SECONDS - 1)) }
    }

    @Test
    fun `a first GRADE after end plus grace period is rejected as too late`() {
        assertRejected("too late") { submit(Command.GRADE, END.plusSeconds(GRACE_SECONDS + 1)) }
    }

    @Test
    fun `a first GRADE more than two hours after the end is accepted again`() {
        // Documents current behaviour: after the lock window the example can be graded like an exercise.
        assertAccepted { submit(Command.GRADE, END.plusHours(2).plusMinutes(1)) }
    }

    @Test
    fun `RUN and TEST are not subject to the submission lock`() {
        previous = listOf(earlier(Command.GRADE, START.plusMinutes(9)))
        assertAccepted { submit(Command.RUN, START.plusMinutes(10)) }
        assertAccepted(times = 2) { submit(Command.TEST, START.plusMinutes(10)) }
    }

    @Test
    fun `only previous GRADE submissions count for the lock`() {
        previous = listOf(earlier(Command.RUN, START.plusMinutes(9)), earlier(Command.TEST, START.plusMinutes(9)))
        assertAccepted { submit(Command.GRADE, START.plusMinutes(10)) }
    }

    @Test
    fun `admins are not subject to the submission lock`() {
        admin = true
        previous = listOf(earlier(Command.GRADE, START.plusMinutes(9)))
        assertAccepted { submit(Command.GRADE, START.plusMinutes(10)) }
    }

    // ---------------------------------------------------------------------------------------------
    // Notifying supervisors (processSubmission)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a student's GRADE is pushed to the supervisors`() {
        submissions.processSubmission(COURSE, EXAMPLE, dto(Command.GRADE), START.plusMinutes(10))
        verify(exactly = 1) { emitterService.sendPayload(EmitterType.SUPERVISOR, COURSE, "student-submission", any()) }
    }

    @Test
    fun `the roles of the student are resolved only once per submission`() {
        submissions.processSubmission(COURSE, EXAMPLE, dto(Command.GRADE), START.plusMinutes(10))
        verify(exactly = 1) { courseAuthorizationService.getUserRoles(any()) }
    }

    @Test
    fun `roles passed by the caller are not resolved again`() {
        submissions.processSubmission(COURSE, EXAMPLE, dto(Command.GRADE), START.plusMinutes(10), isAdmin = false)
        verify(exactly = 0) { courseAuthorizationService.getUserRoles(any()) }
        verify(exactly = 1) { emitterService.sendPayload(EmitterType.SUPERVISOR, COURSE, "student-submission", any()) }
    }

    @Test
    fun `RUN submissions and admin submissions are not pushed to the supervisors`() {
        submissions.processSubmission(COURSE, EXAMPLE, dto(Command.RUN), START.plusMinutes(10))
        admin = true
        submissions.processSubmission(COURSE, EXAMPLE, dto(Command.GRADE), START.plusMinutes(10))
        verify(exactly = 0) { emitterService.sendPayload(any(), any(), "student-submission", any()) }
    }

    // ---------------------------------------------------------------------------------------------
    // Point distribution (computePointDistribution)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the point distribution has one bin per test case and ignores unprocessed submissions`() {
        gradedSubmissions(0.0, 0.2, 0.5, 1.0, 1.0, unprocessed = 2)

        val bins = statistics.computePointDistribution(COURSE, EXAMPLE).pointDistribution

        assertEquals(3, bins.size)
        assertEquals(listOf(0.0, 0.33, 0.67), bins.map { it["lowerBoundary"] })
        assertEquals(listOf(0.33, 0.67, 1.0), bins.map { it["upperBoundary"] })
        assertEquals(listOf(2.0, 1.0, 2.0), bins.map { it["numberOfSubmissions"] })
    }

    @Test
    fun `an example without test cases has an empty point distribution`() {
        example.testNames = mutableListOf()
        gradedSubmissions(0.0, 1.0)

        assertEquals(0, statistics.computePointDistribution(COURSE, EXAMPLE).pointDistribution.size)
    }

    @Test
    fun `more than ten test cases are grouped into ten bins`() {
        example.testNames = MutableList(12) { "t$it" }
        gradedSubmissions(0.05, 0.95)

        val bins = statistics.computePointDistribution(COURSE, EXAMPLE).pointDistribution

        assertEquals(10, bins.size)
        assertEquals(1.0, bins.first()["numberOfSubmissions"])
        assertEquals(1.0, bins.last()["numberOfSubmissions"])
    }

    // ---------------------------------------------------------------------------------------------
    // Live information for supervisors (computeExampleInformation)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `example information aggregates the processed submissions`() {
        stubInformationSources(
            stats("[1,0,1]", 0.5, hasEmbedding = true),
            stats("[1,1,1]", 1.0, hasEmbedding = false),
            stats("[]", null, hasEmbedding = false), // received, not graded yet
            stats(null, null, hasEmbedding = false),
        )
        repeat(5) { counter.increase(COURSE, EXAMPLE) }

        val info = statistics.computeExampleInformation(COURSE, EXAMPLE)

        assertEquals(7, info.participantsOnline)
        assertEquals(4L, info.totalParticipants)
        assertEquals(5, info.numberOfReceivedSubmissions)
        assertEquals(2, info.numberOfProcessedSubmissions)
        assertEquals(1, info.numberOfProcessedSubmissionsWithEmbeddings)
        assertEquals(mapOf("t1" to 1.0, "t2" to 0.5, "t3" to 1.0), info.passRatePerTestCase)
        assertEquals(0.75, info.avgPoints)
    }

    @Test
    fun `the received count never drops below the processed count`() {
        // e.g. after a restart: the in-memory counter starts at 0, the database still has the gradings
        stubInformationSources(stats("[1,1,1]", 1.0, false), stats("[0,0,0]", 0.0, false))

        val info = statistics.computeExampleInformation(COURSE, EXAMPLE)

        assertEquals(2, info.numberOfReceivedSubmissions)
        assertEquals(2, info.numberOfProcessedSubmissions)
    }

    @Test
    fun `without processed submissions the rates and average are zero`() {
        stubInformationSources()

        val info = statistics.computeExampleInformation(COURSE, EXAMPLE)

        assertEquals(mapOf("t1" to 0.0, "t2" to 0.0, "t3" to 0.0), info.passRatePerTestCase)
        assertEquals(0.0, info.avgPoints)
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun dto(command: Command) = SubmissionDTO(userId = USER, command = command)

    private fun submit(command: Command, at: LocalDateTime) =
        submissions.createExampleSubmission(COURSE, EXAMPLE, dto(command), at)

    private fun earlier(command: Command, at: LocalDateTime) = Submission().apply {
        this.command = command
        createdAt = at
        userId = USER
    }

    private fun assertAccepted(times: Int = 1, block: () -> Unit) {
        block()
        verify(exactly = times) { submissionService.createSubmission(any(), any(), any(), any(), any()) }
    }

    private fun assertRejected(reasonPart: String, block: () -> Unit) {
        val error = assertThrows(ResponseStatusException::class.java) { block() }
        assertEquals(400, error.statusCode.value())
        assertTrue(error.reason!!.contains(reasonPart), "unexpected reason: ${error.reason}")
        verify(exactly = 0) { submissionService.createSubmission(any(), any(), any(), any(), any()) }
    }

    private fun gradedSubmissions(vararg points: Double, unprocessed: Int = 0) {
        val processed = points.map { p -> Submission().apply { this.points = p; testsPassed = listOf(1) } }
        val pending = List(unprocessed) { Submission().apply { this.points = null; testsPassed = emptyList() } }
        every {
            submissionRepository.findInteractiveExampleSubmissions(1L, Command.GRADE, START, END.plusSeconds(GRACE_SECONDS))
        } returns processed + pending
    }

    private fun stubInformationSources(vararg rows: InteractiveSubmissionStats) {
        every { visitQueueService.getRecentlyActiveCount(COURSE) } returns 7
        every { courseRepository.getBySlug(COURSE) } returns Course().apply {
            registeredStudents = mutableSetOf("a", "b", "c", "d")
        }
        every {
            submissionRepository.findInteractiveExampleSubmissionStats(1L, START, END.plusSeconds(GRACE_SECONDS))
        } returns rows.toList()
    }

    private fun stats(testsPassed: String?, points: Double?, hasEmbedding: Boolean) = object : InteractiveSubmissionStats {
        override fun getTestsPassed(): String? = testsPassed
        override fun getPoints(): Double? = points
        override fun getHasEmbedding(): Boolean = hasEmbedding
    }
}
