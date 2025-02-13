package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.dto.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.dataformat.toml.TomlMapper
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

@Service
class CourseConfigImporter(
    private val tomlMapper: TomlMapper,
    private val fileService: FileService,
    private val objectMapper: ObjectMapper // Needed for JSON serialization
) {

    fun JsonNode?.asTextOrNull(): String? {
        return if (this is NullNode || this == null) null else this.asText()
    }

    fun readCourseConfig(path: Path): CourseDTO {
        val config: JsonNode = tomlMapper.readTree(Files.readString(path.resolve("config.toml")))
        val course = CourseDTO()

        course.slug = config["slug"].asText()
        course.logo = fileService.readToBase64(path.resolve(config["logo"].asText()))
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

        val files = CourseFilesDTO()
        config["global_files"].fields().forEachRemaining { field ->
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

    private fun readRubricsFromToml(path: Path, rubricsFile: String): String? {
        val rubricsPath = path.resolve(rubricsFile)
        if (!Files.exists(rubricsPath)) return null

        val rubricsConfig: JsonNode = tomlMapper.readTree(Files.readString(rubricsPath))
        val rubricsList = rubricsConfig["rubrics"]?.map { rubric ->
            RubricDTO(
                id = rubric["id"].asText(),
                title = rubric["title"].asText(),
                points = rubric["points"].asDouble()
            )
        } ?: emptyList()

        return objectMapper.writeValueAsString(rubricsList) // Convert to JSON string
    }

    private fun readExamplesFromToml(path: Path, examplesFile: String): String? {
        val examplesPath = path.resolve(examplesFile)
        if (!Files.exists(examplesPath)) return null

        val examplesConfig: JsonNode = tomlMapper.readTree(Files.readString(examplesPath))
        val examplesList = examplesConfig["examples"]?.map { example ->
            FewShotExampleDTO(
                answer = example["answer"].asText(),
                points = example["points"].asText()
            )
        } ?: emptyList()

        return objectMapper.writeValueAsString(examplesList) // Convert to JSON string
    }

    fun readTaskConfig(path: Path): TaskDTO {
        val config: JsonNode = tomlMapper.readTree(Files.readString(path.resolve("config.toml")))
        val task = TaskDTO()

        task.slug = config["slug"].asText()
        task.maxAttempts = config["max_attempts"].asInt()
        task.refill = config["refill"].asInt()
        task.maxPoints = config["max_points"].asDouble()

        val instructionFiles = config["information"].fields().asSequence().map { field ->
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
        evaluator.runCommand = config["evaluator"].get("run_command").asText()
        evaluator.gradeCommand = config["evaluator"].get("grade_command").asText()
        evaluator.testCommand = config["evaluator"].get("test_command").asTextOrNull()
        task.evaluator = evaluator

        val files = TaskFilesDTO()
        files.instruction = instructionFiles
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
                "persist" -> files.persist = filenames
            }

        val llmConfig = config["llm"]
        if (llmConfig != null && !llmConfig.isNull) {
                val submissionContent = llmConfig["submission"]?.asTextOrNull()?.let { Files.readString(path.resolve(it)) }
                    ?: throw InvalidCourseException()

                val solutionContent = llmConfig["solution"]?.asTextOrNull()?.let { Files.readString(path.resolve(it)) }
                val rubricsJson = llmConfig["rubrics"]?.asTextOrNull()?.let { readRubricsFromToml(path, it) }
                val examplesJson = llmConfig["examples"]?.asTextOrNull()?.let { readExamplesFromToml(path, it) }
                val promptContent = llmConfig["prompt"]?.asTextOrNull()?.let { Files.readString(path.resolve(it)) }
                val preContent = llmConfig["pre"]?.asTextOrNull()?.let { Files.readString(path.resolve(it)) }
                val postContent = llmConfig["post"]?.asTextOrNull()?.let { Files.readString(path.resolve(it)) }
                val temperature = llmConfig["temperature"]?.asDouble()
                val model = llmConfig["model"]?.asTextOrNull()
                val modelFamily = llmConfig["model_family"]?.asTextOrNull()

                task.llm = LLMConfigDTO(
                    submission = submissionContent,
                    solution = solutionContent,
                    rubrics = rubricsJson,
                    cot = llmConfig["cot"]?.asBoolean() ?: false,
                    voting = llmConfig["voting"]?.asInt() ?: 1,
                    examples = examplesJson,
                    prompt = promptContent,
                    pre = preContent,
                    post = postContent,
                    temperature = temperature,
                    model = model,
                    modelFamily = modelFamily,
                    maxPoints = llmConfig["max_points"].asDouble()
                )
            }
        }
        task.files = files

        return task
    }
}

class InvalidCourseException : Throwable() {


}