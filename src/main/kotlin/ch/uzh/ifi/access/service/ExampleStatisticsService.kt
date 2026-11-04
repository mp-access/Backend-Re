package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.config.ExampleProperties
import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.constants.EmitterType
import ch.uzh.ifi.access.model.dto.ExampleInformationDTO
import ch.uzh.ifi.access.model.dto.ExampleSubmissionsCountDTO
import ch.uzh.ifi.access.model.dto.PointDistributionDTO
import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.repository.ExampleRepository
import ch.uzh.ifi.access.repository.SubmissionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cache.annotation.Cacheable
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Numbers about examples for supervisors: live information during the interactive period (pushed via SSE),
 * the point distribution afterwards, and submission counts per student.
 */
@Service
class ExampleStatisticsService(
    private val exampleService: ExampleService,
    private val exampleRepository: ExampleRepository,
    private val submissionRepository: SubmissionRepository,
    private val courseRepository: CourseRepository,
    private val visitQueueService: VisitQueueService,
    private val emitterService: EmitterService,
    private val submissionCounter: InteractiveSubmissionCounter,
    private val exampleProperties: ExampleProperties,
) {
    private val logger = KotlinLogging.logger {}

    // (course, example) -> generation; published by publishPendingExampleInformationUpdates.
    private val pendingExampleInformationUpdates = ConcurrentHashMap<Pair<String, String>, Long>()
    private val exampleInformationUpdateSequence = AtomicLong()

    // ---------------------------------------------------------------------------------------------
    // Live information during the interactive period
    // ---------------------------------------------------------------------------------------------

    /** Schedules an "example-information" push; inside a transaction only after it has committed. */
    fun requestExampleInformationUpdate(courseSlug: String, exampleSlug: String) {
        val markPending = {
            pendingExampleInformationUpdates[Pair(courseSlug, exampleSlug)] = exampleInformationUpdateSequence.incrementAndGet()
        }
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    markPending()
                }
            })
        } else {
            markPending()
        }
    }

    @Scheduled(fixedDelayString = "\${examples.information-update-rate:1s}")
    fun publishPendingExampleInformationUpdates() {
        pendingExampleInformationUpdates.entries.toList().forEach { (key, generation) ->
            val (courseSlug, exampleSlug) = key
            try {
                emitterService.sendPayload(
                    EmitterType.SUPERVISOR, courseSlug, "example-information", computeExampleInformation(courseSlug, exampleSlug)
                )
                pendingExampleInformationUpdates.remove(key, generation)
            } catch (exception: Exception) {
                logger.error(exception) { "Failed to publish example information for $courseSlug/$exampleSlug" }
            }
        }
    }

    fun computeExampleInformation(courseSlug: String, exampleSlug: String): ExampleInformationDTO {
        val participantsOnline = visitQueueService.getRecentlyActiveCount(courseSlug)
        val totalParticipants = (courseRepository.getBySlug(courseSlug)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL $courseSlug")).participantCount
        val example = exampleService.getExampleBySlug(courseSlug, exampleSlug)
        val stats = if (example.start == null || example.end == null) emptyList() else
            submissionRepository.findInteractiveExampleSubmissionStats(example.id!!, example.start!!, example.interactiveEnd(exampleProperties.gracePeriod)!!)
        val processed = stats.map { row ->
            Triple(parseTestsPassed(row.getTestsPassed()), row.getPoints(), row.getHasEmbedding())
        }.filter { it.first.isNotEmpty() }
        val passRatePerTestCase = example.testNames.mapIndexed { i, name ->
            name to if (processed.isEmpty()) 0.0
            else processed.sumOf { it.first.getOrElse(i) { 0 } }.toDouble() / processed.size
        }.toMap()
        val avgPoints = processed.mapNotNull { it.second }.ifEmpty { listOf(0.0) }.average()

        return ExampleInformationDTO(
            participantsOnline,
            totalParticipants,
            // The in-memory counter starts at 0 after a restart; never report fewer received than processed.
            maxOf(submissionCounter.get(courseSlug, exampleSlug), processed.size),
            processed.size,
            processed.count { it.third },
            passRatePerTestCase,
            avgPoints
        )
    }

    private fun parseTestsPassed(raw: String?): List<Int> =
        (raw ?: "[]").trim().removePrefix("[").removeSuffix("]")
            .split(",").filter { it.isNotBlank() }.map { it.trim().toInt() }

    // ---------------------------------------------------------------------------------------------
    // Graded submissions and point distribution
    // ---------------------------------------------------------------------------------------------

    /** GRADE submissions of the interactive period that have been graded already. */
    fun getInteractiveExampleSubmissions(courseSlug: String, exampleSlug: String): List<Submission> {
        val example = exampleService.getExampleBySlug(courseSlug, exampleSlug)
        if (example.start == null || example.end == null) return emptyList()

        // Note: For performance reasons, we decided to not check anymore if the interactive example submissions come from students.
        return submissionRepository.findInteractiveExampleSubmissions(
            example.id, Command.GRADE, example.start!!, example.interactiveEnd(exampleProperties.gracePeriod)!!
        ).filter { submission -> submission.testsPassed.isNotEmpty() }
    }

    fun computePointDistribution(courseSlug: String, exampleSlug: String): PointDistributionDTO {
        val example = exampleService.getExampleBySlug(courseSlug, exampleSlug)
        val response = PointDistributionDTO()
        val points = getInteractiveExampleSubmissions(courseSlug, exampleSlug).mapNotNull { it.points }
        val numBins = minOf(example.testNames.size, 10)
        if (numBins == 0) return response // no test cases: nothing to distribute (avoids dividing by zero bins)
        val binSize = 1.0 / numBins
        val binCounts = IntArray(numBins)

        for (point in points) {
            binCounts[minOf((point / binSize).toInt(), numBins - 1)]++
        }
        for (i in 0 until numBins) {
            val lowerBoundary = i * binSize
            val upperBoundary = if (i == numBins - 1) 1.0 else (i + 1) * binSize
            response.pointDistribution.add(
                mapOf(
                    "lowerBoundary" to Math.round(lowerBoundary * 100.0) / 100.0,
                    "upperBoundary" to Math.round(upperBoundary * 100.0) / 100.0,
                    "numberOfSubmissions" to binCounts[i].toDouble()
                )
            )
        }
        return response
    }

    fun sendPointDistribution(courseSlug: String, exampleSlug: String) {
        emitterService.sendPayload(EmitterType.SUPERVISOR, courseSlug, "point-distribution", computePointDistribution(courseSlug, exampleSlug))
    }

    /**
     * Waits until the example is over and then sends "point-distribution" via SSE
     * every few seconds until all received submissions are graded.
     * Always sends at least once, and the last event carries the final state.
     */
    fun sendPointDistributionUpdates(courseSlug: String, exampleSlug: String) {
        val pollMillis = 5_000L
        val maxMillis = 5 * 60_000L

        while (exampleService.isExampleInteractive(courseSlug, exampleSlug)) {
            Thread.sleep(1_000)
        }

        val deadline = System.currentTimeMillis() + maxMillis
        do {
            if (exampleService.getExampleBySlug(courseSlug, exampleSlug).start == null) return // example was reset
            sendPointDistribution(courseSlug, exampleSlug)
            val allGraded = getInteractiveExampleSubmissions(courseSlug, exampleSlug).size >=
                submissionCounter.get(courseSlug, exampleSlug)
            if (allGraded) return
            Thread.sleep(pollMillis)
        } while (System.currentTimeMillis() < deadline)

        // Timed out: send the latest state once more.
        sendPointDistribution(courseSlug, exampleSlug)
    }

    // ---------------------------------------------------------------------------------------------
    // Submission counts per student
    // ---------------------------------------------------------------------------------------------

    @Cacheable(value = [CacheNames.EXAMPLE_SUBMISSIONS_COUNT], key = "#courseSlug")
    fun computeSubmissionsCount(courseSlug: String): ExampleSubmissionsCountDTO {
        val submissionsCount: Map<String, Int> = exampleRepository.getSubmissionsCount(courseSlug, exampleProperties.gracePeriod).associate { row ->
            (row["user_id"] as String) to (row["entry_count"] as Number).toInt()
        }
        return ExampleSubmissionsCountDTO(submissionsCount = submissionsCount.toMutableMap())
    }
}
