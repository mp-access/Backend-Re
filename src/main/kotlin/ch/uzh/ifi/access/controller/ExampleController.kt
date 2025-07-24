package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.model.constants.TaskStatus
import ch.uzh.ifi.access.model.dto.ExampleDurationDTO
import ch.uzh.ifi.access.model.dto.ExampleInformationDTO
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.model.dto.SubmissionSseDTO
import ch.uzh.ifi.access.projections.TaskWorkspace
import ch.uzh.ifi.access.service.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.*


@RestController
@RequestMapping("/courses/{course}/examples")
@EnableAsync
class ExampleController(
    private val exampleService: ExampleService,
    private val roleService: RoleService,
    private val emitterService: EmitterService,
    private val courseService: CourseService,
) {
    private val logger = KotlinLogging.logger {}

    // TODO: Change this to returning TaskOverview, as we don't need more information. However, when changing it, an error occurs in the courses/{course} endpoint.
    @GetMapping("")
    @PreAuthorize("hasRole(#course)")
    fun getExamples(
        @PathVariable course: String,
        authentication: Authentication
    ): List<TaskWorkspace> {
        return exampleService.getExamples(course)
    }

    @GetMapping("/{example}/information")
    @PreAuthorize("hasRole(#course+'-assistant')")
    fun getGeneralInformation(
        @PathVariable course: String,
        @PathVariable example: String,
        authentication: Authentication
    ): ExampleInformationDTO {
        val participantsOnline = roleService.getOnlineCount(course)
        val totalParticipants = courseService.getCourseBySlug(course).participantCount
        val numberOfStudentsWhoSubmitted = exampleService.getSubmissions(course, example).size
        val passRatePerTestCase = exampleService.getExamplePassRatePerTestCase(course, example)

        return ExampleInformationDTO(
            participantsOnline,
            totalParticipants,
            numberOfStudentsWhoSubmitted,
            passRatePerTestCase
        )
    }

    @GetMapping("/{example}/submissions")
    @PreAuthorize("hasRole(#course+'-assistant')")
    fun getExampleSubmissions(
        @PathVariable course: String,
        @PathVariable example: String,
        authentication: Authentication
    ): List<SubmissionSseDTO> {
        val submissions = exampleService.getSubmissions(course, example).map {
            SubmissionSseDTO(
                it.id!!,
                it.userId,
                it.createdAt,
                it.points,
                it.testsPassed,
                it.files[0].content
            )
        }

        return submissions
    }

    @PostMapping("/{example}/submit")
    @PreAuthorize("hasRole(#course) and (#submission.restricted or hasRole(#course + '-assistant'))")
    fun evaluateExampleSubmission(
        @PathVariable course: String,
        @PathVariable example: String,
        @RequestBody submission: SubmissionDTO,
        authentication: Authentication
    ) {
        submission.userId = authentication.name
        val newSubmission = exampleService.createExampleSubmission(course, example, submission)

        emitterService.sendPayload(
            EmitterType.SUPERVISOR,
            course,
            "student-submission",
            SubmissionSseDTO(
                newSubmission.id!!,
                newSubmission.userId,
                newSubmission.createdAt,
                newSubmission.points,
                newSubmission.testsPassed,
                newSubmission.files[0].content
            )
        )

        val participantsOnline = roleService.getOnlineCount(course)
        val totalParticipants = courseService.getCourseBySlug(course).participantCount
        val numberOfStudentsWhoSubmitted = exampleService.getSubmissions(course, example).size
        val passRatePerTestCase = exampleService.getExamplePassRatePerTestCase(course, example)


        emitterService.sendPayload(
            EmitterType.SUPERVISOR,
            course,
            "example-information",
            ExampleInformationDTO(
                participantsOnline,
                totalParticipants,
                numberOfStudentsWhoSubmitted,
                passRatePerTestCase
            )
        )
    }

    @GetMapping("/{example}/users/{user}")
    @PreAuthorize("hasRole(#course+'-assistant') or (#user == authentication.name)")
    fun getExample(
        @PathVariable course: String,
        @PathVariable example: String,
        @PathVariable user: String
    ): TaskWorkspace {
        return exampleService.getExample(course, example, user)
    }

    // Invoked by the teacher when publishing an example to inform the students
    @PostMapping("/{example}/publish")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun publishExample(
        @PathVariable course: String,
        @PathVariable example: String,
        @RequestBody body: ExampleDurationDTO,
    ) {
        val activeExample = exampleService.getExamples(course).firstOrNull {
            it.status == TaskStatus.Interactive
        }

        if (activeExample != null) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "An interactive example already exists."
            )
        }

        val updatedExample = exampleService.publishExampleBySlug(course, example, body.duration)

        emitterService.sendPayload(EmitterType.STUDENT, course, "redirect", "/courses/$course/examples/$example")
        emitterService.sendPayload(
            EmitterType.EVERYONE,
            course,
            "timer-update",
            "${updatedExample.start}/${updatedExample.end}"
        )
    }

    // Invoked by the teacher when want to extend the time of an active example by a certain amount of seconds
    @PutMapping("/{example}/extend")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun extendExampleDeadline(
        @PathVariable course: String,
        @PathVariable example: String,
        @RequestBody body: ExampleDurationDTO,
    ) {
        val updatedExample = exampleService.extendExampleDeadlineBySlug(course, example, body.duration)

        emitterService.sendPayload(
            EmitterType.EVERYONE,
            course,
            "message",
            "Submission time extended by the lecturer by ${body.duration} seconds."
        )
        emitterService.sendPayload(
            EmitterType.EVERYONE,
            course,
            "timer-update",
            "${updatedExample.start}/${updatedExample.end}"
        )
    }

    // Invoked by the teacher when want to terminate the active example
    @PutMapping("/{example}/terminate")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun terminateExample(
        @PathVariable course: String,
        @PathVariable example: String
    ) {
        val updatedExample = exampleService.terminateExampleBySlug(course, example)
        emitterService.sendPayload(
            EmitterType.EVERYONE,
            course,
            "message",
            "The example has been terminated by the lecturer."
        )
        emitterService.sendPayload(
            EmitterType.EVERYONE,
            course,
            "timer-update",
            "${updatedExample.start}/${updatedExample.end}"
        )
    }

    // Invoked by the teacher when publishing an example to inform the students
    @PostMapping("/{example}/user/{userId}/inspect")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun getSubmissionInspectionPath(
        @PathVariable course: String,
        @PathVariable example: String,
        @PathVariable userId: String,
    ) {

        val encodedUserId = Base64.getEncoder().encodeToString(userId.toByteArray())
        emitterService.sendPayload(
            EmitterType.SUPERVISOR,
            course,
            "inspect",
            "/courses/$course/examples/$example/public-dashboard/inspect/users/$encodedUserId"

        )
    }

    @DeleteMapping("/{example}/reset")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun resetExample(
        @PathVariable course: String,
        @PathVariable example: String
    ) {
        exampleService.resetExampleBySlug(course, example)

        emitterService.sendPayload(
            EmitterType.EVERYONE,
            course,
            "example-reset",
            "The example has been reset by the lecturer."
        )
    }
}
