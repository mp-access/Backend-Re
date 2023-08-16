package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.model.dto.ContactDTO
import ch.uzh.ifi.access.model.dto.CourseDTO
import ch.uzh.ifi.access.model.dto.StudentDTO
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.projections.AssignmentWorkspace
import ch.uzh.ifi.access.projections.CourseOverview
import ch.uzh.ifi.access.projections.CourseWorkspace
import ch.uzh.ifi.access.projections.TaskWorkspace
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
    fun createCourse(@RequestBody courseDTO: CourseDTO, authentication: Authentication): String {
        return courseService.createCourse(courseDTO.repository).slug
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
    fun updateCourse(@PathVariable course: String?): String {
        return courseService.updateCourse(course!!).slug
    }

    @GetMapping("")
    fun getCourses(): List<CourseOverview> {
        return courseService.getCourses()
    }

    // TODO: remove?
    /*
    @GetMapping("/featured")
    public List<CourseFeature> getFeaturedCourses() {
        return courseService.getFeaturedCourses();
    }*/
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
        @PathVariable course: String?,
        @PathVariable assignment: String?,
        @PathVariable task: String?,
        @RequestBody submission: SubmissionDTO,
        authentication: Authentication
    ) {
        submission.userId = authentication.name
        courseService.createSubmission(course, assignment, task!!, submission)
    }


    @PostMapping("/{course}/participants")
    fun registerParticipants(@PathVariable course: String?, @RequestBody students: List<String>) {
        roleService.registerParticipants(course, students)
    }

    @GetMapping("/{course}/students")
    @PreAuthorize("hasRole(#course + '-assistant')")
    fun getStudents(@PathVariable course: String?): List<StudentDTO?>? {
        return roleService.getStudents(course)
    }

}