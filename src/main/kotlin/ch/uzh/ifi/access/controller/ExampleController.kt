package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.model.constants.TaskStatus
import ch.uzh.ifi.access.model.dto.ExampleDurationDTO
import ch.uzh.ifi.access.model.dto.ExampleInformationDTO
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.projections.TaskWorkspace
import ch.uzh.ifi.access.service.EmitterService
import ch.uzh.ifi.access.service.ExampleService
import ch.uzh.ifi.access.service.RoleService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException


@RestController
@RequestMapping("/courses/{course}/examples")
@EnableAsync
class ExampleController(
    private val exampleService: ExampleService,
    private val roleService: RoleService,
    private val emitterService: EmitterService,
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
        val totalParticipants = exampleService.getCourseBySlug(course).participantCount
        val numberOfStudentsWhoSubmitted = exampleService.countStudentsWhoSubmittedExample(course, example)
        val passRatePerTestCase = exampleService.getExamplePassRatePerTestCase(course, example)

        return ExampleInformationDTO(
            participantsOnline,
            totalParticipants,
            numberOfStudentsWhoSubmitted,
            passRatePerTestCase
        )
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
        // Is there a better way than passing null to assignmentSlug?
        exampleService.createExampleSubmission(course, example, submission)
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

        emitterService.sendMessage(course, "redirect", "/courses/$course/examples/$example")
        emitterService.sendMessage(course, "timer-update", "${updatedExample.start}/${updatedExample.end}")
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

        emitterService.sendMessage(
            course,
            "message",
            "Submission time extended by the lecturer by ${body.duration} seconds."
        )
        emitterService.sendMessage(course, "timer-update", "${updatedExample.start}/${updatedExample.end}")
    }

    // Invoked by the teacher when want to terminate the active example
    @PutMapping("/{example}/terminate")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun terminateExample(
        @PathVariable course: String,
        @PathVariable example: String
    ) {
        val updatedExample = exampleService.terminateExampleBySlug(course, example)
        emitterService.sendMessage(
            course,
            "message",
            "The example has been terminated by the lecturer."
        )
        emitterService.sendMessage(course, "timer-update", "${updatedExample.start}/${updatedExample.end}")
    }
}
