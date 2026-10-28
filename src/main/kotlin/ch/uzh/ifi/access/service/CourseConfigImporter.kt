package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.dto.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.collections.set

@Service
class CourseConfigImporter(
    private val tomlMapper: TomlMapper,
    private val fileService: FileService,
) {
    private val configFile = "config.toml"

    fun JsonNode?.asTextOrNull(): String? {
        return if (this is NullNode || this == null) null else this.asText()
    }

    fun readCourseConfig(path: Path): CourseDTO {
        val config: JsonNode = tomlMapper.readTree(Files.readString(path.resolve(configFile)))
        val course = CourseDTO()

        course.slug = config["slug"].asText()
        course.logo = fileService.readToBase64(path.resolve(config["logo"].asText()))
        config["assignments"].forEach { directory ->
            course.assignments.add(directory.asText())
        }
        config["examples"]?.forEach { directory ->
            course.examples.add(directory.asText())
        }

        // Visibility-Block sicher auslesen
        val visibility = config["visibility"]
        course.defaultVisibility = visibility?.get("default")?.asTextOrNull()
        course.overrideVisibility = visibility?.get("override")?.asTextOrNull()

        if (course.overrideVisibility != null) {
            val overrideStart = visibility.get("override_start")?.asTextOrNull()
                ?: throw IllegalArgumentException("override_start must not be null when override visibility is specified")
            val overrideEnd = visibility.get("override_end")?.asTextOrNull()
                ?: throw IllegalArgumentException("override_end must not be null when override visibility is specified")

            course.overrideStart = LocalDateTime.parse(overrideStart)
            course.overrideEnd = LocalDateTime.parse(overrideEnd)
        } else {
            course.overrideStart = null
            course.overrideEnd = null
        }

        config["information"].properties().forEach { field ->
            val information = CourseInformationDTO()
            val data = field.value
            information.language = field.key
            information.title = data.get("title").asText()
            information.description = data.get("description").asText()
            information.university = data.get("university").asText()
            information.period = data.get("period").asText()
            course.information[field.key] = information
        }

        val files = CourseFilesDTO()
        config["global_files"].properties().forEach { field ->
            val data = field.value
            val filenames = ArrayList<String>()
            data.forEach { filename ->
                filenames.add(filename.asText())
            }
            when (field.key) {
                "visible" -> files.visible = filenames
                "editable" -> files.editable = filenames
                "grading" -> files.grading = filenames
                "solution" -> files.solution = filenames
            }
        }
        course.globalFiles = files

        return course
    }

    fun readAssignmentConfig(path: Path): AssignmentDTO {
        val config: JsonNode = tomlMapper.readTree(Files.readString(path.resolve(configFile)))
        val assignment = AssignmentDTO()

        assignment.slug = config["slug"].asText()
        assignment.start = LocalDateTime.parse(config["start"].asText())
        val end = config["end"]?.asText()
        assignment.end = end?.let { LocalDateTime.parse(it) }

        config["tasks"].forEach { directory ->
            assignment.tasks.add(directory.asText())
        }

        config["information"].properties().forEach { field ->
            val information = AssignmentInformationDTO()
            val data = field.value
            information.language = field.key
            information.title = data.get("title").asText()
            assignment.information[field.key] = information
        }

        return assignment
    }

    private fun readConfig(path: Path, isExample: Boolean): TaskDTO {
        val config: JsonNode = tomlMapper.readTree(Files.readString(path.resolve(configFile)))
        val task = TaskDTO()

        task.slug = config["slug"].asText()
        task.refill = config["refill"].asInt()

        // Unterschiede anhand des Flags / Typs behandeln
        if (isExample) {
            task.maxAttempts = 1
            task.maxPoints = 1.0
        } else {
            task.maxAttempts = config["max_attempts"].asInt()
            task.maxPoints = config["max_points"].asDouble()
        }

        val instructionFiles = config["information"].properties().map { field ->
            val information = TaskInformationDTO()
            val data = field.value
            information.language = field.key
            information.title = data.get("title").asText()
            information.instructionsFile = data.get("instructions_file").asText()
            task.information[field.key] = information
            information.instructionsFile!!
        }.toList()

        val evaluator = TaskEvaluatorDTO()
        evaluator.dockerImage = config["evaluator"].get("docker_image").asText()

        // runCommand ist beim Example optional, beim Task Pflicht
        evaluator.runCommand = if (isExample) {
            config["evaluator"].get("run_command")?.asTextOrNull()
        } else {
            config["evaluator"].get("run_command").asText()
        }

        evaluator.gradeCommand = config["evaluator"].get("grade_command").asText()
        evaluator.testCommand = config["evaluator"].get("test_command").asTextOrNull()
        task.evaluator = evaluator

        val files = TaskFilesDTO()
        files.instruction = instructionFiles
        config["files"].properties().forEach { field ->
            val data = field.value
            val filenames = data.map { it.asText() }
            when (field.key) {
                "visible" -> files.visible = filenames
                "editable" -> files.editable = filenames
                "grading" -> files.grading = filenames
                "solution" -> files.solution = filenames
                "persist" -> files.persist = filenames
            }
        }
        task.files = files

        return task
    }

    fun readTaskConfig(path: Path): TaskDTO = readConfig(path, isExample = false)

    fun readExampleConfig(path: Path): TaskDTO = readConfig(path, isExample = true)
}
