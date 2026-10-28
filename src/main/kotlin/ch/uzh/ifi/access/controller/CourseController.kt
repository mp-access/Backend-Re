package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.*
import ch.uzh.ifi.access.service.*
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Caching
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest
import org.springframework.web.context.request.async.WebAsyncUtils
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipOutputStream

@RestController
class CourseRootController(
    private val courseService: CourseService,
) {
    @PostMapping("/create")
    @PreAuthorize("hasRole('supervisor')")
    fun createCourse(@RequestBody courseDTO: CourseDTO): String? {
        return courseService.createCourse(courseDTO).slug
    }

    @PostMapping("/edit")
    @PreAuthorize("hasRole('supervisor')")
    fun editCourse(@RequestBody courseDTO: CourseDTO): String? {
        return courseService.editCourse(courseDTO).slug
    }
}

@RestController
@RequestMapping("/webhooks")
class WebhooksController(
    private val courseService: CourseService,
) {

    private val logger = KotlinLogging.logger {}

    @PostMapping("/courses/{course}/update/gitlab")
    fun hookGitlab(
        @PathVariable("course") course: String,
        @RequestHeader("X-Gitlab-Token") secret: String,
    ) {
        logger.debug { "webhook (secret) triggered for $course" }
        courseService.webhookUpdateWithSecret(course, secret)
    }

    @PostMapping("/courses/{course}/update/github")
    fun hookGithub(
        @PathVariable("course") course: String,
        @RequestHeader("X-Hub-Signature-256") signature: String,
        @RequestBody body: String,
    ) {
        logger.debug { "webhook (hmac) triggered for $course" }
        val sig = signature.substringAfter("sha256=")
        courseService.webhookUpdateWithHmac(course, sig, body)
    }

}


@RestController
@RequestMapping("/courses")
class CourseController(
    private val courseService: CourseService,
    private val dumpService: DumpService,
    private val roleService: RoleService,
    private val emitterService: EmitterService,
    private val visitQueueService: VisitQueueService,
    private val submissionQueueService: SubmissionQueueService,
    private val env: Environment,
    @Value("\${submission.queue.requestTimeoutSeconds:120}") private val requestTimeoutSeconds: Long,
) {
    private val logger = KotlinLogging.logger {}

    @PreAuthorize("hasRole(#course+'-supervisor')")
    @GetMapping("/{course}/dump")
    fun getDump(
        @PathVariable course: String,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<StreamingResponseBody> {
        val asyncWebRequest = StandardServletAsyncWebRequest(request, response)
        asyncWebRequest.setTimeout(TimeUnit.MINUTES.toMillis(120))
        WebAsyncUtils.getAsyncManager(request).setAsyncWebRequest(asyncWebRequest)
        return ResponseEntity
            .ok()
            .header("Content-Disposition", "attachment; filename=data.zip")
            .header("Content-Type", "application/zip")
            .body(StreamingResponseBody { out: OutputStream ->
                val zipOutputStream = ZipOutputStream(out)
                dumpService.getCourseDump(course, zipOutputStream)
            })
    }

    @PostMapping("/{course}/delete")
    fun deleteCourse(
        @PathVariable course: String,
        @RequestBody safety: String?,
    ) {
        val expected = env.getProperty("DELETION_KEY")
        val valid = !expected.isNullOrEmpty() && safety != null &&
        MessageDigest.isEqual(expected.toByteArray(), safety.toByteArray())
        if (!valid) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        courseService.deleteCourse(course)
    }

    @PostMapping("/{course}/pull")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun updateCourse(@PathVariable course: String): String? =
        courseService.updateCourse(course).slug

    @GetMapping("")
    fun getCourses(): List<CourseOverview> {
        val userId = roleService.getUserId()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Cannot resolve user ID")
        return courseService.getCoursesOverview(userId)
    }

    @GetMapping("/{course}")
    @PreAuthorize("hasRole(#course)")
    fun getCourseWorkspace(@PathVariable course: String): CourseWorkspace =
        courseService.getCourseWorkspaceBySlug(course)

    @GetMapping("/{course}/assignments/{assignment}")
    @PreAuthorize("hasRole(#course)")
    fun getAssignment(@PathVariable course: String, @PathVariable assignment: String): AssignmentWorkspace =
        courseService.getAssignmentWorkspaceBySlug(course, assignment)

    @GetMapping("/{course}/assignments/{assignment}/tasks/{task}/users/{username}")
    @PreAuthorize("hasRole(#course+'-assistant') or (#username == authentication.name)")
    fun getTask(
        @PathVariable course: String,
        @PathVariable assignment: String,
        @PathVariable task: String,
        @PathVariable username: String,
    ): TaskWorkspace =
        courseService.getTask(course, assignment, task, resolveUserId(username))

    @PostMapping("/{course}/assignments/{assignment}/tasks/{task}/submit")
    @PreAuthorize("hasRole(#course) and (#submission.restricted or hasRole(#course + '-assistant'))")
    fun evaluateTaskSubmission(
        @PathVariable course: String,
        @PathVariable assignment: String,
        @PathVariable task: String,
        @RequestBody submission: SubmissionDTO,
        authentication: Authentication,
    ): DeferredResult<ResponseEntity<Void>> {
        val userId = roleService.getUserId(authentication.name)
        submission.userId = userId

        // Non-blocking: drop the submission on the queue and free the request thread. A worker grades
        // it and completes the DeferredResult, at which point Spring writes the response -- the same
        // "empty 200 when grading is done" contract as before, minus one parked Tomcat thread per call.
        val deferred = DeferredResult<ResponseEntity<Void>>(requestTimeoutSeconds * 1000)
        val future = submissionQueueService.enqueue(course, assignment, task, submission)
        if (future == null) {
            // Backpressure: the queue is full -- shed load politely instead of accepting unbounded work.
            deferred.setResult(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build())
            return deferred
        }
        future.whenComplete { _, ex ->
            if (ex == null) {
                deferred.setResult(ResponseEntity.ok().build())
            } else {
                // Preserve the original error semantics (e.g. 403 no-attempts, 404 unknown task) by
                // handing the real exception, unwrapped, to Spring's async exception handling.
                val cause = (ex as? CompletionException)?.cause ?: ex
                deferred.setErrorResult(cause)
            }
        }
        deferred.onTimeout {
            deferred.setResult(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build())
        }
        return deferred
    }

    private fun emitterTypeFor(course: String, authentication: Authentication): EmitterType {
        val supervisor = authentication.authorities.any {
            it.authority == "$course-supervisor" || it.authority == "supervisor"
        }
        return if (supervisor) EmitterType.SUPERVISOR else EmitterType.STUDENT
    }


    // A text event endpoint to publish events to clients
    @GetMapping("/{course}/subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun subscribe(@PathVariable course: String, authentication: Authentication): ResponseEntity<SseEmitter> {
        val emitter = emitterService.registerEmitter(emitterTypeFor(course, authentication), course, authentication.name)
        return ResponseEntity.ok()
            .header("Cache-Control", "no-transform") // needed to work with webpack-dev-server
            .body(emitter)
    }

    // Sent by the client to keep the emitter alive
    @PutMapping("/{course}/heartbeat/{emitterId}")
    fun heartbeat(@PathVariable course: String, @PathVariable emitterId: String, authentication: Authentication) {
        val emitterType = emitterTypeFor(course, authentication)
        emitterService.keepAliveEmitter(emitterType, course, emitterId)

        if (emitterType == EmitterType.STUDENT) {
            visitQueueService.record(emitterId)
        }
    }

    @GetMapping("/{courseSlug}/points")
    @PreAuthorize("hasRole(#courseSlug + '-assistant')")
    fun getStudentsWithPoints(@PathVariable courseSlug: String): List<StudentDTO> {
        return courseService.getStudentsWithPoints(courseSlug)
    }

    @GetMapping("/{courseSlug}/assignmentPoints", produces = ["text/csv"])
    @PreAuthorize("hasRole(#courseSlug + '-assistant')")
    fun getStudentsWithAssignmentPoints(
        @PathVariable courseSlug: String,
        response: HttpServletResponse
    ) {
        val rows = courseService.getStudentsWithAssignmentPoints(courseSlug)
        response.apply {
            setHeader("Content-Disposition", "attachment; filename=access_points_${courseSlug}.csv")
            contentType = "text/csv"
        }
        response.outputStream.writer(StandardCharsets.UTF_8).use { writer ->
            rows.forEach { row -> writer.appendLine(row.joinToString(",", transform = ::csvCell)) }
        }
    }

    private fun csvCell(value: String?): String {
        val v = value.orEmpty()
        val needsQuotes = v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    @GetMapping("/{courseSlug}/users")
    @PreAuthorize("hasRole(#courseSlug + '-assistant')")
    fun getUsersWithPoints(@PathVariable courseSlug: String): List<StudentDTO> {
        // this includes assistants and supervisors
        return courseService.getStudentsWithPoints(courseSlug, true)
    }

    @GetMapping("/{courseSlug}/students")
    @PreAuthorize("hasRole(#courseSlug + '-assistant')")
    fun getStudents(@PathVariable courseSlug: String): List<StudentDTO> {
        return courseService.getStudents(courseSlug)
    }

    @GetMapping("/{courseSlug}/participants")
    fun getParticipants(@PathVariable courseSlug: String): List<StudentDTO> {
        return courseService.getStudents(courseSlug)
            .filter { it.email != null && it.firstName != null && it.lastName != null }
    }


    private fun updateRoles(slug: String, registrationIDs: List<String>, role: Role) {
        val course = courseService.getCourseBySlug(slug)
        // Saves the list of registrationIDs in the database
        val (remove, add) = courseService.updateCourseRegistration(course, registrationIDs, role)
        // Grants the correct role to any existing users in usernames.
        // This is one of two ways a keycloak user can receive/lose a role, the other way is on first login.
        roleService.updateRoleUsers(course, remove, add, role)
    }

    @PostMapping("/{course}/supervisors")
    fun registerSupervisors(@PathVariable course: String, @RequestBody registrationIDs: List<String>) =
        updateRoles(course, registrationIDs, Role.SUPERVISOR)

    @PostMapping("/{course}/assistants")
    fun registerAssistants(@PathVariable course: String, @RequestBody registrationIDs: List<String>) =
        updateRoles(course, registrationIDs, Role.ASSISTANT)

    @PostMapping("/{course}/participants")
    @Caching(
        evict = [
            CacheEvict(
                value = ["CourseService.getStudents", "ExampleService.computeSubmissionsCount"],
                key = "#course"
            ),
            CacheEvict(value = ["CourseService.getCoursesOverview"], allEntries = true)
        ]
    )
    fun registerParticipants(@PathVariable course: String, @RequestBody registrationIDs: List<String>) =
        updateRoles(course, registrationIDs, Role.STUDENT)

    private fun resolveUserId(username: String): String =
        roleService.getUserId(username)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No participant $username")

    @GetMapping("/{course}/participants/{username}")
    fun getCourseProgress(
        @PathVariable course: String,
        @PathVariable username: String,
        options: ProgressOptions,
    ): CourseProgressDTO =
        courseService.getCourseProgress(course, username, resolveUserId(username), options)

    @GetMapping("/{course}/participants/{username}/assignments/{assignment}")
    fun getAssignmentProgress(
        @PathVariable course: String,
        @PathVariable assignment: String,
        @PathVariable username: String,
        options: ProgressOptions,
    ): AssignmentProgressDTO =
        courseService.getAssignmentProgress(course, assignment, username, resolveUserId(username), options)

    @GetMapping("/{course}/participants/{username}/assignments/{assignment}/tasks/{task}")
    fun getTaskProgress(
        @PathVariable course: String,
        @PathVariable assignment: String,
        @PathVariable task: String,
        @PathVariable username: String,
        options: ProgressOptions,
    ): TaskProgressDTO =
        courseService.getTaskProgress(course, assignment, task, username, resolveUserId(username), options)

    @GetMapping("/{course}/summary")
    fun getCourseSummary(@PathVariable course: String): CourseSummary =
        courseService.getCourseSummary(course)
}
