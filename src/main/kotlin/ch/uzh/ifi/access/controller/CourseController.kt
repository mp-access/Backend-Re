package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.*
import ch.uzh.ifi.access.service.CourseService
import ch.uzh.ifi.access.service.CourseServiceForCaching
import ch.uzh.ifi.access.service.RoleService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException


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
    fun hookGitlab(@PathVariable("course") course: String,
                     @RequestHeader("X-Gitlab-Token") secret: String) {
        logger.debug { "webhook (secret) triggered for $course"}
        courseService.webhookUpdateWithSecret(course, secret)
    }
    @PostMapping("/courses/{course}/update/github")
    fun hookGithub(@PathVariable("course") course: String,
                   @RequestHeader("X-Hub-Signature-256") signature: String,
                   @RequestBody body: String
    ) {
        logger.debug { "webhook (hmac) triggered for $course"}
        val sig = signature.substringAfter("sha256=")
        courseService.webhookUpdateWithHmac(course, sig, body)
    }

}


@RestController
@RequestMapping("/courses")
class CourseController (
    private val courseService: CourseService,
    private val courseServiceForCaching: CourseServiceForCaching,
    private val roleService: RoleService
)
    {

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
    fun evaluateSubmission(
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

    @GetMapping("/{courseSlug}/studentPoints")
    @PreAuthorize("hasRole(#courseSlug + '-assistant')")
    fun getStudentsWithPoints(@PathVariable courseSlug: String): List<StudentDTO> {
        return courseServiceForCaching.getStudentsWithPoints(courseSlug)
    }

    @GetMapping("/{courseSlug}/students")
    @PreAuthorize("hasRole(#courseSlug + '-assistant')")
    fun getStudents(@PathVariable courseSlug: String): List<StudentDTO> {
        return courseServiceForCaching.getStudents(courseSlug)
    }

    @GetMapping("/{courseSlug}/participants")
    fun getParticipants(@PathVariable courseSlug: String): List<StudentDTO> {
        return courseServiceForCaching.getStudents(courseSlug)
            .filter { it.email != null && it.firstName != null && it.lastName != null }
    }

    @PostMapping("/{course}/participants")
    fun registerParticipants(@PathVariable course: String, @RequestBody students: List<String>) {
        // set list of course students
        courseService.setStudents(course, students)
        // update keycloak roles
        roleService.updateStudentRoles(course, students.toSet(),
            Role.STUDENT.withCourse(course))
    }

    @PostMapping("/{course}/assistants")
    //@PreAuthorize("hasRole('supervisor')")
    fun registerAssistants(@PathVariable course: String, @RequestBody assistants: List<String>) {
        // set list of course students
        courseService.setAssistants(course, assistants)
        // update keycloak roles
        roleService.updateStudentRoles(course, assistants.toSet(),
                                       Role.ASSISTANT.withCourse(course))
    }

    @GetMapping("/{course}/participants/{participant}")
    fun getCourseProgress(@PathVariable course: String, @PathVariable participant: String): CourseProgressDTO? {
        val user = roleService.getUserByUsername(participant)?:
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No participant $participant")
        return courseService.getCourseProgress(course, user.username)
    }

    @GetMapping("/{course}/participants/{participant}/assignments/{assignment}")
    fun getAssignmentProgress(
        @PathVariable course: String,
        @PathVariable assignment: String,
        @PathVariable participant: String
    ): AssignmentProgressDTO? {
        val user = roleService.getUserByUsername(participant)?:
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No participant $participant")
        return courseService.getAssignmentProgress(course, assignment, user.username)
    }

    @GetMapping("/{course}/participants/{participant}/assignments/{assignment}/tasks/{task}")
    fun getTaskProgress(
        @PathVariable course: String, @PathVariable assignment: String,
        @PathVariable task: String, @PathVariable participant: String
    ): TaskProgressDTO? {
        val user = roleService.getUserByUsername(participant)?:
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No participant $participant")
        return courseService.getTaskProgress(course, assignment, task, user.username)
    }

        @GetMapping("/{course}/summary")
    fun getCourseSummary(@PathVariable course: String): CourseSummary? {
        return courseService.getCourseSummary(course)
    }


    }