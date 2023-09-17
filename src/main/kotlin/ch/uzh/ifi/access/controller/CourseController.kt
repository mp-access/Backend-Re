package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.*
import ch.uzh.ifi.access.service.CourseService
import ch.uzh.ifi.access.service.RoleService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*


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
    @PostMapping("/courses/{course}/update/gitlab")
    fun updateCourse(@PathVariable("course") course: String,
                     @RequestHeader("X-Gitlab-Token") secret: String) {
        courseService.webhookUpdateCourse(course, secret)
    }

}


@RestController
@RequestMapping("/courses")
class CourseController (
    private val courseService: CourseService,
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
        courseService.createSubmission(course, assignment, task!!, submission)
    }

    @GetMapping("/{course}/students")
    @PreAuthorize("hasRole(#course + '-assistant')")
    fun getStudents(@PathVariable course: String): List<StudentDTO?> {
        return courseService.getStudents(course)
    }

    @GetMapping("/{course}/participants")
    fun getParticipants(@PathVariable course: String): List<StudentDTO?> {
        return courseService.getStudents(course).map {
            // setting these fields to null because OLAT can't ignore them TODO: update OLAT
            it.username = null
            it.registrationId = null
            it
        }.filter { it.email != null }
    }

    @PostMapping("/{course}/participants")
    fun registerParticipants(@PathVariable course: String, @RequestBody students: List<String>) {
        // set list of course students
        courseService.registerStudents(course, students)
        // update keycloak roles
        roleService.updateStudentRoles(courseService.getCourseBySlug(course))
    }

        @GetMapping("/{course}/participants/{participant}")
        fun getCourseProgress(@PathVariable course: String, @PathVariable participant: String): CourseProgressDTO? {
            return courseService.getCourseProgress(course, participant)
        }

        @GetMapping("/{course}/participants/{participant}/assignments/{assignment}")
        fun getAssignmentProgress(
            @PathVariable course: String,
            @PathVariable assignment: String,
            @PathVariable participant: String
        ): AssignmentProgressDTO? {
            return courseService.getAssignmentProgress(course, assignment, participant)
        }

        @GetMapping("/{course}/participants/{participant}/assignments/{assignment}/tasks/{task}")
        fun getTaskProgress(
            @PathVariable course: String, @PathVariable assignment: String,
            @PathVariable task: String, @PathVariable participant: String
        ): TaskProgressDTO? {
            return courseService.getTaskProgress(course, assignment, task, participant)
        }

        @GetMapping("/{course}/summary")
    fun getCourseSummary(@PathVariable course: String): CourseSummary? {
        return courseService.getCourseSummary(course)
    }


    }