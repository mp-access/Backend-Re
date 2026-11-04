package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.Util
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.NotFoundException
import io.github.oshai.kotlinlogging.KotlinLogging

/** Resource limits shared by the one-shot and the pooled execution path, so both grade identically. */
object ExecutionLimits {
    /** Memory limit per container (exceeding it is reported as exit code 137). */
    const val MEMORY_LIMIT: Long = 250 * Util.MEGABYTE

    /** Size of the /workspace tmpfs student code runs in (exit code 201 when full). */
    const val WORKSPACE_TMPFS_SIZE = "50M"

    /** Maximum size of each persistent result file (exit code 202 when exceeded). */
    const val RESULT_FILE_SIZE_LIMIT: Long = 100 * Util.KILOBYTE
}

private val logger = KotlinLogging.logger {}

/** Pulls [image] unless it is already available locally; blocks until the pull has completed. */
fun DockerClient.ensureImage(image: String) {
    try {
        inspectImageCmd(image).exec()
    } catch (_: NotFoundException) {
        logger.info { "Pulling docker image $image" }
        pullImageCmd(image).exec(PullImageResultCallback()).awaitCompletion()
    }
}
