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

    @GetMapping("")
    fun getCourses(): List<CourseOverview> {
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
        @PathVariable example: String,
        @RequestBody submission: SubmissionDTO,
        authentication: Authentication
    ) {
        submission.userId = authentication.name
        // Is there a better way than passing null to assignmentSlug?
        courseService.createSubmission(course, null, example, submission)
    }


    @GetMapping("/{course}/examples/{example}/users/{user}")
    @PreAuthorize("hasRole(#course+'-assistant') or (#user == authentication.name)")
    fun getExample(
        @PathVariable course: String,
        @PathVariable example: String,
        @PathVariable user: String
    ): TaskWorkspace {
        return courseService.getExample(course, example, user)
    }

    // TODO: Change this to returning TaskOverview, as we don't need more information. However, when changing it, an error occurs in the courses/{course} endpoint.
    @GetMapping("/{course}/examples")
    @PreAuthorize("hasRole(#course)")
    fun getExamples(
        @PathVariable course: String,
        authentication: Authentication
    ): List<TaskWorkspace> {
        return courseService.getExamples(course)
    }

    // Invoked by the teacher when publishing an example to inform the students
    @PostMapping("/{course}/examples/{example}/publish")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun publishExample(
        @PathVariable course: String,
        @PathVariable example: String,
        @RequestBody body: ExampleDurationDTO,
    ) {
        val activeExample = courseService.getExamples(course).firstOrNull {
            it.status == TaskStatus.Interactive
        }

        if (activeExample != null) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "An interactive example already exists."
            )
        }

        courseService.publishExampleBySlug(course, example, body.duration)

        emitterService.sendMessage(course, "redirect", "/courses/$course/examples/$example")
        emitterService.sendMessage(course, "timer-update", "${body.duration}/${body.duration}")
    }

    // Invoked by the teacher when want to extend the time of an active example by a certain amount of seconds
    @PutMapping("/{course}/examples/{example}/extend")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun extendExampleDeadline(
        @PathVariable course: String,
        @PathVariable example: String,
        @RequestBody body: ExampleDurationDTO,
    ) {
        val updatedExample = courseService.extendExampleDeadlineBySlug(course, example, body.duration)

        val totalDuration = Duration.between(updatedExample.start!!, updatedExample.end!!).toSeconds()
        val secondsLeft = Duration.between(LocalDateTime.now(), updatedExample.end!!).toSeconds()

        emitterService.sendMessage(
            course,
            "timer-extend",
            "Submission time extended by the lecturer by ${body.duration} seconds."
        )
        emitterService.sendMessage(course, "timer-update", "$secondsLeft/$totalDuration")
    }

    // Invoked by the teacher when want to terminate the active example
    @PutMapping("/{course}/examples/{example}/terminate")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun terminateExample(
        @PathVariable course: String,
        @PathVariable example: String
    ) {
        courseService.terminateExampleBySlug(course, example)

        emitterService.sendMessage(course, "terminate", "Example terminated by teacher.")
    }

    // A text event endpoint to publish events to clients
    @GetMapping("/{course}/subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun subscribe(@PathVariable course: String, authentication: Authentication): ResponseEntity<SseEmitter> {
        val headers = HttpHeaders()
        val emitter = emitterService.registerEmitter(course, authentication.name)
        headers.add("Cache-Control", "no-transform") // needed to work with webpack-dev-server
        return ResponseEntity<SseEmitter>(emitter, headers, HttpStatus.OK)
    }

    // Sent by the client to keep the emitter alive
    @GetMapping("/{course}/heartbeat/{emitterId}")
    fun heartbeat(@PathVariable course: String, @PathVariable emitterId: String) {
        emitterService.keepAliveEmitter(course, emitterId)
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
    private fun updateRoles(slug: String, registrationIDs: List<String>, role: Role) {
        val assessment = courseService.getCourseBySlug(slug)
        // Saves the list of registrationIDs in the database
        val (remove, add) = courseService.updateCourseRegistration(assessment, registrationIDs, role)
        // Grants the correct role to any existing users in usernames
        // This is one of two ways a keycloak user can receive/lose a role, the other way is on first login.
        roleService.updateRoleUsers(assessment, remove, add, role)
    }

    @PostMapping("/{course}/assistants")
    fun registerAssistants(@PathVariable course: String, @RequestBody registrationIDs: List<String>) {
        return updateRoles(course, registrationIDs, Role.ASSISTANT)
    }

    @PostMapping("/{course}/participants")
    fun registerParticipants(@PathVariable course: String, @RequestBody registrationIDs: List<String>) {
        return updateRoles(course, registrationIDs, Role.STUDENT)
    }

    @GetMapping("/{course}/participants/{participant}")
    fun getCourseProgress(
        @PathVariable course: String, @PathVariable participant: String,
        @RequestParam(required = true, defaultValue = "1") submissionLimit: Int,
        @RequestParam(required = true, defaultValue = "true") includeGrade: Boolean,
        @RequestParam(required = true, defaultValue = "false") includeTest: Boolean,
        @RequestParam(required = true, defaultValue = "false") includeRun: Boolean,
    ): CourseProgressDTO? {
        val user = roleService.findUserByAllCriteria(participant) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No participant $participant"

        )
        // TODO: reduce the number of parameters being passed around
        return courseService.getCourseProgress(
            course,
            user.username,
            submissionLimit,
            includeGrade,
            includeTest,
            includeRun
        )
    }

    @GetMapping("/{course}/participants/{participant}/assignments/{assignment}")
    fun getAssignmentProgress(
        @PathVariable course: String,
        @PathVariable assignment: String,
        @PathVariable participant: String,
        @RequestParam(required = true, defaultValue = "1") submissionLimit: Int,
        @RequestParam(required = true, defaultValue = "true") includeGrade: Boolean,
        @RequestParam(required = true, defaultValue = "false") includeTest: Boolean,
        @RequestParam(required = true, defaultValue = "false") includeRun: Boolean,
    ): AssignmentProgressDTO? {
        val user = roleService.findUserByAllCriteria(participant) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No participant $participant"

        )
        return courseService.getAssignmentProgress(
            course, assignment, user.username,
            submissionLimit,
            includeGrade,
            includeTest,
            includeRun
        )
    }

    @GetMapping("/{course}/participants/{participant}/assignments/{assignment}/tasks/{task}")
    fun getTaskProgress(
        @PathVariable course: String, @PathVariable assignment: String,
        @PathVariable task: String, @PathVariable participant: String,
        @RequestParam(required = true, defaultValue = "1") submissionLimit: Int,
        @RequestParam(required = true, defaultValue = "true") includeGrade: Boolean,
        @RequestParam(required = true, defaultValue = "false") includeTest: Boolean,
        @RequestParam(required = true, defaultValue = "false") includeRun: Boolean,
    ): TaskProgressDTO? {
        val user = roleService.findUserByAllCriteria(participant) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No participant $participant"

        )
        return courseService.getTaskProgress(
            course, assignment, task, user.username,
            submissionLimit,
            includeGrade,
            includeTest,
            includeRun
        )
    }

    @GetMapping("/{course}/summary")
    fun getCourseSummary(@PathVariable course: String): CourseSummary? {
        return courseService.getCourseSummary(course)
    }
}
