package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.dto.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.*
import org.apache.commons.codec.binary.Base64
import org.apache.commons.compress.utils.FileNameUtils
import org.apache.tika.Tika;
import kotlin.collections.ArrayList

@Service
class CourseConfigImporter(
    private val tomlMapper: TomlMapper,
   private val tika: Tika) {

    fun JsonNode?.asTextOrNull(): String? {
        return if (this is NullNode || this == null) null else this.asText()
    }

    fun readCourseConfig(path: Path): CourseDTO {
        val config: JsonNode = tomlMapper.readTree(Files.readString(path.resolve("config.toml")))
        val course = CourseDTO()

        course.slug = config["slug"].asText()
        course.logo = config["logo"].asText()
        config["assignments"].forEach { directory ->
            course.assignments.add(directory.asText())
        }

        course.defaultVisibility = config["visibility"].get("default").asText()
        course.overrideVisibility = config["visibility"].get("override").asText()
        course.overrideStart = LocalDateTime.parse(config["visibility"].get("override_start").asText())
        course.overrideEnd = LocalDateTime.parse(config["visibility"].get("override_end").asText())

        config["information"].fields().forEachRemaining { field ->
            val information = CourseInformationDTO()
            val data = field.value
            information.language = field.key
            information.title = data.get("title").asText()
            information.description = data.get("description").asText()
            information.university = data.get("university").asText()
            information.period = data.get("period").asText()
            course.information[field.key] = information
        }

        return course

    }

    fun readAssignmentConfig(path: Path): AssignmentDTO {
        val config: JsonNode = tomlMapper.readTree(Files.readString(path.resolve("config.toml")))
        val assignment = AssignmentDTO()

        assignment.slug = config["slug"].asText()
        assignment.start = LocalDateTime.parse(config["start"].asText())
        assignment.end = LocalDateTime.parse(config["end"].asText())

        config["tasks"].forEach { directory ->
            assignment.tasks.add(directory.asText())
        }

        val information = config["information"].fields().forEachRemaining { field ->
            val information = AssignmentInformationDTO()
            val data = field.value
            information.language = field.key
            information.title = data.get("title").asText()
            assignment.information[field.key] = information
        }

        return assignment

    }

    fun readTaskConfig(path: Path): TaskDTO {
        val config: JsonNode = tomlMapper.readTree(Files.readString(path.resolve("config.toml")))
        val task = TaskDTO()

        config["authors"]?: throw InvalidCourseException()
        config["license"]?: throw InvalidCourseException()

        task.slug = config["slug"].asText()
        task.maxAttempts = config["max_attempts"].asInt()
        task.refill = config["refill"].asInt()
        task.maxPoints = config["max_points"].asDouble()

        config["information"].fields().forEachRemaining { field ->
            val information = TaskInformationDTO()
            val data = field.value
            information.language = field.key
            information.title = data.get("title").asText()
            information.instructionsFile = data.get("instructions_file").asText()
            task.information[field.key] = information
        }

        val evaluator = TaskEvaluatorDTO()
        evaluator.dockerImage = config["evaluator"].get("docker_image").asText()
        evaluator.runCommand = config["evaluator"].get("run_command").asText()
        evaluator.gradeCommand = config["evaluator"].get("grade_command").asText()
        evaluator.testCommand = config["evaluator"].get("test_command").asTextOrNull()
        task.evaluator = evaluator

        val files = TaskFilesDTO()
        config["files"].fields().forEachRemaining { field ->
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
        task.files = files

        return task

    }

    fun readFile(path: Path): TaskFileDTO {
        val file = TaskFileDTO()
        val fileType = tika.detect(path)
        if (fileType.startsWith("text") || fileType.startsWith("application")) {
            file.template = Files.readString(path)
        } else {
            file.binary = true
            file.template = "data:${fileType};base64," + Base64.encodeBase64String(Files.readAllBytes(path))
        }
        file.language = readLanguage(path)
        return file
    }

    private fun readLanguage(path: Path): String {
        val extension = FileNameUtils.getExtension(path.toString())
        return if (extension == "py") "python" else extension
    }


}

class InvalidCourseException : Throwable() {

}