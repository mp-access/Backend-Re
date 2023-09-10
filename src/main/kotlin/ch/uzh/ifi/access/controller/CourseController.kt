package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.*
import ch.uzh.ifi.access.service.CourseService
import ch.uzh.ifi.access.service.RoleService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*


@RestController
class CourseCreationController(
    private val courseService: CourseService,
) {
    @PostMapping("/create")
    @PreAuthorize("hasRole('supervisor')")
    fun createCourse(@RequestBody courseDTO: CourseDTO, authentication: Authentication): String? {
        return courseService.createCourse(courseDTO).slug
    }

}
@RestController
@RequestMapping("/courses")
class CourseController (
    private val courseService: CourseService,
    private val roleService: RoleService
)
    {
    @PostMapping("/contact")
    fun sendMessage(@RequestBody contactDTO: ContactDTO?) {
        courseService.sendMessage(contactDTO!!)
    }

    @PostMapping("/{course}/pull")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun updateCourse(@PathVariable course: String?): String? {
        return courseService.updateCourse(course!!).slug
    }

    @GetMapping("")
    fun getCourses(): List<CourseOverview> {
        return courseService.getCourses()
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
    fun getStudents(@PathVariable course: String): List<StudentDTO?>? {
        return courseService.getStudents(course)
    }

    @GetMapping("/{course}/participants")
    fun getParticipants(@PathVariable course: String): List<StudentDTO?>? {
        return courseService.getStudents(course)
    }

    @PostMapping("/{course}/participants")
    fun registerParticipants(@PathVariable course: String, @RequestBody students: List<String>) {
        roleService.registerParticipants(course, students)
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
        ): EvaluationSummary? {
            return courseService.getTaskProgress(course, assignment, task, participant)
        }

        @GetMapping("/{course}/summary")
    fun getCourseSummary(@PathVariable course: String): CourseSummary? {
        return courseService.getCourseSummary(course)
    }


    }