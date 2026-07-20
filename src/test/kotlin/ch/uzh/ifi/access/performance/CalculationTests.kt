package ch.uzh.ifi.access.performance

import ch.uzh.ifi.access.AccessTestExecutionCondition
import ch.uzh.ifi.access.AccessUser
import ch.uzh.ifi.access.BaseTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cache.CacheManager
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.concurrent.TimeUnit
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import kotlin.random.Random
import java.util.Locale



@ExtendWith(DumpAvailabilityCondition::class)
annotation class SkipWhenDBNotReady

class DumpAvailabilityCondition : AccessTestExecutionCondition() {

    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        val cwd = "scripts/dumpImport/"
        val ready = runCommand("./is_ready.bash", cwd).trim()
        return if (ready == "ready") {
            ConditionEvaluationResult.enabled("DB ready for performance testing")
        } else {
            ConditionEvaluationResult.disabled("DB not ready for performance testing - skipping tests")
        }
    }

}

// Hibernate query statistics are off by default: without this property every benchmark would report 0 queries.
@SpringBootTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SkipWhenDBNotReady
class CalculationTests(
    @Autowired val mvc: MockMvc,
    @Autowired val entityManagerFactory: EntityManagerFactory,
    @Autowired val cacheManager: CacheManager,
) : BaseTest() {

    private val logger = KotlinLogging.logger {}

    companion object {
        private const val COURSE_SLUG = "info1-hs24"
        private const val API_KEY = "1234"
        private const val BENCHMARK_SEED = 42L // fixed seed -> in this way the student sample is reproducible

        // Samples per per-student pass AND repetitions per per-course pass. Kept low so the whole
        // benchmark suite finishes in reasonable time on the ~3GB dump.
        private const val BENCHMARK_SAMPLE_SIZE = 5
    }

    @Test
    @Order(1)
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    fun `Can retrieve participants`() {
        mvc.perform(
            get("/courses/info1-hs24/participants")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.[?(@.email =~ /philip.*?/i)].email", `is`(not(empty<Any>()))))
    }

    @Test
    @Order(1)
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    @AccessUser(
        username = "assistant@uzh.ch",
        authorities = ["assistant", "info1-hs24-assistant", "info1-hs24"]
    )
    fun `Can retrieve participants with points`() {
        mvc.perform(
            get("/courses/info1-hs24/points")
                .contentType("application/json")
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.[?(@.email =~ /philip.*?/i)].email", `is`(not(empty<Any>()))))
    }

    @Test
    @Order(1)
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    @AccessUser(
        username = "assistant@uzh.ch",
        authorities = ["assistant", "info1-hs24-assistant", "info1-hs24"]
    )
    fun `Can retrieve users with points`() {
        mvc.perform(
            get("/courses/info1-hs24/users")
                .contentType("application/json")
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.[?(@.email =~ /supervisor.*?/i)].email", `is`(not(empty<Any>()))))
    }


    private data class BenchmarkResult(val user: String, val queries: Long, val timeMs: Double)


    //  Deterministic seeded random student sample
    //  Endpoint: GET courses/$COURSE_SLUG/participants/$userId -> CourseController.getCourseProgress
    //   Returns CourseProgressDTO = one student's progress across the WHOLE course (every assignment/task
    //   with points/status). Per-student + traverses the full course tree -> prime N+1 candidate.
    //   in two passes — COLD (caches cleared: pays the one-off Keycloak resolution) then WARM (steady
    //   state: isolates the endpoint's DB cost). Compare cold vs warm time, and min vs max queries (N+1 spot).
    @Test
    @Order(3)
    fun `Benchmark getCourseProgress, seeded student sample`() {
        val sample = selectRandomEvaluatedUserIds(COURSE_SLUG, BENCHMARK_SAMPLE_SIZE, BENCHMARK_SEED)
        if (sample.isEmpty()) {
            logger.warn { "getCourseProgress benchmark skipped: no evaluated users found for course '$COURSE_SLUG'" }
            return
        }

        // Deterministic cold baseline: clear all Spring caches so per-user Keycloak resolution
        // (RoleService.getUserId / findUserByAllCriteria) starts cold and is not silently warmed
        // by earlier @Order tests. The cold pass then warms the caches for the warm pass.
        clearApplicationCaches()
        val cold = measurePass(sample, "cold")
        // Steady state: user resolution is now cached, so this isolates the per-request DB cost of
        // getCourseProgress from one-off Keycloak lookup latency. Compare `time (ms)` cold vs warm.
        val warm = measurePass(sample, "warm")

        logger.info { formatBenchmarkReport("getCourseProgress cold (caches cleared)", cold) }
        logger.info { formatBenchmarkReport("getCourseProgress warm (steady state)", warm) }
    }
    //--------------------------------------------------------------------------------------------------------
    // Frontend-facing endpoint benchmarks (student pages)
    //--------------------------------------------------------------------------------------------------------
    // Each test documents the frontend hook it corresponds to. Two patterns are reused:
    //  - benchmarkRepeated:        per-course / per-user endpoints -> fixed course, N repetitions.
    //  - benchmarkSampledStudents: per-student endpoints (username in the URL) -> seeded student sample.
    // All student-page benchmarks authenticate via a runtime-injected student
    // (asStudent = resolveBenchmarkStudent()) instead of a hardcoded @AccessUser id.

    // Endpoint: GET /courses -> CourseController.getCourses
    //   Returns List<CourseOverview> = the courses the CURRENTLY AUTHENTICATED user is enrolled in
    //   (resolves the caller via roleService.getUserId(); no username in the URL).
    //   Auth: the endpoint answers "MY courses", so the request must carry a logged-in student,
    // Frontend: student dashboard (list of my courses). Pattern: benchmarkRepeated (per-course, N reps).
    @Test
    @Order(4)
    fun `Benchmark student dashboard, GET courses`() {
        benchmarkRepeated("GET /courses (student dashboard)", "/courses", useApiKey = false, asStudent = resolveBenchmarkStudent())
    }

    // The HEAVIEST student page -> the #1 N+1 candidate.
    // Endpoint: GET /courses/{c} -> CourseController.getCourseWorkspace
    //   Returns CourseWorkspace = the full course page (assignments/tasks/deadlines/points).
    //   Auth: @PreAuthorize hasRole(course) -> the injected student must carry the course authority.
    // Frontend hook: useCourse. Pattern: benchmarkRepeated (per-course, N reps).
    @Test
    @Order(5)
    fun `Benchmark useCourse, course workspace page`() {
        benchmarkRepeated("GET /courses/{c} (useCourse)", "/courses/$COURSE_SLUG", useApiKey = false, asStudent =resolveBenchmarkStudent())
    }

    // Endpoint: GET /courses/{c}/assignments/{a} -> CourseController.getAssignment
    //   Returns AssignmentWorkspace = a single assignment with its tasks.
    //   No @PreAuthorize.
    //   The {a} slug comes from busiestAssignmentAndTask() -> the assignment/task with the MOST
    //   evaluations (worst-case for N+1). Skips if the course has no assignment/task.
    //   Auth: no method-level @PreAuthorize, but the security chain still requires an authenticated
    //   user -> we inject the same runtime student as the other student-page benchmarks.
    // Frontend hook: useAssignment. Pattern: benchmarkRepeated (per-course, N reps).
    @Test
    @Order(6)
    fun `Benchmark useAssignment, assignment page`() {
        val at = busiestAssignmentAndTask(COURSE_SLUG)
        if (at == null) {
            logger.warn { "assignment benchmark skipped: no assignment/task found in '$COURSE_SLUG'" }
            return
        }
        benchmarkRepeated(
            "GET /courses/{c}/assignments/{a} (useAssignment)",
            "/courses/$COURSE_SLUG/assignments/${at.first}",
            useApiKey = false,
            asStudent = resolveBenchmarkStudent(),
        )
    }

    //--------------------------------------------------------------------------------------------------------
    // Frontend-facing endpoint benchmarks (benchmarks run as STAFF)
    //--------------------------------------------------------------------------------------------------------


    // Endpoint: GET /courses/{c}/assignments/{a}/tasks/{t}/users/{username} -> CourseController.getTask
    //   Returns TaskWorkspace = a SPECIFIC student's work/state on one task (their submissions, points).
    //   Auth: @PreAuthorize assistant OR self -> the benchmark runs as an assistant so it can view ANY
    //   student in the sample. {a}/{t} fixed to the busiest task; {username} varies over the seeded sample.
    // Frontend hook: useTask. Pattern: benchmarkSampledStudents (username in URL -> per-student sample).
    @Test
    @Order(7)
    @AccessUser(username = "assistant@uzh.ch", authorities = ["assistant", "info1-hs24-assistant", "info1-hs24"])
    fun `Benchmark useTask, task page over seeded student sample`() {
        val at = busiestAssignmentAndTask(COURSE_SLUG)
        if (at == null) {
            logger.warn { "task benchmark skipped: no assignment/task found in '$COURSE_SLUG'" }
            return
        }
        benchmarkSampledStudents(
            "GET .../tasks/{t}/users/{username} (useTask)",
            { username -> "/courses/$COURSE_SLUG/assignments/${at.first}/tasks/${at.second}/users/$username" },
            useApiKey = false,
        )
    }
    // Endpoint: GET /courses/{c}/participants -> CourseController.getParticipants
    //   Returns List<StudentDTO> = the participants roster (profiles with email+first+last).
    //   No @PreAuthorize -> secured by X-API-Key, hence useApiKey = true (no simulated user).
    // Frontend hook: useParticipants (staff pages). Pattern: benchmarkRepeated (per-course, N reps).
    @Test
    @Order(8)
    fun `Benchmark useParticipants, staff participants list`() {
        benchmarkRepeated(
            "GET /courses/{c}/participants (useParticipants)",
            "/courses/$COURSE_SLUG/participants",
            useApiKey = true,
        )
    }
    // Endpoint: GET /courses/{c}/points -> CourseController.getStudentsWithPoints
    //   Returns List<StudentDTO> = students with their computed points. Auth: assistant.
    // Frontend hook: usePoints (Participants.tsx). Pattern: benchmarkRepeated (per-course, N reps).
    @Test
    @Order(9)
    @AccessUser(username = "assistant@uzh.ch", authorities = ["assistant", "info1-hs24-assistant", "info1-hs24"])
    fun `Benchmark usePoints, staff points page`() {
        benchmarkRepeated("GET /courses/{c}/points (usePoints)", "/courses/$COURSE_SLUG/points", useApiKey = false)
    }
    // Endpoint: GET /courses/{c}/users -> CourseController.getUsersWithPoints
    //   Returns List<StudentDTO> incl. staff (assistants + supervisors), with points. Auth: assistant.
    // Frontend hook: useUsers (Supervisor.tsx). Pattern: benchmarkRepeated (per-course, N reps).
    @Test
    @Order(10)
    @AccessUser(username = "assistant@uzh.ch", authorities = ["assistant", "info1-hs24-assistant", "info1-hs24"])
    fun `Benchmark useUsers, staff users page`() {
        benchmarkRepeated("GET /courses/{c}/users (useUsers)", "/courses/$COURSE_SLUG/users", useApiKey = false)
    }


/**
    ------------------------------------------------
    HELPER FUNCTIONS
    ------------------------------------------------
*/

    /** It is possible to choose to perform the testing process in two ways:
     * either we set a specific student as an env variable (this can be useful when working with
     * a specific challenging situation) or we can use this private function to retrieve from the database the worst case:
     *  the student with the highest number of evaluations in the course.
     *  Many students are tied at the maximum, so we add an alphabetical tie-break:
     *  without it the database could return a different student on every run. */
    private fun resolveBenchmarkStudent(): String {
        System.getenv("ACCESS_BENCHMARK_STUDENT")?.let { return it }
        entityManagerFactory.createEntityManager().use { em ->
            return em.createNativeQuery("""
            SELECT e.user_id FROM evaluation e
            JOIN task t ON e.task_id = t.id
            JOIN assignment a ON t.assignment_id = a.id
            JOIN course c ON a.course_id = c.id
            WHERE c.slug = :slug
            GROUP BY e.user_id ORDER BY COUNT(*) DESC, e.user_id LIMIT 1 
        """).setParameter("slug", COURSE_SLUG).singleResult as String
        }
    }


    /**
     * Core measurement primitive (reused by every benchmark): performs each request once, recording the
     * Hibernate `prepareStatementCount` (a proxy for JDBC round-trips that also includes Spring Security /
     * auth SQL, not only the endpoint under test) and the wall-clock time. Requests that don't return 200
     * are skipped instead of failing the whole run. When [clearCacheEachRequest] is true, all Spring
     * caches are evicted before each request so every one pays the full cold price (repeated cold passes).
     */
    private fun measure(
        requests: List<Pair<String, RequestBuilder>>,
        label: String,
        clearCacheEachRequest: Boolean = false,
    ): List<BenchmarkResult> {
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        val results = mutableListOf<BenchmarkResult>()
        var skipped = 0
        for ((rowLabel, request) in requests) {
            if (clearCacheEachRequest) clearApplicationCaches() //to empty the cache if the measurement is cold
            statistics.clear()
            val startNanos = System.nanoTime()
            val status = mvc.perform(request).andReturn().response.status
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
            if (status != 200) { //If the request is not successful the student is not considered and added to the skipped ones
                skipped++
                logger.warn { "[$label] skipping $rowLabel: HTTP $status (not reachable / not authorized?)" }
                continue
            }
            results.add(BenchmarkResult(rowLabel, statistics.prepareStatementCount, elapsedMs))
        }
        if (skipped > 0) logger.warn { "[$label] skipped $skipped/${requests.size} request(s)" }
        return results
    }

    /** Wrapper over measure() Per-student pass over the participants/{username} endpoint */
    private fun measurePass(sample: List<String>, label: String): List<BenchmarkResult> {
        val requests: List<Pair<String, RequestBuilder>> = sample.map { userId ->
            userId to get("/courses/$COURSE_SLUG/participants/$userId")
                .header("X-API-Key", API_KEY)
                .contentType("application/json")
        } //transforms every userId in a pair (userId, request). The request is pre-built
        return measure(requests, label)
    }

    private fun clearApplicationCaches() {
        cacheManager.cacheNames.forEach { cacheManager.getCache(it)?.clear() }
    }

    /**
     * Benchmarks a per-course / per-user endpoint by hitting the fixed COURSE_SLUG BENCHMARK_SAMPLE_SIZE
     * times (these endpoints return everything in one request, so there is no per-student sample). The
     * COLD pass clears all caches before every request (each pays full price); the WARM pass reuses the
     * now-warm caches for steady-state numbers.
     */
    private fun benchmarkRepeated(label: String, path: String, useApiKey: Boolean, asStudent: String? = null) {
        fun buildRequests(): List<Pair<String, RequestBuilder>> =
            (1..BENCHMARK_SAMPLE_SIZE).map { i ->
                val request: RequestBuilder = get(path).contentType("application/json")
                    .let { if (useApiKey) it.header("X-API-Key", API_KEY) else it }
                    .let { if (asStudent != null) it.with(user(asStudent).authorities(SimpleGrantedAuthority(COURSE_SLUG))) else it }
                "$COURSE_SLUG#$i" to request
            }
        // for the cold measurement, since we are hitting the same url over and over, it is needed to empty the cache each time
        val cold = measure(buildRequests(), "cold", clearCacheEachRequest = true)
        val warm = measure(buildRequests(), "warm")
        logger.info { formatBenchmarkReport("$label [cold]", cold) }
        logger.info { formatBenchmarkReport("$label [warm]", warm) }
    }

    /**
     * Benchmarks a per-student endpoint (username in the URL) over the seeded random student sample —
     * same shape as the getCourseProgress benchmark. Different students are naturally cold within a pass,
     * so the cold pass only clears caches once up front; the warm pass replays the same sample.
     */
    private fun benchmarkSampledStudents(
        label: String,
        urlFor: (username: String) -> String, //parameter function -> higher order function: given an username it returns the URL
        useApiKey: Boolean,
    ) {
        val sample = selectRandomEvaluatedUserIds(COURSE_SLUG, BENCHMARK_SAMPLE_SIZE, BENCHMARK_SEED)
        if (sample.isEmpty()) {
            logger.warn { "[$label] skipped: no evaluated users found for course '$COURSE_SLUG'" }
            return
        }
        fun buildRequests(): List<Pair<String, RequestBuilder>> =
            sample.map { userId ->
                val request: RequestBuilder = get(urlFor(userId)).contentType("application/json")
                    .let { if (useApiKey) it.header("X-API-Key", API_KEY) else it }
                userId to request
            }
        clearApplicationCaches() // since we are hitting different students then it is enough to clean the cache just at the beginning
        val cold = measure(buildRequests(), "cold")
        val warm = measure(buildRequests(), "warm")
        logger.info { formatBenchmarkReport("$label [cold]", cold) }
        logger.info { formatBenchmarkReport("$label [warm]", warm) }
    }

    /** (assignment slug, task slug) of the course task with the MOST evaluations — worst-case for N+1. */
    private fun busiestAssignmentAndTask(courseSlug: String): Pair<String, String>? {
        val entityManager = entityManagerFactory.createEntityManager()
        try {
            @Suppress("UNCHECKED_CAST") //to remove the warning about the type of the list objects
            val rows = entityManager.createNativeQuery(
                """
                SELECT a.slug, t.slug
                FROM evaluation e
                JOIN task t ON t.id = e.task_id
                JOIN assignment a ON a.id = t.assignment_id
                JOIN course c ON c.id = a.course_id
                WHERE c.slug = :slug
                GROUP BY a.slug, t.slug, a.ordinal_num, t.ordinal_num
                ORDER BY COUNT(*) DESC, a.ordinal_num, t.ordinal_num
                LIMIT 1
                """.trimIndent()
            ).setParameter("slug", courseSlug).resultList as List<Array<Any>>
            return rows.firstOrNull()?.let { (it[0] as String) to (it[1] as String) }
        } finally {
            entityManager.close()
        }
    }

    /**
     * Deterministic (thanks to the seed) random sample of user IDs that respect the following conditions:
     * 1. NOT role-filtered (a user with an evaluation could be staff who submitted)
     * 2. excludes students who never submitted (they have no evaluation row).
     * The sampled IDs are re-resolved against live Keycloak by the endpoint, so an ID may no longer resolve; measurePass() skips such users.
     */
    private fun selectRandomEvaluatedUserIds(courseSlug: String, size: Int, seed: Long): List<String> {
        val entityManager = entityManagerFactory.createEntityManager()
        try {
            @Suppress("UNCHECKED_CAST")
            val candidates = entityManager.createNativeQuery( //take a list of suitable User_Id
                """
            SELECT DISTINCT e.user_id
            FROM evaluation e
            JOIN task t ON t.id = e.task_id
            JOIN assignment a ON a.id = t.assignment_id
            JOIN course c ON c.id = a.course_id
            WHERE c.slug = :slug AND e.user_id IS NOT NULL
            ORDER BY e.user_id
            """.trimIndent()
            ).setParameter("slug", courseSlug).resultList as List<String>
            return candidates.shuffled(Random(seed)).take(size) //get a shuffled but reproducible sample from that list
        } finally {
            entityManager.close()
        }
    }

    private fun formatBenchmarkReport(label: String, results: List<BenchmarkResult>): String {
        val separator = "-".repeat(56)
        val title = "benchmark [$label] | course=$COURSE_SLUG n=${results.size}"
        if (results.isEmpty()) {
            return "\n$separator\n$title\n$separator\n(no results — all sampled requests were skipped)\n$separator"
        }
        val header = String.format(Locale.ROOT, "%-32s %10s %12s", "row", "queries", "time (ms)")
        val rows = results.joinToString("\n") {
            String.format(Locale.ROOT, "%-32s %10d %12.1f", it.user, it.queries, it.timeMs)
        }
        val summary = listOf(
            String.format(
                Locale.ROOT,
                "%-32s %10d %12.1f",
                "min",
                results.minOf { it.queries },
                results.minOf { it.timeMs }),
            String.format(
                Locale.ROOT,
                "%-32s %10d %12.1f",
                "max",
                results.maxOf { it.queries },
                results.maxOf { it.timeMs }),
            String.format(
                Locale.ROOT,
                "%-32s %10.1f %12.1f",
                "avg",
                results.map { it.queries }.average(),
                results.map { it.timeMs }.average()
            ),
        ).joinToString("\n")
        return "\n$separator\n$title\n$separator\n$header\n$separator\n$rows\n$separator\n$summary\n$separator"
    }
}
