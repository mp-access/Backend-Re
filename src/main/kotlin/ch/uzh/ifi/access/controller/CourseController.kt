package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.model.constants.TaskStatus
import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.*
import ch.uzh.ifi.access.service.CourseService
import ch.uzh.ifi.access.service.CourseServiceForCaching
import ch.uzh.ifi.access.service.EmitterService
import ch.uzh.ifi.access.service.RoleService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Semaphore


@RestController
class CourseRootController(
    private val courseService: CourseService,
) {
    @PostMapping("/create")
    @PreAuthorize("hasRole('supervisor')")
    fun createCourse(@RequestBody courseDTO: CourseDTO, authentication: Authentication): String? {
        return courseService.createCourse(courseDTO).slug
    }

    @PostMapping("/edit")
    @PreAuthorize("hasRole('supervisor')")
    fun editCourse(@RequestBody courseDTO: CourseDTO, authentication: Authentication): String? {
        return courseService.editCourse(courseDTO).slug
    }

    /*
    @GetMapping("/pruneSubmissions")
    @PreAuthorize("hasRole('supervisor')")
    fun pruneSubmissions(@RequestBody courseDTO: CourseDTO, authentication: Authentication): String? {
        courseService.globalPruneSubmissions()
        return "done"
    }
    */

    @PostMapping("/contact")
    fun sendMessage(@RequestBody contactDTO: ContactDTO?) {
        courseService.sendMessage(contactDTO!!)
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
        @RequestHeader("X-Gitlab-Token") secret: String
    ) {
        logger.debug { "webhook (secret) triggered for $course" }
        courseService.webhookUpdateWithSecret(course, secret)
    }

    @PostMapping("/courses/{course}/update/github")
    fun hookGithub(
        @PathVariable("course") course: String,
        @RequestHeader("X-Hub-Signature-256") signature: String,
        @RequestBody body: String
    ) {
        logger.debug { "webhook (hmac) triggered for $course" }
        val sig = signature.substringAfter("sha256=")
        courseService.webhookUpdateWithHmac(course, sig, body)
    }

}


@RestController
@RequestMapping("/courses")
@EnableAsync
class CourseController(
    private val courseService: CourseService,
    private val courseServiceForCaching: CourseServiceForCaching,
    private val roleService: RoleService,
    private val emitterService: EmitterService,
) {
    private val logger = KotlinLogging.logger {}

    @PostMapping("/{course}/pull")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun updateCourse(@PathVariable course: String?): String? {
        return courseService.updateCourse(course!!).slug
    }

    private val semaphore = Semaphore(1)
    private fun updateRoles(authentication: Authentication) {
        val username = authentication.name
        try {
            semaphore.acquire()
            roleService.findUserByAllCriteria(username)?.let { user ->
                val attributes = user.attributes ?: mutableMapOf()
                if (attributes["roles_synced_at"] == null) {
                    user.attributes = attributes
                    roleService.getUserResourceById(user.id).update(user)
                    val searchNames = buildList {
                        add(user.username)
                        user.email?.let { add(it) }
                        attributes["swissEduIDLinkedAffiliationMail"]?.let { addAll(it) }
                        attributes["swissEduIDAssociatedMail"]?.let { addAll(it) }
                    }
                    val roles = courseService.getUserRoles(searchNames)
                    roleService.setFirstLoginRoles(user, roles)
                    logger.debug { "Enrolled first-time user $username in their courses" }
                    attributes["roles_synced_at"] = listOf(LocalDateTime.now().toString())
                }
            }
        } catch (e: Exception) {
            logger.error { "Error looking up user $username: ${e.message}" }
            throw e
        } finally {
            semaphore.release()
            logger.debug { "Released semaphore (${semaphore.queueLength} waiting, ${semaphore.availablePermits()} available) for user lookup: $username" }
        }
    }

    @GetMapping("")
    fun getCourses(authentication: Authentication): List<CourseOverview> {
        updateRoles(authentication)
        return courseService.getCoursesOverview()
    }

    @GetMapping("/{course}")
    //@PreAuthorize("hasRole(#course) or hasRole(#course+'-supervisor')")
    @PreAuthorize("hasRole(#course)")
    fun getCourseWorkspace(@PathVariable course: String?): CourseWorkspace {
        return courseService.getCourseWorkspaceBySlug(course!!)
    }

    @GetMapping("/{course}/assignments/{assignment}")
    fun getAssignment(@PathVariable course: String?, @PathVariable assignment: String?): AssignmentWorkspace {
        return courseService.getAssignment(course, assignment!!)
    }

    @GetMapping("/{course}/assignments/{assignment}/tasks/{task}/users/{user}")
    @PreAuthorize("hasRole(#course+'-assistant') or (#user == authentication.name)")
    fun getTask(
        @PathVariable course: String?,
        @PathVariable assignment: String?,
        @PathVariable task: String?,
        @PathVariable user: String?
    ): TaskWorkspace {
        return courseService.getTask(course, assignment, task, user)
    }

    @PostMapping("/{course}/assignments/{assignment}/tasks/{task}/submit")
    @PreAuthorize("hasRole(#course) and (#submission.restricted or hasRole(#course + '-assistant'))")
    fun evaluateTaskSubmission(
        @PathVariable course: String,
        @PathVariable assignment: String,
        @PathVariable task: String?,
        @RequestBody submission: SubmissionDTO,
        authentication: Authentication
    ) {
        submission.userId = authentication.name
        // TODO: what prevents a client from sending a grading command with restricted = false?
        courseService.createSubmission(course, assignment, task!!, submission)
    }

    @PostMapping("/{course}/examples/{example}/submit")
    @PreAuthorize("hasRole(#course) and (#submission.restricted or hasRole(#course + '-assistant'))")
    fun evaluateExampleSubmission(
        @PathVariable course: String,
        @PathVariable example: String?,
        @RequestBody submission: SubmissionDTO,
        authentication: Authentication
    ) {
        submission.userId = authentication.name
        // Is there a better way than passing null to assignmentSlug?
        courseService.createSubmission(course, null, example!!, submission)
    }


    @GetMapping("/{course}/examples/{example}/users/{user}")
    @PreAuthorize("hasRole(#course+'-assistant') or (#user == authentication.name)")
    fun getExample(
        @PathVariable course: String?,
        @PathVariable example: String?,
        @PathVariable user: String?
    ): TaskWorkspace {
        return courseService.getExample(course, example, user)
    }

    // TODO: Change this to returning TaskOverview, as we don't need more information. However, when changing it, an error occurs in the courses/{course} endpoint.
    @GetMapping("/{courseSlug}/examples")
    @PreAuthorize("hasRole(#courseSlug)")
    fun getExamples(
        @PathVariable courseSlug: String,
        authentication: Authentication
    ): List<TaskWorkspace> {
        return courseService.getExamples(courseSlug)
    }

    // Invoked by the teacher when publishing an example to inform the students
    @PostMapping("/{courseSlug}/examples/{exampleSlug}/publish")
    @PreAuthorize("hasRole(#courseSlug+'-supervisor')")
    fun publishExample(
        @PathVariable courseSlug: String,
        @PathVariable exampleSlug: String,
        @RequestBody body: ExampleDurationDTO,
    ) {
        val activeExample = courseService.getExamples(courseSlug).filter {
            it.status == TaskStatus.Interactive
        }.firstOrNull()

        if (activeExample != null) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "An interactive example already exists."
            )
        }

        courseService.publishExampleBySlug(courseSlug, exampleSlug, body.duration)

        emitterService.sendMessage(courseSlug, "redirect", "/courses/$courseSlug/examples/$exampleSlug")
        emitterService.sendMessage(courseSlug, "timer-update", "${body.duration}/${body.duration}")
    }

    // Invoked by the teacher when want to extend the time of an active example by a certain amount of seconds
    @PutMapping("/{courseSlug}/examples/{exampleSlug}/extend")
    @PreAuthorize("hasRole(#courseSlug+'-supervisor')")
    fun extendExampleDeadline(
        @PathVariable courseSlug: String,
        @PathVariable exampleSlug: String,
        @RequestBody body: ExampleDurationDTO,
    ) {
        val updatedExample = courseService.extendExampleDeadlineBySlug(courseSlug, exampleSlug, body.duration)

        val totalDuration = Duration.between(updatedExample.start!!, updatedExample.end!!).toSeconds();
        val secondsLeft = Duration.between(LocalDateTime.now(), updatedExample.end!!).toSeconds();

        emitterService.sendMessage(
            courseSlug,
            "timer-extend",
            "Submission time extended by the lecturer by ${body.duration} seconds."
        )
        emitterService.sendMessage(courseSlug, "timer-update", "$secondsLeft/$totalDuration")
    }

    // Invoked by the teacher when want to terminate the active example
    @PutMapping("/{courseSlug}/examples/{exampleSlug}/terminate")
    @PreAuthorize("hasRole(#courseSlug+'-supervisor')")
    fun terminateExample(
        @PathVariable courseSlug: String,
        @PathVariable exampleSlug: String
    ) {
        courseService.terminateExampleBySlug(courseSlug, exampleSlug)

        emitterService.sendMessage(courseSlug, "terminate", "Example terminated by teacher.")
    }

    // A text event endpoint to publish events to clients
    @GetMapping("/{courseSlug}/subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun subscribe(@PathVariable courseSlug: String, authentication: Authentication): ResponseEntity<SseEmitter> {
        val headers = HttpHeaders()
        val emitter = emitterService.registerEmitter(courseSlug, authentication.name)
        headers.add("Cache-Control", "no-transform") // needed to work with webpack-dev-server
        return ResponseEntity<SseEmitter>(emitter, headers, HttpStatus.OK)
    }

    // Sent by the client to keep the emitter alive
    @GetMapping("/{courseSlug}/heartbeat/{emitterId}")
    fun heartbeat(@PathVariable courseSlug: String, @PathVariable emitterId: String) {
        emitterService.keepAliveEmitter(courseSlug, emitterId)
    }

    @GetMapping("/{courseSlug}/studentPoints")
    @PreAuthorize("hasRole(#courseSlug + '-assistant')")
    fun getStudentsWithPoints(@PathVariable courseSlug: String): List<StudentDTO> {
        return courseService.getStudentsWithPoints(courseSlug)
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


    //@Transactional
    private fun assignRoles(slug: String, loginNames: List<String>, role: Role) {
        val assessment = courseService.getCourseBySlug(slug)
        // Saves the list of usernames in the database
        val (remove, add) = courseService.setRoleUsers(assessment, loginNames, role)
        // Grants the correct role to any existing users in usernames
        // This is one of two ways a keycloak user can receive/lose a role, the other way is on first login.
        roleService.setRoleUsers(assessment, remove, add, role)
        //return courseService.getAssessmentDetailsBySlug(slug)
    }

    @PostMapping("/{course}/assistants")
    //@PreAuthorize("hasAuthority('API_KEY') or hasRole('owner')")
    fun registerAssistants(@PathVariable course: String, @RequestBody usernames: List<String>) {
        return assignRoles(course, usernames, Role.ASSISTANT)
    }

    @PostMapping("/{course}/participants")
    fun registerParticipants(@PathVariable course: String, @RequestBody participants: List<String>) {
        return assignRoles(course, participants, Role.STUDENT)
    }

    @GetMapping("/{course}/participants/{participant}")
    fun getCourseProgress(@PathVariable course: String, @PathVariable participant: String): CourseProgressDTO? {
        val user = roleService.findUserByAllCriteria(participant) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No participant $participant"
        )
        return courseService.getCourseProgress(course, user.username)
    }

    @GetMapping("/{course}/participants/{participant}/assignments/{assignment}")
    fun getAssignmentProgress(
        @PathVariable course: String,
        @PathVariable assignment: String,
        @PathVariable participant: String
    ): AssignmentProgressDTO? {
        val user = roleService.findUserByAllCriteria(participant) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No participant $participant"
        )
        return courseService.getAssignmentProgress(course, assignment, user.username)
    }

    @GetMapping("/{course}/participants/{participant}/assignments/{assignment}/tasks/{task}")
    fun getTaskProgress(
        @PathVariable course: String, @PathVariable assignment: String,
        @PathVariable task: String, @PathVariable participant: String
    ): TaskProgressDTO? {
        val user = roleService.findUserByAllCriteria(participant) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No participant $participant"
        )
        return courseService.getTaskProgress(course, assignment, task, user.username)
    }

    @GetMapping("/{course}/summary")
    fun getCourseSummary(@PathVariable course: String): CourseSummary? {
        return courseService.getCourseSummary(course)
    }
}