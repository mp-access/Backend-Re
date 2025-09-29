package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.*
import ch.uzh.ifi.access.service.*
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.springframework.cache.annotation.CacheEvict
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
@EnableAsync
class CourseController(
    private val courseService: CourseService,
    private val roleService: RoleService,
    private val emitterService: EmitterService,
    private val submissionService: SubmissionService,
    private val visitQueueService: VisitQueueService
) {
    private val logger = KotlinLogging.logger {}

    @PostMapping("/{course}/pull")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun updateCourse(@PathVariable course: String?): String? {
        return courseService.updateCourse(course!!).slug
    }

    @GetMapping("")
    fun getCourses(request: HttpServletRequest): List<CourseOverview> {
        val userId = roleService.getUserId() ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Cannot resolve user ID"
        )
        val courseOverview = courseService.getCoursesOverview(userId)
        return courseOverview
        // TODO: implement correctly for public courses
        /*
        val courses = courseService.getCoursesOverview()
        val now = LocalDateTime.now()
        return courses.filter { course ->
            if (roleService.isSupervisor(course.slug!!)) {
                true
            } else {
                val visibility = if (course.overrideVisibility != null &&
                    course.overrideStart!! < now &&
                    (course.overrideEnd == null || course.overrideEnd!! > now)
                ) {
                    course.overrideVisibility
                } else {
                    course.defaultVisibility
                }
                visibility == Visibility.PUBLIC ||
                (visibility == Visibility.REGISTERED &&
                request.isUserInRole(course.slug))
            }
        }
        */

    }

    @GetMapping("/{course}")
    @PreAuthorize("hasRole(#course)")
    fun getCourseWorkspace(@PathVariable course: String?): CourseWorkspace {
        return courseService.getCourseWorkspaceBySlug(course!!)
    }

    @GetMapping("/{course}/assignments/{assignment}")
    fun getAssignment(@PathVariable course: String?, @PathVariable assignment: String?): AssignmentWorkspace {
        return courseService.getAssignment(course, assignment!!)
    }

    @GetMapping("/{course}/assignments/{assignment}/tasks/{task}/users/{username}")
    @PreAuthorize("hasRole(#course+'-assistant') or (#username == authentication.name)")
    fun getTask(
        @PathVariable course: String,
        @PathVariable assignment: String,
        @PathVariable task: String,
        @PathVariable username: String,
    ): TaskWorkspace {
        val userId = roleService.getUserId(username) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No participant $username"
        )
        return courseService.getTask(course, assignment, task, username, userId)
    }

    @PostMapping("/{course}/assignments/{assignment}/tasks/{task}/submit")
    @PreAuthorize("hasRole(#course) and (#submission.restricted or hasRole(#course + '-assistant'))")
    fun evaluateTaskSubmission(
        @PathVariable course: String,
        @PathVariable assignment: String,
        @PathVariable task: String?,
        @RequestBody submission: SubmissionDTO,
        authentication: Authentication,
    ) {
        val userId = roleService.getUserId(authentication.name)
        submission.userId = userId

        submissionService.createTaskSubmission(course, assignment, task!!, submission)
    }

    // A text event endpoint to publish events to clients
    @GetMapping("/{course}/subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun subscribe(@PathVariable course: String, authentication: Authentication): ResponseEntity<SseEmitter> {
        val headers = HttpHeaders()
        val emitterType = if (roleService.isSupervisor(course)) EmitterType.SUPERVISOR else EmitterType.STUDENT
        val emitter = emitterService.registerEmitter(emitterType, course, authentication.name)
        headers.add("Cache-Control", "no-transform") // needed to work with webpack-dev-server
        return ResponseEntity<SseEmitter>(emitter, headers, HttpStatus.OK)
    }

    // Sent by the client to keep the emitter alive
    @PutMapping("/{course}/heartbeat/{emitterId}")
    fun heartbeat(@PathVariable course: String, @PathVariable emitterId: String) {
        val emitterType = if (roleService.isSupervisor(course)) EmitterType.SUPERVISOR else EmitterType.STUDENT
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


    //@Transactional
    private fun updateRoles(slug: String, registrationIDs: List<String>, role: Role) {
        val assessment = courseService.getCourseBySlug(slug)
        // Saves the list of registrationIDs in the database
        val (remove, add) = courseService.updateCourseRegistration(assessment, registrationIDs, role)
        // Grants the correct role to any existing users in usernames
        // This is one of two ways a keycloak user can receive/lose a role, the other way is on first login.
        roleService.updateRoleUsers(assessment, remove, add, role)
    }

    @PostMapping("/{course}/supervisors")
    fun registerSupervisors(@PathVariable course: String, @RequestBody registrationIDs: List<String>) {
        return updateRoles(course, registrationIDs, Role.SUPERVISOR)
    }

    @PostMapping("/{course}/assistants")
    fun registerAssistants(@PathVariable course: String, @RequestBody registrationIDs: List<String>) {
        return updateRoles(course, registrationIDs, Role.ASSISTANT)
    }

    @PostMapping("/{course}/participants")
    @CacheEvict(value = ["CourseService.getStudents", "ExampleService.computeSubmissionsCount"], key = "#course")
    fun registerParticipants(@PathVariable course: String, @RequestBody registrationIDs: List<String>) {
        return updateRoles(course, registrationIDs, Role.STUDENT)
    }

    @GetMapping("/{course}/participants/{username}")
    fun getCourseProgress(
        @PathVariable course: String, @PathVariable username: String,
        @RequestParam(required = true, defaultValue = "1") submissionLimit: Int,
        @RequestParam(required = true, defaultValue = "true") includeGrade: Boolean,
        @RequestParam(required = true, defaultValue = "false") includeTest: Boolean,
        @RequestParam(required = true, defaultValue = "false") includeRun: Boolean,
    ): CourseProgressDTO? {
        val userId = roleService.getUserId(username) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No participant $username"
        )
        // TODO: reduce the number of parameters being passed around
        return courseService.getCourseProgress(
            course,
            username,
            userId,
            submissionLimit,
            includeGrade,
            includeTest,
            includeRun
        )
    }

    @GetMapping("/{course}/participants/{username}/assignments/{assignment}")
    fun getAssignmentProgress(
        @PathVariable course: String,
        @PathVariable assignment: String,
        @PathVariable username: String,
        @RequestParam(required = true, defaultValue = "1") submissionLimit: Int,
        @RequestParam(required = true, defaultValue = "true") includeGrade: Boolean,
        @RequestParam(required = true, defaultValue = "false") includeTest: Boolean,
        @RequestParam(required = true, defaultValue = "false") includeRun: Boolean,
    ): AssignmentProgressDTO? {
        val userId = roleService.getUserId(username) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No participant $username"
        )
        return courseService.getAssignmentProgress(
            course,
            assignment,
            username,
            userId,
            submissionLimit,
            includeGrade,
            includeTest,
            includeRun
        )
    }

    @GetMapping("/{course}/participants/{username}/assignments/{assignment}/tasks/{task}")
    fun getTaskProgress(
        @PathVariable course: String, @PathVariable assignment: String,
        @PathVariable task: String, @PathVariable username: String,
        @RequestParam(required = true, defaultValue = "1") submissionLimit: Int,
        @RequestParam(required = true, defaultValue = "true") includeGrade: Boolean,
        @RequestParam(required = true, defaultValue = "false") includeTest: Boolean,
        @RequestParam(required = true, defaultValue = "false") includeRun: Boolean,
    ): TaskProgressDTO? {
        val userId = roleService.getUserId(username) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No participant $username"
        )
        return courseService.getTaskProgress(
            course,
            assignment,
            task,
            username,
            userId,
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
